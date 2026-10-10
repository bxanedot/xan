/**
 * xan Project (C) 2026
 * Licensed under GPL-3.0-only | See LICENCE for details
 */

package app.xan.music.utils

import androidx.datastore.preferences.core.edit
import com.music.innertube.YouTube
import app.xan.music.constants.VisitorDataKey
import app.xan.music.utils.cipher.CipherDeobfuscator
import app.xan.music.utils.PlaybackLogManager
import app.xan.music.utils.PlaybackLogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages YouTube bot-check recovery by refreshing the visitor identity when
 * YouTube explicitly asks the client to verify the request.
 *
 * Key improvements:
 * - Locale preservation: snapshot region/language before rotation and restore it
 *   to ensure new visitorData is issued for the user's correct country.
 * - Immediate rotation: removed thresholds/cooldowns for faster playback recovery.
 * - Surgical rotation: only clears visitorData, not the entire session.
 */
object BotDetectionMitigator {
    private const val TAG = "BotDetectionMitigator"

    private val failureCount = AtomicInteger(0)

    // Guards refreshVisitorSession(): several concurrent playback/download failures
    // can each call it at once (e.g. DownloadUtil launches one coroutine per
    // queued song). Without this, each caller independently nulls out then
    // re-fetches visitorData in parallel — a slower fetch can clobber a faster
    // one's freshly-issued token, and requests in between see a null window.
    private val rotationMutex = Mutex()

    // Error reasons that indicate geographic restriction – NOT a bot signal.
    // IMPORTANT: Keep these specific to avoid false positives.
    private val GEO_ERROR_SIGNATURES = listOf(
        "not available in your country",
        "not available in your region",
        "not available in this country",
        "not available in this region",
        "geo-restricted",
        "GEO_RESTRICTED",
        "NOT_AVAILABLE_IN_THIS_COUNTRY",
        "only available in certain countries",
        "country restriction",
        "region restriction",
    )

    // Be specific: HTTP 403 and Media3 error 2000 also describe unrelated
    // failures, so they must not trigger session rotation by themselves.
    private val BOT_ERROR_SIGNATURES = listOf(
        "sign in to confirm that you're not a bot",
        "sign in to confirm you're not a bot",
        "confirm you're not a bot",
        "automated queries",
    )

    /**
     * Call this when a playback error occurs.
     * Returns true if rotation might help (looks like bot detection).
     */
    fun notifyPlaybackFailure(isLoggedIn: Boolean, errorMessage: String? = null): Boolean {
        if (isLoggedIn) return false
        if (isGeoError(errorMessage)) return false

        failureCount.incrementAndGet()
        return true
    }

    /**
     * Call this when a track starts playing successfully.
     */
    fun notifyPlaybackSuccess() {
        failureCount.set(0)
    }

    /**
     * Refreshes visitorData while preserving locale, account cookies, and dataSyncId.
     * visitorData is independent of login state, so this can also recover a
     * stale or flagged visitor identity on an authenticated session.
     */
    suspend fun refreshVisitorSession(): Boolean = rotationMutex.withLock {
        Timber.tag(TAG).i("Refreshing YouTube visitor data after verification challenge")
        PlaybackLogManager.log(
            PlaybackLogLevel.BOT,
            "Refreshing YouTube visitor data",
            "Requesting new visitorData after an explicit verification challenge"
        )

        val refreshed = withContext(Dispatchers.IO) {
            // Snapshot locale so the new token is issued for the user's actual region.
            val currentLocale = YouTube.locale
            val previousVisitorData = YouTube.visitorData

            // Clear only visitorData; keep authentication and account selection intact.
            YouTube.visitorData = null

            YouTube.refreshVisitorData().fold(
                onSuccess = { newData ->
                    Timber.tag(TAG).i("New visitorData obtained successfully for region ${currentLocale.gl}.")

                    // Persist to DataStore
                    CipherDeobfuscator.appContext?.dataStore?.edit { settings ->
                        settings[VisitorDataKey] = newData
                    }
                    true
                },
                onFailure = { error ->
                    Timber.tag(TAG).e(error, "Failed to refresh visitorData during recovery")
                    // Keep the last known value; a failed refresh must not leave
                    // playback and later requests with a null visitor identity.
                    YouTube.visitorData = previousVisitorData
                    YouTube.locale = currentLocale
                    false
                }
            )
        }

        if (refreshed) failureCount.set(0)
        refreshed
    }

    /**
     * Returns true if message matches known geographic restriction patterns.
     */
    fun isGeoError(message: String?): Boolean {
        if (message == null) return false
        val lower = message.lowercase()
        return GEO_ERROR_SIGNATURES.any { lower.contains(it.lowercase()) }
    }

    /**
     * Returns true if message matches known bot-detection signatures.
     */
    fun isBotDetectionError(message: String?): Boolean {
        if (message == null) return false
        val lower = message.lowercase()
        return BOT_ERROR_SIGNATURES.any { lower.contains(it.lowercase()) }
    }

    /** Checks the full wrapped exception chain, as Media3 often hides the source message. */
    fun isBotDetectionError(error: Throwable?): Boolean {
        var current = error
        var depth = 0
        while (current != null && depth < 8) {
            if (isBotDetectionError(current.message)) return true
            current = current.cause
            depth++
        }
        return false
    }

    /**
     * Refresh visitorData only for an explicit bot-verification challenge.
     * This decision is independent of login state: a flagged visitorData value
     * can be attached to both guest and authenticated requests.
     */
    fun shouldRefreshVisitorSession(error: Throwable?): Boolean {
        var current = error
        var depth = 0
        var sawBotChallenge = false
        while (current != null && depth < 8) {
            val message = current.message
            if (isGeoError(message)) return false
            if (isBotDetectionError(message)) sawBotChallenge = true
            current = current.cause
            depth++
        }
        return sawBotChallenge
    }

    fun reset() {
        failureCount.set(0)
    }
}
