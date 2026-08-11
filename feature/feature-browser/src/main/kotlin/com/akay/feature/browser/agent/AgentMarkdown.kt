package com.akay.feature.browser.agent

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Minimal, dependency-free Markdown rendering for agent responses.
 *
 * Supports the subset that actually shows up in agent output:
 *  - fenced code blocks ```lang ... ``` — monospace, horizontally scrollable,
 *    own "Copy" button (this is what curl commands / JSON / code land in)
 *  - inline `code`
 *  - **bold**
 *  - the whole rendered block is wrapped in a SelectionContainer so the user
 *    can long-press and select/copy any part of the response natively.
 */
private sealed class MdBlock {
    data class Paragraph(val text: String) : MdBlock()
    data class Code(val language: String?, val code: String) : MdBlock()
}

private fun parseMarkdownBlocks(raw: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = raw.split("\n")
    var i = 0
    val paragraphBuf = StringBuilder()

    fun flushParagraph() {
        if (paragraphBuf.isNotEmpty()) {
            blocks.add(MdBlock.Paragraph(paragraphBuf.toString().trim('\n')))
            paragraphBuf.clear()
        }
    }

    while (i < lines.size) {
        val line = lines[i]
        val fenceMatch = Regex("^```(\\w*)\\s*$").find(line.trim())
        if (fenceMatch != null) {
            flushParagraph()
            val lang = fenceMatch.groupValues[1].ifBlank { null }
            val codeBuf = StringBuilder()
            i++
            while (i < lines.size && lines[i].trim() != "```") {
                codeBuf.append(lines[i]).append('\n')
                i++
            }
            blocks.add(MdBlock.Code(lang, codeBuf.toString().trimEnd('\n')))
            i++ // skip closing fence
        } else {
            paragraphBuf.append(line).append('\n')
            i++
        }
    }
    flushParagraph()
    return blocks
}

/** Renders **bold** and `inline code` spans inside a plain-text paragraph. */
private fun inlineMarkdownToAnnotated(text: String): AnnotatedString = buildAnnotatedString {
    val pattern = Regex("(\\*\\*[^*]+\\*\\*)|(`[^`]+`)")
    var last = 0
    for (m in pattern.findAll(text)) {
        if (m.range.first > last) append(text.substring(last, m.range.first))
        val token = m.value
        when {
            token.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(token.removePrefix("**").removeSuffix("**"))
            }
            token.startsWith("`") -> withStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, background = Color.Black.copy(alpha = 0.08f))
            ) {
                append(" " + token.removePrefix("`").removeSuffix("`") + " ")
            }
        }
        last = m.range.last + 1
    }
    if (last < text.length) append(text.substring(last))
}

/** Small copy-to-clipboard icon button with a brief checkmark confirmation. */
@Composable
fun CopyIconButton(textToCopy: String, tint: Color) {
    val clipboard = LocalClipboardManager.current
    var justCopied by remember { mutableStateOf(false) }
    LaunchedEffect(justCopied) {
        if (justCopied) {
            delay(1200)
            justCopied = false
        }
    }
    IconButton(
        onClick = {
            clipboard.setText(AnnotatedString(textToCopy))
            justCopied = true
        },
        modifier = Modifier.size(28.dp)
    ) {
        Icon(
            imageVector = if (justCopied) Icons.Default.Check else Icons.Default.ContentCopy,
            contentDescription = "Copy",
            tint = tint,
            modifier = Modifier.size(16.dp)
        )
    }
}

/** Lightly colors curl/bash flags (-X, -H, --data-raw...), the leading command word, and quoted
 *  string arguments, so a long multi-line curl command is actually scannable instead of one wall
 *  of uniform monospace text. Falls back to plain text for anything else. */
private fun highlightShell(code: String, language: String?): AnnotatedString {
    val isShell = language.isNullOrBlank() || language.lowercase() in setOf("bash", "sh", "shell", "curl", "zsh")
    if (!isShell) return AnnotatedString(code)
    return buildAnnotatedString {
        val flagColor = Color(0xFF7EC7FF)
        val stringColor = Color(0xFFC3E88D)
        val cmdColor = Color(0xFFFFB86C)
        val pattern = Regex("(^|\\s)(curl)(?=\\s|$)|(--[a-zA-Z-]+|(?<=\\s)-[a-zA-Z](?=\\s|$))|('[^']*'|\"[^\"]*\")")
        var last = 0
        for (m in pattern.findAll(code)) {
            if (m.range.first > last) append(code.substring(last, m.range.first))
            val g = m.value
            when {
                g.trim() == "curl" -> withStyle(SpanStyle(color = cmdColor, fontWeight = FontWeight.Bold)) { append(g) }
                g.startsWith("--") || (g.startsWith("-") && g.length == 2) -> withStyle(SpanStyle(color = flagColor)) { append(g) }
                g.startsWith("'") || g.startsWith("\"") -> withStyle(SpanStyle(color = stringColor)) { append(g) }
                else -> append(g)
            }
            last = m.range.last + 1
        }
        if (last < code.length) append(code.substring(last))
    }
}

@Composable
private fun CodeBlockView(block: MdBlock.Code) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF1E1E24),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 4.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = block.language?.uppercase() ?: "CODE",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.55f),
                    modifier = Modifier.weight(1f)
                )
                CopyIconButton(textToCopy = block.code, tint = Color.White.copy(alpha = 0.8f))
            }
            SelectionContainer {
                Text(
                    text = highlightShell(block.code, block.language),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = Color(0xFFE6E6E6)
                )
            }
        }
    }
}

/**
 * Renders agent response markdown: paragraphs with inline formatting, and
 * fenced code blocks each with their own copy button. The whole thing sits
 * inside a SelectionContainer so long-press select+copy works everywhere,
 * and [onCopyAll] (wired to a header icon by the caller) copies the raw
 * response text verbatim.
 */
@Composable
fun AgentMarkdown(text: String, contentColor: Color) {
    val blocks = remember(text) { parseMarkdownBlocks(text) }
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (block in blocks) {
                when (block) {
                    is MdBlock.Paragraph -> {
                        if (block.text.isNotBlank()) {
                            Text(
                                text = inlineMarkdownToAnnotated(block.text),
                                style = MaterialTheme.typography.bodyMedium,
                                color = contentColor
                            )
                        }
                    }
                    is MdBlock.Code -> CodeBlockView(block)
                }
            }
        }
    }
}
