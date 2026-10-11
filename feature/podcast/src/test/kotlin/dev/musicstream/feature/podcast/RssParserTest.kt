package dev.musicstream.feature.podcast

import dev.musicstream.feature.podcast.data.rss.RssParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RssParserTest {
    private val feed = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
          <channel>
            <title>科技島讀</title>
            <itunes:author>Hostname</itunes:author>
            <description><![CDATA[<p>每週科技分析</p>]]></description>
            <itunes:image href="https://cdn.example/cover.jpg"/>
            <item>
              <title>EP1 開場</title>
              <guid>ep-1</guid>
              <pubDate>Tue, 01 Oct 2024 08:00:00 +0800</pubDate>
              <enclosure url="https://cdn.example/ep1.mp3" type="audio/mpeg" length="1"/>
              <itunes:duration>1:02:03</itunes:duration>
              <description>第一集 &amp; 介紹<br/>第二行</description>
            </item>
            <item>
              <title>No audio</title>
              <guid>ep-2</guid>
            </item>
            <item>
              <title>EP3</title>
              <enclosure url="https://cdn.example/ep3.m4a" type="audio/x-m4a"/>
              <itunes:duration>754</itunes:duration>
              <pubDate>Wed, 2 Oct 2024 10:00 GMT</pubDate>
            </item>
          </channel>
        </rss>
    """.trimIndent()

    @Test
    fun parsesChannelAndEpisodes() {
        val parsed = RssParser.parse(feed.byteInputStream(), "https://example/feed")
        assertEquals("科技島讀", parsed.title)
        assertEquals("Hostname", parsed.author)
        assertEquals("每週科技分析", parsed.description)
        assertEquals("https://cdn.example/cover.jpg", parsed.artworkUrl)
        assertEquals(2, parsed.episodes.size) // item without enclosure skipped

        val ep1 = parsed.episodes[0]
        assertEquals("EP1 開場", ep1.title)
        assertEquals(3_723_000L, ep1.durationMs)
        assertEquals(1727740800000L, ep1.publishedAt)
        assertTrue(ep1.description.startsWith("第一集 & 介紹\n第二行"))
        assertEquals("https://cdn.example/cover.jpg", ep1.artworkUrl)

        val ep3 = parsed.episodes[1]
        assertEquals(754_000L, ep3.durationMs)
        assertTrue(ep3.publishedAt > 0)
        assertNotEquals(ep1.id, ep3.id) // id falls back to enclosure URL without guid
    }

    @Test
    fun rejectsExternalEntities() {
        val xxe = """<?xml version="1.0"?><!DOCTYPE r [<!ENTITY x SYSTEM "file:///etc/passwd">]>
            <rss><channel><title>&x;</title></channel></rss>"""
        val result = runCatching { RssParser.parse(xxe.byteInputStream(), "u") }
        assertTrue(result.isFailure || !result.getOrThrow().title.contains("root:"))
    }
}

class RssDateTest {
    @Test
    fun lenientDates() {
        val expected = 1727740800000L // 2024-10-01T00:00:00Z
        assertEquals(expected, RssParser.parseDate("Tue, 01 Oct 2024 08:00:00 +0800"))
        assertEquals(expected, RssParser.parseDate("Mon, 1 Oct 2024 00:00 GMT")) // wrong weekday, no seconds
        assertEquals(expected, RssParser.parseDate("1 Oct 24 00:00:00 +00:00"))
        assertEquals(expected, RssParser.parseDate("Mon, 30 Sep 2024 20:00:00 EDT"))
        assertEquals(0L, RssParser.parseDate("yesterday"))
    }
}
