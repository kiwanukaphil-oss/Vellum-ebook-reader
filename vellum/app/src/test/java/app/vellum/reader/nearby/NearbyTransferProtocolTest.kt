package app.vellum.reader.nearby

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyTransferProtocolTest {
    @Test
    fun offerRoundTripPreservesPreviewMetadata() {
        val books = listOf(
            NearbyBookOffer("North Woods", "Daniel Mason", "epub", 1_234_567),
            NearbyBookOffer("A Field Guide", "A. Reader", "pdf", 987_654),
        )

        assertEquals(books, NearbyTransferProtocol.decodeOffer(NearbyTransferProtocol.offerPayload(books)))
    }

    @Test
    fun matchingPairingCodeCreatesEncryptedBidirectionalFrames() {
        val executor = Executors.newSingleThreadExecutor()
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val sender = executor.submit<Boolean> {
                server.accept().use { socket ->
                    val frames = NearbyTransferProtocol.serverHandshake(socket, "ABCD-EFGH")
                    assertNotNull(frames)
                    frames!!.send(NearbyTransferProtocol.OFFER, NearbyTransferProtocol.offerPayload(
                        listOf(NearbyBookOffer("Book", "Author", "epub", 12)),
                    ))
                    val response = frames.receive()
                    response.type == NearbyTransferProtocol.DECISION &&
                        NearbyTransferProtocol.decodeDecision(response.payload)
                }
            }

            Socket(InetAddress.getLoopbackAddress(), server.localPort).use { socket ->
                val frames = NearbyTransferProtocol.clientHandshake(socket, "abcd-efgh")
                assertNotNull(frames)
                val offer = frames!!.receive()
                assertEquals(NearbyTransferProtocol.OFFER, offer.type)
                assertEquals("Book", NearbyTransferProtocol.decodeOffer(offer.payload).single().title)
                frames.send(NearbyTransferProtocol.DECISION, NearbyTransferProtocol.decisionPayload(true))
            }
            assertTrue(sender.get(5, TimeUnit.SECONDS))
        }
        executor.shutdownNow()
    }

    @Test
    fun incorrectPairingCodeIsRejectedBeforeMetadataIsShared() {
        val executor = Executors.newSingleThreadExecutor()
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val sender = executor.submit<Boolean> {
                server.accept().use { socket ->
                    NearbyTransferProtocol.serverHandshake(socket, "ABCD-EFGH") != null
                }
            }
            Socket(InetAddress.getLoopbackAddress(), server.localPort).use { socket ->
                assertNull(NearbyTransferProtocol.clientHandshake(socket, "WXYZ-2345"))
            }
            assertFalse(sender.get(5, TimeUnit.SECONDS))
        }
        executor.shutdownNow()
    }
}
