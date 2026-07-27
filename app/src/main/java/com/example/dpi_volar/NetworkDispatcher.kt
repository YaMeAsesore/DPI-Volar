package com.example.dpi_volar

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * Dispatcher dedicado para todo el trabajo de red de la VPN (sesiones TCP,
 * consultas DNS). Separado de Dispatchers.IO por defecto (limitado a 64
 * hilos compartidos con el resto de la app) para evitar que docenas de
 * conexiones simultáneas saturen el pool y retrasen conexiones nuevas.
 *
 * IMPORTANTE: el límite se mantiene moderado (48) a propósito. Un móvil
 * típico tiene entre 4 y 8 núcleos; permitir cientos de hilos bloqueantes
 * reales en paralelo (como estaba antes con 256) no da más throughput real,
 * solo dispara el cambio de contexto entre hilos, lo cual es una de las
 * causas más comunes de que un servicio de VPN caliente el dispositivo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object NetworkDispatcher {
    val IO: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(48)
}