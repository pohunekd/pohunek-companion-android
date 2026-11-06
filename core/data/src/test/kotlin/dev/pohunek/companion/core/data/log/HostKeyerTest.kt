package dev.pohunek.companion.core.data.log

import dev.pohunek.companion.core.domain.log.LogValue
import dev.pohunek.companion.protocol.HostRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest
import java.security.SecureRandom

class HostKeyerTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val route = HostRoute.parse("host.example:7777")

    private fun HostKeyer.keyOf(route: HostRoute): String = (key(route).value as LogValue.Text).value

    private fun salt(fill: Int) = ByteArray(HOST_KEY_SALT_BYTES) { fill.toByte() }

    @Test
    fun `a key depends on the salt and is not the unsalted hash of the route`() {
        val plain = MessageDigest.getInstance("SHA-256").digest(route.toString().encodeToByteArray())
            .take(4).joinToString("") { "%02x".format(it) }
        val first = HostKeyer(salt(1)).keyOf(route)
        assertEquals(first, HostKeyer(salt(1)).keyOf(route))
        assertNotEquals(first, HostKeyer(salt(2)).keyOf(route))
        assertNotEquals(plain, first)
    }

    @Test
    fun `the salt is created once and read back`() {
        val file = folder.newFolder("private").resolve("salt")
        val created = HostKeyer.loadOrCreate(file, SecureRandom()).keyOf(route)
        assertEquals(HOST_KEY_SALT_BYTES, file.length().toInt())
        assertEquals(created, HostKeyer.loadOrCreate(file, SecureRandom()).keyOf(route))
    }

    @Test
    fun `creating a salt runs the hook first and loading one does not`() {
        val file = folder.newFolder("hook").resolve("salt")
        var calls = 0
        HostKeyer.loadOrCreate(file, SecureRandom()) { calls++ }
        HostKeyer.loadOrCreate(file, SecureRandom()) { calls++ }
        assertEquals(1, calls)
    }

    @Test
    fun `clearing the log removes both files`() {
        val directory = folder.newFolder("logs")
        val log = RotatingLogFile(directory, "app.log", 10L)
        log.append("aaaaaa\n".encodeToByteArray())
        log.append("bbbbbb\n".encodeToByteArray())
        assertEquals(2, directory.list()?.size)
        log.clear()
        assertEquals(0, directory.list()?.size)
    }

    @Test
    fun `a salt file of the wrong size is replaced by a full salt`() {
        val file = folder.newFile("salt").also { it.writeBytes(ByteArray(3)) }
        val replaced = HostKeyer.loadOrCreate(file, SecureRandom()).keyOf(route)
        assertEquals(HOST_KEY_SALT_BYTES, file.length().toInt())
        assertEquals(replaced, HostKeyer.loadOrCreate(file, SecureRandom()).keyOf(route))
    }
}
