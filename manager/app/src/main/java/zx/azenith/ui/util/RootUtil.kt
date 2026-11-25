package zx.azenith.ui.util

import android.os.FileObserver
import android.os.IBinder
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import org.lsposed.hiddenapibypass.HiddenApiBypass
import zx.azenith.R

object RootUtils {
    private const val SERVICE_NAME = "vendor.azenith.IAZenithService/default"
    private const val DAEMON_BIN = "/system_ext/bin/vendor.azenith-service"
    private const val API_DIR_PATH = "/data/system/AZenithInt/API"
    private const val PROFILE_FILE_NAME = "current_profile"
    private const val MODES_FILE_NAME = "current_modes"
    private const val PROFILE_PATH = "$API_DIR_PATH/$PROFILE_FILE_NAME"
    private const val MODES_PATH = "$API_DIR_PATH/$MODES_FILE_NAME"
    private const val GAMEINFO_PATH = "$API_DIR_PATH/gameinfo"

    private val hiddenApiReady by lazy {
        runCatching { HiddenApiBypass.addHiddenApiExemptions("") }.isSuccess
    }

    internal fun getService(): IBinder? = try {
        hiddenApiReady
        Class.forName("android.os.ServiceManager")
            .getMethod("checkService", String::class.java)
            .invoke(null, SERVICE_NAME) as? IBinder
    } catch (_: Exception) {
        null
    }

    internal fun isServiceAlive(): Boolean = try {
        getService()?.pingBinder() == true
    } catch (_: Exception) {
        false
    }

    internal fun readRootFile(path: String): String? = try {
        val f = File(path)
        if (f.exists() && f.canRead()) f.readText().trim() else null
    } catch (_: Exception) {
        null
    }

    internal fun writeRootFile(path: String, content: String) {
        try {
            val file = File(path)
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp_" + System.nanoTime())
            tmp.writeText(content)
            tmp.renameTo(file)
        } catch (_: Exception) {
        }
    }

    internal fun touchRootFile(path: String) {
        try {
            val f = File(path)
            if (!f.exists()) {
                f.parentFile?.mkdirs()
                f.createNewFile()
            }
        } catch (_: Exception) {
        }
    }

    internal fun rootFileExists(path: String): Boolean = try {
        File(path).exists()
    } catch (_: Exception) {
        false
    }

    internal fun deleteRootFile(path: String) {
        try {
            File(path).delete()
        } catch (_: Exception) {
        }
    }

    fun getModuleVersionCode(): Int = AZenithControl.getApiVersion().takeIf { it > 0 } ?: -1

    data class GameInfo(val pkg: String?, val startTime: String?)

    fun observeGameInfo(): Flow<GameInfo> = flow {
        var lastInfo: GameInfo? = null
        while (true) {
            val raw = readRootFile(GAMEINFO_PATH)
            val currentInfo = if (!raw.isNullOrBlank()) {
                val lines = raw.lines()
                val firstLine = lines[0].split(" ")
                val pkg = firstLine.getOrNull(0)?.takeIf { it != "NULL" && it.isNotBlank() }
                val time = lines.find { it.startsWith("Time:") }?.substringAfter("Time:")?.trim()
                GameInfo(pkg, time)
            } else {
                val state = AZenithControl.getParsedState()
                GameInfo(state?.activeApp, null)
            }
            if (currentInfo != lastInfo) {
                emit(currentInfo)
                lastInfo = currentInfo
            }
            delay(2000)
        }
    }.flowOn(Dispatchers.IO)

    fun observeProfileValue(): Flow<String> = callbackFlow {
        trySend(getCurrentProfileValue())
        val job = launch { watchDaemonFile(PROFILE_FILE_NAME) { trySend(getCurrentProfileValue()) } }
        awaitClose { job.cancel() }
    }.flowOn(Dispatchers.IO)

    private suspend fun CoroutineScope.watchDaemonFile(fileName: String, onChange: () -> Unit) {
        val observer = object : FileObserver(API_DIR_PATH, MODIFY or CREATE or MOVED_TO or CLOSE_WRITE) {
            override fun onEvent(event: Int, path: String?) {
                if (path == fileName) onChange()
            }
        }
        runCatching { observer.startWatching() }
        try {
            var last: String? = null
            while (true) {
                val current = readRootFile("$API_DIR_PATH/$fileName") ?: getCurrentProfileValue()
                if (current != last) {
                    last = current
                    onChange()
                }
                delay(1000)
            }
        } finally {
            runCatching { observer.stopWatching() }
        }
    }

    // The integrated daemon has no current_modes file: AIenabled is the single source of truth.
    fun observeAutoMode(): Flow<String> = flow {
        var last: String? = null
        while (true) {
            val cur = getAutoModeValue()
            if (cur != last) { emit(cur); last = cur }
            delay(1000)
        }
    }.flowOn(Dispatchers.IO)

    private fun getAutoModeValue(): String =
        PropertyUtils.get("persist.sys.azenithConfIntg.AIenabled")

    fun getCurrentProfileValue(): String {
        val fileVal = readRootFile(PROFILE_PATH)?.trim()
        if (!fileVal.isNullOrBlank()) return fileVal
        return when (AZenithControl.getParsedState()?.profile?.lowercase()) {
            "performance" -> "1"
            "balanced" -> "2"
            "eco" -> "3"
            "none" -> "0"
            else -> ""
        }
    }

    fun observeProfileRes(): Flow<Int> = callbackFlow {
        trySend(getCurrentProfileRes())
        val job = launch { watchDaemonFile(PROFILE_FILE_NAME) { trySend(getCurrentProfileRes()) } }
        awaitClose { job.cancel() }
    }.flowOn(Dispatchers.IO)

    fun getCurrentProfileRes(): Int {
        val content = getCurrentProfileValue()
        if (content.isBlank()) return R.string.status_unknown
        return when (content) {
            "0" -> R.string.status_initializing
            "1" -> {
                val isLite = PropertyUtils.get("persist.sys.azenithConfIntg.litemode", "0") == "1"
                if (isLite) R.string.profile_perflite else R.string.Profile_Performance
            }
            "2" -> R.string.Profile_Balanced
            "3" -> R.string.Profile_ECO_mode
            else -> R.string.status_unknown
        }
    }

    // ROM-integrated AZenith: Binder is the real service status.
    fun requestRootAccess(): Boolean = isServiceAlive()
    fun isRootGranted(): Boolean = isServiceAlive()
    fun getRootManager(): String = "ROM Integrated"

    fun observeServiceStatusRes(): Flow<Pair<Int, String>> = flow {
        var lastStatus: Pair<Int, String>? = null
        while (true) {
            val currentStatus = getServiceStatusRes()
            if (currentStatus != lastStatus) {
                emit(currentStatus)
                lastStatus = currentStatus
            }
            delay(2000)
        }
    }.flowOn(Dispatchers.IO)

    fun isModuleInstalled(): Boolean = File(DAEMON_BIN).exists() || isServiceAlive()

    fun getServiceStatusRes(): Pair<Int, String> = when {
        isServiceAlive() -> R.string.status_active to ""
        PropertyUtils.get("persist.sys.azenithIntg.state", "1") == "1" -> R.string.status_restarting to ""
        else -> R.string.status_offline to ""
    }

    fun isUpdateApkAvailable(): Boolean = false
    fun isModuleUpdatePendingReboot(): Boolean = false
    fun ensurePackageVisibility() {}
}
