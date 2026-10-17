package com.dedhapp3n.pokeldn.frlg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrlgForegroundOperationTest {
    @Test fun `notification restore progress advances from twelve to thirteen`() {
        val twelve = FrlgForegroundProgress(
            FrlgForegroundKind.SAVE_RESTORE, FrlgOperationPhase.RESTORING_SAVE,
            cartridge = "LeafGreen / German", completed = 11, total = 19,
        )
        val thirteen = twelve.copy(completed = 12)
        assertEquals("Writing sector 12 / 19", FrlgOperationNotificationText.text(twelve))
        assertEquals("Writing sector 13 / 19", FrlgOperationNotificationText.text(thirteen))
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

    @Test fun `silent restore response watchdog expires once and can be reset`() {
        val watchdog = FrlgRestoreResponseWatchdog(120_000)
        watchdog.begin(1_000)
        assertFalse(watchdog.expired(120_999))
        assertTrue(watchdog.expired(121_000))
        watchdog.complete()
        assertFalse(watchdog.active)
        assertFalse(watchdog.expired(Long.MAX_VALUE))
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
