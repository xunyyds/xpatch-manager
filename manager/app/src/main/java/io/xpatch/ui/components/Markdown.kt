package io.xpatch.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class BulletList(val items: List<MdListItem>) : MdBlock
    data class OrderedList(val items: List<MdListItem>) : MdBlock
    data class Code(val language: String, val code: String) : MdBlock
    data class Quote(val blocks: List<MdBlock>) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    data object Divider : MdBlock
}

internal data class MdListItem(
    val depth: Int,
    val marker: String,
    val text: String,
)

internal data class MdSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val strike: Boolean = false,
    val link: String? = null,
)

private const val MD_ASYNC_THRESHOLD = 32 * 1024

private val HtmlComment = Regex("<!--[\\s\\S]*?-->")

private val HtmlBreak = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)

private val HtmlBlock = Regex(
    "</?(?:p|div|li|tr|td|th|h[1-6]|blockquote|pre|ul|ol|table|section|article|header|footer|hr)\\b[^>]*>",
    RegexOption.IGNORE_CASE,
)

private val HtmlTag = Regex(
    "</?(?:" +
        "blockquote|figcaption|noscript|textarea|colgroup|datalist|optgroup|fieldset|details|summary|picture|iframe|section|article|address|header|footer|legend|button|select|option|canvas|center|strike|source|dialog|figure|object|param|video|audio|track|title|style|script|meta|link|base|html|head|body|template|slot|nav|aside|main|menu|pre|ul|ol|li|dl|dt|dd|div|table|caption|tbody|thead|tfoot|tr|td|th|form|label|input|output|progress|meter|map|area|ruby|abbr|span|strong|small|code|samp|kbd|sub|sup|mark|bdi|bdo|ins|del|h[1-6]|s|q|dfn|rt|rp|data|time|var|i|b|u|a|em|p|br|wbr|hr|img|embed|math|svg|font|big|tt|nobr|marquee" +
        ")\\b[^>]*>",
    RegexOption.IGNORE_CASE,
)

internal fun parseMarkdown(text: String): List<MdBlock> = parseBlocks(text, 0)

@Composable
internal fun rememberMarkdownBlocks(text: String?): List<MdBlock> {
    val body = text ?: return emptyList()
    if (body.isBlank()) return emptyList()
    if (body.length <= MD_ASYNC_THRESHOLD) {
        return remember(body) { parseMarkdown(body) }
    }
    val blocks by produceState(initialValue = emptyList<MdBlock>(), body) {
        value = withContext(Dispatchers.Default) { parseMarkdown(body) }
    }
    return blocks
}

@Composable
internal fun MarkdownView(blocks: List<MdBlock>, modifier: Modifier = Modifier) {
    if (blocks.isEmpty()) return
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        SelectionContainer {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                blocks.forEach { block -> MarkdownBlock(block) }
            }
        }
    }
}

