package com.example.dpi_volar

object DpiConfig {
    var enabled: Boolean = true

    /** Si es true, busca el SNI real y corta ahí dentro. Si falla, usa splitPosition. */
    var useSniSplit: Boolean = true

    /** Byte de corte usado solo si useSniSplit falla o está desactivado. */
    var splitPosition: Int = 2

    var fragmentDelayMs: Long = 10
    var reverseOrder: Boolean = false
}