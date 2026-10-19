package me.prince.launcher

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

fun httpGet(url: String, headers: Map<String, String> = emptyMap()): String =
    String(httpBytes(url, headers))

fun httpBytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 5_000
    connection.readTimeout = 10_000
    headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
    return try {
        connection.inputStream.use { it.readBytes() }
    } finally {
        connection.disconnect()
    }
}

fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }
