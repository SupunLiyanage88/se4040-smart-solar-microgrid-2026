package com.example.smartsolarmicrogridmobile

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

class ApiFailure(val status: Int, message: String) : Exception(message)

data class AccountSession(val server: String, val token: String, val expires: String, val user: JSONObject)

/** Native REST client. Authorization and account rules remain in the central API. */
class AccountApi(private val server: String) {
    init {
        val uri = URI(server)
        require(uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null &&
            (uri.scheme == "https" || (BuildConfig.DEBUG && uri.scheme == "http"))) {
            "Enter a valid HTTPS service address. HTTP is allowed only in debug builds."
        }
    }

    fun request(path: String, method: String = "GET", body: JSONObject? = null, token: String? = null): JSONObject {
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
            val data = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            if (code !in 200..299) {
                val errors = data.optJSONObject("errors")
                val validation = errors?.keys()?.asSequence()?.map { errors.optJSONArray(it)?.join(" ") ?: "" }?.joinToString(" ")
                throw ApiFailure(code, if (code == 401) "Invalid credentials, expired session, or inactive account. Please sign in again."
                    else data.optString("message").ifBlank { validation.orEmpty().ifBlank { "The service could not complete this request ($code)." } })
            }
            return data
        } finally { connection.disconnect() }
    }
}
