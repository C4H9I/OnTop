package com.ontop.app.text

import org.junit.Assert.*
import org.junit.Test

class RichDocumentTest {
    @Test fun overlappingStylesCanBeAppliedAndRemovedIndependently() {
        val both = RichDocument("Важная мысль").toggle(RichKind.BOLD, 0, 6).toggle(RichKind.ITALIC, 0, 6)
        assertTrue(both.has(RichKind.BOLD, 0, 6))
        assertTrue(both.has(RichKind.ITALIC, 0, 6))
        val italic = both.toggle(RichKind.BOLD, 0, 6)
        assertFalse(italic.has(RichKind.BOLD, 0, 6))
        assertTrue(italic.has(RichKind.ITALIC, 0, 6))
        assertEquals("Важная мысль", italic.text)
    }
    @Test fun removingStyleFromMiddleSplitsRange() {
        val doc = RichDocument("abcdefgh").toggle(RichKind.BOLD, 0, 8).toggle(RichKind.BOLD, 2, 6)
        assertEquals(listOf(RichRange(0, 2, RichKind.BOLD), RichRange(6, 8, RichKind.BOLD)), doc.ranges)
    }
    @Test fun exactInsertAndDeleteInsideBothStylesMaintainOffsets() {
        val doc = RichDocument("abcdef").toggle(RichKind.BOLD, 1, 5).toggle(RichKind.ITALIC, 1, 5)
        val inserted = doc.replace(3, 3, "XYZ")
        assertEquals("abcXYZdef", inserted.text)
        assertTrue(inserted.has(RichKind.BOLD, 1, 8))
        assertTrue(inserted.has(RichKind.ITALIC, 1, 8))
        val deleted = inserted.replace(2, 7, "")
        assertEquals("abef", deleted.text)
        assertTrue(deleted.has(RichKind.BOLD, 1, 3))
        assertTrue(deleted.has(RichKind.ITALIC, 1, 3))
    }
    @Test fun repeatedCharactersUseExactNativeCoordinatesNotAmbiguousDiff() {
        val doc = RichDocument("aaaaaa").toggle(RichKind.BOLD, 3, 5)
        assertEquals(listOf(RichRange(4, 6, RichKind.BOLD)), doc.replace(0, 0, "a").ranges)
        assertEquals(listOf(RichRange(3, 6, RichKind.BOLD)), doc.replace(4, 4, "a").ranges)
    }
    @Test fun startIsExclusiveEndIsInclusiveAndCaretAloneDoesNotApplyInlineStyle() {
        val doc = RichDocument("abcdef").toggle(RichKind.BOLD, 1, 4)
        assertEquals(listOf(RichRange(2, 5, RichKind.BOLD)), doc.replace(1, 1, "x").ranges)
        assertEquals(listOf(RichRange(1, 5, RichKind.BOLD)), doc.replace(4, 4, "x").ranges)
        assertEquals(doc, doc.toggle(RichKind.ITALIC, 3, 3))
    }
    @Test fun backwardsSelectionAndColorReplacementAreCanonical() {
        val doc = RichDocument("abcdef").toggle(RichKind.COLOR, 6, 0, "forest").toggle(RichKind.COLOR, 2, 4, "clay")
        assertTrue(doc.has(RichKind.COLOR, 2, 4, "clay"))
        assertFalse(doc.has(RichKind.COLOR, 0, 6, "forest"))
        assertEquals(3, doc.ranges.size)
        val plain = doc.clear(0, 6)
        assertEquals(emptyList<RichRange>(), plain.ranges)
        assertEquals(doc.text, plain.text)
    }
    @Test fun deletingWholeTextDoesNotLeaveInvalidInlineRanges() {
        val doc = RichDocument("abcdef").toggle(RichKind.STRIKE, 0, 6).replace(0, 6, "")
        assertEquals(RichDocument(), doc)
    }
    @Test fun headingAndListApplyToWholeParagraphsAndAreMutuallyExclusive() {
        val doc = RichDocument("one\ntwo\nthree").toggle(RichKind.HEADING1, 1, 6)
        assertEquals(listOf(RichRange(0, 4, RichKind.HEADING1), RichRange(4, 8, RichKind.HEADING1)), doc.ranges)
        val list = doc.toggle(RichKind.BULLET, 4, 4)
        assertTrue(list.has(RichKind.BULLET, 4, 4))
        assertFalse(list.has(RichKind.HEADING1, 4, 4))
        assertTrue(list.has(RichKind.HEADING1, 0, 0))
    }
    @Test fun listSplitsOnEnterAndCanBeSwitchedOffOnEmptyLastLine() {
        val list = RichDocument("one").toggle(RichKind.BULLET, 0, 0).replace(3, 3, "\n")
        assertTrue(list.has(RichKind.BULLET, 4, 4))
        val normal = list.toggle(RichKind.BULLET, 4, 4)
        assertFalse(normal.has(RichKind.BULLET, 4, 4))
        assertTrue(normal.has(RichKind.BULLET, 0, 0))
        assertEquals("one\n", normal.text)
    }
    @Test fun emptyParagraphCanBeFormattedBeforeTyping() {
        val empty = RichDocument().toggle(RichKind.HEADING2, 0, 0)
        val written = empty.replace(0, 0, "Мысль")
        assertTrue(written.has(RichKind.HEADING2, 0, 0))
        assertEquals("Мысль", written.text)
    }
    @Test fun unicodeUsesUtf16OffsetsWithoutChangingText() {
        val doc = RichDocument("A🌿Б").toggle(RichKind.BOLD, 1, 3)
        assertEquals("A🌿xБ", doc.replace(3, 3, "x").text)
        assertTrue(doc.replace(3, 3, "x").has(RichKind.BOLD, 1, 4))
        assertEquals("AБ", doc.replace(1, 3, "").text)
    }
    @Test fun invalidRangesAreIgnoredWithoutDroppingContent() {
        val doc = RichDocument("Текст", listOf(RichRange(-1, 9, RichKind.BOLD), RichRange(0, 1, RichKind.COLOR, "unknown"))).normalized()
        assertEquals(RichDocument("Текст"), doc)
    }
    @Test fun repeatedMixedEditsKeepRangesValidAndNormalizationIdempotent() {
        val random = java.util.Random(19)
        var doc = RichDocument("Первая мысль\nВторая мысль").toggle(RichKind.BOLD, 0, 7).toggle(RichKind.BULLET, 14, 14)
        repeat(400) {
            val start = random.nextInt(doc.text.length + 1)
            val end = start + random.nextInt(doc.text.length - start + 1)
            val insertion = listOf("", "x", "\n", "Мысль", "🌿")[random.nextInt(5)]
            val expected = doc.text.replaceRange(start, end, insertion)
            doc = doc.replace(start, end, insertion)
            if (random.nextBoolean()) doc = doc.toggle(RichKind.entries[random.nextInt(RichKind.entries.size)], 0, doc.text.length, "forest")
            assertEquals(expected, doc.text)
            assertEquals(doc, doc.normalized())
            doc.ranges.forEach { range ->
                assertTrue(range.start in 0..doc.text.length)
                assertTrue(range.end in range.start..doc.text.length)
                if (range.kind.block) assertTrue(doc.paragraphs().any { it.first == range.start && it.last + 1 == range.end })
            }
        }
    }
}
