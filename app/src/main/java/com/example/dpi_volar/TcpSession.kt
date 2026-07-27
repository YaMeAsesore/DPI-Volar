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
    private var firstDataSent = false
    private var currentTechnique: DpiTechnique = DpiTechnique.NONE

    private data class OutgoingChunk(val payload: ByteArray, val seq: Long)
    private val writeQueue = Channel<OutgoingChunk>(Channel.UNLIMITED)
    private val socketReady = CompletableDeferred<Unit>()

    suspend fun start(initialClientSeq: Long) {
        clientSeq = initialClientSeq + 1
        serverSeq = (0..Int.MAX_VALUE).random().toLong()

        socket = Socket()

        // Contestamos el handshake al CLIENTE ya, sin esperar a que la
        // conexión real contra el servidor destino termine. Antes se hacía
        // al revés (conectar primero, ACKear después), lo que añadía un RTT
        // completo de espera por cada conexión nueva antes de que el
        // navegador pudiera siquiera mandar el ClientHello. Si la conexión
        // real falla más abajo, se manda RST igual que antes — el navegador
        // ya sabe reaccionar a eso.
        sendControl(PacketBuilder.TCP_SYN or PacketBuilder.TCP_ACK)
        serverSeq += 1

        // Arranca ya a aceptar/ACKear datos del cliente. Lo que llegue se
        // queda en writeQueue (buffer ilimitado) hasta que el socket real
        // esté listo; processWriteQueue espera a socketReady antes de
        // escribir de verdad, así que nada se pierde ni se envía antes de
        // tiempo.
        scope.launch { processWriteQueue() }

        try {
            withContext(NetworkDispatcher.IO) {
                socket.bind(InetSocketAddress(0))
            }

            val protected = vpnService.protect(socket)
            Log.d(TAG, "protect() resultado: $protected para ${key.dstIp}:${key.dstPort}")

            withContext(NetworkDispatcher.IO) {
                val addr = InetAddress.getByAddress(serverIpBytes)
                socket.connect(InetSocketAddress(addr, key.dstPort), 3000) // timeout reducido
                socket.tcpNoDelay = true
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
            // Retransmisión ya confirmada: solo reenviar el ACK (en segundo
            // plano, ver nota abajo), sin volver a encolar el dato.
            scope.launch { sendControl(PacketBuilder.TCP_ACK) }
            return
        }

        // Actualizar clientSeq aquí es solo una asignación de variable (muy
        // rápido, no bloquea nada), así que se hace ya mismo para que
        // futuras comprobaciones de retransmisión sean correctas.
        clientSeq = payloadEnd

        // OJO: onClientData() la llama directamente forwardPackets(), el
        // único bucle que lee TODOS los paquetes de la TUN uno a uno. Si
        // aquí mismo escribiéramos el ACK de forma síncrona (writeToTun usa
        // un candado compartido con el resto de sesiones), estaríamos
        // bloqueando la lectura de paquetes de TODA la app cada vez que
        // llega un dato — eso fue lo que causó que "ahora tarde de más".
        // Por eso el envío del ACK se despacha en una corrutina aparte: sigue
        // siendo prácticamente inmediato (evita las retransmisiones), pero
        // ya no compite por tiempo con la lectura de la TUN.
        scope.launch { sendControl(PacketBuilder.TCP_ACK) }

        writeQueue.trySend(OutgoingChunk(payload, incomingSeq))
    }

    private suspend fun processWriteQueue() {
        try {
            for (chunk in writeQueue) {
                // Espera a que el socket real esté conectado antes de escribir
                // este chunk. El ACK al cliente ya se mandó al recibir el
                // dato (ver onClientData); aquí solo se pausa el envío real
                // al servidor destino.
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

    /** Punto único de despacho: aplica la técnica elegida sobre el ClientHello. */
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

    /** Técnica SPLIT / DISORDER: corta en 2 (dentro del SNI si se puede) y las manda en orden normal o invertido. */
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

    /**
     * Técnica FAKE PACKET: manda un paquete señuelo antes del ClientHello real.
     * NOTA: excluida de la rotación automática en TechniqueStats porque, sin
     * control de TTL a nivel de socket (requiere sockets crudos/NDK), este
     * señuelo llega al servidor real y corrompe el stream TLS. Se deja el
     * código por si en el futuro se implementa vía sockets nativos.
     */
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
        fake[0] = 0x16 // TLS Handshake
        fake[1] = 0x03; fake[2] = 0x01 // TLS 1.0
        fake[3] = 0; fake[4] = (size - 5).toByte()
        fake[5] = 0x01 // ClientHello
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
                val n = withContext(NetworkDispatcher.IO) { socket.getInputStream().read(buffer) }
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
        try {
            synchronized(tunOutput) {
                tunOutput.write(packet)
            }
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