package app.vellum.reader.nearby

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

internal data class TransferFrame(val type: Int, val payload: ByteArray)

/**
 * Small, dependency-free wire protocol for local book transfers. Pairing uses
 * a high-entropy eight-character code; every subsequent frame is independently
 * authenticated and encrypted with AES-GCM, so incomplete or modified files
 * are never handed to the importer.
 */
internal object NearbyTransferProtocol {
    const val OFFER = 1
    const val DECISION = 2
    const val CHUNK = 3
    const val FILE_END = 4
    const val COMPLETE = 5
    const val ERROR = 6

    const val CHUNK_SIZE = 64 * 1024
    private const val MAGIC = 0x56454C4D // VELM
    private const val VERSION = 1
    private const val MAX_FRAME_SIZE = CHUNK_SIZE + 16 * 1024
    private const val SERVER_DIRECTION = 0x53455256
    private const val CLIENT_DIRECTION = 0x434C4E54
    private const val KEY_ITERATIONS = 150_000
    private val random = SecureRandom()

    fun serverHandshake(socket: Socket, pairingCode: String): SecureFrames? {
        val input = DataInputStream(socket.getInputStream().buffered())
        val output = DataOutputStream(socket.getOutputStream().buffered())
        val salt = ByteArray(16).also(random::nextBytes)
        val challenge = ByteArray(32).also(random::nextBytes)
        output.writeInt(MAGIC)
        output.writeInt(VERSION)
        output.write(salt)
        output.write(challenge)
        output.flush()

        val key = deriveKey(pairingCode, salt)
        val expected = authenticate(key, challenge)
        val supplied = ByteArray(expected.size)
        input.readFully(supplied)
        val valid = MessageDigest.isEqual(expected, supplied)
        output.writeBoolean(valid)
        output.flush()
        if (!valid) return null
        return SecureFrames(input, output, key, SERVER_DIRECTION, CLIENT_DIRECTION)
    }

    fun clientHandshake(socket: Socket, pairingCode: String): SecureFrames? {
        val input = DataInputStream(socket.getInputStream().buffered())
        val output = DataOutputStream(socket.getOutputStream().buffered())
        require(input.readInt() == MAGIC) { "This is not a Vellum transfer" }
        require(input.readInt() == VERSION) { "The other device uses an incompatible Vellum version" }
        val salt = ByteArray(16).also(input::readFully)
        val challenge = ByteArray(32).also(input::readFully)
        val key = deriveKey(pairingCode, salt)
        output.write(authenticate(key, challenge))
        output.flush()
        if (!input.readBoolean()) return null
        return SecureFrames(input, output, key, CLIENT_DIRECTION, SERVER_DIRECTION)
    }

    fun offerPayload(books: List<NearbyBookOffer>): ByteArray = payload {
        writeInt(books.size)
        books.forEach { book ->
            writeUTF(book.title.take(4_000))
            writeUTF(book.author.take(2_000))
            writeUTF(book.format.take(16))
            writeLong(book.size)
        }
    }

    fun decodeOffer(payload: ByteArray): List<NearbyBookOffer> = readPayload(payload) {
        val count = readInt()
        require(count in 1..100) { "Invalid book count" }
        List(count) {
            val title = readUTF()
            val author = readUTF()
            val format = readUTF().lowercase()
            val size = readLong()
            require(format in setOf("epub", "pdf", "cbz", "cbr") && size in 1..MAX_BOOK_SIZE) {
                "Unsupported book offer"
            }
            NearbyBookOffer(title, author, format, size)
        }
    }

    fun decisionPayload(accepted: Boolean): ByteArray = payload { writeBoolean(accepted) }
    fun decodeDecision(payload: ByteArray): Boolean = readPayload(payload) { readBoolean() }

    fun chunkPayload(index: Int, bytes: ByteArray, length: Int): ByteArray = payload {
        writeInt(index)
        writeInt(length)
        write(bytes, 0, length)
    }

    data class BookChunk(val index: Int, val bytes: ByteArray)

    fun decodeChunk(payload: ByteArray): BookChunk = readPayload(payload) {
        val index = readInt()
        val length = readInt()
        require(length in 1..CHUNK_SIZE) { "Invalid transfer chunk" }
        BookChunk(index, ByteArray(length).also(::readFully))
    }

    fun fileEndPayload(index: Int, digest: ByteArray): ByteArray = payload {
        writeInt(index)
        writeInt(digest.size)
        write(digest)
    }

    data class FileEnd(val index: Int, val digest: ByteArray)

    fun decodeFileEnd(payload: ByteArray): FileEnd = readPayload(payload) {
        val index = readInt()
        val length = readInt()
        require(length == 32) { "Invalid file fingerprint" }
        FileEnd(index, ByteArray(length).also(::readFully))
    }

    fun errorPayload(message: String): ByteArray = payload { writeUTF(message.take(1_000)) }
    fun decodeError(payload: ByteArray): String = readPayload(payload) { readUTF() }

    class SecureFrames internal constructor(
        private val input: DataInputStream,
        private val output: DataOutputStream,
        key: ByteArray,
        private val sendDirection: Int,
        private val receiveDirection: Int,
    ) {
        private val secretKey = SecretKeySpec(key, "AES")
        private var sendCounter = 0L
        private var receiveCounter = 0L

        @Synchronized
        fun send(type: Int, payload: ByteArray = ByteArray(0)) {
            val plain = ByteBuffer.allocate(Int.SIZE_BYTES + payload.size).putInt(type).put(payload).array()
            val counter = sendCounter++
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, nonce(sendDirection, counter)))
            cipher.updateAAD(aad(sendDirection, counter))
            val encrypted = cipher.doFinal(plain)
            output.writeLong(counter)
            output.writeInt(encrypted.size)
            output.write(encrypted)
            output.flush()
        }

        fun receive(): TransferFrame {
            val counter = input.readLong()
            require(counter == receiveCounter++) { "Transfer frame arrived out of order" }
            val length = input.readInt()
            require(length in 17..MAX_FRAME_SIZE) { "Invalid encrypted frame size" }
            val encrypted = ByteArray(length).also(input::readFully)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, nonce(receiveDirection, counter)))
            cipher.updateAAD(aad(receiveDirection, counter))
            val plain = cipher.doFinal(encrypted)
            val buffer = ByteBuffer.wrap(plain)
            val type = buffer.int
            val payload = ByteArray(buffer.remaining()).also(buffer::get)
            return TransferFrame(type, payload)
        }
    }

    private fun deriveKey(code: String, salt: ByteArray): ByteArray {
        val normalized = code.filter(Char::isLetterOrDigit).uppercase()
        require(normalized.length == 8) { "Enter the complete eight-character code" }
        val spec = PBEKeySpec(normalized.toCharArray(), salt, KEY_ITERATIONS, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun authenticate(key: ByteArray, challenge: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(challenge)
        }

    private fun nonce(direction: Int, counter: Long): ByteArray =
        ByteBuffer.allocate(12).putInt(direction).putLong(counter).array()

    private fun aad(direction: Int, counter: Long): ByteArray =
        ByteBuffer.allocate(16).putInt(MAGIC).putInt(direction).putLong(counter).array()

    private inline fun payload(block: DataOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { it.block() }
            bytes.toByteArray()
        }

    private inline fun <T> readPayload(bytes: ByteArray, block: DataInputStream.() -> T): T =
        DataInputStream(ByteArrayInputStream(bytes)).use { input -> input.block() }

    private const val MAX_BOOK_SIZE = 8L * 1024 * 1024 * 1024
}
