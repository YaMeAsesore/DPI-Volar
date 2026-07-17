package com.example.dpi_volar

import android.net.VpnService
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

data class SessionKey(
    val srcIp: String, val srcPort: Int,
    val dstIp: String, val dstPort: Int
)

class TcpSession(
    val key: SessionKey,
    private val clientIpBytes: ByteArray,
    private val serverIpBytes: ByteArray,
    private val vpnService: VpnService,
    private val tunOutput: FileOutputStream,
    private val scope: CoroutineScope,
    private val onClosed: (SessionKey) -> Unit
) {
    companion object { const val TAG = "TcpSession" }

    private var clientSeq: Long = 0
    private var serverSeq: Long = 0
    private lateinit var socket: Socket
    private var closed = false
    private var firstDataSent = false // controla si ya aplicamos el split al ClientHello

    private data class OutgoingChunk(val payload: ByteArray, val seq: Long)
    private val writeQueue = Channel<OutgoingChunk>(Channel.UNLIMITED)

    suspend fun start(initialClientSeq: Long) {
        clientSeq = initialClientSeq + 1
        serverSeq = (0..Int.MAX_VALUE).random().toLong()

        try {
            socket = Socket()

            withContext(Dispatchers.IO) {
                socket.bind(InetSocketAddress(0))
            }

            val protected = vpnService.protect(socket)
            Log.d(TAG, "protect() resultado: $protected para ${key.dstIp}:${key.dstPort}")

            withContext(Dispatchers.IO) {
                val addr = InetAddress.getByAddress(serverIpBytes)
                socket.connect(InetSocketAddress(addr, key.dstPort), 5000)
                socket.tcpNoDelay = true // desactiva Nagle: cada write() sale como paquete separado
            }

            sendControl(PacketBuilder.TCP_SYN or PacketBuilder.TCP_ACK)
            serverSeq += 1

            scope.launch { readFromServer() }
            scope.launch { processWriteQueue() }

        } catch (e: Exception) {
            Log.e(TAG, "No se pudo conectar a ${key.dstIp}:${key.dstPort} -> ${e.message}")
            sendControl(PacketBuilder.TCP_RST)
            close()
        }
    }

    fun onClientData(payload: ByteArray, incomingSeq: Long) {
        if (payload.isEmpty()) return
        writeQueue.trySend(OutgoingChunk(payload, incomingSeq))
    }

    private suspend fun processWriteQueue() {
        try {
            for (chunk in writeQueue) {
                val isClientHello = !firstDataSent &&
                        chunk.payload.size > 6 &&
                        (chunk.payload[0].toInt() and 0xFF) == 0x16 &&
                        (chunk.payload[5].toInt() and 0xFF) == 0x01

                if (isClientHello && DpiConfig.enabled) {
                    sendFragmented(chunk.payload)
                } else {
                    withContext(Dispatchers.IO) {
                        socket.getOutputStream().write(chunk.payload)
                        socket.getOutputStream().flush()
                    }
                }

                firstDataSent = true
                clientSeq = chunk.seq + chunk.payload.size
                sendControl(PacketBuilder.TCP_ACK)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error escribiendo al servidor real: ${e.message}")
            close()
        }
    }

    /** Fragmenta el ClientHello en 2 partes y las envía como escrituras TCP separadas. */
    private suspend fun sendFragmented(data: ByteArray) {
        var splitAt: Int
        var reason: String

        if (DpiConfig.useSniSplit) {
            val sniInfo = TlsParser.findSniHostname(data)
            if (sniInfo != null) {
                val (hostnameStart, hostnameLen) = sniInfo
                // Corta justo a la mitad del hostname (ej: "goo|gle.com")
                splitAt = (hostnameStart + hostnameLen / 2).coerceIn(1, data.size - 1)
                reason = "dentro del SNI"
            } else {
                splitAt = DpiConfig.splitPosition.coerceIn(1, data.size - 1)
                reason = "SNI no encontrado, usando posición fija"
            }
        } else {
            splitAt = DpiConfig.splitPosition.coerceIn(1, data.size - 1)
            reason = "corte fijo (configurado)"
        }

        val part1 = data.copyOfRange(0, splitAt)
        val part2 = data.copyOfRange(splitAt, data.size)

        Log.i(TAG, "Aplicando Split: ${part1.size} + ${part2.size} bytes ($reason, posición $splitAt) para ${key.dstIp}:${key.dstPort}")

        withContext(Dispatchers.IO) {
            val out = socket.getOutputStream()

            if (DpiConfig.reverseOrder) {
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

    fun onClientFinOrRst() {
        close()
    }

    private suspend fun readFromServer() {
        val buffer = ByteArray(16384)
        try {
            while (true) {
                val n = withContext(Dispatchers.IO) { socket.getInputStream().read(buffer) }
                if (n <= 0) break
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

    @Synchronized
    private fun writeToTun(packet: ByteArray) {
        try {
            tunOutput.write(packet)
        } catch (e: Exception) {
            Log.e(TAG, "Error escribiendo a la TUN: ${e.message}")
        }
    }

    fun close() {
        if (closed) return
        closed = true
        writeQueue.close()
        try { socket.close() } catch (_: Exception) {}
        onClosed(key)
    }
}