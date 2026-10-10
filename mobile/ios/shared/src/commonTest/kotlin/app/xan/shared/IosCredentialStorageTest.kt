package app.xan.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosCredentialStorageTest {
    @Test
    fun storesValidYouTubeSessionCookieAndCanRemoveIt() {
        val store = FakeYouTubeCredentialStore()
        IosCredentialStorage.install(store)
        try {
            val cookie = "SAPISID=123456789012345678901234567890; HSID=abc"
            assertTrue(IosCredentialStorage.isYouTubeCookieValid(cookie))
            assertTrue(IosCredentialStorage.saveYouTubeCookie(cookie))
            assertEquals(cookie, IosCredentialStorage.youtubeCookie())
            assertTrue(IosCredentialStorage.hasYouTubeCookie())

            assertTrue(IosCredentialStorage.clearYouTubeCookie())
            assertNull(IosCredentialStorage.youtubeCookie())
            assertEquals(2, store.saveCalls)
        } finally {
            IosCredentialStorage.install(null)
        }
    }

    @Test
    fun rejectsCookieWithoutNonEmptySapisidBeforeCallingSecureStore() {
        val store = FakeYouTubeCredentialStore()
        IosCredentialStorage.install(store)
        try {
            assertFalse(IosCredentialStorage.isYouTubeCookieValid("HSID=123456789012345678901234567890"))
            assertFalse(IosCredentialStorage.saveYouTubeCookie("SAPISID=; HSID=123456789012345678901234567890"))
            assertFalse(IosCredentialStorage.saveYouTubeCookie("SAPISID=123456789012345678901234567890\r\nX-Injected: bad"))
            assertEquals(0, store.saveCalls)
        } finally {
            IosCredentialStorage.install(null)
        }
    }

    @Test
    fun reportsUnavailableSecureStoreWithoutClaimingSaveSucceeded() {
        IosCredentialStorage.install(null)
        assertFalse(IosCredentialStorage.saveYouTubeCookie("SAPISID=123456789012345678901234567890"))
        assertFalse(IosCredentialStorage.clearYouTubeCookie())
        assertNull(IosCredentialStorage.youtubeCookie())
    }

    private class FakeYouTubeCredentialStore : YouTubeCredentialStore {
        private var cookie: String? = null
        var saveCalls = 0
            private set

        override fun loadCookie(): String? = cookie

        override fun saveCookie(cookie: String?): Boolean {
            saveCalls++
            this.cookie = cookie
            return true
        }
    }
}
