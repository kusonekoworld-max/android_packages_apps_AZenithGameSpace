package zx.azenith.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import zx.azenith.ui.util.AZenithControl
import zx.azenith.ui.util.AppConfig

class AppSettingsViewModel : ViewModel() {

    var fullConfig by mutableStateOf<Map<String, AppConfig>>(emptyMap())
        private set

    var daemonAvailable by mutableStateOf(true)
        private set

    var lastError by mutableStateOf<String?>(null)
        private set

    fun clearError() {
        lastError = null
    }

    fun loadConfig() {
        viewModelScope.launch(Dispatchers.IO) {
            val result = AZenithControl.getGameConfigsResult()
            withContext(Dispatchers.Main) {
                result.onSuccess { decoded ->
                    fullConfig = decoded
                    daemonAvailable = true
                    lastError = null
                }.onFailure { err ->
                    daemonAvailable = false
                    lastError = err.message ?: "AZenith daemon is unavailable."
                }
            }
        }
    }

    fun toggleMasterSwitch(packageName: String, isEnabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = if (isEnabled) {
                val cfg = fullConfig[packageName] ?: AppConfig()
                AZenithControl.setGameConfig(packageName, cfg) || AZenithControl.setGame(packageName, true)
            } else {
                AZenithControl.removeGameConfig(packageName) || AZenithControl.setGame(packageName, false)
            }

            withContext(Dispatchers.Main) {
                if (ok) {
                    val newMap = fullConfig.toMutableMap()
                    if (isEnabled) {
                        newMap.putIfAbsent(packageName, AppConfig())
                    } else {
                        newMap.remove(packageName)
                    }
                    fullConfig = newMap
                    daemonAvailable = true
                    lastError = null
                } else {
                    daemonAvailable = AZenithControl.isDaemonAvailable()
                    lastError = "Failed to synchronize changes with the AZenith daemon."
                }
            }
        }
    }

    fun updateSetting(packageName: String, key: String, value: String) {
        val currentAppConfig = fullConfig[packageName] ?: AppConfig()
        val updated = when (key) {
            "perf_lite_mode" -> currentAppConfig.copy(perf_lite_mode = value)
            "dnd_on_gaming" -> currentAppConfig.copy(dnd_on_gaming = value)
            "app_priority" -> currentAppConfig.copy(app_priority = value)
            "game_preload" -> currentAppConfig.copy(game_preload = value)
            "refresh_rate" -> currentAppConfig.copy(refresh_rate = value)
            "renderer" -> currentAppConfig.copy(renderer = value)
            "resolution_downscale" -> currentAppConfig.copy(
                resolution_downscale = value,
                resolution_fps = if (value == "default") "60" else currentAppConfig.resolution_fps
            )
            "resolution_fps" -> currentAppConfig.copy(resolution_fps = value)
            "bypass_charging" -> currentAppConfig.copy(bypass_charging = value)
            else -> currentAppConfig
        }

        viewModelScope.launch(Dispatchers.IO) {
            val ok = AZenithControl.setGameConfig(packageName, updated)
            withContext(Dispatchers.Main) {
                if (ok) {
                    val newMap = fullConfig.toMutableMap()
                    newMap[packageName] = updated
                    fullConfig = newMap
                    daemonAvailable = true
                    lastError = null
                } else {
                    daemonAvailable = AZenithControl.isDaemonAvailable()
                    lastError = "Failed to save configuration to the AZenith daemon."
                }
            }
        }
    }
}
