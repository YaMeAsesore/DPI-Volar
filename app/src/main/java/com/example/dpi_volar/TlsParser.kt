package com.example.dpi_volar

object TlsParser {

    fun findSniHostname(data: ByteArray): Pair<Int, Int>? {
        try {
            if (data.size < 5) return null
            if ((data[0].toInt() and 0xFF) != 0x16) return null

            var pos = 5
            if (pos + 4 > data.size) return null
            if ((data[pos].toInt() and 0xFF) != 0x01) return null

            pos += 4
            pos += 2
            pos += 32

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

                if (extType == 0x0000) {
                    var sniPos = extDataStart
                    if (sniPos + 2 > data.size) return null
                    sniPos += 2

                    if (sniPos + 3 > data.size) return null
                    val nameType = data[sniPos].toInt() and 0xFF
                    val nameLen = ((data[sniPos + 1].toInt() and 0xFF) shl 8) or (data[sniPos + 2].toInt() and 0xFF)
                    sniPos += 3

                    return if (nameType == 0 && sniPos + nameLen <= data.size) {
                        Pair(sniPos, nameLen)
                    } else null
                }

                pos = extDataStart + extLen
            }
            return null
        } catch (e: Exception) {
            return null
        }
    }
}
