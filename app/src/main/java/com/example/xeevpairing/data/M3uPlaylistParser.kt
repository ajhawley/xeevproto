package com.example.xeevpairing.data

import java.net.URI

/** Parser for extended M3U playlists and common tvg-* attributes. */
object M3uPlaylistParser {
    private val attributePattern = Regex(
        """([A-Za-z0-9_-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s,]+))"""
    )

    fun parse(content: String, playlistUrl: String? = null): ParsedPlaylist {
        val categories = LinkedHashMap<String, PlaylistCategory>()
        val channels = ArrayList<TvChannel>()
        val counts = linkedMapOf("#EXTINF" to 0)
        var epgUrl: String? = null
        var pending: PendingEntry? = null
        var ignored = 0

        content.lineSequence().forEach { raw ->
            val line = raw.removePrefix("\uFEFF").trim()
            if (line.isEmpty()) return@forEach
            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    val attrs = parseAttributes(line.substring(7))
                    epgUrl = firstNonBlank(attrs["url-tvg"], attrs["x-tvg-url"])
                        ?.let { resolveUrl(it, playlistUrl) }?.takeIf(::isHttpUrl)
                }
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    if (pending != null) ignored++
                    counts["#EXTINF"] = (counts["#EXTINF"] ?: 0) + 1
                    pending = parseEntry(line.substringAfter(':', ""))
                }
                line.startsWith("#") -> Unit // VLC options and other tags are metadata, not stream URLs.
                pending != null -> {
                    val entry = pending!!
                    val streamUrl = resolveUrl(line, playlistUrl)
                    if (isHttpUrl(streamUrl)) {
                        val attrs = entry.attributes
                        val categoryName = firstNonBlank(attrs["group-title"], "Uncategorised")!!
                        val channelName = firstNonBlank(attrs["tvg-name"], entry.displayName, "Unknown channel")!!
                        val channelNumber = attrs["tvg-chno"]?.trim()?.takeIf { it.isNotEmpty() }
                        val epgId = attrs["tvg-id"]?.trim()?.takeIf { it.isNotEmpty() }
                        val recordId = "m3u-${channels.size + 1}"
                        categories.putIfAbsent(categoryName, PlaylistCategory(categoryName, categoryName))
                        channels += TvChannel(
                            recordId = recordId,
                            categoryId = categoryName,
                            name = channelName.trim().removeSuffix(" -").trim(),
                            epgChannelId = epgId,
                            logoUrl = attrs["tvg-logo"]?.trim()?.let { resolveUrl(it, playlistUrl) }?.takeIf(::isHttpUrl),
                            playbackUrl = streamUrl,
                            archiveFlag = attrs["catchup"]?.trim()?.takeIf { it.isNotEmpty() },
                            catchupUrl = attrs["catchup-source"]?.trim()?.let { resolveUrl(it, playlistUrl) }
                                ?.takeIf(::isHttpUrl),
                            providerStreamId = null,
                            channelNumber = channelNumber
                        )
                    } else {
                        ignored++
                    }
                    pending = null
                }
            }
        }
        if (pending != null) ignored++

        return ParsedPlaylist(
            categories = categories.values.toList(),
            channels = channels,
            recordCounts = counts,
            ignoredChannelRecords = ignored,
            epgUrl = epgUrl
        )
    }

    private data class PendingEntry(val displayName: String, val attributes: Map<String, String>)

    private fun parseEntry(value: String): PendingEntry {
        val comma = findUnquotedComma(value)
        val metadata = if (comma >= 0) value.substring(0, comma) else value
        val displayName = if (comma >= 0) value.substring(comma + 1).trim() else ""
        return PendingEntry(displayName, parseAttributes(metadata))
    }

    private fun findUnquotedComma(value: String): Int {
        var quote: Char? = null
        value.forEachIndexed { index, char ->
            if (quote == null && (char == '\'' || char == '"')) quote = char
            else if (quote == char) quote = null
            else if (char == ',' && quote == null) return index
        }
        return -1
    }

    private fun parseAttributes(value: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        attributePattern.findAll(value).forEach { match ->
            val key = match.groupValues[1].lowercase()
            val attrValue = sequenceOf(match.groupValues[2], match.groupValues[3], match.groupValues[4])
                .firstOrNull { it.isNotEmpty() }.orEmpty()
            result[key] = attrValue
        }
        return result
    }

    private fun resolveUrl(value: String, baseUrl: String?): String {
        val candidate = value.trim()
        if (isHttpUrl(candidate) || baseUrl.isNullOrBlank()) return candidate
        return runCatching { URI(baseUrl).resolve(candidate).toString() }.getOrDefault(candidate)
    }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }?.trim()

    private fun isHttpUrl(value: String): Boolean =
        value.startsWith("https://", ignoreCase = true) || value.startsWith("http://", ignoreCase = true)
}
