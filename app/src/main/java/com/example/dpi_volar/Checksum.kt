package com.example.dpi_volar

object Checksum {

    /** Checksum estándar de Internet (RFC 1071), usado por IP y TCP. */
    fun compute(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        val end = offset + length

        while (i < end - 1) {
            val word = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i < end) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }

        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return (sum.inv() and 0xFFFF).toInt()
    }

    /** Checksum TCP requiere un "pseudo-header" con IPs y protocolo. */
    fun tcpChecksum(
        srcIp: ByteArray, dstIp: ByteArray,
        tcpSegment: ByteArray, tcpLength: Int
    ): Int {
        val pseudoHeader = ByteArray(12 + tcpLength)
        System.arraycopy(srcIp, 0, pseudoHeader, 0, 4)
        System.arraycopy(dstIp, 0, pseudoHeader, 4, 4)
        pseudoHeader[8] = 0
        pseudoHeader[9] = 6 // protocolo TCP
        pseudoHeader[10] = ((tcpLength shr 8) and 0xFF).toByte()
        pseudoHeader[11] = (tcpLength and 0xFF).toByte()
        System.arraycopy(tcpSegment, 0, pseudoHeader, 12, tcpLength)

        return compute(pseudoHeader, 0, pseudoHeader.size)
    }
}