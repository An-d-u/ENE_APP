package dev.ene.companion

import dev.ene.companion.connection.*
import org.junit.Assert.*
import org.junit.Test

class ConnectionAttemptPolicyTest {
    @Test fun authenticatedFailureWinsRegardlessOfCandidateOrder() {
        val network = ConnectionException("pc_unreachable")
        val protocol = ConnectionException("unsupported_version", peerAuthenticated = true)
        for (errors in listOf(listOf(network, protocol), listOf(protocol, network))) {
            assertSame(protocol, candidateFailure(errors))
        }
    }

    @Test fun untrustedCandidateCannotRequestRegistrationRemoval() {
        val failure = candidateFailure(listOf(ConnectionException("authorization_revoked"), ConnectionException("tls_identity_invalid")))
        assertEquals("pc_unreachable", failure.code)
        assertFalse(failure.peerAuthenticated)
    }

    @Test fun confirmedRevocationWinsAndTlsFailuresNeverBecomeAuthenticated() {
        val revoked = ConnectionException("authorization_revoked", peerAuthenticated = true)
        assertSame(revoked, candidateFailure(listOf(ConnectionException("unsupported_version", true), revoked)))
        for (code in TLS_FAILURE_CODES) assertFalse(authenticatedSessionFailure(code).peerAuthenticated)
    }
}
