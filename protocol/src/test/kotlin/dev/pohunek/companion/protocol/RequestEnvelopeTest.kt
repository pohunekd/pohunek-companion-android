package dev.pohunek.companion.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class RequestEnvelopeTest {
    @Test
    fun `health request is emitted as documented`() {
        assertEquals("""{"v":{"minimum":4,"maximum":4},"id":"r1","method":"daemon.health","params":null}""", Requests.health("r1").toLine())
    }

    @Test
    fun `unfiltered session list sends empty params`() {
        assertEquals("""{"v":{"minimum":4,"maximum":4},"id":"r2","method":"session.list","params":{}}""", Requests.sessionList("r2", emptyList()).toLine())
    }

    @Test
    fun `filters are emitted as key and value pairs`() {
        val filters = listOf(SessionFilter.state(SessionState.Running), SessionFilter.activity(AgentActivity.Blocked), SessionFilter.agent("codex"))
        val line = Requests.sessionList("r3", filters).toLine()
        assertEquals(
            """{"v":{"minimum":4,"maximum":4},"id":"r3","method":"session.list","params":""" +
                """{"filters":[{"key":"state","value":"running"},{"key":"activity","value":"blocked"},{"key":"agent","value":"codex"}]}}""",
            line,
        )
    }

    @Test
    fun `request fixture round-trips unchanged`() {
        val fixture = Fixtures.json("request-session-list")
        val request = Wire.json.decodeFromJsonElement(Request.serializer(), fixture)
        assertEquals(ProtocolVersionRange(3, 4), request.v)
        assertEquals(fixture, Wire.json.parseToJsonElement(request.toLine()))
    }

    @Test
    fun `filter fixture round-trips and matches the typed filter`() {
        val fixture = Fixtures.json("session-list-filter-state")
        val filter = Wire.json.decodeFromJsonElement(SessionFilter.serializer(), fixture)
        assertEquals(SessionFilter.state(SessionState.Running), filter)
        assertEquals(fixture, Wire.json.encodeToJsonElement(SessionFilter.serializer(), filter))
    }
}
