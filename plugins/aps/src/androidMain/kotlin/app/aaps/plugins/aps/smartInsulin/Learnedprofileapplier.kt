package app.aaps.plugins.aps.smartInsulin

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.di.ApplicationScope
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileRepository
import app.aaps.core.interfaces.profile.SingleProfile
import app.aaps.core.interfaces.sharedPreferences.KeyValueStore
import app.aaps.core.interfaces.utils.DateUtil
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes the learned ISF and basal into a new local profile and switches to it, and keeps the
 * circadian learning centred on the profile in force.
 *
 * The circadian multipliers are relative to the profile. If the profile becomes 40 % weaker and the
 * multipliers stay, the loop would weaken twice. So when the profile in force changes to one this
 * class knows (the one it made, or the one before it), [onProfileInForce] returns the ratios the
 * learning must move by, and the loop doses the same as before the switch. Only those two profiles
 * are known: a percentage switch, or any other profile, leaves the learning alone, because that is
 * a change the user wants to be felt.
 */
@SingleIn(AppScope::class)
class LearnedProfileApplier @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val sp: KeyValueStore,
    private val profileFunction: ProfileFunction,
    private val profileRepository: ProfileRepository,
    private val dateUtil: DateUtil,
    @ApplicationScope private val appScope: CoroutineScope
) {

    /** A profile by name, with its ISF (mg/dL per U) and basal (U/h) read at the centre of each hour. */
    class Known(val name: String, val isfMgdl: DoubleArray, val basal: DoubleArray)

    /** How much the learning must move, see [CircadianLearner.rescaleForProfileChange]. */
    class Rescale(val profileName: String, val isfRatio: DoubleArray, val basalRatio: DoubleArray)

    /** The profile the learning is centred on now. Null until the first apply. */
    private var anchor: Known? = null
    /** The profile made by the last apply (or the one an undo goes back to). */
    private var applied: Known? = null
    /** The profile in force before the last apply. Undo goes back to it. */
    private var previous: Known? = null

    /** The profile asked for by the last apply or undo, until it is in force. Only for "waiting for the pump". */
    private var requested: Known? = null
    private var requestedAtMs = 0L

    /** When the last apply ran, ms. 0 = never. */
    var appliedAtMs: Long = 0L
        private set

    val previousName: String? get() = previous?.name
    val appliedName: String? get() = applied?.name

    init { restore() }

    /**
     * True when a profile change made here has not reached the profile in force yet. Gives up after
     * [PENDING_MAX_MS], so a switch the pump never took does not block the buttons for ever. The
     * learning still moves if that profile comes into force later, see [onProfileInForce].
     */
    fun switchPending(isfMgdl: DoubleArray, basal: DoubleArray): Boolean {
        val r = requested ?: return false
        return dateUtil.now() - requestedAtMs < PENDING_MAX_MS && !matches(r, isfMgdl, basal)
    }

    /**
     * Saves [plan] as a new local profile and switches to it at 100 %. [isfMgdl] and [basal] are the
     * profile in force now, hour by hour.
     *
     * @return null when the switch was written, or why it was refused, as a sentence for the user.
     */
    suspend fun apply(plan: LearnedProfilePlan.Plan, isfMgdl: DoubleArray, basal: DoubleArray): String? {
        refusal(isfMgdl, basal)?.let { return it }
        val running = profileFunction.getRequestedProfile() ?: return "No profile is active."
        val store = profileRepository.profile.value ?: return "The local profiles could not be read."
        // The profile switch in force carries its own IC, targets and unit, so the new profile is made
        // from it, not from the local profile list. The switched-to profile may have been renamed or
        // removed from that list since, or may have come from Nightscout.
        val inForce = SingleProfile(
            name = running.profileName, mgdl = running.glucoseUnit == GlucoseUnit.MGDL,
            ic = running.icBlocks, isf = running.isfBlocks, basal = running.basalBlocks, target = running.targetBlocks
        )
        // Undo switches back by name, so the profile in force must be in the local list.
        if (store.getSpecificProfile(running.profileName) == null) {
            profileRepository.add(inForce).exceptionOrNull()?.let { return "Your profile \"${running.profileName}\" could not be saved for undo: ${it.message}" }
            aapsLogger.debug(LTag.APS, "LearnedProfileApplier: saved the profile in force as ${running.profileName}, so undo can go back to it")
        }
        var name = "SI learned " + SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(dateUtil.now()))
        if (profileRepository.profile.value?.getSpecificProfile(name) != null)
            name += " " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(dateUtil.now()))
        // ISF is written in the unit of the profile in force.
        val single = inForce.copy(name = name, isf = plan.isfBlocks(inForce.mgdl), basal = plan.basalBlocks())
        val errors = profileRepository.validateStructured(single) + profileRepository.validatePumpCompatibility(single)
        if (errors.isNotEmpty()) return "The learned profile is not valid: " + errors.joinToString("; ") { it.message }
        profileRepository.add(single).exceptionOrNull()?.let { return "The learned profile could not be saved: ${it.message}" }
        aapsLogger.debug(LTag.APS, "LearnedProfileApplier: saved ${single.name}")

        switchTo(single.name, "SmartInsulin learned profile")?.let { return it }
        val made = Known(
            single.name,
            LearnedProfilePlan.hourly(single.isf, if (single.mgdl) 1.0 else LearnedProfilePlan.MGDL_PER_MMOL),
            LearnedProfilePlan.hourly(single.basal)
        )
        // The learning is relative to the profile in force now, whatever it is, and the loop must dose
        // the same after the switch as now. So the profile in force now is what the learning is moved from.
        val before = Known(running.profileName, isfMgdl.copyOf(), basal.copyOf())
        anchor = before
        previous = before
        applied = made
        appliedAtMs = dateUtil.now()
        request(made)
        persist()
        return null
    }

    /**
     * Switches back to the profile in force before the last apply. The learning moves back when that
     * profile is in force, the same way it moved forward.
     *
     * @return null when the switch was written, or why it was refused.
     */
    suspend fun undo(isfMgdl: DoubleArray, basal: DoubleArray): String? {
        val back = previous ?: return "There is nothing to undo."
        refusal(isfMgdl, basal)?.let { return it }
        if (profileRepository.profile.value?.getSpecificProfile(back.name) == null)
            return "The profile \"${back.name}\" is no longer in your local profiles."
        val inForceName = profileFunction.getRequestedProfile()?.profileName ?: ""
        switchTo(back.name, "SmartInsulin undo learned profile")?.let { return it }
        anchor = Known(inForceName, isfMgdl.copyOf(), basal.copyOf())
        applied = back
        previous = null
        request(back)
        persist()
        return null
    }

    /**
     * Called each loop cycle with the profile in force. When it has changed to a profile this class
     * knows, returns how much the learning must move, and from then on the learning is centred on it.
     */
    fun onProfileInForce(isfMgdl: DoubleArray, basal: DoubleArray): Rescale? {
        val from = anchor ?: return null
        if (matches(from, isfMgdl, basal)) return null
        val to = listOfNotNull(applied, previous).firstOrNull { matches(it, isfMgdl, basal) } ?: return null
        requested = null
        val rescale = Rescale(
            to.name,
            DoubleArray(24) { h -> if (from.isfMgdl[h] > 0.0) isfMgdl[h] / from.isfMgdl[h] else 1.0 },
            DoubleArray(24) { h -> if (from.basal[h] > 0.0) basal[h] / from.basal[h] else 1.0 }
        )
        anchor = Known(to.name, isfMgdl.copyOf(), basal.copyOf())
        persist()
        return rescale
    }

    // Not persisted: after a restart one more try a day early does no harm.
    private var lastAutoTryMs = 0L

    /**
     * The automatic change runs at most once a week, so the learning has time to settle on the new
     * profile, and is tried at most once a day, so a refused switch does not add a profile every cycle.
     */
    fun autoAllowed(nowMs: Long): Boolean = nowMs - appliedAtMs >= AUTO_MIN_GAP_MS && nowMs - lastAutoTryMs >= AUTO_RETRY_MS

    /**
     * [apply] outside the loop cycle: the profile switch goes through the pump queue and must not run
     * inside the loop's own calculation. [onResult] gets what [apply] returned.
     */
    fun applyAutomatically(plan: LearnedProfilePlan.Plan, isfMgdl: DoubleArray, basal: DoubleArray, onResult: (String?) -> Unit) {
        lastAutoTryMs = dateUtil.now()
        appScope.launch {
            val result = try {
                apply(plan, isfMgdl, basal)
            } catch (e: Exception) {
                aapsLogger.error(LTag.APS, "LearnedProfileApplier auto apply failed", e)
                "It failed: ${e.message}"
            }
            onResult(result)
        }
    }

    private fun matches(k: Known, isfMgdl: DoubleArray, basal: DoubleArray): Boolean =
        LearnedProfilePlan.sameSchedule(isfMgdl, k.isfMgdl) && LearnedProfilePlan.sameSchedule(basal, k.basal)

    private fun request(k: Known) {
        requested = k
        requestedAtMs = dateUtil.now()
    }

    /** Why a switch cannot be made now, or null. */
    private suspend fun refusal(isfMgdl: DoubleArray, basal: DoubleArray): String? {
        val running = profileFunction.getRequestedProfile() ?: return "No profile is active."
        if (running.percentage != 100 || running.timeshift != 0L || running.duration != 0L)
            return "A profile switch with a percentage, a time shift or an end time is running. Switch to your normal profile at 100 % first."
        if (switchPending(isfMgdl, basal))
            return "The last profile change has not reached the pump yet. Try again when it has."
        return null
    }

    private suspend fun switchTo(name: String, note: String): String? {
        val store = profileRepository.profile.value ?: return "The local profiles could not be read."
        val iCfg = profileFunction.getRunningOrRequestedICfg() ?: return "No insulin is set in the running profile."
        val ps = profileFunction.createProfileSwitch(
            profileStore = store, profileName = name, durationInMinutes = 0, percentage = 100, timeShiftInHours = 0,
            timestamp = dateUtil.now(), action = Action.PROFILE_SWITCH, source = Sources.Loop, note = note,
            listValues = listOf(ValueWithUnit.SimpleString(name)), iCfg = iCfg
        )
        if (ps == null) return profileFunction.profileSwitchRefusal(store, name, 0, 100, 0, iCfg) ?: "The profile switch was refused."
        return null
    }

    // -- Persistence -------------------------------------------------------------

    private fun persist() {
        val json = JSONObject().put(K_AT, appliedAtMs)
        anchor?.let { json.put(K_ANCHOR, it.toJson()) }
        applied?.let { json.put(K_APPLIED, it.toJson()) }
        previous?.let { json.put(K_PREVIOUS, it.toJson()) }
        sp.edit { putString(SP_KEY, json.toString()) }
    }

    private fun restore() {
        try {
            val raw = sp.getString(SP_KEY, "")
            if (raw.isBlank()) return
            val json = JSONObject(raw)
            appliedAtMs = json.optLong(K_AT, 0L)
            anchor = json.optJSONObject(K_ANCHOR)?.toKnown()
            applied = json.optJSONObject(K_APPLIED)?.toKnown()
            previous = json.optJSONObject(K_PREVIOUS)?.toKnown()
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "LearnedProfileApplier restore failed: ${e.message}")
        }
    }

    private fun Known.toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("isf", JSONArray().also { a -> isfMgdl.forEach { a.put(it) } })
        .put("basal", JSONArray().also { a -> basal.forEach { a.put(it) } })

    private fun JSONObject.toKnown(): Known {
        val i = getJSONArray("isf")
        val b = getJSONArray("basal")
        return Known(getString("name"), DoubleArray(24) { i.getDouble(it) }, DoubleArray(24) { b.getDouble(it) })
    }

    private companion object {

        const val SP_KEY = "si_learned_profile_state"
        const val K_AT = "appliedAt"
        const val K_ANCHOR = "anchor"
        const val K_APPLIED = "applied"
        const val K_PREVIOUS = "previous"
        const val AUTO_MIN_GAP_MS = 7 * 24 * 3_600_000L
        const val AUTO_RETRY_MS = 24 * 3_600_000L
        const val PENDING_MAX_MS = 3_600_000L
    }
}
