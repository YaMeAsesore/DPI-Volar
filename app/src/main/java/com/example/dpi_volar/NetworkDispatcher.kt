package com.example.dpi_volar

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Dispatcher dedicado para todo el trabajo de red de la VPN (sesiones TCP,
 * consultas DNS).
 *
 * HISTORIAL: antes esto era `Dispatchers.IO.limitedParallelism(48)`, bajo la
 * idea de que "menos hilos = menos calor". Esa idea es correcta para hilos
 * que hacen trabajo de CPU (o que giran en un bucle ocupado sin ceder el
 * hilo), pero NO aplica igual aquí: la inmensa mayoría de las tareas que
 * pasan por este dispatcher son llamadas bloqueantes de socket
 * (connect/read/write) que la mayor parte del tiempo están DORMIDAS
 * esperando red, no consumiendo CPU. El sistema operativo no gasta ciclos
 * en un hilo bloqueado en read() esperando datos.
 *
 * El límite de 48 sí causaba un problema real, pero de OTRO tipo:
 * agotamiento del pool. Cada sesión TCP mantiene un hilo ocupado durante
 * TODO el tiempo que dura `readFromServer()` esperando la respuesta del
 * servidor (puede ser segundos en conexiones keep-alive). Con una sola
 * página moderna abriendo 30-50 conexiones concurrentes, el pool de 48 se
 * saturaba rápido: las conexiones NUEVAS (connect()) se quedaban en cola
 * sin hilo disponible, expiraban su timeout, y cuando por fin conseguían
 * hilo el navegador ya había cerrado su lado -> "Socket is closed",
 * "Connection reset", "Broken pipe" en cascada, y el navegador reintentando
 * una y otra vez (lo que se percibe como "internet más lento").
 *
 * Por eso ahora usamos un pool de hilos "cached" (Executors.newCachedThreadPool):
 * crea hilos según se necesitan y reutiliza los que quedan libres; los
 * hilos ociosos se destruyen solos tras 60s sin uso. No hay un techo
 * artificial bajo, así que no volvemos a quedarnos cortos de hilos cuando
 * hay muchas conexiones concurrentes esperando red — y como esos hilos
 * pasan la mayor parte del tiempo dormidos, esto no implica más consumo de
 * batería ni más calor.
 */
object NetworkDispatcher {
    private val threadCounter = AtomicInteger(0)

    private val threadFactory = ThreadFactory { runnable ->
        Thread(runnable, "dpi-volar-net-${threadCounter.incrementAndGet()}").apply {
            isDaemon = true
        }
    }

    val IO: CoroutineDispatcher =
        Executors.newCachedThreadPool(threadFactory).asCoroutineDispatcher()
}