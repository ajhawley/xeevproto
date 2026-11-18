package com.example.xeevpairing.data

/** Parsed, safe-to-display subset of a playlist. */
data class PlaylistCategory(
    val id: String,
    val name: String
)

data class TvChannel(
    val recordId: String,
    val categoryId: String,
    val name: String,
    val epgChannelId: String?,
    val logoUrl: String?,
    val playbackUrl: String,
    val archiveFlag: String?,
    val catchupUrl: String?,
    val providerStreamId: String?,
    val channelNumber: String? = null
)

data class ParsedPlaylist(
    val categories: List<PlaylistCategory>,
    val channels: List<TvChannel>,
    val recordCounts: Map<String, Int>,
    val ignoredChannelRecords: Int,
    val epgUrl: String? = null
)

/**
 * XEEV #CHAN zero-based indexes, verified against an actual playlist:
 * 0 record type, 1 record id, 2 category id, 3 name, 4 EPG id,
 * 5 secondary/provider EPG field, 6 logo URL, 7 playback URL,
 * 8 archive flag, 9 catch-up URL, 10 provider stream id.
 */
object XeevPlaylistParser {
    fun parse(content: String): ParsedPlaylist {
        val categories = LinkedHashMap<String, PlaylistCategory>()
        val channels = ArrayList<TvChannel>()
        val counts = LinkedHashMap<String, Int>()
        var ignoredChannels = 0

        content.lineSequence().forEach { rawLine ->
            val line = rawLine.removePrefix("\uFEFF").trimEnd('\r')
            if (line.isBlank() || !line.startsWith('#')) return@forEach
            val fields = line.split('\t')
            val recordType = fields.firstOrNull()?.trim().orEmpty()
            if (recordType.isBlank()) return@forEach
            counts[recordType] = (counts[recordType] ?: 0) + 1

            when (recordType) {
                "#CAT" -> {
                    val id = fields.getOrNull(1)?.trim().orEmpty()
                    val name = fields.getOrNull(2)?.trim().orEmpty()
                    if (id.isNotEmpty() && name.isNotEmpty()) {
                        categories[id] = PlaylistCategory(id, name)
                    }
                }
                "#CHAN" -> {
                    val playback = fields.getOrNull(7)?.trim().orEmpty()
                    val name = fields.getOrNull(3)?.trim().orEmpty()
                    val categoryId = fields.getOrNull(2)?.trim().orEmpty()
                    if (fields.size < 8 || name.isEmpty() || categoryId.isEmpty() || !isHttpUrl(playback)) {
                        ignoredChannels++
                    } else {
                        channels += TvChannel(
                            recordId = fields.getOrNull(1)?.trim().orEmpty(),
                            categoryId = categoryId,
                            name = name.removeSuffix(" -").trim(),
                            epgChannelId = fields.getOrNull(4)?.trim()?.takeIf { it.isNotEmpty() },
                            logoUrl = fields.getOrNull(6)?.trim()?.takeIf(::isHttpUrl),
                            playbackUrl = playback,
                            archiveFlag = fields.getOrNull(8)?.trim()?.takeIf { it.isNotEmpty() },
                            catchupUrl = fields.getOrNull(9)?.trim()?.takeIf(::isHttpUrl),
                            providerStreamId = fields.getOrNull(10)?.trim()?.takeIf { it.isNotEmpty() }
                        )
                    }
                }
            }
        }

        return ParsedPlaylist(
            categories = categories.values.toList(),
            channels = channels,
            recordCounts = counts,
            ignoredChannelRecords = ignoredChannels
        )
    }

    private fun isHttpUrl(value: String): Boolean =
        value.startsWith("https://", ignoreCase = true) || value.startsWith("http://", ignoreCase = true)
}
