package com.example.xeevpairing.data

import org.xml.sax.Attributes
import org.xml.sax.EntityResolver
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.SimpleTimeZone
import java.util.TimeZone
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory

data class EpgProgramme(
    val channelId: String,
    val startTimeMillis: Long,
    val stopTimeMillis: Long,
    val title: String,
    val description: String?
)

private data class ProgrammeBuilder(
    val channel: String,
    val start: Long,
    val stop: Long,
    var title: String = "",
    var description: String? = null
)

/** Secure SAX-based parser for standard XMLTV <tv><programme> documents. */
object XmlTvParser {
    fun parse(xml: String): List<EpgProgramme> {
        require(xml.isNotBlank()) { "The EPG response was empty" }
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = false
            isXIncludeAware = false
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }

        val results = ArrayList<EpgProgramme>()
        val handler = object : DefaultHandler() {
            private var current: ProgrammeBuilder? = null
            private var activeField: String? = null
            private val text = StringBuilder()

            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
                when (qName ?: localName.orEmpty()) {
                    "programme" -> {
                        val channel = attributes.getValue("channel").orEmpty()
                        val start = parseXmlTvDate(attributes.getValue("start"))
                        val stop = parseXmlTvDate(attributes.getValue("stop"))
                        current = if (channel.isNotBlank() && start != null && stop != null && stop > start) {
                            ProgrammeBuilder(channel.trim(), start, stop)
                        } else null
                    }
                    "title", "desc" -> if (current != null) {
                        activeField = qName ?: localName
                        text.setLength(0)
                    }
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (activeField != null) text.append(ch, start, length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                when (qName ?: localName.orEmpty()) {
                    "title" -> {
                        current?.title = text.toString().trim()
                        activeField = null
                    }
                    "desc" -> {
                        current?.description = text.toString().trim().takeIf { it.isNotEmpty() }
                        activeField = null
                    }
                    "programme" -> {
                        current?.takeIf { it.title.isNotBlank() }?.let {
                            results += EpgProgramme(it.channel, it.start, it.stop, it.title, it.description)
                        }
                        current = null
                        activeField = null
                    }
                }
            }
        }

        try {
            val reader = factory.newSAXParser().xmlReader
            reader.entityResolver = EntityResolver { _, _ -> InputSource(StringReader("")) }
            reader.contentHandler = handler
            reader.parse(InputSource(StringReader(xml.removePrefix("\uFEFF"))))
        } catch (error: Exception) {
            throw IllegalArgumentException("The EPG response is not valid XMLTV: ${error.message}", error)
        }
        return results.sortedWith(compareBy<EpgProgramme> { it.channelId }.thenBy { it.startTimeMillis })
    }

    private fun parseXmlTvDate(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val match = Regex("""^(\d{14})(?:\s*([+-])(\d{2})(\d{2}))?""").find(value.trim()) ?: return null
        val zone = if (match.groupValues[2].isNotEmpty()) {
            val sign = if (match.groupValues[2] == "-") -1 else 1
            val hours = match.groupValues[3].toIntOrNull() ?: return null
            val minutes = match.groupValues[4].toIntOrNull() ?: return null
            SimpleTimeZone(sign * (hours * 60 + minutes) * 60 * 1000, "XMLTV")
        } else TimeZone.getDefault()

        return runCatching {
            SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply {
                isLenient = false
                timeZone = zone
            }.parse(match.groupValues[1])?.time
        }.getOrNull()
    }
}
