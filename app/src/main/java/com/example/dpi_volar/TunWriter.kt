package com.example.dpi_volar

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.FileOutputStream

/**
 * Único punto de escritura hacia la interfaz TUN.
 *
 * ANTES: cada TcpSession (dos escrituras por sesión: datos y control) y la
 * resolución DNS escribían directamente al mismo FileOutputStream, protegido
 * por un `synchronized(tunOutput) { ... }` repetido en varios sitios. Con
 * decenas de conexiones TCP simultáneas —algo normal al cargar cualquier
 * página moderna con sus recursos, anuncios, CDNs, etc.— eso significa
 * muchos hilos distintos peleando por el mismo lock en cada paquete
 * saliente. Aunque cada escritura sea rápida, la contención constante
 * (hilos bloqueándose y despertándose unos a otros) es trabajo de CPU de
 * fondo permanente: justo el patrón que calienta el teléfono y gasta
 * batería sin que "se note" en ninguna función en particular.
 *
 * AHORA: todos los paquetes salientes se mandan a un canal, y una única
 * corrutina consumidora es la que de verdad toca el FileOutputStream.
 * Los productores (sesiones TCP, DNS) ya no compiten por un lock: solo
 * encolan el paquete y siguen. Esto también sirve como un pequeño buffer de
 * absorción ante ráfagas de tráfico.
 */
class TunWriter(
    private val output: FileOutputStream,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "TunWriter"
    }

    // Capacidad acotada a propósito: si algún día el consumidor no da
    // abasto (la TUN está bloqueada o el dispositivo va muy lento), es mejor
    // descartar el paquete más reciente (ver trySend abajo) que dejar crecer
    // la cola sin límite y acumular memoria/latencia indefinidamente.
    private val channel = Channel<ByteArray>(capacity = 2048)
    private var job: Job? = null

    fun start() {
        job = scope.launch {
            for (packet in channel) {
                try {
                    output.write(packet)
                } catch (e: Exception) {
                    Log.e(TAG, "Error escribiendo a la TUN: ${e.message}")
                }
            }
        }
    }

    /**
     * No bloquea ni suspende: si el canal está lleno (algo muy anómalo en
     * condiciones normales), se descarta el paquete en vez de frenar al
     * llamador. Es preferible perder, por ejemplo, un ACK duplicado a
     * bloquear el bucle de forwardPackets() o una sesión TCP entera.
     */
    fun write(packet: ByteArray) {
        val result = channel.trySend(packet)
        if (result.isFailure) {
            Log.w(TAG, "Cola de escritura a la TUN llena, paquete descartado")
        }
    }

    fun close() {
        channel.close()
        job?.cancel()
    }
}