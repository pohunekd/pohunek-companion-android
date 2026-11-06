package dev.pohunek.companion.core.data.log

import dev.pohunek.companion.core.domain.log.LogField
import dev.pohunek.companion.protocol.HostRoute
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val HOST_KEY_BYTES = 4
private const val MAC_ALGORITHM = "HmacSHA256"

/** Length of the per-install salt behind the host keys. */
const val HOST_KEY_SALT_BYTES: Int = 32

/**
 * Derives the log's host keys: the leading bytes of HMAC-SHA256 over the route, keyed with a salt that never leaves the device. Whoever
 * holds an exported log cannot match a key to a candidate address without the salt; the key only tells that two lines concern one host.
 */
class HostKeyer(salt: ByteArray) {
    private val key = SecretKeySpec(salt.copyOf(), MAC_ALGORITHM)

    init {
        require(salt.size == HOST_KEY_SALT_BYTES) { "the host key salt must be $HOST_KEY_SALT_BYTES bytes" }
    }

    fun key(route: HostRoute): LogField {
        val mac = Mac.getInstance(MAC_ALGORITHM).apply { init(key) }.doFinal(route.toString().encodeToByteArray())
        return LogField.hostKey(mac.take(HOST_KEY_BYTES).joinToString("") { "%02x".format(it) })
    }

    companion object {
        /**
         * The salt stored in [file], created from [random] when the file is missing or has the wrong size (a torn write leaves no usable
         * salt). The file must sit in app-private storage that is not exported or backed up. [beforeCreate] runs before a new salt is
         * written, so lines keyed under another salt can be dropped first: a crash in between leaves no salt and the next start repeats it.
         */
        @Throws(IOException::class)
        fun loadOrCreate(file: File, random: SecureRandom, beforeCreate: () -> Unit = {}): HostKeyer {
            if (file.exists()) {
                val salt = file.readBytes()
                if (salt.size == HOST_KEY_SALT_BYTES) return HostKeyer(salt)
            }
            beforeCreate()
            val salt = ByteArray(HOST_KEY_SALT_BYTES).also(random::nextBytes)
            file.parentFile?.mkdirs()
            val temp = File(file.path + ".tmp")
            FileOutputStream(temp).use { out ->
                out.write(salt)
                out.fd.sync()
            }
            if (!temp.renameTo(file)) throw IOException("cannot store the host key salt")
            return HostKeyer(salt)
        }
    }
}
