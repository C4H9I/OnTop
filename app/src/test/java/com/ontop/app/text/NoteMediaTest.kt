package com.ontop.app.text

import org.junit.Assert.*
import org.junit.Test

class NoteMediaTest {
    @Test fun splittingStyledParagraphKeepsStylesOnBothSidesOfMedia() {
        for (paragraph in listOf(RichKind.HEADING1, RichKind.HEADING2, RichKind.BULLET)) {
            val text = "left right"
            val styles = listOf(RichKind.BOLD, RichKind.ITALIC, RichKind.STRIKE, RichKind.COLOR, paragraph)
            val original = RichDocument(text, styles.map { RichRange(0, text.length, it,
                if (it == RichKind.COLOR) "#477451" else null) }).normalized()
            val split = original.insertMedia("body", 5, image("split"))
            assertEquals(listOf("left \n", "right"), split.pieces().map { split.fragment(it).text })
            split.pieces().forEach { piece ->
                val fragment = split.fragment(piece)
                styles.forEach { kind -> assertTrue("$kind in ${fragment.text}",
                    fragment.has(kind, 0, fragment.text.trimEnd('\n').length, if (kind == RichKind.COLOR) "#477451" else null)) }
            }
            assertEquals(split.text, split.removeMedia("split").text)
        }
    }

