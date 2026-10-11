package dev.ene.companion.input

import dev.ene.companion.protocol.*
import org.junit.Assert.*
import org.junit.Test

class PhotoOutboxTest {
    private val context = ExtensionContext(1, "00000000-0000-4000-8000-000000000001", "00000000-0000-4000-8000-000000000002", "00000000-0000-4000-8000-000000000003")
    private val photo = LocalPhoto("00000000-0000-4000-8000-000000000004", "image/png", "a".repeat(64), 8, 1, 1, byteArrayOf())
    @Test fun reconnectQueriesBeforeExplicitRetryAndKeepsId() {
        val box = PhotoOutbox()
        val pending = box.create("registration", context, "예시", 3, listOf(photo))
        box.sent()
        assertEquals(listOf(pending.id), box.sync("registration", context).pending_request_ids)
        assertFalse(box.canRetry)
        box.status(RequestStatus(context.serverEpoch, context.conversationId, pending.id, "unknown"))
        assertTrue(box.canRetry)
        assertSame(pending, box.retry())
        assertFalse(box.canRetry)
    }
    @Test fun staleResultAndChangedRegistrationCannotAccept() {
        val box = PhotoOutbox()
        val pending = box.create("registration", context, "예시", 3, listOf(photo))
        assertNull(box.status(RequestStatus(context.serverEpoch, "00000000-0000-4000-8000-000000000005", pending.id, "accepted")))
        box.sync("another", context)
        assertNull(box.pending)
    }
    @Test fun acceptedRetainsImmutableDraftVersionAndClearsOnlyAtTerminal() {
        val box = PhotoOutbox()
        val pending = box.create("registration", context, "예시", 3, listOf(photo))
        assertSame(pending, box.status(RequestStatus(context.serverEpoch, context.conversationId, pending.id, "accepted")))
        assertFalse(box.canRetry)
        assertSame(pending, box.status(RequestStatus(context.serverEpoch, context.conversationId, pending.id, "completed")))
        assertNull(box.pending)
    }
}
