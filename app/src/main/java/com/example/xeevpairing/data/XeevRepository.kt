package com.example.xeevpairing.data

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Client for the legacy XEEV pairing flow verified against an authorised test account.
 * The playlist endpoint returns private channel URLs; callers must never log or export them.
 */
class XeevRepository(
    private val http: OkHttpClient = defaultClient()
) {
    fun register(deviceId: String): RegistrationResult {
        val body = FormBody.Builder()
            .add("deviceid", deviceId)
            .build()
        val request = Request.Builder()
            .url("$XEEV_BASE/apk/get/newappid")
            .post(body)
            .header("Accept", "application/json")
            .build()
        val json = executeJson(request, "XEEV registration")
        val result = json.getAsJsonObject("xresult")
            ?: throw IOException("XEEV registration response did not contain xresult")
        val appCode = result.stringValue("appid")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: throw IOException("XEEV registration response did not contain an app code")
        return RegistrationResult(
            appCode = appCode,
            qrImageUrl = result.stringValue("qrcode")?.takeIf { it.startsWith("https://") },
            server = result.stringValue("server")
        )
    }

    /** Returns the paired 32-character playlist ID, or null while not paired. */
    fun checkPairing(appCode: String, deviceId: String, appVersion: String = XEEV_APP_VERSION): String? {
        val body = FormBody.Builder()
            .add("version", appVersion)
            .add("deviceid", deviceId)
            .add("wakeup", "0")
            .build()
        val request = Request.Builder()
            .url("$XEEV_BASE/apk/get/settings/${appCode.trim()}")
            .post(body)
            .header("Accept", "application/json")
            .build()
        val json = executeJson(request, "XEEV pairing check")
        val result = json.getAsJsonObject("xresult") ?: return null
        val lineId = result.stringValue("lineid")?.trim() ?: return null
        return lineId.takeIf(::isValidPlaylistId)
    }

    /** Downloads the XEEV text playlist containing the real playback links. */
    fun downloadPlaylist(playlistId: String): String {
        require(isValidPlaylistId(playlistId)) { "Playlist ID is not valid" }
        val url = XEEV_BASE.toHttpUrl().newBuilder()
            .addPathSegment("load")
            .addPathSegment("apk")
            .addPathSegment(playlistId)
            .build()
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "text/plain, */*")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Playlist download failed (HTTP ${response.code})")
            }
            val text = response.body?.string()
                ?: throw IOException("XEEV returned an empty playlist")
            val normalizedStart = text.trimStart('\uFEFF', '\r', '\n', ' ')
            if (!normalizedStart.startsWith("#PL") && !normalizedStart.contains("\n#CAT") && !normalizedStart.contains("\n#CHAN")) {
                throw IOException("The XEEV response does not look like a supported playlist")
            }
            return text
        }
    }

    private fun executeJson(request: Request, operation: String): JsonObject {
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("$operation failed (HTTP ${response.code})")
            }
            val text = response.body?.string()
                ?: throw IOException("$operation returned an empty response")
            val root = try {
                JsonParser.parseString(text).asJsonObject
            } catch (_: Exception) {
                throw IOException("$operation returned invalid JSON")
            }
            return root
        }
    }

    private fun JsonObject.stringValue(name: String): String? {
        val value = get(name) ?: return null
        if (value is JsonNull || value.isJsonNull) return null
        return runCatching {
            if (value.isJsonPrimitive) value.asString else value.toString()
        }.getOrNull()
    }

    companion object {
        const val XEEV_BASE = "https://xeev.app"
        const val XEEV_APP_VERSION = "2.6.0"

        fun isValidPlaylistId(value: String): Boolean =
            value.matches(Regex("^[A-Fa-f0-9]{32}$"))

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}

data class RegistrationResult(
    val appCode: String,
    val qrImageUrl: String?,
    val server: String?
)
