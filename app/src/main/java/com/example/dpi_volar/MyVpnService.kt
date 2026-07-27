package com.example.dpi_volar

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

class MyVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val sessions = ConcurrentHashMap<SessionKey, TcpSession>()

    companion object {
        const val TAG = "MyVpnService"
        const val ACTION_START = "com.example.dpi_volar.START"
        const val ACTION_STOP = "com.example.dpi_volar.STOP"
        private const val NOTIFICATION_CHANNEL_ID = "dpi_volar_channel"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Protección DPI",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Estado de la protección contra DPI"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): android.app.Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("DPI-Volar activo")
            .setContentText("Tu conexión está protegida contra bloqueos DPI")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopVpn(); return START_NOT_STICKY }
            else -> startVpn()
        }
        return START_STICKY
    }

    /**
     * Resuelve una consulta DNS por UDP plano.
     * IMPORTANTE: todos los parámetros deben ser copias independientes
     * (ByteArray propios, no vistas sobre el buffer compartido de forwardPackets),
     * porque esta función corre en una corrutina lanzada de forma asíncrona
     * y el buffer principal puede sobrescribirse antes de que esto se ejecute.
     */
    private suspend fun handleDnsQuery(
        queryPayload: ByteArray,
        srcIpBytes: ByteArray,
        dstIpBytes: ByteArray,
        srcPort: Int,
        dstPort: Int,
        tunOutput: FileOutputStream
    ) {
        // 1. Revisa el cache antes de hacer cualquier trabajo de red
        val cachedResponse = DnsCache.get(queryPayload)
        if (cachedResponse != null) {
            val replyPacket = PacketBuilder.buildUdpPacket(
                srcIp = dstIpBytes, dstIp = srcIpBytes,
                srcPort = dstPort, dstPort = srcPort,
                payload = cachedResponse
            )
            synchronized(tunOutput) {
                tunOutput.write(replyPacket)
            }
            return // <-- respuesta instantánea, sin tocar la red
        }

        // 2. No hay cache: resuelve de verdad y guarda el resultado para la próxima
        try {
            java.net.DatagramSocket().use { socket ->
                protect(socket)

                val dnsServerAddr = java.net.InetAddress.getByAddress(dstIpBytes)
                val outPacket = java.net.DatagramPacket(queryPayload, queryPayload.size, dnsServerAddr, dstPort)

                withContext(NetworkDispatcher.IO) {
                    socket.soTimeout = 5000
                    socket.send(outPacket)

                    val responseBuffer = ByteArray(4096)
                    val responsePacket = java.net.DatagramPacket(responseBuffer, responseBuffer.size)
                    socket.receive(responsePacket)

                    val responseData = responseBuffer.copyOf(responsePacket.length)

                    DnsCache.put(queryPayload, responseData) // <-- guarda para la próxima vez

                    val replyPacket = PacketBuilder.buildUdpPacket(
                        srcIp = dstIpBytes, dstIp = srcIpBytes,
                        srcPort = dstPort, dstPort = srcPort,
                        payload = responseData
                    )
                    synchronized(tunOutput) {
                        tunOutput.write(replyPacket)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resolviendo DNS: ${e.message}")
        }
    }

    private fun startVpn() {
        if (vpnInterface != null) return

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        val builder = Builder()
            .setSession("DPI-Volar")
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")
            .setMtu(1500)

        vpnInterface = builder.establish()
        if (vpnInterface == null) {
            Log.e(TAG, "No se pudo establecer la interfaz VPN")
            return
        }

        Log.i(TAG, "VPN establecida, iniciando bucle de reenvío")
        isRunning = true
        job = scope.launch { forwardPackets() }
    }

    private suspend fun forwardPackets() {
        val fd = vpnInterface?.fileDescriptor ?: return
        val input = FileInputStream(fd)
        val output = FileOutputStream(fd)
        val buffer = ByteArray(32767)

        while (coroutineContext.isActive) {
            try {
                val length = input.read(buffer)

                if (length < 0) {
                    // -1 significa fin de stream / descriptor cerrado o inválido:
                    // no hay forma de que vuelva a dar datos, así que salimos del
                    // bucle en vez de seguir girando en vacío.
                    Log.e(TAG, "read() de la TUN devolvió -1, deteniendo bucle de reenvío")
                    break
                }
                if (length == 0) {
                    // Lectura vacía puntual: cede el hilo un instante en vez de
                    // reintentar inmediatamente en un bucle caliente (esto era lo
                    // que estaba quemando CPU y calentando el dispositivo).
                    delay(5)
                    continue
                }

                val packet = IPv4Packet(buffer, length)

                if (packet.isUdp()) {
                    val udp = packet.getUdpSegment()
                    if (udp != null && udp.destPort == 53) {
                        // Copiamos TODO lo necesario de forma síncrona AHORA,
                        // antes de que el buffer compartido se reutilice.
                        val queryPayload = udp.getPayload()
                        val srcIpBytes = packet.sourceIpBytes
                        val dstIpBytes = packet.destIpBytes
                        val srcPort = udp.sourcePort
                        val dstPort = udp.destPort

                        scope.launch {
                            handleDnsQuery(queryPayload, srcIpBytes, dstIpBytes, srcPort, dstPort, output)
                        }
                    }
                    continue
                }

                if (!packet.isTcp()) continue

                val tcp = packet.getTcpSegment() ?: continue
                val sessionKey = SessionKey(packet.sourceIp, tcp.sourcePort, packet.destIp, tcp.destPort)

                when {
                    tcp.isSyn && !tcp.isAck -> {
                        if (sessions.containsKey(sessionKey)) continue
                        val session = TcpSession(
                            key = sessionKey,
                            clientIpBytes = packet.sourceIpBytes,
                            serverIpBytes = packet.destIpBytes,
                            vpnService = this,
                            tunOutput = output,
                            scope = scope,
                            onClosed = { key -> sessions.remove(key) }
                        )
                        sessions[sessionKey] = session
                        scope.launch { session.start(tcp.seqNum) }
                    }
                    tcp.isRst || tcp.isFin -> {
                        sessions[sessionKey]?.onClientFinOrRst()
                    }
                    tcp.hasPayload() -> {
                        if (tcp.isTlsClientHello()) {
                            Log.i(TAG, "ClientHello detectado: ${packet.sourceIp}:${tcp.sourcePort} -> ${packet.destIp}:${tcp.destPort}")
                        }
                        sessions[sessionKey]?.onClientData(tcp.getPayload(), tcp.seqNum)
                    }
                    else -> { }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error en el bucle de reenvío: ${e.message}")
                // Si el error se repite en cada vuelta (ej. TUN en mal estado),
                // este delay evita que el catch se convierta en otro bucle caliente.
                delay(20)
            }
        }
    }

    private fun stopVpn() {
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        job?.cancel()
        sessions.values.forEach { it.close() }
        sessions.clear()
        try { vpnInterface?.close() } catch (e: Exception) {
            Log.e(TAG, "Error cerrando interfaz: ${e.message}")
        }
        vpnInterface = null
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        scope.cancel()
        super.onDestroy()
    }
}