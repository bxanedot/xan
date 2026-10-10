/**
 * xan Project (C) 2026
 * Licensed under GPL-3.0-only | See LICENCE for details
 */
// Adapted player interface; distributed under GPL-3.0.

package app.xan.music.ui.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.xan.music.canvas.CanvasArtwork
import app.xan.music.canvas.TidalCanvasProvider
import app.xan.music.applecanvas.AppleMusicCanvasProvider
import app.xan.music.xancanvas.XanCanvasProvider
import app.xan.music.constants.CanvasSource
import app.xan.music.constants.CanvasSourceKey
import app.xan.music.constants.CanvasWifiOnlyKey
import app.xan.music.models.MediaMetadata
import app.xan.music.utils.rememberEnumPreference
import app.xan.music.utils.rememberPreference
import app.xan.music.constants.CanvasThumbnailAnimationKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun PlayerV2Canvas(
    mediaMetadata: MediaMetadata?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    if (mediaMetadata == null) return

    val enableCanvas by rememberPreference(CanvasThumbnailAnimationKey, defaultValue = true)
    if (!enableCanvas) return
    val context = LocalContext.current
    val wifiOnly by rememberPreference(CanvasWifiOnlyKey, defaultValue = false)
    val onWifi by rememberIsConnectedToWifi(context)

    val (canvasSource) = rememberEnumPreference(CanvasSourceKey, defaultValue = CanvasSource.AUTO)
    val albumTitle = mediaMetadata.album?.title
    var canvasArtwork by remember(mediaMetadata.id, albumTitle) { mutableStateOf<CanvasArtwork?>(null) }
    
    val storefront = remember {
        val country = Locale.getDefault().country
        if (country.length == 2) country.lowercase(Locale.ROOT) else "us"
    }

    LaunchedEffect(mediaMetadata.id, albumTitle, canvasSource, wifiOnly, onWifi) {
        if (wifiOnly && !onWifi) {
            canvasArtwork = null
            return@LaunchedEffect
        }

        val cacheKey = "${mediaMetadata.id}:${canvasSource.name}"
        CanvasArtworkPlaybackCache.get(cacheKey)?.let { cached ->
            canvasArtwork = cached
            return@LaunchedEffect
        }

        val fetched = withContext(Dispatchers.IO) {
            val songTitle = mediaMetadata.title ?: ""
            val artistName = mediaMetadata.artists.firstOrNull()?.name ?: ""
            val albumName = albumTitle ?: ""

            when (canvasSource) {
                CanvasSource.AUTO -> {
                    AppleMusicCanvasProvider.getBySongArtist(songTitle, artistName, albumName, storefront)
                        ?.takeIf { !it.animated.isNullOrBlank() }
                        ?: TidalCanvasProvider.getBySongArtist(songTitle, artistName, albumName)
                            ?.takeIf { !it.animated.isNullOrBlank() }
                        ?: XanCanvasProvider.getBySongArtist(songTitle, artistName)
                            ?.takeIf { !it.animated.isNullOrBlank() }
                }
                CanvasSource.APPLE_MUSIC -> {
                    AppleMusicCanvasProvider.getBySongArtist(songTitle, artistName, albumName, storefront)
                        ?.takeIf { !it.animated.isNullOrBlank() }
                }
                CanvasSource.XAN -> {
                    XanCanvasProvider.getBySongArtist(songTitle, artistName)
                        ?.takeIf { !it.animated.isNullOrBlank() }
                }
                CanvasSource.TIDAL -> {
                    TidalCanvasProvider.getBySongArtist(songTitle, artistName, albumName)
                        ?.takeIf { !it.animated.isNullOrBlank() }
                }
                else -> null
            }
        }

        if (fetched != null) {
            canvasArtwork = fetched
            CanvasArtworkPlaybackCache.put(cacheKey, fetched)
        }
    }

    if (!wifiOnly || onWifi) canvasArtwork?.let { artwork ->
        CanvasArtworkPlayer(
            primaryUrl = artwork.preferredAnimationUrl,
            fallbackUrl = artwork.animated,
            isPlaying = isPlaying,
            modifier = modifier
        )
    }
}

@Composable
private fun rememberIsConnectedToWifi(context: Context) = remember(context) {
    callbackFlow {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        fun activeNetworkUsesWifi(): Boolean {
            val network = manager.activeNetwork ?: return false
            val capabilities = manager.getNetworkCapabilities(network) ?: return false
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(activeNetworkUsesWifi()) }
            override fun onLost(network: Network) { trySend(activeNetworkUsesWifi()) }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                trySend(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
            }
        }

        trySend(activeNetworkUsesWifi())
        try {
            manager.registerDefaultNetworkCallback(callback)
            awaitClose { runCatching { manager.unregisterNetworkCallback(callback) } }
        } catch (_: SecurityException) {
            trySend(false)
            awaitClose { }
        } catch (_: RuntimeException) {
            trySend(false)
            awaitClose { }
        }
    }.conflate().distinctUntilChanged()
}.collectAsState(initial = false)
