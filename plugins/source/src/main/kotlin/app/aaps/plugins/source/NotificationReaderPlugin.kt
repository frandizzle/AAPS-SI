package app.aaps.plugins.source

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.source.BgSource
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.source.notificationreader.NotificationCollectorService
import app.aaps.plugins.source.notificationreader.PackageConfig
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads glucose values from the notifications posted by official CGM apps.
 *
 * Ported from AndroidAPS dev (4.0), where the plugin shell is written against Metro DI, Compose
 * and TextRef — none of which exist on this branch — so this file is a rewrite against the local
 * [AbstractBgSourcePlugin] contract rather than a copy. The logic it drives (parser, package
 * config, deduplicator, collector service) is upstream's, near-verbatim.
 *
 * The point of it for a G7: Dexcom's G7 app publishes no broadcast for AAPS to receive, so the
 * only native route is scraping the notification it posts every reading. That is inherently
 * weaker than a broadcast — the user has to grant notification access, the CGM app has to keep
 * posting, and Android may coalesce notifications — but it removes the need for xDrip or
 * Juggluco as a bridge.
 *
 * Package definitions live in an asset and are refreshed from upstream's `versions` branch on
 * start, so a CGM app changing its notification format can be handled without an app release.
 *
 * AndroidAPS is licensed AGPL-3.0; this fork inherits that licence.
 */
