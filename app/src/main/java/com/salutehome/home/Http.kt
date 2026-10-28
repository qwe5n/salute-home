package com.salutehome.home

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/** An HTTP answer other than 200; [code] 429 means "slow down". */
class HttpStatusException(val code: Int, url: String) : IOException("HTTP $code from ${url.substringBefore('?')}")

/**
 * Plain GETs with short timeouts and size caps, for the weather, the shelf and its pictures. Blocking:
 * always called off the main thread. Android's HttpURLConnection follows redirects and unzips gzip itself.
 */
open class Http(
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 12_000,
) {
    /** The body as text (JSON), at most [maxBytes]. */
    open fun getString(url: String, headers: Map<String, String> = emptyMap(), maxBytes: Int = 2 shl 20): String {
        val out = ByteArrayOutputStream()
        get(url, headers, maxBytes, out)
        return out.toString("UTF-8")
    }

    /** The body into [file] (a picture), at most [maxBytes]; the file is removed if it fails. */
    open fun download(url: String, file: File, maxBytes: Int = 8 shl 20) {
        try {
            FileOutputStream(file).use { get(url, emptyMap(), maxBytes, it) }
        } catch (e: IOException) {
            file.delete()
            throw e
        }
    }

    /** POSTs [json] and returns the answer as text; an error answer's body is in the exception's message. */
    open fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap(), maxBytes: Int = 4 shl 20): String {
        val out = ByteArrayOutputStream()
        get(url, headers + ("Content-Type" to "application/json"), maxBytes, out, json.toByteArray())
        return out.toString("UTF-8")
    }

    private fun get(url: String, headers: Map<String, String>, maxBytes: Int, out: OutputStream, body: ByteArray? = null) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", "SaluteHome/1.0 (Android TV home screen)")
            for ((k, v) in headers) connection.setRequestProperty(k, v)
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK && body != null) {
                val text = connection.errorStream?.use { it.readBytes().toString(Charsets.UTF_8).take(400) } ?: ""
                throw HttpStatusException(code, "$url $text")
            }
            if (code != HttpURLConnection.HTTP_OK) throw HttpStatusException(code, url)
            if (connection.contentLengthLong > maxBytes) throw IOException("Too big: ${connection.contentLengthLong} bytes")
            connection.inputStream.use { copyCapped(it, out, maxBytes) }
        } finally {
            connection.disconnect()
        }
    }

    private fun copyCapped(input: InputStream, out: OutputStream, maxBytes: Int) {
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > maxBytes) throw IOException("Too big: over $maxBytes bytes")
            out.write(buffer, 0, n)
        }
    }
}
