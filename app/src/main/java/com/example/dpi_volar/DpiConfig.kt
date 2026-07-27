package com.example.dpi_volar

enum class DpiTechnique {
    NONE,           // sin modificación, passthrough normal
    SPLIT,          // corta el ClientHello en 2 (la que ya tenías)
    DISORDER,       // manda los fragmentos en orden invertido con delay
    FAKE_PACKET     // manda un paquete señuelo (decoy) antes del real
}

object DpiConfig {
    var enabled: Boolean = true
    var useSniSplit: Boolean = true
    var splitPosition: Int = 2
    var fragmentDelayMs: Long = 10

    /** Técnica activa por defecto para conexiones nuevas sin historial. */
    var defaultTechnique: DpiTechnique = DpiTechnique.SPLIT
}