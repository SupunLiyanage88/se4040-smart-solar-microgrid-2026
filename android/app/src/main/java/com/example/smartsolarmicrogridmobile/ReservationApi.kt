package com.example.smartsolarmicrogridmobile

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Native REST client for reservations. Self-contained: mirrors AccountApi's HttpURLConnection pattern. */
class ReservationApi(private val server: String) {
    private fun raw(path: String, method: String = "GET", body: JSONObject? = null, token: String? = null): Pair<Int, String> {
        val connection = URL(server.trimEnd('/') + "/api" + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
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

    private fun requestObject(path: String, method: String = "GET", body: JSONObject? = null, token: String? = null): JSONObject {
        val (code, text) = raw(path, method, body, token)
        if (code !in 200..299) throw failure(code, text)
        return runCatching { JSONObject(text) }.getOrDefault(JSONObject())
    }

    fun create(body: JSONObject, token: String): JSONObject =
        requestObject("/reservations", "POST", body, token)

    fun list(view: String, search: String = "", token: String): JSONArray {
        val query = "?view=" + URLEncoder.encode(view, "UTF-8") +
            (if (search.isNotBlank()) "&search=" + URLEncoder.encode(search, "UTF-8") else "")
        val (code, text) = raw("/reservations$query", token = token)
        if (code !in 200..299) throw failure(code, text)
        return runCatching { JSONArray(text) }.getOrDefault(JSONArray())
    }

    fun summary(token: String): JSONObject = requestObject("/reservations/summary", token = token)

    fun get(id: String, token: String): JSONObject = requestObject("/reservations/$id", token = token)

    fun update(id: String, body: JSONObject, token: String): JSONObject =
        requestObject("/reservations/$id", "PUT", body, token)

    fun cancel(id: String, token: String): JSONObject =
        requestObject("/reservations/$id/cancel", "POST", token = token)

    fun decide(id: String, decision: String, token: String): JSONObject =
        requestObject("/reservations/$id/decision", "POST", JSONObject().put("decision", decision), token)

    fun complete(qrToken: String, token: String): JSONObject =
        requestObject("/reservations/complete", "POST", JSONObject().put("qrToken", qrToken), token)
}