    @Test fun manyMediaAndEmptyTextPiecesRetainTheirDocumentOrder() {
        var document = RichDocument()
        var key = "body"
        repeat(100) { index ->
            val block = image("image-$index")
            document = document.insertMedia(key, 0, block)
            key = block.id
        }
        assertEquals((0 until 100).map { "image-$it" }, document.media.map { it.id })
        assertEquals(101, document.pieces().size)
        assertTrue(document.pieces().all { document.fragment(it).text.isEmpty() })
        val withText = document.replacePiece("image-49", RichDocument("between"))
        assertEquals(0, withText.media[49].offset)
        assertEquals(7, withText.media[50].offset)
        assertEquals("between", withText.fragment(withText.pieces()[50]).text)
    }
    private fun image(id: String) = NoteMedia(id, 0, MediaKind.IMAGE, "images/$id.jpg")
    @Test fun mediaOnlyDocumentAllowsTypingBeforeAndAfterWithoutMovingBlockToWrongSide() {
        val empty = RichDocument().insertMedia("body", 0, image("a"))
        assertEquals(listOf("", ""), empty.pieces().map { empty.fragment(it).text })
        val after = empty.replacePiece("a", RichDocument("after"))
        assertEquals(0, after.media.single().offset)
        val both = after.replacePiece("body", RichDocument("before"))
        assertEquals(6, both.media.single().offset)
        assertEquals(listOf("before", "after"), both.pieces().map { both.fragment(it).text })
    }
    @Test fun headingChosenInEmptyTextAfterLastImageSurvivesUntilTyping() {
        val doc = RichDocument("before").insertMedia("body", 6, image("a"))
        val empty = doc.fragment(doc.pieces().last()).toggle(RichKind.HEADING1, 0, 0)
        val styled = doc.replacePiece("a", empty)
        val typed = styled.replacePiece("a", styled.fragment(styled.pieces().last()).replace(0, 0, "heading"))
        assertTrue(typed.fragment(typed.pieces().last()).has(RichKind.HEADING1, 0, 0))
    }
    @Test fun removingLastSeparatorDoesNotSpreadHeadingAcrossNeighbouringText() {
        val image = RichDocument().insertMedia("body", 0, image("a"))
        val typed = image.replacePiece("a", RichDocument("plain"))
            .replacePiece("body", RichDocument("heading").toggle(RichKind.HEADING1, 0, 0))
        val removed = typed.removeMedia("a")
        assertEquals("heading\nplain", removed.text)
        assertEquals(listOf(RichRange(0, 8, RichKind.HEADING1)), removed.ranges)
        assertTrue(removed.media.isEmpty())
    }
    @Test fun beginningMiddleEndAndConsecutiveImagesRetainTextAndOrder() {
        for (cursor in listOf(0, 2, 4)) {
            val original = RichDocument("abcd").toggle(RichKind.BOLD, 0, 4).withTextColor(0, 4, "#416A9B")
            val doc = original.insertMedia("body", cursor, image("a"))
            assertEquals("abcd", doc.text.replace("\n", ""))
            assertEquals(2, doc.pieces().size)
            assertTrue(doc.fragment(doc.pieces()[0]).text.endsWith("\n") || cursor == 0)
            assertEquals("abcd".drop(cursor), doc.fragment(doc.pieces()[1]).text)
            val again = doc.insertMedia("a", 0, image("b"))
            assertEquals(listOf("a", "b"), again.media.map { it.id })
            assertEquals(3, again.pieces().size)
            val typed = again.replacePiece("b", again.fragment(again.pieces().last()).replace(0, 0, "after"))
            assertEquals(again.media, typed.media)
            assertTrue(typed.text.contains("after"))
        }
    }
    @Test fun editingEitherSideOfEqualOffsetsAndDeletingOnlyBlockPreservesStyles() {
        val doc = RichDocument("alpha beta").toggle(RichKind.BOLD, 0, 10).toggle(RichKind.ITALIC, 0, 10)
            .insertMedia("body", 5, image("a")).insertMedia("a", 0, image("b"))
        val left = doc.replacePiece("body", doc.fragment(doc.pieces()[0]).replace(0, 0, "L"))
        assertEquals(doc.media.map { it.offset + 1 }, left.media.map { it.offset })
        val right = left.replacePiece("b", left.fragment(left.pieces().last()).replace(0, 0, "R"))
        assertEquals(left.media, right.media)
        val removed = right.copy(media = right.media.filterNot { it.id == "a" }).normalized()
        assertEquals(right.text, removed.text)
        assertEquals(right.ranges, removed.ranges)
        assertTrue(removed.ranges.any { it.kind == RichKind.BOLD })
        assertTrue(removed.ranges.any { it.kind == RichKind.ITALIC })
    }
    @Test fun fragmentsKeepIndependentParagraphStylesEvenIfNewlineIsDeleted() {
        val doc = RichDocument("hello world").toggle(RichKind.HEADING1, 0, 0).insertMedia("body", 5, image("a"))
        val changed = doc.replacePiece("body", doc.fragment(doc.pieces()[0]).replace(5, 6, ""))
        assertEquals("hello world", changed.text)
        assertEquals("hello", changed.fragment(changed.pieces()[0]).text)
        assertEquals(" world", changed.fragment(changed.pieces()[1]).text)
        assertTrue(changed.fragment(changed.pieces()[1]).has(RichKind.HEADING1, 0, 0))
    }
    @Test fun recognizedYouTubeFormsAndTimesUseExactHosts() {
        val id = "M7lc1UVf-VE"
        listOf("https://youtube.com/watch?v=$id", "https://youtu.be/$id", "https://www.youtube.com/shorts/$id",
            "https://m.youtube.com/embed/$id", "https://youtube.com/live/$id").forEach {
            assertEquals(id, YouTubeLink.parse(it)!!.id)
        }
        assertEquals(3723, YouTubeLink.parse("https://youtu.be/$id?t=1h2m3s")!!.seconds)
        assertEquals(90, YouTubeLink.parse("https://youtube.com/watch?v=$id&start=90")!!.seconds)
        assertEquals(12, YouTubeLink.parse("https://youtu.be/$id#t=12s")!!.seconds)
        listOf("https://youtube.com.evil.org/watch?v=$id", "https://evil.org/$id", "https://youtube.com@evil.org/$id",
            "javascript:youtube.com/$id", "file:///youtube.com/$id", "https://youtube.com/playlist?v=$id",
            "https://youtu.be/short", "https://youtu.be/$id/more", "https://youtu.be/$id?t=-2", "https://youtu.be/$id?t=oops").forEach {
            assertNull(it, YouTubeLink.parse(it))
        }
    }
}
