package com.mtechviral.musicfinderexample.core.easytier

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TunnelConfigurationUpdateTest {
    @Test
    fun enabledTunnelStopsBeforeSaveAndStartsWithNewHostAndPort() = runBlocking {
        val events = mutableListOf<String>()
        var address = "http://10.0.0.222:4533"
        var target: Pair<String, Int>? = null
        val result = updateTunnelConfiguration(
            enabled = true, changed = true,
            stop = { events += "stop" },
            start = { events += "start"; target = EasyTierConfig().resolveForwardTarget(address) },
            update = { events += "save"; address = "http://10.0.0.223:8002"; "saved" },
        )
        assertEquals("saved", result)
        assertEquals(listOf("stop", "save", "start"), events)
        assertEquals("10.0.0.223" to 8002, target)
    }

    @Test
    fun disabledTunnelIsNotTouchedEvenWhenConfigurationChanges() = runBlocking {
        var saved = false
        updateTunnelConfiguration(
            enabled = false, changed = true,
            stop = { fail("Disabled tunnel must not be stopped") },
            start = { fail("Disabled tunnel must not be enabled") },
            update = { saved = true },
        )
        assertTrue(saved)
    }

    @Test
    fun unchangedConfigurationDoesNotDisconnectEnabledTunnel() = runBlocking {
        val result = updateTunnelConfiguration(
            enabled = true, changed = false,
            stop = { fail("Unchanged configuration must not disconnect") },
            start = { fail("Unchanged configuration must not restart") },
            update = { "saved" },
        )
        assertEquals("saved", result)
    }

    @Test
    fun failedSaveRestartsWithPreviouslyCommittedTargetAndPreservesError() = runBlocking {
        val expected = IllegalStateException("Could not save")
        val oldAddress = "http://10.0.0.222:4533"
        var restartedTarget: Pair<String, Int>? = null
        try {
            updateTunnelConfiguration(
                enabled = true, changed = true, stop = {},
                start = { restartedTarget = EasyTierConfig().resolveForwardTarget(oldAddress) },
                update = { throw expected },
            )
            fail("Save error must be propagated")
        } catch (actual: IllegalStateException) {
            assertSame(expected, actual)
        }
        assertEquals("10.0.0.222" to 4533, restartedTarget)
    }

    @Test
    fun cancellationDuringStopStillRestoresTunnelInActiveContext() = runBlocking {
        var restarted = false
        val job = launch {
            updateTunnelConfiguration(
                enabled = true, changed = true,
                stop = {
                    currentCoroutineContext().cancel(CancellationException("Screen closed"))
                    currentCoroutineContext().ensureActive()
                },
                start = { assertTrue(currentCoroutineContext().isActive); restarted = true },
                update = { fail("Cancelled update must not commit") },
            )
        }
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(restarted)
    }
}
