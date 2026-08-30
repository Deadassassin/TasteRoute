package space.gexemy.tasteroute.data.network

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** OSM's tile/API terms require a User-Agent that identifies the app. */
const val USER_AGENT = "TasteRoute/1.0 (Android; space.gexemy.tasteroute)"

/**
 * Timeouts are short on purpose. Every one of these is spent in front of a user waiting for
 * restaurants, and a host that hasn't answered in a few seconds is not about to. The old 15s
 * connect / 30s read pair meant one unreachable service froze search for most of a minute.
 */
const val CONNECT_TIMEOUT_MS = 5_000
const val READ_TIMEOUT_MS = 10_000

class HttpException(val code: Int, message: String) : IOException(message)

/**
 * Accept defaults to "* / *" on purpose: overpass-api.de content-negotiates and answers
 * "Accept: application/json" with a 406, even though it then serves JSON happily.
 */
internal fun httpGet(
    url: String,
    headers: Map<String, String> = emptyMap(),
    accept: String = "*/*",
    readTimeoutMs: Int = READ_TIMEOUT_MS,
    connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
): String = httpSend("GET", url, null, null, headers, accept, readTimeoutMs, connectTimeoutMs)

internal fun httpPost(
    url: String,
    body: String,
    contentType: String,
    headers: Map<String, String> = emptyMap(),
    accept: String = "*/*",
    readTimeoutMs: Int = READ_TIMEOUT_MS,
    connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
): String = httpSend("POST", url, body, contentType, headers, accept, readTimeoutMs, connectTimeoutMs)

/** Photo uploads are raw image bytes, not a JSON envelope — no multipart, no base64 inflation. */
internal fun httpSendBytes(
    method: String,
    url: String,
    body: ByteArray,
    contentType: String,
    headers: Map<String, String> = emptyMap(),
    readTimeoutMs: Int = 30_000,
): String = send(method, url, body, contentType, headers, "application/json", readTimeoutMs, CONNECT_TIMEOUT_MS)

internal fun httpSend(
    method: String,
    url: String,
    body: String?,
    contentType: String?,
    headers: Map<String, String> = emptyMap(),
    accept: String = "*/*",
    readTimeoutMs: Int = READ_TIMEOUT_MS,
    connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
): String = send(method, url, body?.toByteArray(Charsets.UTF_8), contentType, headers, accept, readTimeoutMs, connectTimeoutMs)

/**
 * Server-sent events, delivered line by line on the calling thread.
 *
 * A chat reply arrives as a hundred small chunks over several seconds. Buffering all of them and
 * printing the finished paragraph wastes the one part of the wait that could have been interesting
 * to watch, so this hands each line straight to the caller. Blocking, like everything else here —
 * call it inside Dispatchers.IO.
 */
internal fun httpPostStream(
    url: String,
    body: String,
    contentType: String,
    headers: Map<String, String> = emptyMap(),
    readTimeoutMs: Int = READ_TIMEOUT_MS,
    connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    onLine: (String) -> Unit,
) {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = connectTimeoutMs
        readTimeout = readTimeoutMs
        setRequestProperty("Accept", "text/event-stream")
        setRequestProperty("User-Agent", USER_AGENT)
        setRequestProperty("Content-Type", contentType)
        headers.forEach { (k, v) -> setRequestProperty(k, v) }
        doOutput = true
    }
    try {
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        if (code !in 200..299) {
            val text = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            throw HttpException(code, "HTTP $code: ${text.take(200)}")
        }
        conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader -> reader.forEachLine(onLine) }
    } finally {
        conn.disconnect()
    }
}

private fun send(
    method: String,
    url: String,
    body: ByteArray?,
    contentType: String?,
    headers: Map<String, String>,
    accept: String,
    readTimeoutMs: Int,
    connectTimeoutMs: Int,
): String {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = connectTimeoutMs
        readTimeout = readTimeoutMs
        setRequestProperty("Accept", accept)
        setRequestProperty("User-Agent", USER_AGENT)
        contentType?.let { setRequestProperty("Content-Type", it) }
        headers.forEach { (k, v) -> setRequestProperty(k, v) }
        if (body != null) doOutput = true
    }
    try {
        body?.let { payload -> conn.outputStream.use { it.write(payload) } }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (code !in 200..299) throw HttpException(code, "HTTP $code: ${text.take(200)}")
        return text
    } finally {
        conn.disconnect()
    }
}
