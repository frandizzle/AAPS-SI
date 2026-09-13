package app.aaps.plugins.source

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.time.T
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.source.BgSource
import app.aaps.core.interfaces.source.DexcomBoyda
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.workflow.LoggingWorker
import app.aaps.core.utils.receivers.DataWorkerStorage
import app.aaps.plugins.source.activities.RequestDexcomPermissionActivity
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class DexcomPlugin @Inject constructor(
    rh: ResourceHelper,
    aapsLogger: AAPSLogger,
    private val context: Context,
    config: Config,
    // Injected rather than inherited: this plugin's base is PluginBase, which carries no
    // preference store, and the sensor-type filter needs one.
    private val preferences: Preferences
) : AbstractBgSourceWithSensorInsertLogPlugin(
    PluginDescription()
        .mainType(PluginType.BGSOURCE)
        .fragmentClass(BGSourceFragment::class.java.name)
        .pluginIcon(app.aaps.core.objects.R.drawable.ic_dexcom_g6)
        .preferencesId(PluginDescription.PREFERENCE_SCREEN)
        .pluginName(R.string.dexcom_app_patched)
        .shortName(R.string.dexcom_short)
        .preferencesVisibleInSimpleMode(false)
        .description(R.string.description_source_dexcom),
    aapsLogger, rh
), BgSource, DexcomBoyda {

    init {
        if (!config.AAPSCLIENT) {
            pluginDescription.setDefault()
        }
    }

    /**
     * Sensor types whose broadcasts are accepted. Empty means all of them, which is right when a
     * single Dexcom app is broadcasting — the case upstream assumes.
     *
     * Two BYODA builds can be installed at once (a G6 and a G7), and both broadcast into the same
     * receiver. Nothing downstream separates them, so both sensors land in one BG stream. The
     * bundle's "sensorType" is what tells them apart, and it is read a few lines into the worker
     * already.
     */
    fun enabledSensorTypes(): Set<String> =
        preferences.get(StringNonKey.DexcomEnabledSensorTypes)
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    fun isSensorTypeEnabled(sensorType: String?): Boolean {
        val enabled = enabledSensorTypes()
        return enabled.isEmpty() || (sensorType ?: "") in enabled
    }

    override fun addPreferenceScreen(preferenceManager: PreferenceManager, parent: PreferenceScreen, context: Context, requiredKey: String?) {
        super.addPreferenceScreen(preferenceManager, parent, context, requiredKey)
        if (requiredKey != null) return
        val category = androidx.preference.PreferenceCategory(context)
        parent.addPreference(category)
        category.apply {
            key = "dexcom_sensor_type_settings"
            title = rh.gs(R.string.dexcom_app_patched)
            initialExpandedChildrenCount = 0
            addPreference(MultiSelectListPreference(context).apply {
                key = "dexcom_enabled_sensor_types_ui"
                title = rh.gs(R.string.dexcom_sensor_types_title)
                // Persistence is ours: the selection lives in the typed preference store, so the
                // widget is non-persistent and writes through on change.
                isPersistent = false
                entries = SENSOR_TYPES.toTypedArray()
                entryValues = SENSOR_TYPES.toTypedArray()
                values = enabledSensorTypes()
                summary = sensorTypeSummary()
                setOnPreferenceChangeListener { pref, newValue ->
                    @Suppress("UNCHECKED_CAST")
                    val selected = (newValue as? Set<String>).orEmpty()
                    preferences.put(StringNonKey.DexcomEnabledSensorTypes, selected.joinToString(","))
                    pref.summary = sensorTypeSummary()
                    true
                }
            })
        }
    }

    private fun sensorTypeSummary(): String = when {
        enabledSensorTypes().isEmpty() -> rh.gs(R.string.dexcom_sensor_types_all)
        enabledSensorTypes().size > 1  -> rh.gs(R.string.dexcom_sensor_types_multiple, enabledSensorTypes().size)
        else                           -> enabledSensorTypes().first()
    }

    override fun advancedFilteringSupported(): Boolean = true

    override fun onStart() {
        super.onStart()
        requestPermissionIfNeeded()
    }

    // cannot be inner class because of needed injection
    class DexcomWorker(
        context: Context,
        params: WorkerParameters
    ) : LoggingWorker(context, params, Dispatchers.IO) {

        @Inject lateinit var dexcomPlugin: DexcomPlugin
        @Inject lateinit var preferences: Preferences
        @Inject lateinit var dateUtil: DateUtil
        @Inject lateinit var dataWorkerStorage: DataWorkerStorage
        @Inject lateinit var persistenceLayer: PersistenceLayer
        @Inject lateinit var profileUtil: ProfileUtil

        @SuppressLint("CheckResult")
        override suspend fun doWorkAndLog(): Result {
            var ret = Result.success()

            if (!dexcomPlugin.isEnabled()) return Result.success(workDataOf("Result" to "Plugin not enabled"))
            val bundle = dataWorkerStorage.pickupBundle(inputData.getLong(DataWorkerStorage.STORE_KEY, -1))
                ?: return Result.failure(workDataOf("Error" to "missing input data"))
            try {
                val sensorType = bundle.getString("sensorType") ?: ""
                if (!dexcomPlugin.isSensorTypeEnabled(sensorType)) {
                    // Two BYODA builds broadcasting at once; the user has picked which one counts.
                    return Result.success(workDataOf("Result" to "Sensor type $sensorType not selected"))
                }
                val sourceSensor = when (sensorType) {
                    "G6" -> SourceSensor.DEXCOM_G6_NATIVE
                    "G7" -> SourceSensor.DEXCOM_G7_NATIVE
                    else -> SourceSensor.DEXCOM_NATIVE_UNKNOWN
                }
                val calibrations = mutableListOf<PersistenceLayer.Calibration>()
                bundle.getBundle("meters")?.let { meters ->
                    for (i in 0 until meters.size()) {
                        meters.getBundle(i.toString())?.let {
                            val timestamp = it.getLong("timestamp") * 1000
                            val now = dateUtil.now()
                            val value = it.getInt("meterValue").toDouble()
                            if (timestamp > now - T.months(1).msecs() && timestamp < now) {
                                calibrations.add(
                                    PersistenceLayer.Calibration(
                                        timestamp = it.getLong("timestamp") * 1000,
                                        value = value,
                                        glucoseUnit = profileUtil.unitsDetect(value)
                                    )
                                )
                            }
                        }
                    }
                }
                val now = dateUtil.now()
                val glucoseValuesBundle = bundle.getBundle("glucoseValues")
                    ?: return Result.failure(workDataOf("Error" to "missing glucoseValues"))
                val glucoseValues = mutableListOf<GV>()
                for (i in 0 until glucoseValuesBundle.size()) {
                    val glucoseValueBundle = glucoseValuesBundle.getBundle(i.toString())!!
                    val timestamp = glucoseValueBundle.getLong("timestamp") * 1000
                    // G5 calibration bug workaround (calibration is sent as glucoseValue too)
                    var valid = true
                    // G6 is sending one 24h old changed value causing recalculation. Ignore
                    if (sourceSensor == SourceSensor.DEXCOM_G6_NATIVE)
                        if ((now - timestamp) > T.hours(20).msecs()) valid = false
                    if (valid)
                        glucoseValues += GV(
                            timestamp = timestamp,
                            value = glucoseValueBundle.getInt("glucoseValue").toDouble(),
                            noise = null,
                            raw = null,
                            trendArrow = TrendArrow.fromString(glucoseValueBundle.getString("trendArrow")!!),
                            sourceSensor = sourceSensor
                        )
                }
                var sensorStartTime = if (preferences.get(BooleanKey.BgSourceCreateSensorChange) && bundle.containsKey("sensorInsertionTime")) {
                    bundle.getLong("sensorInsertionTime", 0) * 1000
                } else {
                    null
                }
                // check start time validity
                sensorStartTime?.let {
                    if (abs(it - now) > T.months(1).msecs() || it > now) sensorStartTime = null
                }
                persistenceLayer.insertCgmSourceData(Sources.Dexcom, glucoseValues, calibrations, sensorStartTime)
                    .doOnError { ret = Result.failure(workDataOf("Error" to it.toString())) }
                    .blockingGet()
                    .also { result ->
                        // G6 calibration bug workaround (2 additional GVs are created within 1 minute)
                        for (i in result.inserted.indices) {
                            if (sourceSensor == SourceSensor.DEXCOM_G6_NATIVE) {
                                if (i < result.inserted.size - 1) {
                                    if (abs(result.inserted[i].timestamp - result.inserted[i + 1].timestamp) < T.mins(1).msecs()) {
                                        persistenceLayer.invalidateGlucoseValue(result.inserted[i].id, Action.BG_REMOVED, Sources.Dexcom, note = null, listValues = listOf()).blockingGet()
                                        persistenceLayer.invalidateGlucoseValue(result.inserted[i + 1].id, Action.BG_REMOVED, Sources.Dexcom, note = null, listValues = listOf()).blockingGet()
                                        result.inserted.removeAt(i + 1)
                                        result.inserted.removeAt(i)
                                        continue
                                    }
                                }
                            }
                        }
                    }
            } catch (e: Exception) {
                aapsLogger.error("Error while processing intent from Dexcom App", e)
                ret = Result.failure(workDataOf("Error" to e.toString()))
            }
            return ret
        }
    }

    override fun requestPermissionIfNeeded() {
        if (ContextCompat.checkSelfPermission(context, PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            val intent = Intent(context, RequestDexcomPermissionActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    override fun dexcomPackages() = PACKAGE_NAMES

    companion object {

        /** The values Dexcom puts in the broadcast's "sensorType" extra. */
        private val SENSOR_TYPES = listOf("G6", "G7")

        private val PACKAGE_NAMES = listOf(
            "com.dexcom.g6.region1.mmol", "com.dexcom.g6.region2.mgdl",
            "com.dexcom.g6.region3.mgdl", "com.dexcom.g6.region3.mmol",
            "com.dexcom.g6", "com.dexcom.g7"
        )
        const val PERMISSION = "com.dexcom.cgm.EXTERNAL_PERMISSION"
    }
}
