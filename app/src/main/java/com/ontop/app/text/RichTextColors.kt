package com.ontop.app.text

internal data class TextColorChoice(val name: String, val value: String?)

internal val textColorPalette = listOf(
    TextColorChoice("Обычный", null),
    TextColorChoice("Зелёный", "#477451"),
    TextColorChoice("Шалфей", "#819776"),
    TextColorChoice("Синий", "#416A9B"),
    TextColorChoice("Терракота", "#BC6952"),
    TextColorChoice("Красный", "#B84747"),
    TextColorChoice("Фиолетовый", "#8465A3"),
    TextColorChoice("Золотой", "#B18A31")
)

internal fun validTextColor(value: String?): Boolean = value in RichDocument.COLOR_TOKENS ||
    (value != null && Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?").matches(value))

internal data class TextColorSelection(val value: String? = null, val mixed: Boolean = false) {
    val name: String get() = if (mixed) "Несколько цветов" else
        textColorPalette.firstOrNull { it.value.equals(value, ignoreCase = true) }?.name ?: "Другой цвет"
}

/** Includes uncoloured gaps, so a multicoloured selection never claims one active swatch. */
internal fun RichDocument.selectedTextColor(start: Int, end: Int): TextColorSelection {
    val from = minOf(start, end).coerceIn(0, text.length)
    val to = maxOf(start, end).coerceIn(0, text.length)
    val colors = ranges.filter { it.kind == RichKind.COLOR }
    if (from == to) return TextColorSelection(colors.lastOrNull { from > it.start && from <= it.end }?.color)
    val boundaries = (listOf(from, to) + colors.flatMap { listOf(it.start, it.end) }.filter { it > from && it < to }).distinct().sorted()
    val values = boundaries.zipWithNext().map { (position, _) ->
        colors.lastOrNull { position >= it.start && position < it.end }?.color
    }.distinct()
    return TextColorSelection(values.singleOrNull(), values.size > 1)
}

/** Choosing an already active swatch is idempotent; only 'Обычный цвет' removes colour. */
internal fun RichDocument.withTextColor(start: Int, end: Int, color: String?): RichDocument {
    if (start == end || (color != null && !validTextColor(color))) return this
    val cleared = without(RichKind.COLOR, start, end)
    return if (color == null) cleared else cleared.toggle(RichKind.COLOR, start, end, color)
}
