package zx.azenith.ui.component

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import zx.azenith.ui.viewmodel.ApplistViewmodel

object AppIconCache {
    private val cache = LruCache<String, ImageBitmap>(150)

    fun get(packageName: String): ImageBitmap? = synchronized(cache) { cache.get(packageName) }

    fun clear() = synchronized(cache) { cache.evictAll() }

    suspend fun loadIcon(pm: PackageManager, appInfo: ApplicationInfo, targetSizePx: Int): ImageBitmap =
        withContext(Dispatchers.IO) {
            get(appInfo.packageName)?.let { return@withContext it }

            val drawable = try {
                appInfo.loadIcon(pm) ?: pm.defaultActivityIcon
            } catch (_: Exception) {
                pm.defaultActivityIcon
            }

            val bitmap = Bitmap.createBitmap(targetSizePx, targetSizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, targetSizePx, targetSizePx)
            drawable.draw(canvas)

            val imageBitmap = bitmap.asImageBitmap()
            synchronized(cache) {
                cache.put(appInfo.packageName, imageBitmap)
            }
            imageBitmap
        }
}

@Composable
fun AppIconImage(
    app: ApplistViewmodel.AppInfo,
    size: Dp = 40.dp
) {
    if (!app.isInstalled) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.errorContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.BrokenImage,
                contentDescription = app.label,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(size * 0.55f)
            )
        }
        return
    }

    val context = LocalContext.current
    val pm = context.packageManager
    val density = LocalDensity.current
    val targetSizePx = remember(size, density) {
        with(density) { size.roundToPx() }
    }

    var appBitmap by remember(app.packageName) {
        mutableStateOf(AppIconCache.get(app.packageName))
    }

    LaunchedEffect(app.packageName, targetSizePx) {
        if (appBitmap == null) {
            app.packageInfo.applicationInfo?.let { appInfo ->
                try {
                    appBitmap = AppIconCache.loadIcon(pm, appInfo, targetSizePx)
                } catch (_: Exception) {
                }
            }
        }
    }

    Box(modifier = Modifier.size(size)) {
        Crossfade(
            targetState = appBitmap,
            animationSpec = tween(durationMillis = 200),
            label = "IconFade"
        ) { icon ->
            if (icon == null) {
                PlaceHolderBox(Modifier.fillMaxSize())
            } else {
                Image(
                    bitmap = icon,
                    contentDescription = app.label,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(8.dp))
                )
            }
        }
    }
}

@Composable
private fun PlaceHolderBox(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
    )
}
