package dev.ene.companion

import dev.ene.companion.connection.*
import org.junit.Assert.*
import org.junit.Test

class ConnectionDiagnosticsTest {
    @Test fun externalProcessesAndCurrentRunNeverBecomePreviousMainExit() {
        val records = listOf(ExitSample("synthetic.app", 4, 100), ExitSample("synthetic.app:renderer", 5, 200), ExitSample("synthetic.app", 6, 400))
        assertEquals(PreviousExit(ExitRecordStatus.AVAILABLE, 4, 100), selectPreviousExit(records, "synthetic.app", 300))
        assertEquals(ExitRecordStatus.EMPTY, selectPreviousExit(records, "another.app", 300).status)
    }

    @Test fun latestMatchingRecordIsSelectedWithoutAssumingCrash() {
        val records = listOf(ExitSample("synthetic.app", 10, 200), ExitSample("synthetic.app", 4, 100))
        assertEquals(10, selectPreviousExit(records, "synthetic.app", 300).reason)
        assertNotEquals(ExitRecordStatus.EMPTY, PreviousExit(ExitRecordStatus.UNSUPPORTED).status)
        assertNotEquals(ExitRecordStatus.EMPTY, PreviousExit(ExitRecordStatus.UNAVAILABLE).status)
    }

    @Test fun unknownWireCodesAreNeverShownVerbatim() {
        assertEquals("connection_failed", diagnosticCode("synthetic private payload"))
        assertEquals("pc_unreachable", diagnosticCode("pc_unreachable"))
        assertEquals("discovery_stop_failed", diagnosticCode("discovery_stop_failed"))
        val failed = ConnectionDiagnostics().failed(ConnectionStage.SECURE_SESSION, "synthetic private payload")
        assertEquals(ConnectionFailure(ConnectionStage.SECURE_SESSION, "connection_failed"), failed.lastFailure)
        assertFalse(failed.toString().contains("synthetic private payload"))
    }

    @Test fun retryAndSuccessPreserveLastFailureAndStorageNotice() {
        val failed = ConnectionDiagnostics(storageNotice = "connection_settings_save_failed").failed(ConnectionStage.INITIAL_SYNC, "snapshot_timeout")
        val connected = failed.copy(stage = ConnectionStage.RETRY_WAIT).copy(stage = ConnectionStage.CONNECTED)
        assertEquals(failed.lastFailure, connected.lastFailure)
        assertEquals("connection_settings_save_failed", connected.storageNotice)
    }
}
