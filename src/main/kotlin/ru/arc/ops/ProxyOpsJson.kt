package ru.arc.ops

import com.fasterxml.jackson.databind.ObjectMapper

object ProxyOpsJson {
    private val mapper = ObjectMapper()

    fun ok(vararg pairs: Pair<String, Any?>): String {
        val map = linkedMapOf<String, Any?>("ok" to true)
        pairs.forEach { (k, v) -> map[k] = v }
        return mapper.writeValueAsString(map)
    }

    fun error(message: String): String =
        mapper.writeValueAsString(mapOf("ok" to false, "error" to message))
}

object ProxyOpsAuth {
    fun isAuthorized(
        headers: Map<String, String>,
        expected: String,
    ): Boolean {
        if (expected.isBlank()) return false
        val provided = extractToken(headers) ?: return false
        if (provided.length != expected.length) return false
        var diff = 0
        for (i in provided.indices) {
            diff = diff or (provided[i].code xor expected[i].code)
        }
        return diff == 0
    }

    private fun extractToken(headers: Map<String, String>): String? {
        val auth = headers["Authorization"] ?: headers["authorization"] ?: return null
        val prefix = "Bearer "
        if (!auth.startsWith(prefix, ignoreCase = true)) return null
        return auth.substring(prefix.length).trim()
    }
}
