package app.aaps.plugins.aps.smartInsulin.testutil

import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.BooleanComposedNonPreferenceKey
import app.aaps.core.keys.interfaces.BooleanNonPreferenceKey
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.ComposedKey
import app.aaps.core.keys.interfaces.DoubleComposedNonPreferenceKey
import app.aaps.core.keys.interfaces.DoubleNonPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey
import app.aaps.core.keys.interfaces.IntComposedNonPreferenceKey
import app.aaps.core.keys.interfaces.IntNonPreferenceKey
import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.core.keys.interfaces.LongComposedNonPreferenceKey
import app.aaps.core.keys.interfaces.LongNonPreferenceKey
import app.aaps.core.keys.interfaces.LongPreferenceKey
import app.aaps.core.keys.interfaces.NonPreferenceKey
import app.aaps.core.keys.interfaces.PreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.StringComposedNonPreferenceKey
import app.aaps.core.keys.interfaces.StringNonPreferenceKey
import app.aaps.core.keys.interfaces.StringPreferenceKey
import app.aaps.core.keys.interfaces.UnitDoublePreferenceKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * In-memory [Preferences] for unit tests.
 * Stores all values in a single string-keyed map; typed accessors fall back to
 * the key's defaultValue when nothing has been explicitly set.
 *
 * CircadianLearner and BolusCurveTracker only use StringKey for persistence
 * (CircadianLearner: ApsSmartInsulinCircadianState, BolusCurveTracker: ApsSmartInsulinTrackerState)
 * so the only methods that actually need to work are get/put for StringNonPreferenceKey.
 * Everything else returns its default value silently.
 */
class FakePreferences : Preferences {

    private val strings  = mutableMapOf<String, String>()
    private val booleans = mutableMapOf<String, Boolean>()
    private val doubles  = mutableMapOf<String, Double>()
    private val ints     = mutableMapOf<String, Int>()
    private val longs    = mutableMapOf<String, Long>()

    // ── Mode flags — always false in tests ───────────────────────────────────
    override val simpleMode:      Boolean = false
    override val apsMode:         Boolean = true
    override val nsclientMode:    Boolean = false
    override val pumpControlMode: Boolean = false

    // ── Boolean ──────────────────────────────────────────────────────────────
    override fun get(key: BooleanNonPreferenceKey): Boolean           = booleans[key.key] ?: key.defaultValue
    override fun get(key: BooleanPreferenceKey): Boolean              = booleans[key.key] ?: key.defaultValue
    override fun get(key: BooleanComposedNonPreferenceKey, vararg arguments: Any): Boolean = key.defaultValue
    override fun get(key: BooleanComposedNonPreferenceKey, vararg arguments: Any, defaultValue: Boolean): Boolean = defaultValue
    override fun getIfExists(key: BooleanNonPreferenceKey): Boolean?  = booleans[key.key]
    override fun getIfExists(key: BooleanComposedNonPreferenceKey, vararg arguments: Any): Boolean? = null
    override fun put(key: BooleanNonPreferenceKey, value: Boolean)    { booleans[key.key] = value }
    override fun put(key: BooleanComposedNonPreferenceKey, vararg arguments: Any, value: Boolean) {}
    override fun observe(key: BooleanNonPreferenceKey): StateFlow<Boolean> = MutableStateFlow(get(key))
    override fun observe(key: BooleanComposedNonPreferenceKey, vararg arguments: Any): StateFlow<Boolean> = MutableStateFlow(false)

    // ── String ───────────────────────────────────────────────────────────────
    override fun get(key: StringNonPreferenceKey): String             = strings[key.key] ?: key.defaultValue
    override fun get(key: StringPreferenceKey): String                = strings[key.key] ?: key.defaultValue
    override fun get(key: StringComposedNonPreferenceKey, vararg arguments: Any): String = key.defaultValue
    override fun getIfExists(key: StringNonPreferenceKey): String?    = strings[key.key]
    override fun getIfExists(key: StringComposedNonPreferenceKey, vararg arguments: Any): String? = null
    override fun put(key: StringNonPreferenceKey, value: String)      { strings[key.key] = value }
    override fun put(key: StringComposedNonPreferenceKey, vararg arguments: Any, value: String) {}
    override fun observe(key: StringNonPreferenceKey): StateFlow<String> = MutableStateFlow(get(key))
    override fun observe(key: StringComposedNonPreferenceKey, vararg arguments: Any): StateFlow<String> = MutableStateFlow("")

