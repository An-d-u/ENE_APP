package dev.ene.companion

import dev.ene.companion.connection.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EndpointResolverTest {
    private val serverId = "00000000-0000-4000-8000-000000000001"

    @Test fun firstCandidateTimeoutThenSecondCandidateMatches() = runTest {
        val first = Endpoint.parse("192.0.2.10", 8765)
        val second = Endpoint.parse("192.0.2.11", 9000)
        val seen = mutableListOf<Endpoint>()
        val resolver = EndpointResolver(InfoProbe { endpoint ->
            seen += endpoint
            if (endpoint == first) delay(10000)
            ServerInfo(serverId, listOf(1))
        })
        assertEquals(second, resolver.resolve(serverId, listOf(first, second)))
        assertEquals(listOf(first, second), seen)
        assertEquals(3000L, testScheduler.currentTime)
    }

    @Test fun wrongPcIsSkippedAndNoCredentialExistsInProbeInterface() = runTest {
        val first = Endpoint.parse("192.0.2.10", 8765)
        val second = Endpoint.parse("192.0.2.11", 8765)
        val resolver = EndpointResolver(InfoProbe { endpoint ->
            ServerInfo(if (endpoint == first) "00000000-0000-4000-8000-000000000099" else serverId, listOf(1))
        })
        assertEquals(second, resolver.resolve(serverId, listOf(first, second)))
        val failure = runCatching { resolver.resolve(serverId, listOf(first)) }.exceptionOrNull()
        assertEquals("server_mismatch", (failure as ConnectionException).code)
    }

    @Test fun unsupportedVersionAndMalformedInfoAreNotSuccess() = runTest {
        val endpoint = Endpoint.parse("192.0.2.10", 8765)
        val resolver = EndpointResolver(InfoProbe { ServerInfo(serverId, listOf(2)) })
        assertEquals("unsupported_version", (runCatching { resolver.resolve(serverId, listOf(endpoint)) }.exceptionOrNull() as ConnectionException).code)
        for (raw in listOf("{}", """{"server_id":"$serverId","protocol_versions":["1"]}""", """{"server_id":"invalid","protocol_versions":[1]}""")) {
            assertThrows(ConnectionException::class.java) { ServerInfo.parse(raw) }
        }
        assertEquals(ServerInfo(serverId, listOf(1)), ServerInfo.parse("""{"server_id":"$serverId","protocol_versions":[1],"future":true}"""))
    }
}
