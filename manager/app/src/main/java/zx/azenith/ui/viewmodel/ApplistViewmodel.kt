package zx.azenith.ui.viewmodel

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Parcelable
import android.os.SystemClock
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import zx.azenith.R
import zx.azenith.ui.util.AZenithControl

class ApplistViewmodel : ViewModel() {

    companion object {
        private const val MIN_REFRESH_VISIBLE_MS = 400L
        private val appsLock = Any()
        var apps by mutableStateOf<List<AppInfo>>(emptyList())

        @JvmStatic
        fun getAppIconDrawable(context: Context, packageName: String): Drawable? {
            val appList = synchronized(appsLock) { apps }
            val appDetail = appList.find { it.packageName == packageName }
            if (appDetail == null || !appDetail.isInstalled) return null
            return appDetail.packageInfo.applicationInfo?.loadIcon(context.packageManager)
        }
    }

    @Parcelize
    data class AppInfo(
        val label: String,
        val packageInfo: PackageInfo,
        val isRecommended: Boolean = false,
        var isEnabledInConfig: Boolean = false,
        val isInstalled: Boolean = true
    ) : Parcelable {
        val packageName: String get() = packageInfo.packageName
        val isSystem: Boolean
            get() = isInstalled &&
                (((packageInfo.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM) != 0)
        val uid: Int get() = packageInfo.applicationInfo?.uid ?: 0

        internal val searchLabel: String = label.lowercase(Locale.getDefault())
        internal val searchPackage: String = packageName.lowercase(Locale.getDefault())
    }

    var isRefreshing by mutableStateOf(false)
    var daemonAvailable by mutableStateOf(true)
        private set
    var daemonError by mutableStateOf<String?>(null)
        private set

    enum class SystemFilter { SHOW_ALL, ONLY_SYSTEM, HIDE_SYSTEM }

    var systemFilter by mutableStateOf(SystemFilter.HIDE_SYSTEM)
    var filterGames by mutableStateOf(false)
    var filterEnabled by mutableStateOf(false)
    var filterDisabled by mutableStateOf(false)

    val activeFilterCount: Int
        get() = (if (filterGames) 1 else 0) +
                (if (filterEnabled) 1 else 0) +
                (if (filterDisabled) 1 else 0) +
                (if (systemFilter != SystemFilter.HIDE_SYSTEM) 1 else 0)

    fun resetFilters() {
        filterGames = false
        filterEnabled = false
        filterDisabled = false
        systemFilter = SystemFilter.HIDE_SYSTEM
    }

    var searchTextFieldValue by mutableStateOf(TextFieldValue(""))
        private set

    private val searchQueryString: String get() = searchTextFieldValue.text
    val searchQuery: String get() = searchTextFieldValue.text

    fun updateSearch(newValue: TextFieldValue) {
        searchTextFieldValue = newValue
    }

    fun clearSearch() {
        searchTextFieldValue = TextFieldValue("")
    }

    private fun matchesFilters(
        app: AppInfo,
        query: String,
        games: Boolean,
        enabled: Boolean,
        disabled: Boolean,
        system: SystemFilter,
    ): Boolean {
        if (!(app.searchLabel.contains(query) || app.searchPackage.contains(query))) return false
        if (games && !(app.isRecommended || (!app.isInstalled && app.isEnabledInConfig))) return false
        val serviceOk = (!enabled && !disabled) ||
            (enabled && app.isEnabledInConfig) ||
            (disabled && !app.isEnabledInConfig)
        if (!serviceOk) return false
        return when (system) {
            SystemFilter.SHOW_ALL -> true
            SystemFilter.ONLY_SYSTEM -> app.isSystem
            SystemFilter.HIDE_SYSTEM -> !app.isSystem
        }
    }

    val filteredApps by derivedStateOf {
        val query = searchQueryString.lowercase(Locale.getDefault())
        synchronized(appsLock) {
            apps.filter {
                matchesFilters(it, query, filterGames, filterEnabled, filterDisabled, systemFilter)
            }.sortedWith(appComparator)
        }
    }

    fun countIf(
        games: Boolean = filterGames,
        enabled: Boolean = filterEnabled,
        disabled: Boolean = filterDisabled,
        system: SystemFilter = systemFilter,
    ): Int {
        val query = searchQueryString.lowercase(Locale.getDefault())
        return synchronized(appsLock) {
            apps.count { matchesFilters(it, query, games, enabled, disabled, system) }
        }
    }

    private val appComparator: Comparator<AppInfo> = run {
        val collator = Collator.getInstance(Locale.getDefault())
        compareByDescending<AppInfo> { !it.isInstalled && it.isEnabledInConfig }
            .thenByDescending { it.isEnabledInConfig }
            .thenByDescending { it.isRecommended }
            .then(compareBy(collator) { it.label })
    }

    fun loadApps(context: Context, forceRefresh: Boolean = false) {
        if (!forceRefresh && apps.isNotEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { isRefreshing = true }
            val refreshStarted = SystemClock.elapsedRealtime()
            val pm = context.packageManager

            val daemonResult = AZenithControl.getGamesResult()
            val enabledList = daemonResult.getOrDefault(emptySet())
            val installed = pm.getInstalledPackages(PackageManager.GET_META_DATA)
            val installedPkgNames = HashSet<String>(installed.size)

            val loadedApps = installed.map { pkg ->
                installedPkgNames.add(pkg.packageName)
                val appInfo = pkg.applicationInfo
                @Suppress("DEPRECATION")
                val isGame = appInfo != null && (
                    appInfo.category == ApplicationInfo.CATEGORY_GAME ||
                    (appInfo.flags and ApplicationInfo.FLAG_IS_GAME) != 0
                )
                AppInfo(
                    label = appInfo?.loadLabel(pm)?.toString()
                        ?: context.getString(R.string.status_unknown),
                    packageInfo = pkg,
                    isRecommended = isGame,
                    isEnabledInConfig = enabledList.contains(pkg.packageName),
                    isInstalled = true
                )
            }

            // Detect packages in the daemon's managed game list that are no longer installed.
            val missingApps = enabledList
                .filter { pkg -> pkg.isNotBlank() && !installedPkgNames.contains(pkg) }
                .sorted()
                .map { pkg ->
                    val syntheticPkg = PackageInfo().apply {
                        this.packageName = pkg
                    }
                    AppInfo(
                        label = pkg,
                        packageInfo = syntheticPkg,
                        isRecommended = false,
                        isEnabledInConfig = true,
                        isInstalled = false
                    )
                }

            val combined = missingApps + loadedApps

            withContext(Dispatchers.Main) {
                daemonAvailable = daemonResult.isSuccess
                daemonError = daemonResult.exceptionOrNull()?.message
                synchronized(appsLock) {
                    apps = combined
                }
                val elapsed = SystemClock.elapsedRealtime() - refreshStarted
                val remaining = MIN_REFRESH_VISIBLE_MS - elapsed
                if (remaining > 0) delay(remaining)
                isRefreshing = false
            }
        }
    }

    fun refreshAppConfigStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            val daemonResult = AZenithControl.getGamesResult()
            val enabledList = daemonResult.getOrElse {
                withContext(Dispatchers.Main) {
                    daemonAvailable = false
                    daemonError = it.message
                }
                return@launch
            }
            val updated = synchronized(appsLock) {
                val installedEntries = apps
                    .filter { it.isInstalled }
                    .map { it.copy(isEnabledInConfig = enabledList.contains(it.packageName)) }
                val installedSet = installedEntries.mapTo(HashSet()) { it.packageName }
                val missingEntries = enabledList
                    .filter { pkg -> pkg.isNotBlank() && !installedSet.contains(pkg) }
                    .sorted()
                    .map { pkg ->
                        val syntheticPkg = PackageInfo().apply { this.packageName = pkg }
                        AppInfo(
                            label = pkg,
                            packageInfo = syntheticPkg,
                            isRecommended = false,
                            isEnabledInConfig = true,
                            isInstalled = false
                        )
                    }
                missingEntries + installedEntries
            }
            withContext(Dispatchers.Main) {
                daemonAvailable = true
                daemonError = null
                synchronized(appsLock) {
                    apps = updated
                }
            }
        }
    }

    fun removeManagedGame(
        context: Context,
        packageName: String,
        onComplete: (Boolean) -> Unit = {}
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val removed = AZenithControl.removeGameConfig(packageName)
            withContext(Dispatchers.Main) {
                if (removed) {
                    daemonAvailable = true
                    daemonError = null
                    synchronized(appsLock) {
                        apps = apps.mapNotNull { entry ->
                            when {
                                entry.packageName != packageName -> entry
                                !entry.isInstalled -> null
                                else -> entry.copy(isEnabledInConfig = false)
                            }
                        }
                    }
                    onComplete(true)
                } else {
                    daemonAvailable = AZenithControl.isDaemonAvailable()
                    daemonError = context.getString(R.string.daemon_sync_failed)
                    onComplete(false)
                }
            }
        }
    }

    fun rescanDaemonGames(context: Context, onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = AZenithControl.rescanGames()
            withContext(Dispatchers.Main) {
                onComplete(ok)
                loadApps(context, forceRefresh = true)
            }
        }
    }
}
