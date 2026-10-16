package io.github.johnkuruvila.z50bridge.camera

import org.json.JSONArray
import org.json.JSONObject

internal fun parseJson(text: String): Any? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    return when (trimmed.first()) {
        '{' -> jsonToAny(JSONObject(trimmed))
        '[' -> jsonToAny(JSONArray(trimmed))
        else -> trimmed
    }
}

internal fun unwrap(value: Any?): Any? {
    return when (value) {
        is String -> {
            val trimmed = value.trim()
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                try {
                    unwrap(parseJson(trimmed))
                } catch (_: Exception) {
                    value
                }
            } else {
                value
            }
        }
        is Map<*, *> -> value.entries.associate { it.key to unwrap(it.value) }
        is List<*> -> value.map { unwrap(it) }
        else -> value
    }
}

private fun jsonToAny(value: Any?): Any? {
    return when (value) {
        JSONObject.NULL, null -> null
        is JSONObject -> {
            val out = linkedMapOf<String, Any?>()
            val keys = value.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                out[key] = jsonToAny(value.get(key))
            }
            out
        }
        is JSONArray -> (0 until value.length()).map { jsonToAny(value.get(it)) }
        else -> value
    }
}

@Suppress("UNCHECKED_CAST")
internal fun asMap(value: Any?): Map<String, Any?> {
    return (value as? Map<*, *>)?.mapKeys { it.key.toString() }?.mapValues { it.value } ?: emptyMap()
}

internal fun asFileList(data: Any?, key: String = "file"): List<Map<String, Any?>> {
    val map = asMap(data)
    val raw = map[key] ?: map["event"] ?: emptyList<Any?>()
    val list = when (raw) {
        is List<*> -> raw
        is Map<*, *> -> listOf(raw)
        else -> emptyList()
    }
    return list.mapNotNull { item ->
        (item as? Map<*, *>)?.mapKeys { it.key.toString() }?.mapValues { it.value }
    }
}

internal fun intOrNull(value: Any?): Long? {
    return when (value) {
        null, "" -> null
        is Number -> value.toLong()
        else -> value.toString().toLongOrNull()
    }
}

internal fun kbToBytes(sizeKb: Long?): Long? {
    if (sizeKb == null || sizeKb == 0L) return null
    return sizeKb * 1024
}
