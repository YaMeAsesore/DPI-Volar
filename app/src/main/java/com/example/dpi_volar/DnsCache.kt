package com.example.dpi_volar

import java.util.concurrent.ConcurrentHashMap

/**
 * Cache simple de respuestas DNS en memoria, para evitar repetir
 * consultas idénticas en un corto periodo de tiempo (típico al cargar
 * una página con muchos recursos del mismo dominio/CDN).
 *
 * La clave se construye a partir del cuerpo de la consulta DNS
 * (omitiendo el transaction ID de 2 bytes, que cambia en cada intento),
 * así "mismo dominio + mismo tipo de registro" siempre mapea a la
 * misma entrada de cache.
 */
object DnsCache {

    private data class Entry(val response: ByteArray, val expiresAt: Long)

    // TTL corto y fijo (no parseamos el TTL real del registro DNS, por simplicidad).
    // Suficiente para cubrir ráfagas de carga de una misma página.
    private const val CACHE_TTL_MS = 30_000L

    private val cache = ConcurrentHashMap<String, Entry>()

    private fun keyFor(query: ByteArray): String {
        // Ignora los primeros 2 bytes (transaction ID); el resto (flags,
        // preguntas, nombre de dominio, tipo) sí identifica la consulta.
        if (query.size < 2) return query.joinToString(",")
        return query.copyOfRange(2, query.size).joinToString(",")
    }

    /** Devuelve una respuesta cacheada (ya con el transaction ID correcto) o null si no hay/expiró. */
    fun get(query: ByteArray): ByteArray? {
        val key = keyFor(query)
        val entry = cache[key] ?: return null
        if (System.currentTimeMillis() > entry.expiresAt) {
            cache.remove(key)
            return null
        }
        // Copiamos la respuesta y le ponemos el transaction ID de ESTA consulta,
        // porque el cliente valida que coincida con el que mandó.
        val response = entry.response.copyOf()
        if (response.size >= 2 && query.size >= 2) {
            response[0] = query[0]
            response[1] = query[1]
        }
        return response
    }

    fun put(query: ByteArray, response: ByteArray) {
        val key = keyFor(query)
        cache[key] = Entry(response.copyOf(), System.currentTimeMillis() + CACHE_TTL_MS)
    }

    fun clear() {
        cache.clear()
    }
}