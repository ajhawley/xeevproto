package com.example.xeevpairing.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XeevPlaylistParserTest {
    @Test
    fun parsesVerifiedCategoryAndChannelFields() {
        val playlist = listOf(
            "#PL\t172349\tplaylist\t1234\t0\t0\thttp://x3m.li\t0123456789ABCDEF\t0123456789ABCDEF",
            "#CAT\t15032989\tUK | ENTERTAINMENT\t0\t0\t0",
            // Verified XEEV #CHAN layout:
            // index 5 = secondary EPG/provider field, 6 = logo, 7 = stream,
            // 8 = archive flag, 9 = catch-up URL, 10 = provider stream ID.
            "#CHAN\t2784342685\t15032989\tUK FHD  BBC ONE - \t0\tBBC1\thttps://logos.example/bbc.png\thttps://strike.example:2096/token/99999\t0\t\t99999\t0\t1794867253",
            "#CHAN\t2784342686\t15032989\tUK HD : BBC 1 - \t0\tBBC1.uk\thttps://logos.example/bbc.png\thttps://strike.example:2096/token/4244\t5\thttps://strike.example:2096/timeshift/token/[d]/[t]/4244.ts\t4244\t0\t1794867253"
        ).joinToString("\n")

        val parsed = XeevPlaylistParser.parse(playlist)

        assertEquals(1, parsed.categories.size)
        assertEquals("UK | ENTERTAINMENT", parsed.categories.single().name)
        assertEquals(2, parsed.channels.size)
        assertEquals("UK FHD  BBC ONE", parsed.channels.first().name)
        assertEquals("https://logos.example/bbc.png", parsed.channels.first().logoUrl)
        assertEquals("https://strike.example:2096/token/99999", parsed.channels.first().playbackUrl)
        assertTrue(parsed.channels.last().catchupUrl?.contains("timeshift") == true)
        assertEquals("99999", parsed.channels.first().providerStreamId)
        assertEquals(1, parsed.recordCounts["#CAT"])
        assertEquals(2, parsed.recordCounts["#CHAN"])
    }

    @Test
    fun ignoresMalformedChannelRecordsAndCountsUnknownRecords() {
        val playlist = listOf(
            "#CAT\t1\tTest Category",
            "#CHAN\tbroken",
            "#UNKNOWN\tanything"
        ).joinToString("\n")

        val parsed = XeevPlaylistParser.parse(playlist)

        assertTrue(parsed.channels.isEmpty())
        assertEquals(1, parsed.ignoredChannelRecords)
        assertEquals(1, parsed.recordCounts["#UNKNOWN"])
    }
}
