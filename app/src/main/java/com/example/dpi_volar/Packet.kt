package com.example.dpi_volar

class IPv4Packet(private val raw: ByteArray, private val length: Int) {
    fun isUdp(): Boolean = protocol == 17

    fun getUdpSegment(): UdpSegment? {
        if (!isUdp()) return null
        return UdpSegment(raw, payloadOffset, length)
    }

    val version: Int = (raw[0].toInt() shr 4) and 0x0F
    val ihl: Int = (raw[0].toInt() and 0x0F) * 4
    val protocol: Int = raw[9].toInt() and 0xFF
    val sourceIp: String = ipToString(raw, 12)
    val destIp: String = ipToString(raw, 16)

    val sourceIpBytes: ByteArray = raw.copyOfRange(12, 16)
    val destIpBytes: ByteArray = raw.copyOfRange(16, 20)

    val payloadOffset: Int = ihl

    fun isTcp(): Boolean = protocol == 6

    fun getTcpSegment(): TcpSegment? {
        if (!isTcp()) return null
        return TcpSegment(raw, payloadOffset, length)
    }

    private fun ipToString(data: ByteArray, offset: Int): String {
        return "${data[offset].toInt() and 0xFF}." +
                "${data[offset + 1].toInt() and 0xFF}." +
                "${data[offset + 2].toInt() and 0xFF}." +
                "${data[offset + 3].toInt() and 0xFF}"
    }
}
class UdpSegment(private val raw: ByteArray, private val offset: Int, private val totalLength: Int) {

    val sourcePort: Int = ((raw[offset].toInt() and 0xFF) shl 8) or (raw[offset + 1].toInt() and 0xFF)
    val destPort: Int = ((raw[offset + 2].toInt() and 0xFF) shl 8) or (raw[offset + 3].toInt() and 0xFF)
    val length: Int = ((raw[offset + 4].toInt() and 0xFF) shl 8) or (raw[offset + 5].toInt() and 0xFF)

    val payloadStart: Int = offset + 8
    val payloadLength: Int = totalLength - payloadStart

    fun getPayload(): ByteArray {
        if (payloadLength <= 0) return ByteArray(0)
        return raw.copyOfRange(payloadStart, payloadStart + payloadLength)
    }
}
class TcpSegment(private val raw: ByteArray, private val offset: Int, private val totalLength: Int) {

    val sourcePort: Int = ((raw[offset].toInt() and 0xFF) shl 8) or (raw[offset + 1].toInt() and 0xFF)
    val destPort: Int = ((raw[offset + 2].toInt() and 0xFF) shl 8) or (raw[offset + 3].toInt() and 0xFF)
    val dataOffset: Int = ((raw[offset + 12].toInt() shr 4) and 0x0F) * 4

    // Secuencia del CLIENTE (importante: la necesitamos para el ACK)
    val seqNum: Long = readUInt32(offset + 4)

    val flags: Int = raw[offset + 13].toInt() and 0xFF
    val isSyn: Boolean = (flags and 0x02) != 0
    val isAck: Boolean = (flags and 0x10) != 0
    val isFin: Boolean = (flags and 0x01) != 0
    val isRst: Boolean = (flags and 0x04) != 0
    val isPsh: Boolean = (flags and 0x08) != 0

    val payloadStart: Int = offset + dataOffset
    val payloadLength: Int = totalLength - payloadStart

    fun hasPayload(): Boolean = payloadLength > 0

    fun getPayload(): ByteArray {
        if (!hasPayload()) return ByteArray(0)
        return raw.copyOfRange(payloadStart, payloadStart + payloadLength)
    }

    fun isTlsClientHello(): Boolean {
        if (payloadLength < 6) return false
        return raw[payloadStart].toInt() and 0xFF == 0x16 &&
                raw[payloadStart + 5].toInt() and 0xFF == 0x01
    }

    private fun readUInt32(pos: Int): Long {
        return ((raw[pos].toLong() and 0xFF) shl 24) or
                ((raw[pos + 1].toLong() and 0xFF) shl 16) or
                ((raw[pos + 2].toLong() and 0xFF) shl 8) or
                (raw[pos + 3].toLong() and 0xFF)
    }
}