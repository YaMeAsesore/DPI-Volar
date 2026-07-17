package com.example.dpi_volar

object PacketBuilder {

    /**
     * Construye un paquete IPv4 + TCP completo, listo para escribir a la TUN.
     * srcIp/dstIp deben ser arrays de 4 bytes.
     */
    fun buildUdpPacket(
        srcIp: ByteArray, dstIp: ByteArray,
        srcPort: Int, dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val udpHeaderLength = 8
        val udpTotalLength = udpHeaderLength + payload.size
        val ipHeaderLength = 20
        val totalLength = ipHeaderLength + udpTotalLength

        val packet = ByteArray(totalLength)

        // ---- IP Header ----
        packet[0] = 0x45
        packet[1] = 0
        packet[2] = ((totalLength shr 8) and 0xFF).toByte()
        packet[3] = (totalLength and 0xFF).toByte()
        packet[4] = 0; packet[5] = 0
        packet[6] = 0x40.toByte(); packet[7] = 0
        packet[8] = 64
        packet[9] = 17 // protocolo UDP
        packet[10] = 0; packet[11] = 0
        System.arraycopy(srcIp, 0, packet, 12, 4)
        System.arraycopy(dstIp, 0, packet, 16, 4)

        val ipChecksum = Checksum.compute(packet, 0, ipHeaderLength)
        packet[10] = ((ipChecksum shr 8) and 0xFF).toByte()
        packet[11] = (ipChecksum and 0xFF).toByte()

        // ---- UDP Header ----
        val udpOffset = ipHeaderLength
        packet[udpOffset] = ((srcPort shr 8) and 0xFF).toByte()
        packet[udpOffset + 1] = (srcPort and 0xFF).toByte()
        packet[udpOffset + 2] = ((dstPort shr 8) and 0xFF).toByte()
        packet[udpOffset + 3] = (dstPort and 0xFF).toByte()
        packet[udpOffset + 4] = ((udpTotalLength shr 8) and 0xFF).toByte()
        packet[udpOffset + 5] = (udpTotalLength and 0xFF).toByte()
        packet[udpOffset + 6] = 0; packet[udpOffset + 7] = 0 // checksum opcional en UDP/IPv4, lo dejamos en 0

        System.arraycopy(payload, 0, packet, udpOffset + udpHeaderLength, payload.size)

        return packet
    }
    fun buildTcpPacket(
        srcIp: ByteArray, dstIp: ByteArray,
        srcPort: Int, dstPort: Int,
        seqNum: Long, ackNum: Long,
        flags: Int, // ej: TCP_SYN or TCP_ACK
        payload: ByteArray = ByteArray(0)
    ): ByteArray {
        val tcpHeaderLength = 20
        val totalTcpLength = tcpHeaderLength + payload.size
        val ipHeaderLength = 20
        val totalLength = ipHeaderLength + totalTcpLength

        val packet = ByteArray(totalLength)

        // ---- IP Header ----
        packet[0] = 0x45 // version 4, IHL 5 (20 bytes)
        packet[1] = 0
        packet[2] = ((totalLength shr 8) and 0xFF).toByte()
        packet[3] = (totalLength and 0xFF).toByte()
        packet[4] = 0; packet[5] = 0 // ID
        packet[6] = 0x40.toByte(); packet[7] = 0 // flags: Don't Fragment
        packet[8] = 64 // TTL
        packet[9] = 6 // protocolo TCP
        packet[10] = 0; packet[11] = 0 // checksum (se calcula después)
        System.arraycopy(srcIp, 0, packet, 12, 4)
        System.arraycopy(dstIp, 0, packet, 16, 4)

        val ipChecksum = Checksum.compute(packet, 0, ipHeaderLength)
        packet[10] = ((ipChecksum shr 8) and 0xFF).toByte()
        packet[11] = (ipChecksum and 0xFF).toByte()

        // ---- TCP Header ----
        val tcpOffset = ipHeaderLength
        packet[tcpOffset] = ((srcPort shr 8) and 0xFF).toByte()
        packet[tcpOffset + 1] = (srcPort and 0xFF).toByte()
        packet[tcpOffset + 2] = ((dstPort shr 8) and 0xFF).toByte()
        packet[tcpOffset + 3] = (dstPort and 0xFF).toByte()

        packet[tcpOffset + 4] = ((seqNum shr 24) and 0xFF).toByte()
        packet[tcpOffset + 5] = ((seqNum shr 16) and 0xFF).toByte()
        packet[tcpOffset + 6] = ((seqNum shr 8) and 0xFF).toByte()
        packet[tcpOffset + 7] = (seqNum and 0xFF).toByte()

        packet[tcpOffset + 8] = ((ackNum shr 24) and 0xFF).toByte()
        packet[tcpOffset + 9] = ((ackNum shr 16) and 0xFF).toByte()
        packet[tcpOffset + 10] = ((ackNum shr 8) and 0xFF).toByte()
        packet[tcpOffset + 11] = (ackNum and 0xFF).toByte()

        packet[tcpOffset + 12] = (5 shl 4).toByte() // data offset = 5 (20 bytes), sin opciones
        packet[tcpOffset + 13] = flags.toByte()
        packet[tcpOffset + 14] = 0xFF.toByte(); packet[tcpOffset + 15] = 0xFF.toByte() // window size
        packet[tcpOffset + 16] = 0; packet[tcpOffset + 17] = 0 // checksum (después)
        packet[tcpOffset + 18] = 0; packet[tcpOffset + 19] = 0 // urgent pointer

        if (payload.isNotEmpty()) {
            System.arraycopy(payload, 0, packet, tcpOffset + tcpHeaderLength, payload.size)
        }

        val tcpSegment = packet.copyOfRange(tcpOffset, totalLength)
        val tcpChecksum = Checksum.tcpChecksum(srcIp, dstIp, tcpSegment, totalTcpLength)
        packet[tcpOffset + 16] = ((tcpChecksum shr 8) and 0xFF).toByte()
        packet[tcpOffset + 17] = (tcpChecksum and 0xFF).toByte()

        return packet
    }

    const val TCP_FIN = 0x01
    const val TCP_SYN = 0x02
    const val TCP_RST = 0x04
    const val TCP_PSH = 0x08
    const val TCP_ACK = 0x10
}