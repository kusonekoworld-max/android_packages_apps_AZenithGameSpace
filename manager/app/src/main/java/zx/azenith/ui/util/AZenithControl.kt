package zx.azenith.ui.util

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import org.json.JSONArray
import vendor.azenith.IAZenithService

object AZenithControl {
    private const val PROP_STATE = "persist.sys.azenithIntg.state"
    private val main = Handler(Looper.getMainLooper())

    private fun service(): IAZenithService? =
        RootUtils.getService()?.let { IAZenithService.Stub.asInterface(it) }

    fun restartService(onDone: (Boolean) -> Unit = {}) {
        PropertyUtils.set(PROP_STATE, "0")
        main.postDelayed({
            PropertyUtils.set(PROP_STATE, "1")
            onDone(true)
        }, 1500)
    }

    fun getGames(): Set<String> {
        val raw = try { service()?.games ?: "" } catch (e: Exception) { "" }
        if (raw.isBlank()) return emptySet()
        return try {
            val json = JSONArray(raw)
            (0 until json.length()).map { json.optString(it) }.filter { it.isNotBlank() }.toSet()
        } catch (e: Exception) {
            raw.split(',', '\n').map { it.trim().removeSurrounding("\"") }.filter { it.isNotBlank() }.toSet()
        }
    }

    fun setGame(pkg: String, enabled: Boolean): Boolean = try {
        if (enabled) service()?.addGame(pkg) == true else service()?.removeGame(pkg) == true
    } catch (e: Exception) { false }

    fun reboot(reason: String?): Boolean = try {
        RootUtils.getService()
        val app = Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication").invoke(null) as Application
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        PowerManager::class.java.getMethod("reboot", String::class.java).invoke(pm, reason)
        true
    } catch (e: Exception) { false }
}
