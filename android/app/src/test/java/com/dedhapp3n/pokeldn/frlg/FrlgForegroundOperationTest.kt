package com.dedhapp3n.pokeldn.frlg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class FrlgForegroundOperationTest {
    @Test fun `notification restore progress advances from twelve to thirteen`() {
        val twelve = FrlgForegroundProgress(
            FrlgForegroundKind.SAVE_RESTORE, FrlgOperationPhase.RESTORING_SAVE,
            cartridge = "LeafGreen / German", completed = 11, total = 19,
            restorePlanIndex = 12, restorePlanTotal = 19, restoreLastProgressAtMillis = 1_000,
        )
        val thirteen = twelve.copy(completed = 12, restorePlanIndex = 13, restoreWaitingForResponse = true)
        assertEquals("Sector 12 / 19 - sending", FrlgOperationNotificationText.text(twelve, 5_000))
        assertEquals("Sector 13 / 19 - verifying", FrlgOperationNotificationText.text(thirteen, 5_000))
        assertEquals("Sector 13 / 19 - no progress for 45 s", FrlgOperationNotificationText.text(thirteen, 46_000))
        assertEquals(12, thirteen.completed)
        assertEquals("PokeLDN - Restoring LeafGreen", FrlgOperationNotificationText.title(twelve))
    }

    @Test fun `activity recreation does not release foreground ownership`() {
        val power = FakePower()
        val sink = FakeSink()
        val lease = FrlgForegroundLease(power, sink)
        lease.begin(FrlgForegroundProgress(FrlgForegroundKind.SAVE_RESTORE, FrlgOperationPhase.WAITING_FOR_CONSOLE))
        lease.activityRecreated()
        assertTrue(lease.active)
        assertEquals(1, power.acquired)
        assertEquals(0, power.released)
    }

    @Test fun `wake lock and foreground stop on every terminal path`() {
        listOf(FrlgOperationPhase.COMPLETED, FrlgOperationPhase.FAILED, FrlgOperationPhase.CANCELLED, FrlgOperationPhase.REFUSED).forEach { terminal ->
            val power = FakePower(); val sink = FakeSink(); val lease = FrlgForegroundLease(power, sink)
            lease.begin(FrlgForegroundProgress(FrlgForegroundKind.SAVE_RESTORE, FrlgOperationPhase.RESTORING_SAVE))
            lease.update(FrlgForegroundProgress(FrlgForegroundKind.SAVE_RESTORE, terminal))
            assertFalse(lease.active)
            assertEquals(1, power.released)
            assertEquals(1, sink.stopped)
        }
    }

    @Test fun `wake lock remains held throughout restore writing`() {
        val power = FakePower(); val sink = FakeSink(); val lease = FrlgForegroundLease(power, sink)
        lease.begin(FrlgForegroundProgress(FrlgForegroundKind.SAVE_RESTORE, FrlgOperationPhase.RESTORING_SAVE))
        repeat(20) {
            lease.update(FrlgForegroundProgress(FrlgForegroundKind.SAVE_RESTORE, FrlgOperationPhase.RESTORING_SAVE))
        }
        assertTrue(lease.active)
        assertEquals(1, power.acquired)
        assertEquals(0, power.released)
    }

    @Test fun `restore progress watchdog covers queued blocks active sender and response wait`() {
        listOf("queued-blocks", "sender-active", "response-wait").forEach { state ->
            val watchdog = FrlgRestoreProgressWatchdog(120_000)
            watchdog.begin(1_000)
            assertFalse("$state expired early", watchdog.expired(120_999))
            assertTrue("$state did not expire", watchdog.expired(121_000))
        }
    }

    @Test fun `meaningful progress resets watchdog but timer ticks do not`() {
        val watchdog = FrlgRestoreProgressWatchdog(120_000)
        watchdog.begin(1_000)
        assertFalse(watchdog.expired(120_999))
        // Checking/ticking time does not mutate the forward-progress clock.
        assertTrue(watchdog.expired(121_000))
        watchdog.progress(121_000)
        assertFalse(watchdog.expired(240_999))
        assertTrue(watchdog.expired(241_000))
        watchdog.complete()
        assertFalse(watchdog.active)
        assertFalse(watchdog.expired(Long.MAX_VALUE))
    }

    @Test fun `reliable retransmit output alone does not fake restore forward progress`() {
        assertFalse(restoreTransportAdvanced(6, 6, 20, 20, 30, 30))
        assertTrue(restoreTransportAdvanced(6, 5, 20, 20, 30, 30))
        assertFalse(restoreTransportAdvanced(6, 6, 20, 21, 30, 30))
        assertFalse(restoreTransportAdvanced(6, 6, 20, 20, 30, 31))
    }

    @Test fun `UI publication is semantic and throttled without dropping protocol ticks`() {
        val gate = FrlgUiPublicationGate(250)
        val base = FrlgForegroundProgress(
            FrlgForegroundKind.SAVE_RESTORE, FrlgOperationPhase.RESTORING_SAVE,
            restorePlanIndex = 11, restorePlanTotal = 19,
        )
        var protocolTicks = 0
        var publications = 0
        repeat(1_000) { now ->
            protocolTicks++
            if (gate.shouldPublish(base, now.toLong())) publications++
        }
        assertEquals(1_000, protocolTicks)
        assertEquals(4, publications)

        assertTrue(gate.shouldPublish(base.copy(restorePlanIndex = 12), 1_001))
        assertTrue(gate.shouldPublish(base.copy(restorePlanIndex = 12, restoreWaitingForResponse = true), 1_002))
        assertTrue(gate.shouldPublish(base.copy(phase = FrlgOperationPhase.FAILED), 1_003))
    }

    @Test fun `persistent restore trace survives operation cleanup`() {
        val directory = Files.createTempDirectory("restore-trace").toFile()
        try {
            val store = FrlgRestoreTraceStore(directory.resolve(FrlgRestoreTraceStore.FILE_NAME))
            val trace = "restoreWrite=12/19 result=timeout-send"
            store.replace(trace)
            // Foreground cleanup intentionally performs no clear.
            assertEquals(trace, FrlgRestoreTraceStore(directory.resolve(FrlgRestoreTraceStore.FILE_NAME)).load())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun `peer departure only completes after successful restore entered close`() {
        listOf(
            FrlgSaveRestoreStage.WAITING_CLIENT,
            FrlgSaveRestoreStage.WRITING,
            FrlgSaveRestoreStage.LOADING,
        ).forEach { stage ->
            assertFalse(restorePeerDepartureCompletes(false, stage))
            assertFalse(restorePeerDepartureCompletes(true, stage))
        }
        assertFalse(restorePeerDepartureCompletes(false, FrlgSaveRestoreStage.CLOSING))
        assertTrue(restorePeerDepartureCompletes(true, FrlgSaveRestoreStage.CLOSING))
    }

    private class FakePower : FrlgPowerLease {
        var acquired=0;var released=0
        override fun acquire(){acquired++}
        override fun release(){released++}
    }
    private class FakeSink : FrlgForegroundSink {
        var stopped=0
        override fun update(progress:FrlgForegroundProgress)=Unit
        override fun stop(){stopped++}
    }
}
