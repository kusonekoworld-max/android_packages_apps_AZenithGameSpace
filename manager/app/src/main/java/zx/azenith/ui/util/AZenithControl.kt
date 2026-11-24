package zx.azenith.ui.util

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import vendor.azenith.IAZenithService

object AZenithControl {
    private const val PROP_STATE = "persist.sys.azenithIntg.state"
    private val main = Handler(Looper.getMainLooper())
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    data class DaemonState(
        val profile: String = "",
        val activeApp: String? = null,
        val screen: String = "",
        val batterySaver: String = "",
        val charging: String = "",
        val batteryLevel: Int = -1
    )

    private fun service(): IAZenithService? = try {
        val binder = RootUtils.getService() ?: return null
        if (!binder.isBinderAlive) return null
        IAZenithService.Stub.asInterface(binder)
    } catch (_: Exception) {
        null
    }

    fun isDaemonAvailable(): Boolean = try {
        val svc = service() ?: return false
        svc.asBinder().pingBinder()
    } catch (_: Exception) {
        false
    }

    fun getApiVersion(): Int = try {
        service()?.getApiVersion() ?: 0
    } catch (_: Exception) {
        0
    }

    /** Daemon built with the v5 interface (startTask / getTaskOutput). */
    fun supportsTasks(): Boolean = getApiVersion() >= 5

    fun startTask(name: String, arg: String = ""): Boolean = try {
        service()?.startTask(name, arg) == true
    } catch (_: Exception) { false }

    fun taskOutput(name: String): String = try {
        service()?.getTaskOutput(name) ?: ""
    } catch (_: Exception) { "" }

    fun isDisabled(): Boolean = try {
        service()?.isDisabled() ?: (PropertyUtils.get("persist.sys.azenithIntg.disabletweak", "0") == "1")
    } catch (_: Exception) {
        PropertyUtils.get("persist.sys.azenithIntg.disabletweak", "0") == "1"
    }

    fun setDisabled(disabled: Boolean): Boolean = try {
        service()?.setDisabled(disabled) ?: false
    } catch (_: Exception) {
        false
    }

    fun getConfig(key: String, defaultValue: String = ""): String = try {
        val svc = service()
        if (svc != null && svc.getApiVersion() >= 4) {
            svc.getConfig(key)?.takeIf { it.isNotEmpty() } ?: defaultValue
        } else {
            PropertyUtils.get("persist.sys.azenithConfIntg.$key", defaultValue)
        }
    } catch (_: Exception) {
        PropertyUtils.get("persist.sys.azenithConfIntg.$key", defaultValue)
    }

    fun setConfig(key: String, value: String): Boolean = try {
        service()?.setConfig(key, value) == true
    } catch (_: Exception) {
        false
    }

    fun getStateRaw(): String = try {
        service()?.getState().orEmpty()
    } catch (_: Exception) {
        ""
    }

    fun getParsedState(): DaemonState? {
        val raw = getStateRaw()
        if (raw.isBlank()) return null
        val values = mutableMapOf<String, String>()
        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
            val eq = trimmed.indexOf('=')
            if (eq > 0) {
                val k = trimmed.substring(0, eq).trim()
                val v = trimmed.substring(eq + 1).trim().removeSurrounding("\"")
                values[k] = v
            }
        }
        val active = values["active_app"]?.takeIf { it.isNotBlank() && it != "NULL" }
        return DaemonState(
            profile = values["profile"].orEmpty(),
            activeApp = active,
            screen = values["screen"].orEmpty(),
            batterySaver = values["battery_saver"].orEmpty(),
            charging = values["charging"].orEmpty(),
            batteryLevel = values["battery_level"]?.toIntOrNull() ?: -1
        )
    }

    fun restartService(onDone: (Boolean) -> Unit = {}) {
        PropertyUtils.set(PROP_STATE, "0")
        main.postDelayed({
            PropertyUtils.set(PROP_STATE, "1")
            onDone(true)
        }, 1500)
    }

    fun applyProfile(profileId: String): Boolean {
        return when (profileId.trim()) {
            "0" -> setDisabled(true)
            "1", "2", "3" -> {
                setDisabled(false)
                setConfig("manual_profile", profileId.trim())
            }
            else -> false
        }
    }

    fun getGamesResult(): Result<Set<String>> {
        val svc = service()
            ?: return Result.failure(IllegalStateException("AZenith daemon service is unavailable."))
        return runCatching {
            val raw = svc.getGames().orEmpty()
            parseGamesRaw(raw)
        }
    }

    fun getGames(): Set<String> = getGamesResult().getOrDefault(emptySet())

    private fun parseGamesRaw(raw: String): Set<String> {
        if (raw.isBlank()) return emptySet()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length())
                .map { arr.optString(it).trim() }
                .filter { it.isNotBlank() }
                .toSet()
        } catch (_: Exception) {
            raw.split(',', '\n')
                .map { it.trim().removeSurrounding("\"") }
                .filter { it.isNotBlank() }
                .toSet()
        }
    }

    fun getGameConfigsResult(): Result<Map<String, AppConfig>> {
        val svc = service()
            ?: return Result.failure(IllegalStateException("AZenith daemon service is unavailable."))
        return runCatching {
            val games = parseGamesRaw(svc.getGames().orEmpty())
            val map = linkedMapOf<String, AppConfig>()
            if (svc.getApiVersion() >= 4) {
                val rawJson = svc.getGameConfigs().orEmpty()
                if (rawJson.isNotBlank()) {
                    val decoded = json.decodeFromString<Map<String, AppConfig>>(rawJson)
                    map.putAll(decoded)
                }
            }
            for (pkg in games) {
                map.putIfAbsent(pkg, AppConfig())
            }
            map.keys.retainAll(games)
            map
        }
    }

    fun setGame(pkg: String, enabled: Boolean): Boolean = try {
        val svc = service() ?: return false
        if (enabled) svc.addGame(pkg) else svc.removeGame(pkg)
    } catch (_: Exception) {
        false
    }

    fun setGameConfig(pkg: String, config: AppConfig): Boolean = try {
        val svc = service() ?: return false
        if (svc.getApiVersion() >= 4) {
            svc.setGameConfig(pkg, json.encodeToString(config))
        } else {
            svc.addGame(pkg)
        }
    } catch (_: Exception) {
        false
    }

    fun removeGameConfig(pkg: String): Boolean = try {
        val svc = service() ?: return false
        if (svc.getApiVersion() >= 4) {
            svc.removeGameConfig(pkg)
        } else {
            svc.removeGame(pkg)
        }
    } catch (_: Exception) {
        false
    }

    fun rescanGames(): Boolean = try {
        val svc = service() ?: return false
        if (svc.getApiVersion() >= 4) svc.rescanGames() else false
    } catch (_: Exception) {
        false
    }

    fun reboot(reason: String?): Boolean = try {
        val app = Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication").invoke(null) as Application
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        PowerManager::class.java.getMethod("reboot", String::class.java).invoke(pm, reason)
        true
    } catch (_: Exception) {
        false
    }
}