internal fun LazyListScope.markdownItems(blocks: List<MdBlock>, horizontalPadding: Dp = 20.dp) {
    blocks.forEachIndexed { index, block ->
        item(key = "md:" + index) {
            val shape = when {
                blocks.size == 1 -> RoundedCornerShape(16.dp)
                index == 0 -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                index == blocks.lastIndex -> RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
                else -> RoundedCornerShape(0.dp)
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPadding)
                    .background(MaterialTheme.colorScheme.surfaceContainer, shape),
            ) {
                SelectionContainer {
                    MarkdownBlock(
                        block = block,
                        modifier = Modifier.padding(
                            start = 16.dp,
                            end = 16.dp,
                            top = if (index == 0) 4.dp else 0.dp,
                            bottom = if (index == blocks.lastIndex) 12.dp else 0.dp,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
internal fun MarkdownBlock(block: MdBlock, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(top = blockTopPadding(block), bottom = blockBottomPadding(block)),
    ) {
        when (block) {
            is MdBlock.Heading -> Text(
                text = mdInline(block.text),
                style = headingStyle(block.level),
                color = MaterialTheme.colorScheme.onSurface,
            )

            is MdBlock.Paragraph -> Text(
                text = mdInline(block.text),
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            is MdBlock.BulletList -> Column(Modifier.fillMaxWidth()) {
                block.items.forEach { item ->
                    MdListRow(item, MaterialTheme.typography.bodyMedium)
                }
            }

            is MdBlock.OrderedList -> Column(Modifier.fillMaxWidth()) {
                block.items.forEach { item ->
                    MdListRow(item, MaterialTheme.typography.bodyMedium)
                }
            }

            is MdBlock.Code -> MdCodeBlock(block.code)

            is MdBlock.Quote -> MdQuote(block.blocks)

            is MdBlock.Table -> MdTable(block)

            MdBlock.Divider -> HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
        }
    }
}

@Composable
private fun MdListRow(item: MdListItem, style: TextStyle) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(
                start = (item.depth.coerceIn(0, 4) * 14).dp,
                top = 2.dp,
                bottom = 2.dp,
            ),
    ) {
        Text(
            text = item.marker,
            style = style.copy(lineHeight = 21.sp),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(if (item.marker.length > 2) 28.dp else 20.dp),
        )
        Text(
            text = mdInline(item.text),
            style = style.copy(lineHeight = 21.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun MdCodeBlock(code: String) {
    val scroll = rememberScrollState()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .horizontalScroll(scroll)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = code,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                lineHeight = 18.sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            softWrap = false,
        )
    }
}

@Composable
private fun MdQuote(blocks: List<MdBlock>) {
    val bar = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    Column(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                val barWidth = 3.dp.toPx()
                drawRect(
                    color = bar,
                    topLeft = Offset.Zero,
                    size = Size(barWidth, this.size.height),
                )
            }
            .padding(start = 14.dp),
    ) {
        blocks.forEach { child -> MarkdownBlock(child) }
    }
}

@Composable
private fun MdTable(block: MdBlock.Table) {
    val scroll = rememberScrollState()
    val columns = block.header.size.coerceAtLeast(1)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cellWidth = (maxWidth / columns).coerceAtLeast(94.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f))
                .horizontalScroll(scroll),
        ) {
            MdTableRow(block.header, cellWidth, true)
            block.rows.forEach { row ->
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
                MdTableRow(row, cellWidth, false)
            }
        }
    }
}

@Composable
private fun MdTableRow(cells: List<String>, cellWidth: Dp, header: Boolean) {
    val background = if (header) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent
    Row(Modifier.background(background)) {
        cells.forEach { cell ->
            Box(
                Modifier
                    .width(cellWidth)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(
                    text = mdInline(cell),
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                    color = if (header) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun headingStyle(level: Int): TextStyle = when (level) {
    1 -> MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
    2 -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
    3 -> MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
    4 -> MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
    else -> MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
}

private fun blockTopPadding(block: MdBlock): Dp = when (block) {
    is MdBlock.Heading -> 14.dp
    else -> 0.dp
}

private fun blockBottomPadding(block: MdBlock): Dp = when (block) {
    is MdBlock.Heading -> 6.dp
    is MdBlock.Paragraph -> 10.dp
    is MdBlock.BulletList, is MdBlock.OrderedList -> 10.dp
    is MdBlock.Code -> 12.dp
    is MdBlock.Quote -> 12.dp
    is MdBlock.Table -> 12.dp
    MdBlock.Divider -> 10.dp
}

@Composable
private fun mdInline(raw: String): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeColor = MaterialTheme.colorScheme.onSurface
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest
    val handler = LocalUriHandler.current
    return remember(raw, linkColor, codeColor, codeBackground, handler) {
        buildMdInline(raw, linkColor, codeColor, codeBackground, handler)
    }
}

private fun buildMdInline(
    raw: String,
    linkColor: Color,
    codeColor: Color,
    codeBackground: Color,
    handler: UriHandler,
): AnnotatedString = buildAnnotatedString {
    parseInlineSpans(raw).forEach { span ->
        val decoration = when {
            span.strike && span.link != null -> TextDecoration.combine(
                listOf(TextDecoration.LineThrough, TextDecoration.Underline),
            )

            span.strike -> TextDecoration.LineThrough
            span.link != null -> TextDecoration.Underline
            else -> null
        }
        val style = SpanStyle(
            color = when {
                span.link != null -> linkColor
                span.code -> codeColor
                else -> Color.Unspecified
            },
            fontFamily = if (span.code) FontFamily.Monospace else null,
            fontWeight = if (span.bold) FontWeight.SemiBold else null,
            fontStyle = if (span.italic) FontStyle.Italic else null,
            textDecoration = decoration,
            background = if (span.code) codeBackground else Color.Unspecified,
        )
        val target = span.link
        if (target == null) {
            withStyle(style) { append(span.text) }
        } else {
            withLink(
                LinkAnnotation.Clickable(
                    tag = target,
                    styles = TextLinkStyles(style = style),
                    linkInteractionListener = MdLinkListener(handler, target),
                ),
            ) {
                append(span.text)
            }
        }
    }
}

private class MdLinkListener(
    private val handler: UriHandler,
    private val url: String,
) : LinkInteractionListener {
    override fun onClick(link: LinkAnnotation) {
        runCatching { handler.openUri(url) }
    }
}

private fun parseBlocks(source: String, depth: Int): List<MdBlock> {
    if (source.isBlank()) return emptyList()
    val normalized = source.replace("\r\n", "\n").replace('\r', '\n')
    val lines = normalized.split('\n')
    val blocks = ArrayList<MdBlock>()
    val paragraph = ArrayList<String>()
    var index = 0

    fun flushParagraph() {
        if (paragraph.isEmpty()) return
        val body = joinSoftLines(paragraph)
        paragraph.clear()
        if (body.isNotBlank()) blocks.add(MdBlock.Paragraph(stripHtml(body)))
    }

    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trim()

        if (trimmed.isEmpty()) {
            flushParagraph()
            index += 1
            continue
        }

        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            flushParagraph()
            val marker = trimmed[0]
            val language = trimmed.trimStart(marker).trim()
            val code = ArrayList<String>()
            index += 1
            while (index < lines.size) {
                val closing = lines[index].trim()
                if (closing.length >= 3 && closing.all { it == marker }) {
                    index += 1
                    break
                }
                code.add(lines[index].trimEnd())
                index += 1
            }
            blocks.add(MdBlock.Code(language, code.joinToString("\n")))
            continue
        }

        val heading = headingOf(trimmed)
        if (heading != null) {
            flushParagraph()
            blocks.add(MdBlock.Heading(heading.first, stripHtml(heading.second)))
            index += 1
            continue
        }

        if (isRule(trimmed)) {
            flushParagraph()
            blocks.add(MdBlock.Divider)
            index += 1
            continue
        }

        if (trimmed.startsWith(">")) {
            flushParagraph()
            val quoted = ArrayList<String>()
            while (index < lines.size) {
                val current = lines[index].trim()
                if (!current.startsWith(">")) break
                var content = current.substring(1)
                if (content.startsWith(" ")) content = content.substring(1)
                quoted.add(content)
                index += 1
            }
            val children = if (depth < 2) {
                parseBlocks(quoted.joinToString("\n"), depth + 1)
            } else {
                listOf(MdBlock.Paragraph(stripHtml(quoted.joinToString(" "))))
            }
            if (children.isNotEmpty()) blocks.add(MdBlock.Quote(children))
            continue
        }

        if (isTableStart(lines, index)) {
            flushParagraph()
            val header = splitTableRow(lines[index])
            index += 2
            val rows = ArrayList<List<String>>()
            while (index < lines.size) {
                val current = lines[index].trim()
                if (current.isEmpty() || !current.contains('|')) break
                rows.add(normalizeCells(splitTableRow(current), header.size))
                index += 1
            }
            if (header.isNotEmpty()) blocks.add(MdBlock.Table(header, rows))
            continue
        }

        val bullet = bulletOf(line)
        if (bullet != null) {
            flushParagraph()
            val items = ArrayList<MdListItem>()
            while (index < lines.size) {
                index = skipListGap(lines, index, bullet.depth) { bulletOf(it) }
                if (index >= lines.size) break
                val marker = bulletOf(lines[index]) ?: break
                var body = marker.text
                index += 1
                while (index < lines.size) {
                    val next = lines[index]
                    if (next.isBlank() || bulletOf(next) != null || orderedOf(next) != null) break
                    if (!next.startsWith("  ") && !next.startsWith("\t")) break
                    body = body + " " + next.trim()
                    index += 1
                }
                items.add(MdListItem(marker.depth, marker.marker, stripHtml(body)))
            }
            if (items.isNotEmpty()) blocks.add(MdBlock.BulletList(items))
            continue
        }

        val ordered = orderedOf(line)
        if (ordered != null) {
            flushParagraph()
            val items = ArrayList<MdListItem>()
            while (index < lines.size) {
                index = skipListGap(lines, index, ordered.depth) { orderedOf(it) }
                if (index >= lines.size) break
                val marker = orderedOf(lines[index]) ?: break
                var body = marker.text
                index += 1
                while (index < lines.size) {
                    val next = lines[index]
                    if (next.isBlank() || bulletOf(next) != null || orderedOf(next) != null) break
                    if (!next.startsWith("  ") && !next.startsWith("\t")) break
                    body = body + " " + next.trim()
                    index += 1
                }
                items.add(MdListItem(marker.depth, marker.marker, stripHtml(body)))
            }
            if (items.isNotEmpty()) blocks.add(MdBlock.OrderedList(items))
            continue
        }

        paragraph.add(trimmed)
        index += 1
    }
    flushParagraph()
    return blocks
}

private fun skipListGap(
    lines: List<String>,
    start: Int,
    baseDepth: Int,
    markerOf: (String) -> ListMarker?,
): Int {
    var index = start
    while (index < lines.size && lines[index].isBlank()) index += 1
    if (index >= lines.size) return index
    val marker = markerOf(lines[index]) ?: return start
    return if (marker.depth == baseDepth) index else start
}

private class ListMarker(val depth: Int, val marker: String, val text: String)

private fun bulletOf(line: String): ListMarker? {
    val indent = line.indexOfFirst { it != ' ' && it != '\t' }
    if (indent < 0) return null
    val trimmed = line.substring(indent)
    if (trimmed.length < 2) return null
    val symbol = trimmed[0]
    if (symbol != '-' && symbol != '*' && symbol != '+') return null
    if (!trimmed[1].isWhitespace()) return null
    val depth = (indent / 2).coerceAtMost(4)
    val body = trimmed.substring(1).trim()
    if (body.isEmpty()) return null
    return ListMarker(depth, bulletMarker(depth), body)
}

private fun orderedOf(line: String): ListMarker? {
    val indent = line.indexOfFirst { it != ' ' && it != '\t' }
    if (indent < 0) return null
    val trimmed = line.substring(indent)
    var digits = 0
    while (digits < trimmed.length && digits < 9 && trimmed[digits].isDigit()) digits += 1
    if (digits == 0 || digits + 1 >= trimmed.length) return null
    val separator = trimmed[digits]
    if (separator != '.' && separator != ')') return null
    if (!trimmed[digits + 1].isWhitespace()) return null
    val number = trimmed.substring(0, digits).toIntOrNull() ?: return null
    val depth = (indent / 2).coerceAtMost(4)
    val body = trimmed.substring(digits + 1).trim()
    if (body.isEmpty()) return null
    return ListMarker(depth, number.toString() + ".", body)
}

private fun bulletMarker(depth: Int): String = when (depth) {
    0 -> "•"
    1 -> "◦"
    else -> "▪"
}

private fun headingOf(line: String): Pair<Int, String>? {
    val text = line.trimStart()
    if (!text.startsWith("#")) return null
    var level = 0
    while (level < text.length && level < 7 && text[level] == '#') level += 1
    if (level == 0 || level > 6) return null
    if (level < text.length && !text[level].isWhitespace()) return null
    val body = text.substring(level).trim().trimEnd('#').trim()
    return level to body
}

private fun isRule(line: String): Boolean {
    val compact = line.filterNot { it == ' ' || it == '\t' }
    if (compact.length < 3) return false
    val first = compact[0]
    if (first != '-' && first != '*' && first != '_') return false
    return compact.all { it == first }
}

private fun isTableStart(lines: List<String>, index: Int): Boolean {
    if (index + 1 >= lines.size) return false
    val header = lines[index].trim()
    if (!header.contains('|')) return false
    val separator = lines[index + 1].trim()
    if (!separator.contains('|')) return false
    val cells = splitTableRow(separator)
    if (cells.isEmpty()) return false
    return cells.all { cell ->
        val value = cell.trim()
        value.isNotEmpty() && value.contains('-') && value.all { it == '-' || it == ':' }
    }
}

private fun splitTableRow(line: String): List<String> {
    var text = line.trim()
    if (text.startsWith("|")) text = text.substring(1)
    if (text.endsWith("|")) text = text.dropLast(1)
    if (text.isEmpty()) return emptyList()
    return text.split('|').map { stripHtml(it.trim()) }
}

private fun normalizeCells(cells: List<String>, size: Int): List<String> = when {
    cells.size == size -> cells
    cells.size > size -> cells.subList(0, size)
    else -> cells + List(size - cells.size) { "" }
}

private fun joinSoftLines(lines: List<String>): String {
    val builder = StringBuilder()
    lines.forEach { line ->
        val value = line.trim()
        if (value.isEmpty()) return@forEach
        if (builder.isNotEmpty()) {
            val previous = builder.last()
            val next = value.first()
            if (previous != ' ' && !(isCjk(previous) && isCjk(next))) builder.append(' ')
        }
        builder.append(value)
    }
    return builder.toString()
}

private fun isCjk(char: Char): Boolean {
    val code = char.code
    return code in 0x3000..0x303F || code in 0x4E00..0x9FFF || code in 0xFF00..0xFFEF
}

private fun stripHtml(text: String): String {
    var value = HtmlComment.replace(text, "")
    value = HtmlBreak.replace(value, " ")
    value = HtmlBlock.replace(value, " ")
    value = HtmlTag.replace(value, "")
    value = value.replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
    return value.trim()
}

internal fun parseInlineSpans(text: String): List<MdSpan> =
    scanInline(text, false, false, false, 0)

private fun scanInline(
    text: String,
    bold: Boolean,
    italic: Boolean,
    strike: Boolean,
    depth: Int,
): List<MdSpan> {
    val spans = ArrayList<MdSpan>(8)
    if (text.isEmpty() || depth > 4) {
        if (text.isNotEmpty()) spans.add(MdSpan(text, bold, italic, false, strike))
        return spans
    }
    val plain = StringBuilder()
    var index = 0

    fun flushPlain() {
        if (plain.isEmpty()) return
        spans.add(MdSpan(plain.toString(), bold, italic, false, strike))
        plain.setLength(0)
    }

    while (index < text.length) {
        val char = text[index]

        if (char == '\\' && index + 1 < text.length) {
            plain.append(text[index + 1])
            index += 2
            continue
        }

        if (char == '`') {
            val run = countRun(text, index, '`')
            val close = indexOfRun(text, index + run, '`', run)
            if (close > index + run) {
                flushPlain()
                spans.add(MdSpan(text.substring(index + run, close), bold, italic, true, strike))
                index = close + run
                continue
            }
        }

        if (char == '!' && index + 1 < text.length && text[index + 1] == '[') {
            val link = parseLink(text, index + 1)
            if (link != null) {
                flushPlain()
                val label = if (link.label.isBlank()) link.url else link.label
                spans.add(MdSpan("[图片] " + label, bold, italic, false, strike, link.url))
                index = link.end
                continue
            }
        }

        if (char == '[') {
            val link = parseLink(text, index)
            if (link != null) {
                flushPlain()
                val label = if (link.label.isBlank()) link.url else link.label
                spans.add(MdSpan(label, bold, italic, false, strike, link.url))
                index = link.end
                continue
            }
        }

        if (char == '*' && index + 1 < text.length && text[index + 1] == '*') {
            val close = if (bold) -1 else text.indexOf("**", index + 2)
            if (close > index + 2) {
                flushPlain()
                spans.addAll(scanInline(text.substring(index + 2, close), true, italic, strike, depth + 1))
                index = close + 2
                continue
            }
            plain.append("**")
            index += 2
            continue
        }

        if (char == '~' && index + 1 < text.length && text[index + 1] == '~') {
            val close = if (strike) -1 else text.indexOf("~~", index + 2)
            if (close > index + 2) {
                flushPlain()
                spans.addAll(scanInline(text.substring(index + 2, close), bold, italic, true, depth + 1))
                index = close + 2
                continue
            }
            plain.append("~~")
            index += 2
            continue
        }

        if ((char == '*' || char == '_') && !italic && canOpenEmphasis(text, index, char)) {
            val close = findEmphasisClose(text, index + 1, char)
            if (close > index + 1) {
                flushPlain()
                spans.addAll(scanInline(text.substring(index + 1, close), bold, true, strike, depth + 1))
                index = close + 1
                continue
            }
        }

        plain.append(char)
        index += 1
    }
    flushPlain()
    return spans
}

private fun canOpenEmphasis(text: String, index: Int, marker: Char): Boolean {
    if (index + 1 >= text.length) return false
    if (text[index + 1].isWhitespace()) return false
    if (marker == '_' && index > 0 && text[index - 1].isLetterOrDigit()) return false
    return true
}

private fun findEmphasisClose(text: String, from: Int, marker: Char): Int {
    var index = from
    while (index < text.length) {
        val char = text[index]
        if (char == '\\') {
            index += 2
            continue
        }
        if (char == marker) {
            if (marker == '_' && index + 1 < text.length && text[index + 1].isLetterOrDigit()) {
                index += 1
                continue
            }
            if (marker == '*' && index + 1 < text.length && text[index + 1] == '*') {
                index += 2
                continue
            }
            if (index > from) return index
        }
        index += 1
    }
    return -1
}

private fun countRun(text: String, start: Int, marker: Char): Int {
    var index = start
    while (index < text.length && text[index] == marker) index += 1
    return index - start
}

private fun indexOfRun(text: String, start: Int, marker: Char, length: Int): Int {
    var index = start
    while (index < text.length) {
        if (text[index] == marker) {
            var run = 0
            while (index + run < text.length && text[index + run] == marker) run += 1
            if (run >= length) return index
            index += run
        } else {
            index += 1
        }
    }
    return -1
}

private class InlineLink(val label: String, val url: String, val end: Int)

private fun parseLink(text: String, start: Int): InlineLink? {
    if (start >= text.length || text[start] != '[') return null
    var depth = 0
    var index = start
    var closeBracket = -1
    while (index < text.length) {
        val char = text[index]
        if (char == '\\') {
            index += 2
            continue
        }
        if (char == '[') depth += 1
        if (char == ']') {
            depth -= 1
            if (depth == 0) {
                closeBracket = index
                break
            }
        }
        index += 1
    }
    if (closeBracket < 0) return null
    if (closeBracket + 1 >= text.length || text[closeBracket + 1] != '(') return null
    var cursor = closeBracket + 2
    val target = StringBuilder()
    var parens = 0
    var closed = false
    while (cursor < text.length) {
        val char = text[cursor]
        if (char == ')') {
            if (parens == 0) {
                closed = true
                break
            }
            parens -= 1
            target.append(char)
            cursor += 1
            continue
        }
        if (char == '(') parens += 1
        target.append(char)
        cursor += 1
    }
    if (!closed) return null
    val url = cleanUrl(target.toString())
    if (url.isEmpty()) return null
    return InlineLink(stripHtml(text.substring(start + 1, closeBracket)), url, cursor + 1)
}

private fun cleanUrl(raw: String): String {
    var value = raw.trim()
    if (value.startsWith("<") && value.endsWith(">")) {
        value = value.substring(1, value.length - 1).trim()
    }
    val space = value.indexOfFirst { it == ' ' || it == '\t' }
    if (space > 0) value = value.substring(0, space)
    return value
}
