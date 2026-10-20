package com.ontop.app.text

import org.junit.Assert.*
import org.junit.Test

class VideoConversionTest {
    private val first = "https://youtu.be/M7lc1UVf-VE?si=original&t=12s"
    private val second = "https://www.youtube.com/shorts/dQw4w9WgXcQ"

    @Test fun supportedFormsInsideUnicodeParagraphsKeepOriginalUrlsAndPunctuation() {
        listOf(first, second, "https://youtube.com/watch?v=M7lc1UVf-VE&feature=share",
            "https://m.youtube.com/live/M7lc1UVf-VE", "www.youtube.com/watch?v=M7lc1UVf-VE").forEach { url ->
            val text = "До 🧠 ($url), после\nЕщё абзац"
            val converted = RichDocument(text).convertYouTubeLinks()
            assertEquals("До 🧠 (), после\nЕщё абзац", converted.text)
            assertEquals(url, converted.media.single().video!!.source)
            assertEquals(text, converted.videoToLink(converted.media.single().id).text)
        }
    }
    @Test fun manyLinksRoundTripWithoutLosingTextAndNeighbourStyles() {
        val text = "До\n$first середина $second\nПосле"
        val doc = RichDocument(text, listOf(RichRange(0, 2, RichKind.BOLD),
            RichRange(text.length - 5, text.length, RichKind.ITALIC)))
        var converted = doc.convertYouTubeLinks()
        assertEquals(listOf(first, second), converted.media.map { it.video!!.source })
        assertEquals("До\n середина \nПосле", converted.text)
        converted.media.map { it.id }.forEach { converted = converted.videoToLink(it) }
        assertEquals(doc, converted)
    }
    @Test fun adjacentVideosWithEqualOffsetsRoundTripInEitherOrder() {
        val text = first + second
        // Separate supported URLs are normally whitespace delimited; create the exact two occurrences explicitly.
        var doc = RichDocument(text).linkToVideo("body", VideoLinkOccurrence(first.length, text.length, YouTubeLink.parse(second)!!))
            .linkToVideo("body", VideoLinkOccurrence(0, first.length, YouTubeLink.parse(first)!!))
        assertEquals(listOf(0, 0), doc.media.map { it.offset })
        val ids = doc.media.map { it.id }
        doc = doc.videoToLink(ids[1]).videoToLink(ids[0])
        assertEquals("$first\n$second", doc.text)
        assertTrue(doc.media.isEmpty())
    }
    @Test fun manuallyInsertedVideoBetweenWordsReturnsAnIndependentlyConvertibleLink() {
        val video = NoteMedia("manual", 4, MediaKind.YOUTUBE, video = YouTubeLink.parse(first))
        val doc = RichDocument("leftright", media = listOf(video)).videoToLink(video.id)
        assertEquals("left\n$first\nright", doc.text)
        assertEquals(first, youtubeLinks(doc.text).single().video.source)
        assertEquals(first, doc.convertYouTubeLinks().media.single().video!!.source)
    }
    @Test fun imageBeforeBetweenAndAfterLinksRemainsInSameLogicalPosition() {
        val doc = RichDocument("A $first B $second C", media = listOf(
            NoteMedia("start", 0, MediaKind.IMAGE, "images/a.jpg"),
            NoteMedia("middle", 4 + first.length, MediaKind.IMAGE, "images/b.jpg"),
            NoteMedia("end", 7 + first.length + second.length, MediaKind.IMAGE, "images/c.jpg")))
        val converted = doc.convertYouTubeLinks()
        assertEquals(listOf(MediaKind.IMAGE, MediaKind.YOUTUBE, MediaKind.IMAGE, MediaKind.YOUTUBE, MediaKind.IMAGE), converted.media.map { it.kind })
        var restored = converted
        converted.media.filter { it.kind == MediaKind.YOUTUBE }.forEach { restored = restored.videoToLink(it.id) }
        assertEquals(doc, restored)
    }
    @Test fun staleLinkActionDoesNotDeleteEditedText() {
        val doc = RichDocument(first)
        val link = youtubeLinks(first).single()
        val edited = doc.replace(0, 5, "changed")
        assertEquals(edited, edited.linkToVideo("body", link))
        assertEquals(doc, doc.linkToVideo("deleted-piece", link))
        assertEquals(doc, doc.videoToLink("missing"))
    }
    @Test fun invalidAndUnrelatedUrlsRemainPlainAndConversionIsIdempotent() {
        val doc = RichDocument("https://example.com/ https://youtube.com.evil.org/watch?v=M7lc1UVf-VE https://youtu.be/short")
        assertEquals(doc, doc.convertYouTubeLinks())
        val converted = RichDocument(first).convertYouTubeLinks()
        assertEquals(converted, converted.convertYouTubeLinks())
    }
}
