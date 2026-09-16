package com.example.dpi_volar

import java.util.concurrent.ConcurrentHashMap

object DnsCache {

    private data class Entry(val response: ByteArray, val expiresAt: Long)

    private const val CACHE_TTL_MS = 30_000L

    private val cache = ConcurrentHashMap<String, Entry>()

    private fun keyFor(query: ByteArray): String {
        if (query.size < 2) return query.joinToString(",")
        return query.copyOfRange(2, query.size).joinToString(",")
    }

    fun get(query: ByteArray): ByteArray? {
        val key = keyFor(query)
        val entry = cache[key] ?: return null
        if (System.currentTimeMillis() > entry.expiresAt) {
            cache.remove(key)
            return null
        }
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
