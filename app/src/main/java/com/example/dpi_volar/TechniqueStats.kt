package com.example.dpi_volar

import java.util.concurrent.ConcurrentHashMap

/**
 * Recuerda qué técnica tuvo éxito la última vez para un host+puerto dado, y rota
 * automáticamente a la siguiente técnica cuando una falla varias veces seguidas.
 */
object TechniqueStats {

    private data class Record(
        var currentTechnique: DpiTechnique,
        var consecutiveFailures: Int,
        var confirmedWorking: Boolean
    )

    // Umbral: cuántos fallos seguidos antes de probar la siguiente técnica.
    private const val FAILURE_THRESHOLD = 2

    // FAKE_PACKET excluido: requiere control de TTL a nivel de socket crudo,
    // no disponible vía java.net.Socket sin permisos root/NDK. Si se activa,
    // corrompe el stream real garantizadamente (ver net_error -107 en Chrome).
    private val order = listOf(
        DpiTechnique.SPLIT,
        DpiTechnique.DISORDER,
        DpiTechnique.NONE
    )

    private val records = ConcurrentHashMap<String, Record>()

    private fun keyFor(host: String, port: Int) = "$host:$port"

    fun techniqueToUse(host: String, port: Int): DpiTechnique {
        val record = records[keyFor(host, port)]
        return record?.currentTechnique ?: DpiConfig.defaultTechnique
    }

    fun reportSuccess(host: String, port: Int, technique: DpiTechnique) {
        records[keyFor(host, port)] = Record(technique, consecutiveFailures = 0, confirmedWorking = true)
    }

    fun reportFailure(host: String, port: Int, technique: DpiTechnique) {
        val key = keyFor(host, port)
        val record = records.getOrPut(key) {
            Record(technique, consecutiveFailures = 0, confirmedWorking = false)
        }

        if (record.currentTechnique != technique) {
            record.currentTechnique = technique
            record.consecutiveFailures = 0
        }

        record.consecutiveFailures += 1
        record.confirmedWorking = false

        if (record.consecutiveFailures >= FAILURE_THRESHOLD) {
            record.currentTechnique = nextTechnique(record.currentTechnique)
            record.consecutiveFailures = 0
        }
    }

    private fun nextTechnique(current: DpiTechnique): DpiTechnique {
        val idx = order.indexOf(current)
        // Si la técnica actual es FAKE_PACKET (ya no está en `order` pero pudo
        // quedar guardada de una sesión previa), el idx será -1; en ese caso
        // arrancamos desde el principio de la lista.
        return if (idx == -1) order[0] else order[(idx + 1) % order.size]
    }

    /** Para mostrar en la pantalla de diagnóstico (Paso 5). */
    fun snapshot(): Map<String, Pair<DpiTechnique, Boolean>> {
        return records.mapValues { (_, r) -> r.currentTechnique to r.confirmedWorking }
    }

    fun reset() {
        records.clear()
    }
}