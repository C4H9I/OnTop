package com.ontop.app.text

/** UTF-16 offsets, matching Android's Editable and selection indices. Text is always plain. */
internal enum class RichKind(val block: Boolean = false) {
    BOLD, ITALIC, STRIKE, COLOR, HEADING1(true), HEADING2(true), BULLET(true)
}

internal data class RichRange(val start: Int, val end: Int, val kind: RichKind, val color: String? = null) : java.io.Serializable

internal data class RichDocument(val text: String = "", val ranges: List<RichRange> = emptyList(),
    val media: List<NoteMedia> = emptyList()) : java.io.Serializable {
    fun paragraphs(): List<IntRange> = buildList {
        var start = 0
        val boundaries = media.map { it.offset }.toSet()
        text.forEachIndexed { index, char ->
            if (index in boundaries) { add(start until index); start = index }
            if (char == '\n') { add(start until index + 1); start = index + 1 }
        }
        if (text.length in boundaries && start < text.length) { add(start until text.length); start = text.length }
        add(start until text.length)
    }

    private fun touches(range: RichRange, paragraph: IntRange): Boolean {
        val end = paragraph.last + 1
        return if (paragraph.isEmpty()) range.start == paragraph.first && range.end == paragraph.first
        else range.start < end && range.end > paragraph.first
    }

    fun normalized(): RichDocument {
        val valid = ranges.filter { it.start >= 0 && it.end <= text.length && it.start <= it.end &&
            (it.kind.block || it.start < it.end) && (it.kind != RichKind.COLOR || validTextColor(it.color)) }
        val inline = valid.filterNot { it.kind.block }.groupBy { it.kind to it.color }.flatMap { (_, values) ->
            val merged = mutableListOf<RichRange>()
            for (range in values.sortedBy { it.start }) {
                val previous = merged.lastOrNull()
                if (previous != null && range.start <= previous.end) merged[merged.lastIndex] = previous.copy(end = maxOf(previous.end, range.end))
                else merged += range
            }
            merged
        }
        val blocks = paragraphs().mapNotNull { paragraph ->
            valid.firstOrNull { it.kind.block && touches(it, paragraph) }?.copy(start = paragraph.first, end = paragraph.last + 1)
        }
        return copy(ranges = (inline + blocks).sortedWith(compareBy({ it.start }, { it.end }, { it.kind.ordinal }, { it.color })),
            media = media.filter { it.offset in 0..text.length }.distinctBy { it.id }.sortedBy { it.offset })
    }

    fun selectedParagraphs(start: Int, end: Int): List<IntRange> {
        val from = minOf(start, end).coerceIn(0, text.length)
        val to = maxOf(start, end).coerceIn(0, text.length)
        val anchor = if (from == 0) 0 else text.lastIndexOf('\n', from - 1) + 1
        return paragraphs().filter { p ->
            if (from == to) p.first == anchor
            else p.first < to && p.last + 1 > from
        }.takeIf { it.isNotEmpty() } ?: listOf(paragraphs().last())
    }

    fun has(kind: RichKind, start: Int, end: Int, color: String? = null): Boolean {
        val from = minOf(start, end).coerceIn(0, text.length)
        val to = maxOf(start, end).coerceIn(0, text.length)
        if (kind.block) return selectedParagraphs(from, to).all { p -> ranges.any { it.kind == kind && it.start == p.first } }
        if (from == to) return ranges.any { it.kind == kind && it.color == color && from > it.start && from <= it.end }
        var covered = from
        ranges.filter { it.kind == kind && it.color == color }.sortedBy { it.start }.forEach {
            if (it.start <= covered && it.end > covered) covered = it.end
        }
        return covered >= to
    }

    fun toggle(kind: RichKind, start: Int, end: Int, color: String? = null): RichDocument {
        val from = minOf(start, end).coerceIn(0, text.length)
        val to = maxOf(start, end).coerceIn(0, text.length)
        if (kind.block) {
            val paragraphs = selectedParagraphs(from, to)
            val remove = has(kind, from, to)
            val remaining = ranges.filterNot { it.kind.block && paragraphs.any { p -> p.first == it.start } }
            return copy(ranges = remaining + if (remove) emptyList() else paragraphs.map { RichRange(it.first, it.last + 1, kind) }).normalized()
        }
        if (from == to) return this // Inline actions deliberately require an actual selection.
        val remove = has(kind, from, to, color)
        val remaining = ranges.flatMap { range ->
            if (range.kind != kind || range.end <= from || range.start >= to) listOf(range)
            else buildList {
                if (range.start < from) add(range.copy(end = from))
                if (range.end > to) add(range.copy(start = to))
            }
        }
        return copy(ranges = remaining + if (remove) emptyList() else listOf(RichRange(from, to, kind, color))).normalized()
    }

    fun clear(start: Int, end: Int): RichDocument {
        val from = minOf(start, end).coerceIn(0, text.length)
        val to = maxOf(start, end).coerceIn(0, text.length)
        val paragraphs = selectedParagraphs(from, to)
        return copy(ranges = ranges.flatMap { range ->
            when {
                range.kind.block -> if (paragraphs.any { it.first == range.start }) emptyList() else listOf(range)
                range.end <= from || range.start >= to -> listOf(range)
                else -> buildList {
                    if (range.start < from) add(range.copy(end = from))
                    if (range.end > to) add(range.copy(start = to))
                }
            }
        }).normalized()
    }

    fun without(kind: RichKind, start: Int, end: Int): RichDocument {
        val from = minOf(start, end).coerceIn(0, text.length)
        val to = maxOf(start, end).coerceIn(0, text.length)
        return copy(ranges = ranges.flatMap { range ->
            if (range.kind != kind || range.end <= from || range.start >= to) listOf(range)
            else buildList {
                if (range.start < from) add(range.copy(end = from))
                if (range.end > to) add(range.copy(start = to))
            }
        }).normalized()
    }

    /** Exact edit coordinates come from TextWatcher, never inferred by diffing repeated text. */
    fun replace(start: Int, end: Int, inserted: String): RichDocument {
        require(start in 0..text.length && end in start..text.length)
        val delta = inserted.length - (end - start)
        val mapped = ranges.flatMap { range ->
            buildList {
                if (range.start < start) {
                    val leftEnd = minOf(range.end, start)
                    if (leftEnd > range.start) add(range.copy(end = leftEnd))
                }
                if (range.end > end) {
                    val rightStart = maxOf(range.start, end)
                    add(range.copy(start = rightStart + delta, end = range.end + delta))
                }
                val inherits = if (range.kind.block) start >= range.start && start <= range.end
                    else (start > range.start && start <= range.end) || (end > start && start >= range.start && start < range.end)
                if (inserted.isNotEmpty() && inherits) add(range.copy(start = start, end = start + inserted.length))
                if (range.kind.block && inherits && inserted.endsWith('\n')) {
                    add(range.copy(start = start + inserted.length, end = start + inserted.length))
                }
                if (range.kind.block && range.start == range.end && range.start != start) {
                    val position = if (range.start >= end) range.start + delta else minOf(range.start, start)
                    add(range.copy(start = position, end = position))
                }
            }
        }
        return RichDocument(text.replaceRange(start, end, inserted), mapped,
            media.map { it.copy(offset = if (it.offset >= end) it.offset + delta else if (it.offset > start) start else it.offset) }).normalized()
    }

    companion object { val COLOR_TOKENS = setOf("forest", "clay", "moss", "earth") }
}
