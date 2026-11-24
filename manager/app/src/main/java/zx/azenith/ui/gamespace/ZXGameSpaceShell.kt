@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package zx.azenith.ui.gamespace

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import zx.azenith.R
import zx.azenith.ui.util.AZenithControl
import zx.azenith.ui.util.RootUtils
import zx.azenith.ui.viewmodel.ApplistViewmodel

@Composable
fun ZXGameSpaceShell(
    onExit: () -> Unit,
    onExitApp: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val motion = MaterialTheme.motionScheme
    val applist: ApplistViewmodel = viewModel()
    val scope = rememberCoroutineScope()

    var introActive by remember { mutableStateOf(true) }
    var leaving by remember { mutableStateOf(false) }
    var pendingRemoveApp by remember { mutableStateOf<ApplistViewmodel.AppInfo?>(null) }
    val hubFade = remember { Animatable(0f) }
    val hubScale = remember { Animatable(0.95f) }
    val exitFade = remember { Animatable(0f) }

    LaunchedEffect(Unit) { applist.loadApps(context, forceRefresh = true) }

    // Refresh installed/uninstalled detection whenever Game Space resumes.
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                applist.loadApps(context, forceRefresh = true)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Live refresh when packages are installed, removed, or replaced.
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                applist.loadApps(context, forceRefresh = true)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    val installed = ApplistViewmodel.apps
    val games = remember(installed) {
        installed
            .filter { it.isRecommended || it.isEnabledInConfig }
            .sortedWith(
                compareByDescending<ApplistViewmodel.AppInfo> { !it.isInstalled && it.isEnabledInConfig }
                    .thenByDescending { it.isEnabledInConfig }
                    .thenBy { it.label.lowercase() }
            )
    }
    val userApps = remember(installed) {
        installed
            .filter { !it.isSystem }
            .sortedWith(
                compareByDescending<ApplistViewmodel.AppInfo> { !it.isInstalled && it.isEnabledInConfig }
                    .thenByDescending { it.isEnabledInConfig }
                    .thenByDescending { it.isRecommended }
                    .thenBy { it.label.lowercase() }
            )
    }

    val profileRes by RootUtils.observeProfileRes().collectAsState(initial = R.string.status_unknown)
    val profileValue by RootUtils.observeProfileValue().collectAsState(initial = "")
    val profile = stringResource(profileRes)

    LaunchedEffect(leaving) {
        if (!leaving) return@LaunchedEffect
        exitFade.animateTo(1f, motion.defaultEffectsSpec())
        onExit()
    }

    BackHandler(enabled = introActive) {
        introActive = false
        scope.launch {
            hubFade.snapTo(1f)
            hubScale.snapTo(1f)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = hubFade.value
                    scaleX = hubScale.value
                    scaleY = hubScale.value
                }
        ) {
            ZXGameSpaceHub(
                introActive = introActive,
                profile = profile,
                profileValue = profileValue,
                games = games,
                allApps = userApps,
                onApplyProfile = { id ->
                    scope.launch(Dispatchers.IO) {
                        AZenithControl.applyProfile(id)
                    }
                },
                onLaunch = { app ->
                    if (!app.isInstalled) {
                        pendingRemoveApp = app
                    } else {
                        launchGame(context, app.packageName)
                    }
                },
                onExit = { leaving = true },
                onExitApp = onExitApp,
                loading = applist.isRefreshing
            )
        }

        if (!introActive && !applist.daemonAvailable) {
            Surface(
                onClick = { applist.loadApps(context, forceRefresh = true) },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shadowElevation = 4.dp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CloudOff,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "${stringResource(R.string.daemon_unavailable_title)} — ${stringResource(R.string.action_retry)}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        pendingRemoveApp?.let { stale ->
            AlertDialog(
                onDismissRequest = { pendingRemoveApp = null },
                title = { Text(stringResource(R.string.remove_uninstalled_title)) },
                text = {
                    Text(stringResource(R.string.remove_uninstalled_message, stale.packageName))
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pendingRemoveApp = null
                            applist.removeManagedGame(context, stale.packageName) { ok ->
                                val msg = if (ok) {
                                    context.getString(R.string.uninstalled_removed_msg, stale.packageName)
                                } else {
                                    context.getString(R.string.daemon_sync_failed)
                                }
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(stringResource(R.string.action_remove))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingRemoveApp = null }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
        }

        if (introActive) {
            ZXGameSpaceIntro(
                onTransitionStart = {
                    scope.launch {
                        launch {
                            hubFade.animateTo(
                                targetValue = 1f,
                                animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing)
                            )
                        }
                        launch {
                            hubScale.animateTo(
                                targetValue = 1f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioLowBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                )
                            )
                        }
                    }
                },
                onFinished = {
                    introActive = false
                    scope.launch {
                        hubFade.snapTo(1f)
                        hubScale.snapTo(1f)
                    }
                }
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = exitFade.value }
                .background(MaterialTheme.colorScheme.surface)
        )
    }
}

internal fun launchGame(context: android.content.Context, packageName: String) {
    zx.azenith.ui.gamespace.ZXSidePanelService.registerAppOpenedFromAzenith(packageName)
    zx.azenith.ui.gamespace.ZXSidePanelService.show(context, packageName)
    val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return
    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
