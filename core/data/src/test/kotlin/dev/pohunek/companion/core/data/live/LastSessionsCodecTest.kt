package dev.pohunek.companion.core.data.live

import dev.pohunek.companion.core.domain.sessions.OwnerSession
import dev.pohunek.companion.core.domain.sessions.OwnerState
import dev.pohunek.companion.protocol.SessionCapabilities
import dev.pohunek.companion.protocol.SessionInfo
import dev.pohunek.companion.protocol.SessionState
import dev.pohunek.companion.protocol.StateSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.OffsetDateTime

class LastSessionsCodecTest {
    private fun session(id: String, state: OwnerState, waitingSince: OffsetDateTime? = null) = OwnerSession(
        SessionInfo(
            id = id,
            capabilities = SessionCapabilities(resume = true, fork = false),
            name = "build",
            agent = "claude",
            agentBase = "claude",
            cwd = "/work",
            pid = 7,
            cols = 80,
            rows = 24,
            state = SessionState.Running,
            stateSource = StateSource.Process,
            metadata = mapOf("companion.group" to "Work"),
            createdAt = "2026-01-01T09:00:00Z",
            updatedAt = "2026-01-01T10:00:00Z",
        ),
        state,
        waitingSince,
    )

    @Test
    fun `every host's list survives a round trip with owner state and waiting time`() {
        val hosts = mapOf(
            "100.1.2.3:18722" to listOf(
                session("s-1", OwnerState.NeedsOwner, OffsetDateTime.parse("2026-01-01T10:05:00+01:00")),
                session("s-2", OwnerState.Idle),
            ),
            "100.1.2.4:18722" to emptyList(),
        )

        assertEquals(hosts, LastSessionsCodec.decode(LastSessionsCodec.encode(hosts)))
    }

    @Test
    fun `text that is not a file of this version, or holds an unknown value, is unreadable`() {
        val valid = LastSessionsCodec.encode(mapOf("h" to listOf(session("s-1", OwnerState.Working))))

        assertNull(LastSessionsCodec.decode("{"))
        assertNull(LastSessionsCodec.decode(""))
        assertNull(LastSessionsCodec.decode(valid.replace("\"version\":1", "\"version\":2")))
        assertNull(LastSessionsCodec.decode(valid.replace("Working", "Elsewhere")))
        assertNull(LastSessionsCodec.decode("""{"version":1,"hosts":[{"host":"h","sessions":[{"info":{},"owner_state":"Idle"}]}]}"""))
    }
}
