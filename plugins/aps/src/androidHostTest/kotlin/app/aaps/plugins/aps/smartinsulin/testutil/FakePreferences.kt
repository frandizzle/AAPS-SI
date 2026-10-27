package app.aaps.plugins.aps.smartInsulin.testutil

import app.aaps.core.interfaces.sharedPreferences.KeyValueStore

class FakePreferences : KeyValueStore {
    private val values = mutableMapOf<String, Any?>()

    override fun getString(key: String, defaultValue: String): String = values[key] as? String ?: defaultValue
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = values[key] as? Boolean ?: defaultValue
    override fun getInt(key: String, defaultValue: Int): Int = values[key] as? Int ?: defaultValue
    override fun getLong(key: String, defaultValue: Long): Long = values[key] as? Long ?: defaultValue
    override fun getDouble(key: String, defaultValue: Double): Double = values[key] as? Double ?: defaultValue
    
    override fun getStringOrNull(key: String, defaultValue: String?): String? = values[key] as? String ?: defaultValue
    
    override fun contains(key: String): Boolean = values.containsKey(key)

    override fun edit(commit: Boolean, block: KeyValueStore.Editor.() -> Unit) {
        val editor = object : KeyValueStore.Editor {
            override fun putString(key: String, value: String) { values[key] = value }
            override fun putBoolean(key: String, value: Boolean) { values[key] = value }
            override fun putInt(key: String, value: Int) { values[key] = value }
            override fun putLong(key: String, value: Long) { values[key] = value }
            override fun putDouble(key: String, value: Double) { values[key] = value }
            override fun clear() { values.clear() }
            override fun remove(key: String) { values.remove(key) }
        }
        editor.block()
    }

    override fun getAll(): Map<String, *> = values
    override fun clear() { values.clear() }
    override fun remove(key: String) { values.remove(key) }
    
    override fun incLong(key: String) { values[key] = (values[key] as? Long ?: 0L) + 1 }
    override fun incInt(key: String) { values[key] = (values[key] as? Int ?: 0) + 1 }
    
    override fun putBoolean(key: String, value: Boolean) { values[key] = value }
    override fun putDouble(key: String, value: Double) { values[key] = value }
    override fun putLong(key: String, value: Long) { values[key] = value }
    override fun putInt(key: String, value: Int) { values[key] = value }
    override fun putString(key: String, value: String) { values[key] = value }
}
