package com.example.dpi_volar

object TlsParser {

    /**
     * Recorre la estructura de un ClientHello TLS y localiza el hostname
     * dentro de la extensión SNI.
     * Devuelve Pair(offsetAbsoluto, longitud) del hostname, o null si no se encuentra.
     */
    fun findSniHostname(data: ByteArray): Pair<Int, Int>? {
        try {
            if (data.size < 5) return null
            if ((data[0].toInt() and 0xFF) != 0x16) return null // no es TLS Handshake

            var pos = 5 // salta el Record Header
            if (pos + 4 > data.size) return null
            if ((data[pos].toInt() and 0xFF) != 0x01) return null // no es ClientHello

            pos += 4   // Handshake Header (type + length de 3 bytes)
            pos += 2   // client_version
            pos += 32  // random

            if (pos >= data.size) return null
            val sessionIdLen = data[pos].toInt() and 0xFF
            pos += 1 + sessionIdLen

            if (pos + 2 > data.size) return null
            val cipherSuitesLen = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
            pos += 2 + cipherSuitesLen

            if (pos + 1 > data.size) return null
            val compressionLen = data[pos].toInt() and 0xFF
            pos += 1 + compressionLen

            if (pos + 2 > data.size) return null
            val extensionsLen = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
            pos += 2
            val extensionsEnd = (pos + extensionsLen).coerceAtMost(data.size)

            while (pos + 4 <= extensionsEnd) {
                val extType = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
                val extLen = ((data[pos + 2].toInt() and 0xFF) shl 8) or (data[pos + 3].toInt() and 0xFF)
                val extDataStart = pos + 4

                if (extType == 0x0000) { // extensión "server_name" (SNI)
                    var sniPos = extDataStart
                    if (sniPos + 2 > data.size) return null
                    sniPos += 2 // server_name_list_length

                    if (sniPos + 3 > data.size) return null
                    val nameType = data[sniPos].toInt() and 0xFF
                    val nameLen = ((data[sniPos + 1].toInt() and 0xFF) shl 8) or (data[sniPos + 2].toInt() and 0xFF)
                    sniPos += 3

                    return if (nameType == 0 && sniPos + nameLen <= data.size) {
                        Pair(sniPos, nameLen) // offset absoluto + longitud del hostname
                    } else null
                }

                pos = extDataStart + extLen
            }
            return null
        } catch (e: Exception) {
            return null // cualquier estructura inesperada -> fallback seguro
        }
    }
}