    // ── Double ───────────────────────────────────────────────────────────────
    override fun get(key: DoubleNonPreferenceKey): Double             = doubles[key.key] ?: key.defaultValue
    override fun get(key: DoublePreferenceKey): Double                = doubles[key.key] ?: key.defaultValue
    override fun get(key: DoubleComposedNonPreferenceKey, vararg arguments: Any): Double = key.defaultValue
    override fun getIfExists(key: DoublePreferenceKey): Double?       = doubles[key.key]
    override fun getIfExists(key: DoubleComposedNonPreferenceKey, vararg arguments: Any): Double? = null
    override fun put(key: DoubleNonPreferenceKey, value: Double)      { doubles[key.key] = value }
    override fun put(key: DoubleComposedNonPreferenceKey, vararg arguments: Any, value: Double) {}
    override fun observe(key: DoubleNonPreferenceKey): StateFlow<Double> = MutableStateFlow(get(key))
    override fun observe(key: DoubleComposedNonPreferenceKey, vararg arguments: Any): StateFlow<Double> = MutableStateFlow(0.0)

    // ── UnitDouble ───────────────────────────────────────────────────────────
    override fun get(key: UnitDoublePreferenceKey): Double            = doubles[key.key] ?: key.defaultValue
    override fun getIfExists(key: UnitDoublePreferenceKey): Double?   = doubles[key.key]
    override fun put(key: UnitDoublePreferenceKey, value: Double)     { doubles[key.key] = value }
    override fun observe(key: UnitDoublePreferenceKey): StateFlow<Double> = MutableStateFlow(get(key))

    // ── Int ──────────────────────────────────────────────────────────────────
    override fun get(key: IntNonPreferenceKey): Int                   = ints[key.key] ?: key.defaultValue
    override fun get(key: IntPreferenceKey): Int                      = ints[key.key] ?: key.defaultValue
    override fun get(key: IntComposedNonPreferenceKey, vararg arguments: Any): Int = key.defaultValue
    override fun getIfExists(key: IntNonPreferenceKey): Int?          = ints[key.key]
    override fun put(key: IntNonPreferenceKey, value: Int)            { ints[key.key] = value }
    override fun put(key: IntComposedNonPreferenceKey, vararg arguments: Any, value: Int) {}
    override fun inc(key: IntNonPreferenceKey)                        { ints[key.key] = (ints[key.key] ?: 0) + 1 }
    override fun observe(key: IntNonPreferenceKey): StateFlow<Int>    = MutableStateFlow(get(key))
    override fun observe(key: IntComposedNonPreferenceKey, vararg arguments: Any): StateFlow<Int> = MutableStateFlow(0)

    // ── Long ─────────────────────────────────────────────────────────────────
    override fun get(key: LongNonPreferenceKey): Long                 = longs[key.key] ?: key.defaultValue
    override fun get(key: LongPreferenceKey): Long                    = longs[key.key] ?: key.defaultValue
    override fun get(key: LongComposedNonPreferenceKey, vararg arguments: Any): Long = key.defaultValue
    override fun getIfExists(key: LongNonPreferenceKey): Long?        = longs[key.key]
    override fun getIfExists(key: LongComposedNonPreferenceKey, vararg arguments: Any): Long? = null
    override fun put(key: LongNonPreferenceKey, value: Long)          { longs[key.key] = value }
    override fun put(key: LongComposedNonPreferenceKey, vararg arguments: Any, value: Long) {}
    override fun inc(key: LongNonPreferenceKey)                       { longs[key.key] = (longs[key.key] ?: 0L) + 1L }
    override fun observe(key: LongNonPreferenceKey): StateFlow<Long>  = MutableStateFlow(get(key))
    override fun observe(key: LongComposedNonPreferenceKey, vararg arguments: Any): StateFlow<Long> = MutableStateFlow(0L)

    // ── General ───────────────────────────────────────────────────────────────
    override fun remove(key: ComposedKey, vararg arguments: Any)      {}
    override fun remove(key: NonPreferenceKey)                        { strings.remove(key.key); booleans.remove(key.key); doubles.remove(key.key); ints.remove(key.key); longs.remove(key.key) }
    override fun get(key: String): NonPreferenceKey?                  = null
    override fun getIfExists(key: String): NonPreferenceKey?          = null
    override fun isUnitDependent(key: String): Boolean                = false
    override fun getDependingOn(key: String): List<PreferenceKey>     = emptyList()
    override fun registerPreferences(clazz: Class<out NonPreferenceKey>) {}
    override fun allMatchingStrings(key: ComposedKey): List<String>   = emptyList()
    override fun allMatchingInts(key: ComposedKey): List<Int>         = emptyList()
    override fun isExportableKey(key: String): Boolean                = false
    override fun getAllPreferenceKeys(): List<PreferenceKey>           = emptyList()
}