package com.example.xeevpairing.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uPlaylistParserTest {
    @Test
    fun parsesChannelsCategoriesLogosStreamsAndEpgUrl() {
        val m3u = """
            #EXTM3U url-tvg="https://guide.example/load/epg/0123456789ABCDEFGHIJKLMN12345678"
            #EXTINF:-1 tvg-chno="144" tvg-id="BBC1" tvg-name="BBC ONE HD" tvg-logo="https://logos.example/bbc.png" group-title="UK | ENTERTAINMENT",BBC ONE
            https://stream.example/live/token/516877
            #EXTINF:-1 tvg-chno="145" tvg-id="BBC2" tvg-name="BBC TWO" tvg-logo="http://logos.example/bbc2.png" group-title="UK | ENTERTAINMENT",BBC TWO
            https://stream.example/live/token/516878
            #EXTINF:-1 tvg-id="NEWS1" group-title="NEWS",News Channel
            https://stream.example/live/token/77
        """.trimIndent()

        val parsed = M3uPlaylistParser.parse(m3u)

        assertEquals(3, parsed.channels.size)
        assertEquals(2, parsed.categories.size)
        assertEquals("144", parsed.channels[0].channelNumber)
        assertEquals("BBC1", parsed.channels[0].epgChannelId)
        assertEquals("https://logos.example/bbc.png", parsed.channels[0].logoUrl)
        assertEquals("https://stream.example/live/token/516877", parsed.channels[0].playbackUrl)
        assertEquals("UK | ENTERTAINMENT", parsed.categories.first().name)
        assertNotNull(parsed.epgUrl)
        assertTrue(parsed.epgUrl!!.contains("/load/epg/"))
    }

    @Test
    fun supportsSingleQuotedAttributesAndIgnoresBadEntries() {
        val m3u = """
            #EXTM3U x-tvg-url='https://guide.example/epg.xml'
            #EXTINF:-1 tvg-id='SAMPLE' group-title='Test Group' tvg-logo='not-a-url',Sample Channel
            https://stream.example/sample.ts
            #EXTINF:-1 group-title='Broken',Broken Channel
            not a stream url
        """.trimIndent()

        val parsed = M3uPlaylistParser.parse(m3u)

        assertEquals(1, parsed.channels.size)
        assertEquals("Sample Channel", parsed.channels.single().name)
        assertEquals("Test Group", parsed.channels.single().categoryId)
        assertEquals(1, parsed.ignoredChannelRecords)
        assertEquals("https://guide.example/epg.xml", parsed.epgUrl)
    }
}
