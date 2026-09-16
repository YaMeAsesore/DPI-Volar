package com.example.dpi_volar

import android.net.VpnService
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

data class SessionKey(
    val srcIp: String, val srcPort: Int,
    val dstIp: String, val dstPort: Int
)

class TcpSession(
    val key: SessionKey,
    private val clientIpBytes: ByteArray,
    private val serverIpBytes: ByteArray,
    private val vpnService: VpnService,
    private val tunWriter: TunWriter,
    private val scope: CoroutineScope,
    private val onClosed: (SessionKey) -> Unit
) {
    companion object {
        const val TAG = "TcpSession"
        private const val SOCKET_READ_TIMEOUT_MS = 60_000
    }

    private var clientSeq: Long = 0
    private var serverSeq: Long = 0
    private lateinit var socket: Socket
    private var closed = false
    private var firstDataSent = false
    private var currentTechnique: DpiTechnique = DpiTechnique.NONE

    private data class OutgoingChunk(val payload: ByteArray, val seq: Long)
    private val writeQueue = Channel<OutgoingChunk>(Channel.UNLIMITED)
    private val socketReady = CompletableDeferred<Unit>()

    private val ackSignal = Channel<Unit>(Channel.CONFLATED)

    suspend fun start(initialClientSeq: Long) {
        clientSeq = initialClientSeq + 1
        serverSeq = (0..Int.MAX_VALUE).random().toLong()

        socket = Socket()

        sendControl(PacketBuilder.TCP_SYN or PacketBuilder.TCP_ACK)
        serverSeq += 1

        scope.launch {
            for (unit in ackSignal) {
                sendControl(PacketBuilder.TCP_ACK)
            }
        }

        scope.launch { processWriteQueue() }

        try {
            withContext(NetworkDispatcher.IO) {
                socket.bind(InetSocketAddress(0))
            }

            val protected = vpnService.protect(socket)
            Log.d(TAG, "protect() resultado: $protected para ${key.dstIp}:${key.dstPort}")

            withContext(NetworkDispatcher.IO) {
                val addr = InetAddress.getByAddress(serverIpBytes)
                socket.connect(InetSocketAddress(addr, key.dstPort), 3000)
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.soTimeout = SOCKET_READ_TIMEOUT_MS
            }

            socketReady.complete(Unit)
            scope.launch { readFromServer() }

        } catch (e: Exception) {
            Log.e(TAG, "No se pudo conectar a ${key.dstIp}:${key.dstPort} -> ${e.message}")
            socketReady.completeExceptionally(e)
            sendControl(PacketBuilder.TCP_RST)
            close()
        }
    }

    fun onClientData(payload: ByteArray, incomingSeq: Long) {
        if (payload.isEmpty()) return

        val payloadEnd = incomingSeq + payload.size
        if (payloadEnd <= clientSeq) {
            ackSignal.trySend(Unit)
            return
        }

        clientSeq = payloadEnd

        ackSignal.trySend(Unit)

        writeQueue.trySend(OutgoingChunk(payload, incomingSeq))
    }

    private suspend fun processWriteQueue() {
        try {
            for (chunk in writeQueue) {
                socketReady.await()

                val isClientHello = !firstDataSent &&
                        chunk.payload.size > 6 &&
                        (chunk.payload[0].toInt() and 0xFF) == 0x16 &&
                        (chunk.payload[5].toInt() and 0xFF) == 0x01

                if (isClientHello && DpiConfig.enabled) {
                    currentTechnique = TechniqueStats.techniqueToUse(key.dstIp, key.dstPort)
                    applyTechnique(currentTechnique, chunk.payload)
                } else {
                    withContext(NetworkDispatcher.IO) {
                        socket.getOutputStream().write(chunk.payload)
                        socket.getOutputStream().flush()
                    }
                }

                firstDataSent = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error escribiendo al servidor real: ${e.message}")
            if (currentTechnique != DpiTechnique.NONE) {
                TechniqueStats.reportFailure(key.dstIp, key.dstPort, currentTechnique)
            }
            close()
        }
    }

    private suspend fun applyTechnique(technique: DpiTechnique, data: ByteArray) {
        when (technique) {
            DpiTechnique.SPLIT -> sendSplit(data, reverse = false)
            DpiTechnique.DISORDER -> sendSplit(data, reverse = true)
            DpiTechnique.FAKE_PACKET -> sendFakePacketThenReal(data)
            DpiTechnique.NONE -> {
                withContext(NetworkDispatcher.IO) {
                    socket.getOutputStream().write(data)
                    socket.getOutputStream().flush()
                }
            }
        }
    }

    private suspend fun sendSplit(data: ByteArray, reverse: Boolean) {
        val splitAt: Int
        val reason: String

        if (DpiConfig.useSniSplit) {
            val sniInfo = TlsParser.findSniHostname(data)
            if (sniInfo != null) {
                val (hostnameStart, hostnameLen) = sniInfo
                splitAt = (hostnameStart + hostnameLen / 2).coerceIn(1, data.size - 1)
                reason = "dentro del SNI"
            } else {
                splitAt = DpiConfig.splitPosition.coerceIn(1, data.size - 1)
                reason = "SNI no encontrado, posición fija"
            }
        } else {
            splitAt = DpiConfig.splitPosition.coerceIn(1, data.size - 1)
            reason = "corte fijo"
        }

        val part1 = data.copyOfRange(0, splitAt)
        val part2 = data.copyOfRange(splitAt, data.size)

        Log.i(TAG, "Técnica ${if (reverse) "DISORDER" else "SPLIT"}: ${part1.size}+${part2.size}B ($reason) para ${key.dstIp}:${key.dstPort}")

        withContext(NetworkDispatcher.IO) {
            val out = socket.getOutputStream()
            if (reverse) {
                out.write(part2); out.flush()
                if (DpiConfig.fragmentDelayMs > 0) delay(DpiConfig.fragmentDelayMs)
                out.write(part1); out.flush()
            } else {
                out.write(part1); out.flush()
                if (DpiConfig.fragmentDelayMs > 0) delay(DpiConfig.fragmentDelayMs)
                out.write(part2); out.flush()
            }
        }
    }

    private suspend fun sendFakePacketThenReal(data: ByteArray) {
        Log.i(TAG, "Técnica FAKE_PACKET para ${key.dstIp}:${key.dstPort}")

        withContext(NetworkDispatcher.IO) {
            val out = socket.getOutputStream()

            val fakeHello = buildFakeClientHello(data.size.coerceAtMost(64))
            out.write(fakeHello); out.flush()

            if (DpiConfig.fragmentDelayMs > 0) delay(DpiConfig.fragmentDelayMs)

            out.write(data); out.flush()
        }
    }

    private fun buildFakeClientHello(size: Int): ByteArray {
        val fake = ByteArray(size)
        fake[0] = 0x16
        fake[1] = 0x03; fake[2] = 0x01
        fake[3] = 0; fake[4] = (size - 5).toByte()
        fake[5] = 0x01
        for (i in 6 until size) fake[i] = (0..255).random().toByte()
        return fake
    }

    fun onClientFinOrRst() {
        close()
    }

    private suspend fun readFromServer() {
        val buffer = ByteArray(16384)
        var reportedSuccess = false
        try {
            while (true) {
                val n = try {
                    withContext(NetworkDispatcher.IO) { socket.getInputStream().read(buffer) }
                } catch (e: SocketTimeoutException) {
                    Log.w(TAG, "Timeout de lectura sin actividad, cerrando sesión inactiva ${key.dstIp}:${key.dstPort}")
                    break
                }
                if (n <= 0) break

                if (!reportedSuccess && currentTechnique != DpiTechnique.NONE) {
                    TechniqueStats.reportSuccess(key.dstIp, key.dstPort, currentTechnique)
                    reportedSuccess = true
                }

                sendData(buffer.copyOf(n))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Conexión con servidor real cerrada: ${e.message}")
        } finally {
            sendControl(PacketBuilder.TCP_FIN or PacketBuilder.TCP_ACK)
            close()
        }
    }

    private fun sendData(data: ByteArray) {
        val packet = PacketBuilder.buildTcpPacket(
            srcIp = serverIpBytes, dstIp = clientIpBytes,
            srcPort = key.dstPort, dstPort = key.srcPort,
            seqNum = serverSeq, ackNum = clientSeq,
            flags = PacketBuilder.TCP_ACK or PacketBuilder.TCP_PSH,
            payload = data
        )
        writeToTun(packet)
        serverSeq += data.size
    }

    private fun sendControl(flags: Int) {
        val packet = PacketBuilder.buildTcpPacket(
            srcIp = serverIpBytes, dstIp = clientIpBytes,
            srcPort = key.dstPort, dstPort = key.srcPort,
            seqNum = serverSeq, ackNum = clientSeq,
            flags = flags
        )
        writeToTun(packet)
    }

    private fun writeToTun(packet: ByteArray) {
        tunWriter.write(packet)
    }

    fun close() {
        if (closed) return
        closed = true
        writeQueue.close()
        ackSignal.close()
        try { socket.close() } catch (_: Exception) {}
        onClosed(key)
    }
}
