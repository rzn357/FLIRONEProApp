package com.exciteddev.flironepro.testing

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.File

/** The little of an Android [Context] that the settings and the logs need: preferences in memory and a files directory. */
class TestContext(private val filesDirectory: File) : ContextWrapper(null) {
    private val preferences = HashMap<String, FakePreferences>()

    override fun getApplicationContext(): Context = this

    override fun getFilesDir(): File = filesDirectory

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = preferences.getOrPut(name.orEmpty()) {
        FakePreferences()
    }
}

/** [SharedPreferences] that live in memory (and are applied at once). */
class FakePreferences : SharedPreferences {
    private val values = HashMap<String, Any?>()

    override fun getAll(): MutableMap<String, *> = HashMap(values)

    override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        values[key] as? MutableSet<String> ?: defValues

    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = key in values

    override fun edit(): SharedPreferences.Editor = FakeEditor()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    private inner class FakeEditor : SharedPreferences.Editor {
        private val pending = HashMap<String, Any?>()
        private var clear = false

        private fun put(key: String?, value: Any?) = apply { pending[key.orEmpty()] = value }

        override fun putString(key: String?, value: String?) = put(key, value)

        override fun putStringSet(key: String?, values: MutableSet<String>?) = put(key, values)

        override fun putInt(key: String?, value: Int) = put(key, value)

        override fun putLong(key: String?, value: Long) = put(key, value)

        override fun putFloat(key: String?, value: Float) = put(key, value)

        override fun putBoolean(key: String?, value: Boolean) = put(key, value)

        override fun remove(key: String?) = put(key, null)

        override fun clear() = apply { clear = true }

        override fun commit(): Boolean {
            if (clear) values.clear()
            pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            return true
        }

        override fun apply() {
            commit()
        }
    }
}
