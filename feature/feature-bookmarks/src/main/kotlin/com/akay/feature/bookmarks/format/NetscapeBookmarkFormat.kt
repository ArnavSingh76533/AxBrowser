package com.akay.feature.bookmarks.format

import com.akay.core.domain.model.Bookmark
import java.util.UUID

/**
 * The de-facto standard "Netscape Bookmark File Format" that every major
 * browser uses for bookmark export/import, so files exported here open in
 * Chrome/Firefox and vice versa.
 */
object NetscapeBookmarkFormat {

    fun export(bookmarks: List<Bookmark>): String {
        val sb = StringBuilder()
        sb.append("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n")
        sb.append("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n")
        sb.append("<TITLE>Bookmarks</TITLE>\n")
        sb.append("<H1>Bookmarks</H1>\n")
        sb.append("<DL><p>\n")
        bookmarks.forEach { bookmark ->
            val addDate = bookmark.createdAt / 1000
            val title = bookmark.title.ifBlank { bookmark.url }.escapeHtml()
            sb.append("    <DT><A HREF=\"${bookmark.url.escapeHtml()}\" ADD_DATE=\"$addDate\">$title</A>\n")
        }
        sb.append("</DL><p>\n")
        return sb.toString()
    }

    private val linkRegex = Regex(
        "<A[^>]*HREF=\"([^\"]*)\"[^>]*>(.*?)</A>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val addDateRegex = Regex("ADD_DATE=\"(\\d+)\"", RegexOption.IGNORE_CASE)

    fun parse(html: String): List<Bookmark> {
        val now = System.currentTimeMillis()
        return linkRegex.findAll(html).mapNotNull { match ->
            val tagAttrs = match.value
            val url = match.groupValues[1].unescapeHtml().trim()
            val title = match.groupValues[2].stripTags().unescapeHtml().trim()
            if (url.isBlank() || url.startsWith("javascript:")) return@mapNotNull null
            val addDateSeconds = addDateRegex.find(tagAttrs)?.groupValues?.get(1)?.toLongOrNull()
            Bookmark(
                id = UUID.randomUUID().toString(),
                title = title.ifBlank { url },
                url = url,
                folderId = null,
                createdAt = addDateSeconds?.let { it * 1000 } ?: now
            )
        }.toList()
    }

    private fun String.stripTags(): String = replace(Regex("<[^>]*>"), "")

    private fun String.escapeHtml(): String = this
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun String.unescapeHtml(): String = this
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
}
