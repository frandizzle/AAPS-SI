package app.aaps.plugins.aps.smartInsulin.testutil

import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey
import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.core.keys.interfaces.LongPreferenceKey
import app.aaps.core.keys.interfaces.StringPreferenceKey
import app.aaps.core.keys.interfaces.UnitDoublePreferenceKey

/**
 * Simple in-memory [Preferences] for unit tests.
 * Keys are stored by their string key value; type safety is handled by the
 * typed get/put overloads returning defaults when not explicitly set.
 */
class FakePreferences : Preferences {
    private val store = mutableMapOf<String, Any>()

    fun setBoolean(key: String, value: Boolean)  { store[key] = value }
    fun setDouble(key: String, value: Double)     { store[key] = value }
    fun setInt(key: String, value: Int)           { store[key] = value }
    fun setLong(key: String, value: Long)         { store[key] = value }
    fun setString(key: String, value: String)     { store[key] = value }

    override fun get(key: BooleanPreferenceKey): Boolean =
        store[key.key] as? Boolean ?: key.defaultValue

    override fun get(key: DoublePreferenceKey): Double =
        store[key.key] as? Double ?: key.defaultValue

    override fun get(key: IntPreferenceKey): Int =
        store[key.key] as? Int ?: key.defaultValue

    override fun get(key: LongPreferenceKey): Long =
        store[key.key] as? Long ?: key.defaultValue

    override fun get(key: StringPreferenceKey): String =
        store[key.key] as? String ?: key.defaultValue

    override fun get(key: UnitDoublePreferenceKey): Double =
        store[key.key] as? Double ?: key.defaultValue

    override fun put(key: BooleanPreferenceKey, value: Boolean)     { store[key.key] = value }
    override fun put(key: DoublePreferenceKey, value: Double)        { store[key.key] = value }
    override fun put(key: IntPreferenceKey, value: Int)              { store[key.key] = value }
    override fun put(key: LongPreferenceKey, value: Long)            { store[key.key] = value }
    override fun put(key: StringPreferenceKey, value: String)        { store[key.key] = value }
    override fun put(key: UnitDoublePreferenceKey, value: Double)    { store[key.key] = value }

    override fun remove(key: StringPreferenceKey)                    { store.remove(key.key) }
    override fun isSet(key: StringPreferenceKey): Boolean            = store.containsKey(key.key)
}