@Singleton
class NotificationReaderPlugin @Inject constructor(
    rh: ResourceHelper,
    aapsLogger: AAPSLogger,
    preferences: Preferences,
    private val context: Context
) : AbstractBgSourcePlugin(
    pluginDescription = PluginDescription()
        .mainType(PluginType.BGSOURCE)
        .fragmentClass(BGSourceFragment::class.java.name)
        .pluginIcon(app.aaps.core.objects.R.drawable.ic_dexcom_g6)
        .preferencesId(PluginDescription.PREFERENCE_SCREEN)
        .pluginName(R.string.notification_reader)
        .shortName(R.string.notification_reader_short)
        .preferencesVisibleInSimpleMode(false)
        .description(R.string.description_source_notification_reader),
    aapsLogger = aapsLogger,
    rh = rh,
    preferences = preferences
), BgSource {

    @Volatile
    var packageConfig: PackageConfig = PackageConfig(0, emptySet(), emptyMap(), emptyMap())
        private set

    override fun onStart() {
        super.onStart()
        packageConfig = loadPackageConfig()
        // Off the main thread — this reaches the network. Upstream spawns a bare Thread here too;
        // a failure is non-fatal, the bundled asset stays in force.
        Thread { updateDefinitionsFromRemote() }.start()
    }

    /**
     * True when the user has granted this app notification access, which is what lets
     * [NotificationCollectorService] see anything at all. There is no runtime-permission dialog
     * for it — it is a Settings toggle — hence the preference below rather than a request.
     */
    fun notificationAccessGranted(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /**
     * Packages the reader is allowed to take readings from. Empty means every supported package,
     * which is upstream's behaviour and the right default for someone running a single CGM app.
     */
    fun enabledPackages(): Set<String> =
        preferences.get(StringNonKey.NotificationReaderEnabledPackages)
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /**
     * Whether a reading from this package should be taken.
     *
     * This is the guard that was missing. Running a G6 and a G7 app side by side, both post
     * reading notifications, both are in the supported list, and [app.aaps.plugins.source.notificationreader.GlucoseDeduplicator]
     * keys its interval window PER PACKAGE — so the two never dedupe against each other and both
     * sensors land in one BG stream seconds apart. That is not a variant of "duplicate
     * notification", it is two different sensors, and no dedup heuristic should be asked to sort
     * it out.
     */
    fun isPackageEnabled(packageName: String): Boolean {
        val enabled = enabledPackages()
        return enabled.isEmpty() || packageName in enabled
    }

    /** Records a supported package as having been seen, so it can be offered in the picker. */
    fun recordSeenPackage(packageName: String) {
        val seen = seenPackages()
        if (packageName in seen) return
        preferences.put(StringNonKey.NotificationReaderSeenPackages, (seen + packageName).joinToString(","))
    }

    private fun seenPackages(): Set<String> =
        preferences.get(StringNonKey.NotificationReaderSeenPackages)
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** Friendly label for a package — the app's own name where the system will tell us, otherwise
     *  the sensor it maps to. The package is always appended, since two Dexcom apps read alike. */
    private fun labelFor(packageName: String): String {
        val appLabel = try {
            context.packageManager.getApplicationLabel(
                context.packageManager.getApplicationInfo(packageName, 0)
            ).toString()
        } catch (_: Exception) {
            // Package-visibility rules can hide an installed app from PackageManager; the sensor
            // mapping is always available because it comes from our own config.
            packageConfig.sensorForPackage(packageName).text
        }
        return "$appLabel  ($packageName)"
    }

    /**
     * Multi-select over the packages actually observed posting notifications.
     *
     * Deliberately not a list of every supported package: there are 47 of them and 45 are noise
     * for any given user. Deliberately not a list of installed packages either — from API 30 the
     * system may refuse to tell us what is installed, but a NotificationListenerService is handed
     * the package name of everything it sees, so what has actually posted is both accurate and
     * free to collect.
     *
     * Persistence is ours, not the widget's: the selection lives in the typed preference store
     * alongside everything else this plugin keeps, so the widget is non-persistent and writes
     * through on change.
     */
    private fun buildSourcePicker(context: Context): MultiSelectListPreference {
        val choices = (seenPackages() + enabledPackages()).sorted()
        return MultiSelectListPreference(context).apply {
            key = "notification_reader_enabled_packages_ui"
            title = rh.gs(R.string.notification_reader_sources_title)
            isPersistent = false
            entries = choices.map { labelFor(it) }.toTypedArray()
            entryValues = choices.toTypedArray()
            values = enabledPackages()
            isEnabled = choices.isNotEmpty()
            summary = sourceSummary(choices)
            setOnPreferenceChangeListener { pref, newValue ->
                @Suppress("UNCHECKED_CAST")
                val selected = (newValue as? Set<String>).orEmpty()
                preferences.put(StringNonKey.NotificationReaderEnabledPackages, selected.joinToString(","))
                pref.summary = sourceSummary(choices)
                true
            }
        }
    }

    private fun sourceSummary(choices: List<String>): String = when {
        choices.isEmpty()           -> rh.gs(R.string.notification_reader_sources_none_seen)
        enabledPackages().isEmpty() -> rh.gs(R.string.notification_reader_sources_all)
        enabledPackages().size > 1  -> rh.gs(R.string.notification_reader_sources_multiple, enabledPackages().size)
        else                        -> labelFor(enabledPackages().first())
    }

    override fun addPreferenceScreen(preferenceManager: PreferenceManager, parent: PreferenceScreen, context: Context, requiredKey: String?) {
        super.addPreferenceScreen(preferenceManager, parent, context, requiredKey)
        if (requiredKey != null) return
        val category = PreferenceCategory(context)
        parent.addPreference(category)
        category.apply {
            key = "notification_reader_settings"
            title = rh.gs(R.string.notification_reader)
            initialExpandedChildrenCount = 0
            addPreference(buildSourcePicker(context))
            addPreference(Preference(context).apply {
                title = rh.gs(R.string.notification_reader_access_title)
                summary = rh.gs(
                    if (notificationAccessGranted()) R.string.notification_reader_access_granted
                    else R.string.notification_reader_access_missing
                )
                setOnPreferenceClickListener {
                    // No runtime permission exists for notification access; the only route is the
                    // system settings screen, so send the user straight there.
                    context.startActivity(
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    true
                }
            })
        }
    }

    private fun loadPackageConfig(): PackageConfig =
        try {
            val stored = preferences.get(StringNonKey.NotificationReaderPackages)
            val json = if (stored.isNotEmpty()) stored
            else context.assets.open(PACKAGE_CONFIG_ASSET).bufferedReader().use { it.readText() }
                .also { preferences.put(StringNonKey.NotificationReaderPackages, it) }
            PackageConfig.fromJson(json)
        } catch (e: Exception) {
            aapsLogger.error(LTag.BGSOURCE, "Failed to load package config", e)
            PackageConfig(0, emptySet(), emptyMap(), emptyMap())
        }

    private fun updateDefinitionsFromRemote() {
        try {
            val remoteJson = URL(REMOTE_DEFINITIONS_URL).readText()
            val remoteConfig = PackageConfig.fromJson(remoteJson)
            if (remoteConfig.version > packageConfig.version) {
                aapsLogger.info(LTag.BGSOURCE, "Updating notification reader definitions: v${packageConfig.version} → v${remoteConfig.version}")
                preferences.put(StringNonKey.NotificationReaderPackages, remoteJson)
                packageConfig = remoteConfig
            } else {
                aapsLogger.debug(LTag.BGSOURCE, "Notification reader definitions up to date (v${packageConfig.version})")
            }
        } catch (e: Exception) {
            aapsLogger.debug(LTag.BGSOURCE, "Failed to fetch remote definitions: ${e.message}")
        }
    }

    companion object {

        private const val PACKAGE_CONFIG_ASSET = "notification_reader_packages.json"
        private const val REMOTE_DEFINITIONS_URL =
            "https://raw.githubusercontent.com/nightscout/AndroidAPS/refs/heads/versions/notification_reader_packages.json"
    }
}
