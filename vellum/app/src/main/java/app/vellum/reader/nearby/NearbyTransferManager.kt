package app.vellum.reader.nearby

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import app.vellum.reader.VellumApp
import app.vellum.reader.core.data.BookEntity
import app.vellum.reader.library.BookImporter
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Coordinates one temporary, user-initiated transfer at a time. */
class NearbyTransferManager(private val app: VellumApp) {
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val random = SecureRandom()

    private val _senderState = MutableStateFlow<NearbySenderState>(NearbySenderState.Idle)
    val senderState: StateFlow<NearbySenderState> = _senderState.asStateFlow()
    private val _receiverState = MutableStateFlow<NearbyReceiverState>(NearbyReceiverState.Idle)
    val receiverState: StateFlow<NearbyReceiverState> = _receiverState.asStateFlow()
    private val _devices = MutableStateFlow<List<NearbyDevice>>(emptyList())
    val devices: StateFlow<List<NearbyDevice>> = _devices.asStateFlow()

    private var senderJob: Job? = null
    private var receiverJob: Job? = null
    @Volatile private var senderServer: ServerSocket? = null
    @Volatile private var senderClient: Socket? = null
    @Volatile private var receiverSocket: Socket? = null
    @Volatile private var senderRegistration: NsdManager.RegistrationListener? = null
    @Volatile private var discoveryListener: NsdManager.DiscoveryListener? = null
    @Volatile private var receiveDecision: CompletableDeferred<Boolean>? = null
    private val resolvedDevices = ConcurrentHashMap<String, NearbyDevice>()
    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    @Volatile private var resolving = false

