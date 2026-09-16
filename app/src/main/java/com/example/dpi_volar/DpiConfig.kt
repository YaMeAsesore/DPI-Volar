package com.example.dpi_volar

enum class DpiTechnique {
    NONE,
    SPLIT,
    DISORDER,
    FAKE_PACKET
}

object DpiConfig {
    var enabled: Boolean = true
    var useSniSplit: Boolean = true
    var splitPosition: Int = 2
    var fragmentDelayMs: Long = 10

    var defaultTechnique: DpiTechnique = DpiTechnique.SPLIT
}
