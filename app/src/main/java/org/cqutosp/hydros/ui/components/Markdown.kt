package org.cqutosp.hydros.ui.components

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.PaddingValues
import com.mikepenz.markdown.compose.LazyMarkdownSuccess
import com.mikepenz.markdown.compose.MarkdownSuccess
import com.mikepenz.markdown.model.MarkdownState
import com.mikepenz.markdown.model.rememberMarkdownState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.mikepenz.markdown.m3.Markdown as MaterialMarkdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownBulletList
import com.mikepenz.markdown.compose.elements.MarkdownOrderedList
import com.mikepenz.markdown.compose.elements.listDepth

/** Project typography and selection around the library's Markdown renderer. */
@Composable
fun Markdown(
    content: String,
    modifier: Modifier = Modifier,
    lazy: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(),
    markdownState: MarkdownState = rememberMarkdownState(content)
) {
    val typography = MaterialTheme.typography
    val colors = MaterialTheme.colorScheme
    val body = typography.bodyMedium
    SelectionContainer {
        MaterialMarkdown(
            markdownState = markdownState,
            modifier = modifier,
            colors = markdownColor(
                text = colors.onSurfaceVariant,
                codeBackground = colors.surfaceVariant,
                dividerColor = colors.outlineVariant,
            ),
            success = { state, components, markdownModifier ->
                if (lazy) LazyMarkdownSuccess(state, components, markdownModifier, contentPadding)
                else MarkdownSuccess(state, components, markdownModifier)
            },
            // Align the library's marker and content by text baseline, including CJK fonts.
            components = markdownComponents(
                orderedList = {
                    MarkdownOrderedList(
                        it.content, it.node, depth = it.listDepth,
                        markerModifier = { Modifier.alignByBaseline() },
                        listModifier = { Modifier.alignByBaseline() },
                    )
                },
                unorderedList = {
                    MarkdownBulletList(
                        it.content, it.node, depth = it.listDepth,
                        markerModifier = { Modifier.alignByBaseline() },
                        listModifier = { Modifier.alignByBaseline() },
                    )
                },
            ),
            typography = markdownTypography(
                h1 = typography.titleLarge.copy(fontWeight = FontWeight.Bold, color = colors.onSurface),
                h2 = typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = colors.onSurface),
                h3 = typography.titleSmall.copy(fontWeight = FontWeight.SemiBold, color = colors.onSurface),
                h4 = body.copy(fontWeight = FontWeight.SemiBold, color = colors.onSurface),
                h5 = body.copy(fontWeight = FontWeight.SemiBold, color = colors.onSurface),
                h6 = body.copy(fontWeight = FontWeight.SemiBold, color = colors.onSurface),
                text = body,
                paragraph = body,
                ordered = body,
                bullet = body,
                list = body,
                quote = body,
                textLink = TextLinkStyles(
                    style = SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline),
                ),
            ),
        )
    }
}