    fun startSending(books: List<BookEntity>) {
        stopSending()
        _senderState.value = NearbySenderState.Preparing
        senderJob = app.appScope.launch(Dispatchers.IO) {
            val outgoing = books.distinctBy { it.uuid }.mapNotNull { book ->
                val file = File(app.booksDir, book.fileName)
                if (file.isFile && file.length() > 0L) OutgoingBook(book, file) else null
            }
            if (outgoing.isEmpty()) {
                _senderState.value = NearbySenderState.Error("The selected book files are unavailable")
                return@launch
            }
            val code = pairingCode()
            val offers = outgoing.map { it.offer }
            val totalBytes = outgoing.sumOf { it.file.length() }
            var server: ServerSocket? = null
            try {
                server = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(0))
                    soTimeout = 1_000
                }
                senderServer = server
                registerSender(server.localPort)
                _senderState.value = NearbySenderState.Advertising(code, outgoing.size, totalBytes)
                while (isActive) {
                    val socket = try {
                        server.accept()
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    senderClient = socket
                    val finished = try {
                        socket.use {
                            it.soTimeout = 30_000
                            sendToClient(it, code, outgoing, offers, totalBytes)
                        }
                    } finally {
                        senderClient = null
                    }
                    if (finished) break
                    _senderState.value = NearbySenderState.Advertising(code, outgoing.size, totalBytes)
                }
            } catch (_: CancellationException) {
                throw CancellationException()
            } catch (exception: Exception) {
                if (!isActive) throw CancellationException()
                Log.e(TAG, "Nearby sender failed", exception)
                _senderState.value = NearbySenderState.Error(senderMessage(exception))
            } finally {
                server?.runCatching { close() }
                senderServer = null
                unregisterSender()
            }
        }
    }

    /** Returns false only for a bad pairing code, allowing another attempt. */
    private fun sendToClient(
        socket: Socket,
        code: String,
        outgoing: List<OutgoingBook>,
        offers: List<NearbyBookOffer>,
        totalBytes: Long,
    ): Boolean {
        val frames = NearbyTransferProtocol.serverHandshake(socket, code) ?: return false
        // Give the recipient enough time to inspect a large multi-book offer.
        socket.soTimeout = 5 * 60_000
        frames.send(NearbyTransferProtocol.OFFER, NearbyTransferProtocol.offerPayload(offers))
        _senderState.value = NearbySenderState.AwaitingAcceptance(code, offers)
        val decision = frames.receive()
        require(decision.type == NearbyTransferProtocol.DECISION) { "The receiving device sent an invalid response" }
        if (!NearbyTransferProtocol.decodeDecision(decision.payload)) {
            _senderState.value = NearbySenderState.Declined
            return true
        }

        var sent = 0L
        outgoing.forEachIndexed { index, outgoingBook ->
            val digest = MessageDigest.getInstance("SHA-256")
            outgoingBook.file.inputStream().buffered().use { input ->
                val buffer = ByteArray(NearbyTransferProtocol.CHUNK_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                    frames.send(NearbyTransferProtocol.CHUNK, NearbyTransferProtocol.chunkPayload(index, buffer, count))
                    sent += count
                    _senderState.value = NearbySenderState.Sending(
                        title = outgoingBook.book.title,
                        bookIndex = index + 1,
                        bookCount = outgoing.size,
                        bytesSent = sent,
                        totalBytes = totalBytes,
                    )
                }
            }
            frames.send(NearbyTransferProtocol.FILE_END, NearbyTransferProtocol.fileEndPayload(index, digest.digest()))
        }
        frames.send(NearbyTransferProtocol.COMPLETE)
        _senderState.value = NearbySenderState.Complete(outgoing.size)
        return true
    }

    fun stopSending() {
        senderJob?.cancel()
        senderJob = null
        senderServer?.runCatching { close() }
        senderServer = null
        senderClient?.runCatching { close() }
        senderClient = null
        unregisterSender()
        _senderState.value = NearbySenderState.Idle
    }

    fun startDiscovery() {
        stopReceiver(clearState = false)
        stopDiscovery()
        resolvedDevices.clear()
        resolveQueue.clear()
        resolving = false
        _devices.value = emptyList()
        _receiverState.value = NearbyReceiverState.Discovering
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType.startsWith(SERVICE_TYPE.removeSuffix("."))) enqueueResolve(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                resolvedDevices.entries.removeAll { it.value.id.startsWith("${serviceInfo.serviceName}|") }
                publishDevices()
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                _receiverState.value = NearbyReceiverState.Error("Nearby discovery couldn't start. Check Wi-Fi and try again.")
                stopDiscovery()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        discoveryListener = listener
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (exception: Exception) {
            Log.e(TAG, "Could not start NSD", exception)
            discoveryListener = null
            _receiverState.value = NearbyReceiverState.Error("Nearby discovery isn't available on this network")
        }
    }

    @Synchronized
    private fun enqueueResolve(service: NsdServiceInfo) {
        resolveQueue.offer(service)
        resolveNext()
    }

    /** Android 13's resolver accepts only one request at a time. */
    @Synchronized
    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving || discoveryListener == null) return
        val service = resolveQueue.poll() ?: return
        resolving = true
        try {
            nsd.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    finishResolve()
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    serviceInfo.host?.let { address ->
                        val id = "${serviceInfo.serviceName}|${address.hostAddress}|${serviceInfo.port}"
                        resolvedDevices[id] = NearbyDevice(
                            id = id,
                            name = serviceInfo.serviceName.removePrefix("Vellum on "),
                            address = address,
                            port = serviceInfo.port,
                        )
                        publishDevices()
                    }
                    finishResolve()
                }
            })
        } catch (exception: Exception) {
            Log.w(TAG, "Could not resolve ${service.serviceName}", exception)
            resolving = false
            resolveNext()
        }
    }

    @Synchronized
    private fun finishResolve() {
        resolving = false
        resolveNext()
    }

    private fun publishDevices() {
        _devices.value = resolvedDevices.values.sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    fun connect(device: NearbyDevice, pairingCode: String) {
        if (pairingCode.filter(Char::isLetterOrDigit).length != 8) {
            _receiverState.value = NearbyReceiverState.Error("Enter the complete eight-character code")
            return
        }
        stopReceiver(clearState = false)
        stopDiscovery()
        receiverJob = app.appScope.launch(Dispatchers.IO) {
            _receiverState.value = NearbyReceiverState.Connecting(device.name)
            val receivedFiles = mutableListOf<Pair<NearbyBookOffer, File>>()
            try {
                Socket().use { socket ->
                    receiverSocket = socket
                    socket.connect(InetSocketAddress(device.address, device.port), 8_000)
                    socket.soTimeout = 45_000
                    val frames = NearbyTransferProtocol.clientHandshake(socket, pairingCode)
                        ?: run {
                            _receiverState.value = NearbyReceiverState.Error("That pairing code wasn't accepted")
                            return@launch
                        }
                    val offerFrame = frames.receive()
                    require(offerFrame.type == NearbyTransferProtocol.OFFER) { "The sender did not provide a valid offer" }
                    val offers = NearbyTransferProtocol.decodeOffer(offerFrame.payload)
                    val transferBytes = offers.sumOf { it.size }
                    val requiredBytes = transferBytes + offers.maxOf { it.size } + 64L * 1024 * 1024
                    val availableBytes = app.cacheDir.usableSpace
                    require(availableBytes <= 0L || requiredBytes <= availableBytes) {
                        "There isn't enough free space to receive and import these books"
                    }
                    val decision = CompletableDeferred<Boolean>()
                    receiveDecision = decision
                    _receiverState.value = NearbyReceiverState.Offer(device.name, offers)
                    val accepted = decision.await()
                    receiveDecision = null
                    frames.send(NearbyTransferProtocol.DECISION, NearbyTransferProtocol.decisionPayload(accepted))
                    if (!accepted) {
                        _receiverState.value = NearbyReceiverState.Idle
                        return@launch
                    }
                    receivedFiles += receiveFiles(frames, offers)
                }
                receiverSocket = null

                val importer = BookImporter(app)
                var imported = 0
                receivedFiles.forEachIndexed { index, (offer, file) ->
                    ensureActive()
                    _receiverState.value = NearbyReceiverState.Importing(offer.title, index + 1, receivedFiles.size)
                    if (importer.importFromFile(file, offer.title) != null) imported++
                    file.delete()
                }
                _receiverState.value = NearbyReceiverState.Complete(imported)
            } catch (_: CancellationException) {
                throw CancellationException()
            } catch (exception: Exception) {
                if (!isActive) throw CancellationException()
                Log.e(TAG, "Nearby receiver failed", exception)
                receivedFiles.forEach { it.second.delete() }
                _receiverState.value = NearbyReceiverState.Error(receiverMessage(exception))
            } finally {
                receiveDecision = null
                receiverSocket = null
            }
        }
    }

    private fun receiveFiles(
        frames: NearbyTransferProtocol.SecureFrames,
        offers: List<NearbyBookOffer>,
    ): List<Pair<NearbyBookOffer, File>> {
        val directory = File(app.cacheDir, "nearby").apply { mkdirs() }
        val completed = mutableListOf<Pair<NearbyBookOffer, File>>()
        var currentIndex = 0
        var currentFile = tempFile(directory, offers[0])
        var output = currentFile.outputStream().buffered()
        var digest = MessageDigest.getInstance("SHA-256")
        var currentBytes = 0L
        var totalReceived = 0L
        val totalBytes = offers.sumOf { it.size }
        try {
            while (true) {
                val frame = frames.receive()
                when (frame.type) {
                    NearbyTransferProtocol.CHUNK -> {
                        val chunk = NearbyTransferProtocol.decodeChunk(frame.payload)
                        require(chunk.index == currentIndex) { "Books arrived out of order" }
                        output.write(chunk.bytes)
                        digest.update(chunk.bytes)
                        currentBytes += chunk.bytes.size
                        totalReceived += chunk.bytes.size
                        require(currentBytes <= offers[currentIndex].size) { "The received book is larger than advertised" }
                        _receiverState.value = NearbyReceiverState.Receiving(
                            offers[currentIndex].title,
                            currentIndex + 1,
                            offers.size,
                            totalReceived,
                            totalBytes,
                        )
                    }
                    NearbyTransferProtocol.FILE_END -> {
                        val end = NearbyTransferProtocol.decodeFileEnd(frame.payload)
                        require(end.index == currentIndex && currentBytes == offers[currentIndex].size) {
                            "The received book is incomplete"
                        }
                        output.close()
                        require(MessageDigest.isEqual(digest.digest(), end.digest)) { "The received book failed verification" }
                        completed += offers[currentIndex] to currentFile
                        currentIndex++
                        if (currentIndex < offers.size) {
                            currentFile = tempFile(directory, offers[currentIndex])
                            output = currentFile.outputStream().buffered()
                            digest = MessageDigest.getInstance("SHA-256")
                            currentBytes = 0L
                        }
                    }
                    NearbyTransferProtocol.COMPLETE -> {
                        require(currentIndex == offers.size) { "The transfer ended before every book arrived" }
                        return completed
                    }
                    NearbyTransferProtocol.ERROR -> error(NearbyTransferProtocol.decodeError(frame.payload))
                    else -> error("The sender returned an invalid transfer message")
                }
            }
        } catch (exception: Exception) {
            runCatching { output.close() }
            currentFile.delete()
            completed.forEach { it.second.delete() }
            throw exception
        }
    }

    fun acceptOffer() { receiveDecision?.complete(true) }
    fun declineOffer() { receiveDecision?.complete(false) }

    fun stopReceiver(clearState: Boolean = true) {
        receiveDecision?.cancel()
        receiveDecision = null
        receiverJob?.cancel()
        receiverJob = null
        receiverSocket?.runCatching { close() }
        receiverSocket = null
        if (clearState) _receiverState.value = NearbyReceiverState.Idle
    }

    fun stopDiscovery() {
        val listener = discoveryListener ?: return
        discoveryListener = null
        runCatching { nsd.stopServiceDiscovery(listener) }
    }

    fun resetReceiver() {
        stopReceiver()
        stopDiscovery()
        resolvedDevices.clear()
        resolveQueue.clear()
        resolving = false
        _devices.value = emptyList()
    }

    private fun registerSender(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = "Vellum on ${Build.MODEL}"
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                _senderState.value = NearbySenderState.Error("This device couldn't become visible on the Wi-Fi network")
                senderServer?.runCatching { close() }
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        senderRegistration = listener
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun unregisterSender() {
        val listener = senderRegistration ?: return
        senderRegistration = null
        runCatching { nsd.unregisterService(listener) }
    }

    private fun pairingCode(): String {
        val chars = CharArray(8) { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] }
        return "${String(chars, 0, 4)}-${String(chars, 4, 4)}"
    }

    private fun tempFile(directory: File, offer: NearbyBookOffer): File =
        File.createTempFile("vellum-", ".${offer.format}", directory)

    private fun senderMessage(exception: Exception): String = when (exception) {
        is java.net.BindException -> "Vellum couldn't open a local transfer connection"
        is java.net.SocketException -> "The Wi-Fi connection was interrupted"
        else -> exception.message?.takeIf { it.isNotBlank() } ?: "The transfer couldn't be completed"
    }

    private fun receiverMessage(exception: Exception): String = when (exception) {
        is java.net.ConnectException -> "Couldn't reach that device. Make sure both devices are on the same Wi-Fi."
        is SocketTimeoutException -> "The connection timed out. Keep Vellum open on both devices and try again."
        is javax.crypto.AEADBadTagException -> "The encrypted transfer failed verification"
        else -> exception.message?.takeIf { it.isNotBlank() } ?: "The transfer couldn't be completed"
    }

    private data class OutgoingBook(val book: BookEntity, val file: File) {
        val offer = NearbyBookOffer(book.title, book.author, file.extension.lowercase(), file.length())
    }

    private companion object {
        const val TAG = "VellumNearby"
        const val SERVICE_TYPE = "_vellum._tcp."
        const val CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
    }
}
