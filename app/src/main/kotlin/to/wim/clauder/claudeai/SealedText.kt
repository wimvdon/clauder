package to.wim.clauder.claudeai

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts short text with AES-GCM. On the phone the key lives in the Android Keystore (ClaudeAiLogin). */
object SealedText {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    /** Base64 of the cipher's IV length (1 byte), the IV and the encrypted text. */
    fun seal(key: SecretKey, plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(byteArrayOf(iv.size.toByte()) + iv + encrypted)
    }

    /** The text, or null when it can't be read (another key, damaged data). */
    fun open(key: SecretKey, sealed: String): String? = try {
        val bytes = Base64.getDecoder().decode(sealed)
        val ivLength = bytes[0].toInt()
        val iv = bytes.copyOfRange(1, 1 + ivLength)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        String(cipher.doFinal(bytes.copyOfRange(1 + ivLength, bytes.size)), Charsets.UTF_8)
    } catch (e: java.security.GeneralSecurityException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: IndexOutOfBoundsException) {
        null
    }
}
