/**
 * xan Project (C) 2026
 * Licensed under GPL-3.0-only | See LICENCE for details
 */

package app.xan.music.lyrics

import android.content.Context
import android.util.LruCache
import app.xan.music.constants.LyricsProviderOrderKey
import app.xan.music.constants.PreferredLyricsProvider
import app.xan.music.constants.PreferredLyricsProviderKey
import app.xan.music.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import app.xan.music.extensions.toEnum
import app.xan.music.models.MediaMetadata
import app.xan.music.utils.NetworkConnectivityObserver
import app.xan.music.utils.dataStore
import app.xan.music.utils.reportException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

class LyricsHelper
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val networkConnectivity: NetworkConnectivityObserver,
) {
    /**
     * Resolves the ordered list of lyrics providers from the user's saved priority order.
     * Falls back to migrating the legacy [PreferredLyricsProvider] enum if the new order
     * preference has not been written yet, ensuring a smooth upgrade for existing users.
     */
    private suspend fun resolveLyricsProviders(): List<LyricsProvider> {
        val preferences = context.dataStore.data.first()
        val orderString = preferences[LyricsProviderOrderKey].orEmpty()

        if (orderString.isNotBlank()) {
            val savedOrder = LyricsProviderRegistry.deserializeProviderOrder(orderString)
            val completeOrder = savedOrder + LyricsProviderRegistry.getDefaultProviderOrder()
                .filterNot { it in savedOrder }
            return completeOrder.mapNotNull(LyricsProviderRegistry::getProviderByName)
        }

        // Migration path: place the old preferred provider first in the default order
        val preferredEnum = preferences[PreferredLyricsProviderKey]
            .toEnum(PreferredLyricsProvider.YOULYPLUS)
        val preferredName = LyricsProviderRegistry.getProviderNameForEnum(preferredEnum)
        val defaultOrder = LyricsProviderRegistry.getDefaultProviderOrder()
        val migratedOrder = listOf(preferredName) + defaultOrder.filter { it != preferredName }
        return migratedOrder.mapNotNull { LyricsProviderRegistry.getProviderByName(it) }
    }



    private val cache = LruCache<String, List<LyricsResult>>(MAX_CACHE_SIZE)
    private var currentLyricsJob: Job? = null

    suspend fun getLyrics(mediaMetadata: MediaMetadata): LyricsWithProvider {
        currentLyricsJob?.cancel()

        val cached = (cache.get(singleCacheKey(mediaMetadata.id))
            ?: cache.get(allCacheKey(mediaMetadata.id)))
            ?.maxByOrNull { lyricSyncQuality(it.lyrics) }
        if (cached != null) {
            return LyricsWithProvider(cached.lyrics, cached.providerName)
        }

        // Check network connectivity before making network requests
        // Use synchronous check as fallback if flow doesn't emit
        val isNetworkAvailable = try {
            networkConnectivity.isCurrentlyConnected()
        } catch (e: Exception) {
            // If network check fails, try to proceed anyway
            true
        }
        
        if (!isNetworkAvailable) {
            // Still proceed but return not found to avoid hanging
            return LyricsWithProvider(LYRICS_NOT_FOUND, "Unknown")
        }

        val providers = resolveLyricsProviders()
        val rankedResults = supervisorScope {
            providers.mapIndexed { priority, provider ->
                async {
                    try {
                        if (!provider.isEnabled(context)) return@async null
                        val response = withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                            provider.getLyrics(
                                LyricsTrackMetadata(
                                    id = mediaMetadata.id,
                                    title = mediaMetadata.title,
                                    artist = mediaMetadata.artists.joinToString { it.name },
                                    duration = mediaMetadata.duration,
                                    album = mediaMetadata.album?.title,
                                    isrc = mediaMetadata.isrc,
                                )
                            ).getOrNull()
                        }
                        val lyrics = response?.takeIf { it.isNotBlank() && it != LYRICS_NOT_FOUND }
                            ?: return@async null
                        RankedLyrics(
                            result = LyricsResult(provider.name, lyrics),
                            quality = lyricSyncQuality(lyrics),
                            priority = priority,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        reportException(error)
                        null
                    }
                }
            }.awaitAll()
        }

        // Stable sort keeps the user's saved priority when providers return the
        // same synchronization quality.
        val orderedResults = rankedResults.filterNotNull()
            .sortedWith(compareByDescending<RankedLyrics> { it.quality }.thenBy { it.priority })
        val winner = orderedResults.firstOrNull()?.result
            ?: return LyricsWithProvider(LYRICS_NOT_FOUND, "Unknown")
        cache.put(singleCacheKey(mediaMetadata.id), listOf(winner))
        return LyricsWithProvider(winner.lyrics, winner.providerName)
    }

    suspend fun getAllLyrics(
        mediaId: String,
        songTitle: String,
        songArtists: String,
        duration: Int,
        album: String? = null,
        callback: (LyricsResult) -> Unit,
    ) {
        currentLyricsJob?.cancel()

        cache.get(allCacheKey(mediaId))?.let { results ->
            results.forEach {
                callback(it)
            }
            return
        }

        // Check network connectivity before making network requests
        // Use synchronous check as fallback if flow doesn't emit
        val isNetworkAvailable = try {
            networkConnectivity.isCurrentlyConnected()
        } catch (e: Exception) {
            // If network check fails, try to proceed anyway
            true
        }
        
        if (!isNetworkAvailable) {
            // Still try to proceed in case of false negative
            return
        }

        val allResult = mutableListOf<LyricsResult>()
        val providers = resolveLyricsProviders()
        currentLyricsJob = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            val updates = Channel<LyricsResult>(Channel.UNLIMITED)
            val seenLyrics = mutableSetOf<String>()
            val collector = launch {
                for (result in updates) {
                    if (seenLyrics.add(result.lyrics)) {
                        allResult += result
                        callback(result)
                    }
                }
            }
            try {
                supervisorScope {
                    providers.map { provider ->
                        async {
                            try {
                                if (!provider.isEnabled(context)) return@async
                                withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                                    provider.getAllLyrics(mediaId, songTitle, songArtists, duration, album) { lyrics ->
                                        if (lyrics.isNotBlank() && lyrics != LYRICS_NOT_FOUND) {
                                            updates.trySend(LyricsResult(provider.name, lyrics))
                                        }
                                    }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                reportException(error)
                            }
                        }
                    }.awaitAll()
                }
            } finally {
                updates.close()
                collector.join()
                if (allResult.isNotEmpty()) cache.put(allCacheKey(mediaId), allResult.toList())
            }
        }

        currentLyricsJob?.join()
    }

    fun cancelCurrentLyricsJob() {
        currentLyricsJob?.cancel()
        currentLyricsJob = null
    }

    /**
     * 3 = word/syllable timed, 2 = line timed, 1 = plain text.
     * Supports both enhanced-LRC word timestamps and the richer timing syntax
     * returned by some YouLyPlus/LyricsPlus mirrors.
     */
    private fun lyricSyncQuality(lyrics: String): Int {
        if (lyrics.isBlank() || lyrics == LYRICS_NOT_FOUND) return 0
        val hasWordTiming =
            Regex("<\\d{1,2}:\\d{2}(?:\\.\\d{2,3})?>").containsMatchIn(lyrics) ||
                Regex("(?m)^<[^>]+:\\d+(?:\\.\\d+)?:\\d+(?:\\.\\d+)?(?:\\|[^>]+)*>$")
                    .containsMatchIn(lyrics)
        if (hasWordTiming) return 3

        val hasLineTiming =
            Regex("\\[\\d{1,2}:\\d{2}(?:\\.\\d{2,3})?]").containsMatchIn(lyrics)
        return if (hasLineTiming) 2 else 1
    }

    private fun singleCacheKey(mediaId: String) = "single:$mediaId"
    private fun allCacheKey(mediaId: String) = "all:$mediaId"

    private data class RankedLyrics(
        val result: LyricsResult,
        val quality: Int,
        val priority: Int,
    )

    companion object {
        private const val MAX_CACHE_SIZE = 3
        private const val PROVIDER_TIMEOUT_MS = 8_000L
    }
}

data class LyricsResult(
    val providerName: String,
    val lyrics: String,
)

data class LyricsWithProvider(
    val lyrics: String,
    val provider: String,
)
