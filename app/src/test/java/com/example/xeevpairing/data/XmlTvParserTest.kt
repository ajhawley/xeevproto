package com.example.xeevpairing.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XmlTvParserTest {
    @Test
    fun parsesCurrentAndNextXmlTvProgrammes() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE tv SYSTEM "xmltv.dtd">
            <tv>
              <channel id="BBC1"><display-name>BBC One</display-name></channel>
              <programme start="20261118190000 +0000" stop="20261118193000 +0000" channel="BBC1">
                <title lang="en">Evening News</title>
                <desc>Latest headlines</desc>
              </programme>
              <programme start="20261118193000 +0000" stop="20261118200000 +0000" channel="BBC1">
                <title>Regional News</title>
              </programme>
            </tv>
        """.trimIndent()

        val parsed = XmlTvParser.parse(xml)

        assertEquals(2, parsed.size)
        assertEquals("BBC1", parsed.first().channelId)
        assertEquals("Evening News", parsed.first().title)
        assertEquals("Latest headlines", parsed.first().description)
        assertTrue(parsed.last().startTimeMillis > parsed.first().startTimeMillis)
    }

    @Test
    fun ignoresProgrammeWithInvalidTimes() {
        val xml = """
            <tv>
              <programme start="bad" stop="also-bad" channel="BBC1">
                <title>Broken times</title>
              </programme>
            </tv>
        """.trimIndent()
        assertTrue(XmlTvParser.parse(xml).isEmpty())
    }
}
