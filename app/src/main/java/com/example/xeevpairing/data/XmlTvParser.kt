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

/**
 * Secure, Android-compatible SAX parser for standard XMLTV documents.
 *
 * Optional SAX security features are enabled where the platform supports them.
 * The EntityResolver below independently prevents external DTD/entity retrieval.
 */
object XmlTvParser {
    fun parse(xml: String): List<EpgProgramme> {
        require(xml.isNotBlank()) { "The EPG response was empty" }

        val results = ArrayList<EpgProgramme>()
        val handler = object : DefaultHandler() {
            private var current: ProgrammeBuilder? = null
            private var activeField: String? = null
            private val text = StringBuilder()

            override fun startElement(
                uri: String?,
                localName: String?,
                qName: String?,
                attributes: Attributes
            ) {
                when (qName ?: localName.orEmpty()) {
                    "programme" -> {
                        val channel = attributes.getValue("channel").orEmpty()
                        val start = parseXmlTvDate(attributes.getValue("start"))
                        val stop = parseXmlTvDate(attributes.getValue("stop"))
                        current = if (
                            channel.isNotBlank() &&
                            start != null &&
                            stop != null &&
                            stop > start
                        ) {
                            ProgrammeBuilder(channel.trim(), start, stop)
                        } else {
                            null
                        }
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
                        current?.description =
                            text.toString().trim().takeIf { it.isNotEmpty() }
                        activeField = null
                    }

                    "programme" -> {
                        current?.takeIf { it.title.isNotBlank() }?.let {
                            results += EpgProgramme(
                                it.channel,
                                it.start,
                                it.stop,
                                it.title,
                                it.description
                            )
                        }
                        current = null
                        activeField = null
                    }
                }
            }
        }

        try {
            val factory = SAXParserFactory.newInstance().apply {
                isNamespaceAware = false

                // Android's SAX provider may not implement every optional feature.
                // Try each independently so one unsupported feature cannot stop parsing.
                setFeatureIfSupported(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeatureIfSupported(
                    "http://xml.org/sax/features/external-general-entities",
                    false
                )
                setFeatureIfSupported(
                    "http://xml.org/sax/features/external-parameter-entities",
                    false
                )
                setFeatureIfSupported(
                    "http://apache.org/xml/features/nonvalidating/load-external-dtd",
                    false
                )
            }

            val reader = factory.newSAXParser().xmlReader

            // Never fetch external DTDs/entities, even if a parser feature is unsupported.
            reader.entityResolver = EntityResolver { _, _ ->
                InputSource(StringReader(""))
            }
            reader.contentHandler = handler
            reader.parse(InputSource(StringReader(xml.removePrefix("\uFEFF"))))
        } catch (error: Exception) {
            throw IllegalArgumentException(
                "The EPG response could not be parsed as XMLTV: ${error.message}",
                error
            )
        }

        return results.sortedWith(
            compareBy<EpgProgramme> { it.channelId }.thenBy { it.startTimeMillis }
        )
    }

    private fun SAXParserFactory.setFeatureIfSupported(name: String, value: Boolean) {
        try {
            setFeature(name, value)
        } catch (_: Exception) {
            // Optional on Android. The external EntityResolver remains in place.
        }
    }

    private fun parseXmlTvDate(value: String?): Long? {
        if (value.isNullOrBlank()) return null

        val match = Regex(
            """^(\d{14})(?:\s*([+-])(\d{2})(\d{2}))?"""
        ).find(value.trim()) ?: return null

        val zone = if (match.groupValues[2].isNotEmpty()) {
            val sign = if (match.groupValues[2] == "-") -1 else 1
            val hours = match.groupValues[3].toIntOrNull() ?: return null
            val minutes = match.groupValues[4].toIntOrNull() ?: return null
            SimpleTimeZone(
                sign * (hours * 60 + minutes) * 60 * 1000,
                "XMLTV"
            )
        } else {
            TimeZone.getDefault()
        }

        return runCatching {
            SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply {
                isLenient = false
                timeZone = zone
            }.parse(match.groupValues[1])?.time
        }.getOrNull()
    }
}
