package com.example.dpi_volar

import java.util.concurrent.ConcurrentHashMap

object TechniqueStats {

    private data class Record(
        var currentTechnique: DpiTechnique,
        var consecutiveFailures: Int,
        var confirmedWorking: Boolean
    )

    private const val FAILURE_THRESHOLD = 2

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
        return if (idx == -1) order[0] else order[(idx + 1) % order.size]
    }

    fun snapshot(): Map<String, Pair<DpiTechnique, Boolean>> {
        return records.mapValues { (_, r) -> r.currentTechnique to r.confirmedWorking }
    }

    fun reset() {
        records.clear()
    }
}
