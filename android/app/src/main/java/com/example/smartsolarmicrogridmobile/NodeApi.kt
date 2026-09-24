package com.example.smartsolarmicrogridmobile

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Native REST client for grid hubs. Self-contained: mirrors AccountApi's HttpURLConnection pattern. */
class NodeApi(private val server: String) {
    private fun raw(path: String, method: String = "GET", token: String? = null): Pair<Int, String> {
        val connection = URL(server.trimEnd('/') + "/api" + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return code to text
        } finally { connection.disconnect() }
    }

    private fun failure(code: Int, text: String): ApiFailure {
        val data = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
        val errors = data.optJSONObject("errors")
        val validation = errors?.keys()?.asSequence()?.map { errors.optJSONArray(it)?.join(" ") ?: "" }?.joinToString(" ")
        return ApiFailure(code, if (code == 401) "Invalid credentials, expired session, or inactive account. Please sign in again."
            else data.optString("message").ifBlank { validation.orEmpty().ifBlank { "The service could not complete this request ($code)." } })
    }

    /** Active nodes available to reserve against. */
    fun list(token: String): JSONArray {
        val (code, text) = raw("/nodes", token = token)
        if (code !in 200..299) throw failure(code, text)
        return runCatching { JSONArray(text) }.getOrDefault(JSONArray())
    }

    /** Active nodes within [radiusKm] of a point, sorted nearest first (server-side filtering). */
    fun nearby(latitude: Double, longitude: Double, radiusKm: Double, token: String): JSONArray {
        val query = "?latitude=$latitude&longitude=$longitude&radiusKm=$radiusKm"
        val (code, text) = raw("/nodes/nearby$query", token = token)
        if (code !in 200..299) throw failure(code, text)
        return runCatching { JSONArray(text) }.getOrDefault(JSONArray())
    }

    fun get(id: String, token: String): JSONObject {
        val (code, text) = raw("/nodes/$id", token = token)
        if (code !in 200..299) throw failure(code, text)
        return runCatching { JSONObject(text) }.getOrDefault(JSONObject())
    }
}
