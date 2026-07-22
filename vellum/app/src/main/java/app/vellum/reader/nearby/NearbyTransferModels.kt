package app.vellum.reader.nearby

import java.net.InetAddress

data class NearbyBookOffer(
    val title: String,
    val author: String,
    val format: String,
    val size: Long,
)

data class NearbyDevice(
    val id: String,
    val name: String,
    val address: InetAddress,
    val port: Int,
)

sealed interface NearbySenderState {
    data object Idle : NearbySenderState
    data object Preparing : NearbySenderState
    data class Advertising(val code: String, val bookCount: Int, val totalBytes: Long) : NearbySenderState
    data class AwaitingAcceptance(val code: String, val books: List<NearbyBookOffer>) : NearbySenderState
    data class Sending(
        val title: String,
        val bookIndex: Int,
        val bookCount: Int,
        val bytesSent: Long,
        val totalBytes: Long,
    ) : NearbySenderState
    data class Complete(val bookCount: Int) : NearbySenderState
    data object Declined : NearbySenderState
    data class Error(val message: String) : NearbySenderState
}

sealed interface NearbyReceiverState {
    data object Idle : NearbyReceiverState
    data object Discovering : NearbyReceiverState
    data class Connecting(val deviceName: String) : NearbyReceiverState
    data class Offer(val deviceName: String, val books: List<NearbyBookOffer>) : NearbyReceiverState
    data class Receiving(
        val title: String,
        val bookIndex: Int,
        val bookCount: Int,
        val bytesReceived: Long,
        val totalBytes: Long,
    ) : NearbyReceiverState
    data class Importing(val title: String, val bookIndex: Int, val bookCount: Int) : NearbyReceiverState
    data class Complete(val receivedCount: Int) : NearbyReceiverState
    data class Error(val message: String) : NearbyReceiverState
}
