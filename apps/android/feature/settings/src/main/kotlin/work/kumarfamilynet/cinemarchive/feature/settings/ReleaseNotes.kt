package work.kumarfamilynet.cinemarchive.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** One rendered line of release notes. */
internal sealed interface NotesBlock {
    data class Heading(val text: String) : NotesBlock
    data class Bullet(val text: String, val depth: Int) : NotesBlock
    data class Paragraph(val text: String) : NotesBlock
}

/**
 * Splits a GitHub release body into blocks. Covers only what `CHANGELOG.md` and the release
 * bodies built from it use — `#`/`##`/`###` headings, `-`/`*` bullets (indented for nesting),
 * and paragraphs — instead of pulling in a Markdown library for one screen. Anything else is
 * shown as plain text, which degrades readably.
 */
internal fun parseReleaseNotes(markdown: String): List<NotesBlock> =
    markdown.lines().mapNotNull { raw ->
        val line = raw.trimEnd()
        val trimmed = line.trimStart()
        when {
            trimmed.isEmpty() -> null
            trimmed.startsWith("#") -> NotesBlock.Heading(trimmed.trimStart('#').trim())
            trimmed.startsWith("- ") || trimmed.startsWith("* ") ->
                NotesBlock.Bullet(trimmed.substring(2).trim(), depth = (line.length - trimmed.length) / 2)
            else -> NotesBlock.Paragraph(trimmed)
        }
    }

private val inlinePattern = Regex("""\*\*(.+?)\*\*|`([^`]+)`|\[([^\]]+)]\((https?://[^)\s]+)\)""")

/** `**bold**`, `` `code` `` and `[text](https://…)` links; everything else stays literal. */
internal fun inlineMarkdown(text: String, linkColor: Color): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    for (match in inlinePattern.findAll(text)) {
        append(text, cursor, match.range.first)
        val (bold, code, linkText, url) = match.destructured
        when {
            bold.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(bold) }
            code.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(code) }
            else -> withLink(
                LinkAnnotation.Url(
                    url,
                    TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                ),
            ) { append(linkText) }
        }
        cursor = match.range.last + 1
    }
    append(text, cursor, text.length)
}

@Composable
internal fun ReleaseNotes(markdown: String, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { parseReleaseNotes(markdown) }
    val linkColor = MaterialTheme.colorScheme.primary
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is NotesBlock.Heading -> Text(
                    block.text,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
                is NotesBlock.Bullet -> Row(modifier = Modifier.padding(start = (block.depth * 16).dp)) {
                    Text("•", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 8.dp))
                    Text(inlineMarkdown(block.text, linkColor), style = MaterialTheme.typography.bodyMedium)
                }
                is NotesBlock.Paragraph -> Text(inlineMarkdown(block.text, linkColor), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
