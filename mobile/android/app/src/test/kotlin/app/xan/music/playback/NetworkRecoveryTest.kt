package app.xan.music.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkRecoveryTest {
    @Test
    fun `a long outage still retries when the connection returns`() = runTest {
        val connected = MutableStateFlow(false)
        var retried = false
        val waiter = launch { awaitNetworkRetry(connected, 3000); retried = true }
        advanceTimeBy(300_000)
        runCurrent()
        assertFalse(retried)
        assertTrue(waiter.isActive)
        connected.value = true
        runCurrent()
        assertTrue(retried)
    }

    @Test
    fun `a connected failure respects its backoff`() = runTest {
        val connected = MutableStateFlow(true)
        var retried = false
        launch { awaitNetworkRetry(connected, 3000); retried = true }
        advanceTimeBy(2999)
        runCurrent()
        assertFalse(retried)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(retried)
    }

    @Test
    fun `losing the connection during backoff waits for reconnection`() = runTest {
        val connected = MutableStateFlow(true)
        var retried = false
        launch { awaitNetworkRetry(connected, 3000); retried = true }
        advanceTimeBy(1000)
        connected.value = false
        advanceTimeBy(10_000)
        runCurrent()
        assertFalse(retried)
        connected.value = true
        runCurrent()
        assertTrue(retried)
    }

    @Test
    fun `a cancelled recovery cannot retry after a track change`() = runTest {
        val connected = MutableStateFlow(false)
        var retried = false
        val waiter = launch { awaitNetworkRetry(connected, 3000); retried = true }
        advanceTimeBy(5000)
        runCurrent()
        waiter.cancel()
        connected.value = true
        runCurrent()
        assertTrue(waiter.isCancelled)
        assertFalse(retried)
    }
}
