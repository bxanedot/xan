/**
 * xan Project (C) 2026
 * Licensed under GPL-3.0-only | See LICENCE for details
 */

package app.xan.music.playback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class SponsorSegment(
    val startMs: Long,
    val endMs: Long,
    val category: String,
)

object SponsorBlockClient {
    private const val TAG = "SponsorBlock"
    private val categories =
        JSONArray(
            listOf(
                "sponsor",
                "selfpromo",
                "interaction",
                "music_offtopic",
            ),
        ).toString()

    private val client =
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(7, TimeUnit.SECONDS)
            .callTimeout(9, TimeUnit.SECONDS)
            .build()

    private val cache = ConcurrentHashMap<String, List<SponsorSegment>>()

    suspend fun getSegments(videoId: String): List<SponsorSegment> {
        if (videoId.isBlank()) return emptyList()
        cache[videoId]?.let { return it }

        return withContext(Dispatchers.IO) {
            val result =
                runCatching {
                    val url =
                        "https://sponsor.ajay.app/api/skipSegments"
                            .toHttpUrl()
                            .newBuilder()
                            .addQueryParameter("videoID", videoId)
                            .addQueryParameter("categories", categories)
                            .build()

                    client
                        .newCall(Request.Builder().url(url).get().build())
                        .execute()
                        .use { response ->
                            if (response.code == 404) return@use emptyList()
                            if (!response.isSuccessful) {
                                throw IllegalStateException("SponsorBlock HTTP ${response.code}")
                            }

                            val array = JSONArray(response.body?.string().orEmpty())
                            buildList {
                                for (index in 0 until array.length()) {
                                    val item = array.optJSONObject(index) ?: continue
                                    val segment = item.optJSONArray("segment") ?: continue
                                    if (segment.length() < 2) continue

                                    val startMs = (segment.optDouble(0, -1.0) * 1000.0).toLong()
                                    val endMs = (segment.optDouble(1, -1.0) * 1000.0).toLong()
                                    if (startMs < 0 || endMs <= startMs) continue

                                    add(
                                        SponsorSegment(
                                            startMs = startMs,
                                            endMs = endMs,
                                            category = item.optString("category", "sponsor"),
                                        ),
                                    )
                                }
                            }
                        }
                }

            result
                .onSuccess { cache[videoId] = it }
                .onFailure { Timber.tag(TAG).w(it, "Failed to fetch segments for %s", videoId) }
                .getOrDefault(emptyList())
        }
    }
}
