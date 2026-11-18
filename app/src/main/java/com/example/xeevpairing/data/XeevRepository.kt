package com.example.xeevpairing.data

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/** Client for XEEV pairing and the XEEV/M3U/XMLTV download endpoints. */
class XeevRepository(private val http: OkHttpClient = defaultClient()) {
    fun register(deviceId: String): RegistrationResult {
        val body = FormBody.Builder().add("deviceid", deviceId).build()
        val request = Request.Builder().url("$XEEV_BASE/apk/get/newappid").post(body)
            .header("Accept", "application/json").build()
        val result = executeJson(request, "XEEV registration").getAsJsonObject("xresult")
            ?: throw IOException("XEEV registration response did not contain xresult")
        val appCode = result.stringValue("appid")?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IOException("XEEV registration response did not contain an app code")
        return RegistrationResult(
            appCode = appCode,
            qrImageUrl = result.stringValue("qrcode")?.takeIf { it.startsWith("https://") },
            server = result.stringValue("server")
        )
    }

    /** Returns the paired 32-character playlist ID, or null while not paired. */
    fun checkPairing(appCode: String, deviceId: String, appVersion: String = XEEV_APP_VERSION): String? {
        val body = FormBody.Builder().add("version", appVersion).add("deviceid", deviceId).add("wakeup", "0").build()
        val request = Request.Builder().url("$XEEV_BASE/apk/get/settings/${appCode.trim()}")
            .post(body).header("Accept", "application/json").build()
        val json = executeJson(request, "XEEV pairing check")
        val element = json.get("xresult") ?: throw IOException("XEEV response has no xresult field")
        if (!element.isJsonObject) throw IOException("XEEV response xresult is not an object")
        val lineId = element.asJsonObject.stringValue("lineid")?.trim()
            ?: throw IOException("XEEV response has no lineid field")
        if (lineId.isBlank() || lineId == "0") return null
        if (!isValidPlaylistId(lineId)) throw IOException("XEEV returned an unexpected playlist ID format (length=${lineId.length})")
        return lineId
    }

    /** Existing XEEV custom playlist endpoint. */
    fun downloadPlaylist(playlistId: String): String {
        require(isValidPlaylistId(playlistId)) { "Playlist ID is not valid" }
        val url = XEEV_BASE.toHttpUrl().newBuilder().addPathSegment("load").addPathSegment("apk")
            .addPathSegment(playlistId).build()
        val text = executeText(url.toString(), "XEEV playlist download", MAX_PLAYLIST_BYTES)
        val start = text.trimStart('\uFEFF', '\r', '\n', ' ')
        if (!start.startsWith("#PL") && !start.contains("\n#CAT") && !start.contains("\n#CHAN")) {
            throw IOException("The XEEV response does not look like a supported playlist")
        }
        return text
    }

    /** Canonical M3U URL, also used as the base for relative M3U stream URLs. */
    fun m3uPlaylistUrl(playlistId: String): String {
        require(isValidPlaylistId(playlistId)) { "Playlist ID is not valid" }
        return M3U_BASE.toHttpUrl().newBuilder().addPathSegment("load").addPathSegment("m3u")
            .addPathSegment(playlistId).build().toString()
    }

    /** Standard M3U endpoint using the same ID returned by XEEV pairing. */
    fun downloadM3uPlaylist(playlistId: String): String {
        val url = m3uPlaylistUrl(playlistId)
        val text = executeText(url, "M3U playlist download", MAX_PLAYLIST_BYTES)
        val start = text.trimStart('\uFEFF', '\r', '\n', ' ', '\t')
        if (!start.startsWith("#EXTM3U", ignoreCase = true) && !text.contains("#EXTINF:")) {
            throw IOException("The server response does not look like an M3U playlist")
        }
        return text
    }

    /** Downloads the XMLTV guide referenced by url-tvg / x-tvg-url in an M3U header. */
    fun downloadEpg(epgUrl: String): String {
        val parsed = try { epgUrl.trim().toHttpUrl() }
        catch (_: Exception) { throw IOException("The playlist contains an invalid EPG URL") }
        if (parsed.scheme != "https" && parsed.scheme != "http") throw IOException("The EPG URL must use HTTP or HTTPS")
        val request = Request.Builder().url(parsed).get().header("Accept", "application/xml, text/xml, */*").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("EPG download failed (HTTP ${response.code})")
            val body = response.body ?: throw IOException("The EPG server returned an empty response")
            if (body.contentLength() > MAX_EPG_COMPRESSED_BYTES) throw IOException("The EPG download is too large")
            val bytes = body.byteStream().use { readLimited(it, MAX_EPG_COMPRESSED_BYTES) }
            val decoded = if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
                GZIPInputStream(ByteArrayInputStream(bytes)).use { readLimited(it, MAX_EPG_UNCOMPRESSED_BYTES) }
            } else bytes
            val text = decoded.toString(Charsets.UTF_8)
            if (!text.removePrefix("\uFEFF").take(4096).contains("<tv", ignoreCase = true)) {
                throw IOException("The EPG response is not an XMLTV document")
            }
            return text
        }
    }

    private fun executeText(url: String, operation: String, maxBytes: Long): String {
        val request = Request.Builder().url(url).get()
            .header("Accept", "text/plain, application/vnd.apple.mpegurl, */*").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("$operation failed (HTTP ${response.code})")
            val body = response.body ?: throw IOException("$operation returned an empty response")
            if (body.contentLength() > maxBytes) throw IOException("$operation is too large")
            return body.byteStream().use { readLimited(it, maxBytes) }.toString(Charsets.UTF_8)
        }
    }

    private fun readLimited(input: java.io.InputStream, maxBytes: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) throw IOException("Download exceeded the allowed size")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun executeJson(request: Request, operation: String): JsonObject {
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("$operation failed (HTTP ${response.code})")
            val text = response.body?.string() ?: throw IOException("$operation returned an empty response")
            return try { JsonParser.parseString(text).asJsonObject }
            catch (_: Exception) { throw IOException("$operation returned invalid JSON") }
        }
    }

    private fun JsonObject.stringValue(name: String): String? {
        val value = get(name) ?: return null
        if (value is JsonNull || value.isJsonNull) return null
        return runCatching { if (value.isJsonPrimitive) value.asString else value.toString() }.getOrNull()
    }

    companion object {
        const val XEEV_BASE = "https://xeev.app"
        const val M3U_BASE = "https://xtream-editor.com"
        const val XEEV_APP_VERSION = "2.6.0"
        private const val MAX_PLAYLIST_BYTES = 80L * 1024 * 1024
        private const val MAX_EPG_COMPRESSED_BYTES = 60L * 1024 * 1024
        private const val MAX_EPG_UNCOMPRESSED_BYTES = 150L * 1024 * 1024

        fun isValidPlaylistId(value: String): Boolean = value.matches(Regex("^[A-Za-z0-9]{32}$"))

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}

data class RegistrationResult(val appCode: String, val qrImageUrl: String?, val server: String?)
