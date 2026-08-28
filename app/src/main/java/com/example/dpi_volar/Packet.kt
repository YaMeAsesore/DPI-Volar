package com.example.dpi_volar

class IPv4Packet(private val raw: ByteArray, private val length: Int) {
    fun isUdp(): Boolean = protocol == 17

    fun getUdpSegment(): UdpSegment? {
        if (!isUdp()) return null
        return UdpSegment(raw, payloadOffset, length)
    }

    // OPTIMIZACIÓN: antes estos campos eran `val` normales, es decir, se
    // calculaban TODOS en el instante en que se crea el IPv4Packet — para
    // CADA paquete que pasa por la VPN, sin excepción. Eso incluye construir
    // dos Strings de IP por concatenación (sourceIp/destIp) y copiar 8 bytes
    // (sourceIpBytes/destIpBytes) aunque el paquete se vaya a descartar dos
    // líneas después en forwardPackets() por no ser TCP ni UDP, o aunque ese
    // campo en particular nunca se llegue a leer para ese paquete en concreto.
    // Con tráfico normal esto son miles de paquetes por segundo -> miles de
    // Strings y arrays basura por segundo para el recolector de basura, lo
    // cual es trabajo de CPU constante y de fondo, exactamente el tipo de
    // cosa que se nota como "calentamiento constante" aunque cada operación
    // individual sea barata.
    //
    // `by lazy` hace que el valor se calcule solo la primera vez que
    // alguien lo lee. Usamos NONE (sin sincronización) porque cada
    // IPv4Packet se construye y se lee dentro del mismo hilo del bucle de
    // forwardPackets antes de que cualquier valor cruce a otra corrutina
    // (los sitios que necesitan pasar estos datos a otra corrutona, como
    // handleDnsQuery, ya los leen y copian a variables locales ANTES de
    // lanzar esa corrutina — ver comentario en MyVpnService).
    val version: Int by lazy(LazyThreadSafetyMode.NONE) { (raw[0].toInt() shr 4) and 0x0F }
    val ihl: Int by lazy(LazyThreadSafetyMode.NONE) { (raw[0].toInt() and 0x0F) * 4 }
    val protocol: Int by lazy(LazyThreadSafetyMode.NONE) { raw[9].toInt() and 0xFF }
    val sourceIp: String by lazy(LazyThreadSafetyMode.NONE) { ipToString(raw, 12) }
    val destIp: String by lazy(LazyThreadSafetyMode.NONE) { ipToString(raw, 16) }

    val sourceIpBytes: ByteArray by lazy(LazyThreadSafetyMode.NONE) { raw.copyOfRange(12, 16) }
    val destIpBytes: ByteArray by lazy(LazyThreadSafetyMode.NONE) { raw.copyOfRange(16, 20) }

    val payloadOffset: Int by lazy(LazyThreadSafetyMode.NONE) { ihl }

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