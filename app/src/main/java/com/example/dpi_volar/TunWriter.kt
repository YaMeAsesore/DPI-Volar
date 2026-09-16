package com.example.dpi_volar

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.FileOutputStream

class TunWriter(
    private val output: FileOutputStream,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "TunWriter"
    }

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
