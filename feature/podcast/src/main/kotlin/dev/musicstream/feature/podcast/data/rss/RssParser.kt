package dev.musicstream.feature.podcast.data.rss

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneOffset
import javax.xml.parsers.SAXParserFactory

data class ParsedFeed(
    val title: String,
    val author: String,
    val description: String,
    val artworkUrl: String?,
    val episodes: List<ParsedEpisode>,
)

data class ParsedEpisode(
    val id: String,
    val title: String,
    val description: String,
    val audioUrl: String,
    val publishedAt: Long,
    val durationMs: Long,
    val artworkUrl: String?,
)

/**
 * Streaming (SAX) parser for podcast RSS 2.0 feeds with iTunes extensions.
 * SAX is available both on Android and the JVM, so this is unit-testable.
 */
object RssParser {
    private const val ITUNES = "http://www.itunes.com/dtds/podcast-1.0.dtd"
    private const val MAX_DESCRIPTION = 4000

    fun parse(input: InputStream, feedUrl: String): ParsedFeed {
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            // Feeds are untrusted input: no external entities / DTDs (XXE).
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        }
        val handler = Handler(feedUrl)
        factory.newSAXParser().parse(InputSource(input), handler)
        return handler.result()
    }

    private val inlineHtml = setOf("br", "p", "b", "i", "em", "strong", "a", "span", "div", "ul", "ol", "li", "h1", "h2", "h3")

    private class Handler(private val feedUrl: String) : DefaultHandler() {
        private val text = StringBuilder()
        private var inItem = false
        private var inImage = false

        private var title = ""
        private var author = ""
        private var description = ""
        private var artwork: String? = null
        private val episodes = mutableListOf<ParsedEpisode>()

        // current item
        private var iTitle = ""
        private var iGuid = ""
        private var iDesc = ""
        private var iSummary = ""
        private var iPub = ""
        private var iUrl = ""
        private var iType = ""
        private var iDuration = ""
        private var iImage: String? = null

        override fun startElement(uri: String, localName: String, qName: String, attrs: Attributes) {
            // Un-escaped HTML inside <description> arrives as elements: keep the text.
            if (uri.isEmpty() && localName in inlineHtml) {
                if (localName == "br" || localName == "p") text.append('\n')
                return
            }
            text.setLength(0)
            when {
                localName == "item" -> {
                    inItem = true
                    iTitle = ""; iGuid = ""; iDesc = ""; iSummary = ""; iPub = ""; iUrl = ""; iType = ""; iDuration = ""; iImage = null
                }
                localName == "image" && uri == ITUNES -> {
                    val href = attrs.getValue("href")
                    if (inItem) iImage = href else if (artwork == null) artwork = href
                }
                localName == "image" -> inImage = true
                localName == "enclosure" && inItem -> {
                    val type = attrs.getValue("type").orEmpty()
                    // Prefer the first audio enclosure.
                    if (iUrl.isEmpty() || (!iType.startsWith("audio") && type.startsWith("audio"))) {
                        iUrl = attrs.getValue("url").orEmpty()
                        iType = type
                    }
                }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            text.appendRange(ch, start, start + length)
        }

        override fun endElement(uri: String, localName: String, qName: String) {
            if (uri.isEmpty() && localName in inlineHtml) return
            val value = text.toString().trim()
            if (inItem) {
                when {
                    localName == "item" -> { finishItem(); inItem = false }
                    localName == "title" && uri.isEmpty() -> iTitle = value
                    localName == "guid" -> iGuid = value
                    localName == "description" && uri.isEmpty() -> iDesc = value
                    localName == "summary" && uri == ITUNES -> iSummary = value
                    localName == "pubDate" -> iPub = value
                    localName == "duration" && uri == ITUNES -> iDuration = value
                }
            } else {
                when {
                    localName == "image" && uri.isEmpty() -> inImage = false
                    localName == "url" && inImage && artwork == null -> artwork = value
                    localName == "title" && uri.isEmpty() && !inImage && title.isEmpty() -> title = value
                    localName == "author" && uri == ITUNES -> author = value
                    localName == "description" && uri.isEmpty() && description.isEmpty() -> description = value
                }
            }
            text.setLength(0)
        }

        private fun finishItem() {
            if (iUrl.isBlank() || (iType.isNotEmpty() && !iType.startsWith("audio") && !iType.startsWith("video"))) return
            val key = iGuid.ifBlank { iUrl }
            episodes += ParsedEpisode(
                id = sha1("$feedUrl\n$key"),
                title = iTitle.ifBlank { "Untitled" },
                description = stripHtml(iDesc.ifBlank { iSummary }),
                audioUrl = iUrl,
                publishedAt = parseDate(iPub),
                durationMs = parseDuration(iDuration),
                artworkUrl = iImage ?: artwork,
            )
        }

        fun result() = ParsedFeed(title.ifBlank { feedUrl }, author, stripHtml(description), artwork, episodes)
    }

    private val rfc822 = Regex("""^(?:[A-Za-z]{3},?\s+)?(\d{1,2})\s+([A-Za-z]{3})[a-z]*\s+(\d{2,4})\s+(\d{1,2}):(\d{2})(?::(\d{2}))?\s*(\S*)$""")
    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val namedZones = mapOf(
        "" to 0, "GMT" to 0, "UT" to 0, "UTC" to 0, "Z" to 0,
        "EST" to -5, "EDT" to -4, "CST" to -6, "CDT" to -5, "MST" to -7, "MDT" to -6, "PST" to -8, "PDT" to -7,
        "JST" to 9, "KST" to 9, "HKT" to 8, "SGT" to 8,
    )

    /**
     * Lenient RFC 822 date parsing. Real feeds often have a wrong weekday,
     * missing seconds, 2-digit years or named zones, which strict
     * java.time formatters reject; the weekday is therefore ignored.
     */
    internal fun parseDate(s: String): Long {
        val m = rfc822.matchEntire(s.trim()) ?: return 0
        val (day, mon, yearRaw, hour, minute, second, zone) = m.destructured
        val month = months.indexOf(mon.lowercase()) + 1
        if (month == 0) return 0
        val year = yearRaw.toInt().let { if (it < 100) 2000 + it else it }
        val offset = zoneOffset(zone) ?: return 0
        return runCatching {
            LocalDateTime.of(year, month, day.toInt(), hour.toInt(), minute.toInt(), second.ifEmpty { "0" }.toInt())
                .toInstant(offset).toEpochMilli()
        }.getOrDefault(0)
    }

    private fun zoneOffset(z: String): ZoneOffset? {
        namedZones[z.uppercase()]?.let { return ZoneOffset.ofHours(it) }
        val m = Regex("""^([+-])(\d{2}):?(\d{2})$""").matchEntire(z) ?: return null
        val sign = if (m.groupValues[1] == "-") -1 else 1
        return ZoneOffset.ofHoursMinutes(sign * m.groupValues[2].toInt(), sign * m.groupValues[3].toInt())
    }

    /** Accepts "3600", "59:30", "1:02:03" (iTunes duration formats). */
    internal fun parseDuration(s: String): Long {
        val parts = s.trim().split(':').map { it.trim().toDoubleOrNull() ?: return 0 }
        val seconds = parts.fold(0.0) { acc, p -> acc * 60 + p }
        return (seconds * 1000).toLong()
    }

    private val tags = Regex("<[^>]+>")
    private val spaces = Regex("[ \\t\\x0B\\f\\r]+")
    private val blankLines = Regex("\\n{3,}")

    internal fun stripHtml(s: String): String {
        val noTags = s.replace(Regex("(?i)<br\\s*/?>|</p>"), "\n").replace(tags, "")
        val decoded = noTags.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        return decoded.replace(spaces, " ").replace(blankLines, "\n\n").trim().take(MAX_DESCRIPTION)
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
