package com.example.dpi_volar

import android.net.VpnService
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.SNIHostName

object DohClient {
    private const val TAG = "DohClient"
    private const val DOH_IP = "1.1.1.1"
    private const val DOH_HOSTNAME = "cloudflare-dns.com"
    private const val DOH_PATH = "/dns-query"

    suspend fun resolve(vpnService: VpnService, queryBytes: ByteArray): ByteArray? {
        return withContext(Dispatchers.IO) {
            var plainSocket: Socket? = null
            var sslSocket: SSLSocket? = null
            try {
                plainSocket = Socket()
                plainSocket.bind(InetSocketAddress(0))
                vpnService.protect(plainSocket)

                val addr = InetAddress.getByName(DOH_IP)
                plainSocket.connect(InetSocketAddress(addr, 443), 5000)

                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                sslSocket = factory.createSocket(plainSocket, DOH_HOSTNAME, 443, true) as SSLSocket

                val params = SSLParameters()
                params.serverNames = listOf(SNIHostName(DOH_HOSTNAME))
                sslSocket.sslParameters = params

                sslSocket.startHandshake()

                val verifier = javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier()
                if (!verifier.verify(DOH_HOSTNAME, sslSocket.session)) {
                    Log.e(TAG, "Certificado TLS inválido para $DOH_HOSTNAME")
                    return@withContext null
                }

                val request = buildString {
                    append("POST $DOH_PATH HTTP/1.1\r\n")
                    append("Host: $DOH_HOSTNAME\r\n")
                    append("Content-Type: application/dns-message\r\n")
                    append("Accept: application/dns-message\r\n")
                    append("Content-Length: ${queryBytes.size}\r\n")
                    append("Connection: close\r\n")
                    append("\r\n")
                }

                sslSocket.outputStream.write(request.toByteArray(Charsets.ISO_8859_1))
                sslSocket.outputStream.write(queryBytes)
                sslSocket.outputStream.flush()

                val response = readHttpResponse(sslSocket)
                response
            } catch (e: Exception) {
                Log.e(TAG, "Error en consulta DoH: ${e.message}")
                null
            } finally {
                try { sslSocket?.close() } catch (_: Exception) {}
                try { plainSocket?.close() } catch (_: Exception) {}
            }
        }
    }

    private fun readHttpResponse(socket: SSLSocket): ByteArray? {
        val input = BufferedInputStream(socket.inputStream)

        val headerBytes = ArrayList<Byte>()
        var prevWasCR = false
        var consecutiveNewlines = 0
        while (true) {
            val b = input.read()
            if (b == -1) return null
            headerBytes.add(b.toByte())
            when (b.toChar()) {
                '\r' -> { }
                '\n' -> {
                    consecutiveNewlines++
                    if (consecutiveNewlines == 2) break
                    continue
                }
                else -> { consecutiveNewlines = 0 }
            }
            if (b.toChar() != '\r') consecutiveNewlines = if (b.toChar() == '\n') consecutiveNewlines else 0
        }

        val headerText = String(headerBytes.toByteArray(), Charsets.ISO_8859_1)
        val statusLine = headerText.lineSequence().firstOrNull() ?: return null
        if (!statusLine.contains("200")) {
            Log.e(TAG, "Respuesta DoH no-200: $statusLine")
            return null
        }

        val contentLength = headerText.lineSequence()
            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(":")?.trim()?.toIntOrNull()

        return if (contentLength != null) {
            val body = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(body, read, contentLength - read)
                if (n == -1) break
                read += n
            }
            body
        } else {
            input.readBytes()
        }
    }
}
