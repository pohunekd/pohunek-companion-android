package dev.pohunek.companion.core.data.live

import dev.pohunek.companion.protocol.ErrorClass
import dev.pohunek.companion.protocol.ProtocolClientException
import dev.pohunek.companion.protocol.ProtocolError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProtocolCodeTest {
    private fun daemon(code: String) = ProtocolClientException.Daemon(ProtocolError(ErrorClass.Daemon, code, "msg"))

    @Test
    fun `a daemon that is shutting down is a transient reconnect without a refusal code`() {
        assertNull(daemon("daemon_shutting_down").protocolCode())
    }

    @Test
    fun `any other daemon error code is shown as the refusal`() {
        assertEquals("bad_request", daemon("bad_request").protocolCode())
    }
}
