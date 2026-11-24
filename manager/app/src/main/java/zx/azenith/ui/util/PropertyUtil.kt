package zx.azenith.ui.util

import android.annotation.SuppressLint

@SuppressLint("PrivateApi")
object PropertyUtils {
    private const val CONF_PREFIX = "persist.sys.azenithConfIntg."

    private val systemPropertiesClass by lazy {
        Class.forName("android.os.SystemProperties")
    }

    private val getMethod by lazy {
        systemPropertiesClass.getMethod("get", String::class.java, String::class.java)
    }

    private val setMethod by lazy {
        systemPropertiesClass.getMethod("set", String::class.java, String::class.java)
    }

    fun get(key: String, def: String = ""): String {
        return try {
            getMethod.invoke(null, key, def) as String
        } catch (_: Exception) {
            if (key.startsWith(CONF_PREFIX)) {
                AZenithControl.getConfig(key.removePrefix(CONF_PREFIX), def)
            } else {
                def
            }
        }
    }

    fun set(key: String, value: String) {
        if (key.startsWith(CONF_PREFIX)) {
            if (AZenithControl.setConfig(key.removePrefix(CONF_PREFIX), value)) {
                return
            }
        }
        runCatching {
            setMethod.invoke(null, key, value)
        }
    }
}
