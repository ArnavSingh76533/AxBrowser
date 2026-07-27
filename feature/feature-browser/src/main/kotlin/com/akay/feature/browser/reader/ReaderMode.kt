package com.akay.feature.browser.reader

import org.json.JSONObject

/**
 * Lightweight Readability-style article extractor. Scores block-level
 * elements by text density / link density and picks the best candidate,
 * then serializes it into a clean, distraction-free HTML document that is
 * rendered back into the same WebView via loadDataWithBaseURL.
 */
object ReaderMode {

    /**
     * Injected into the page. Returns a JSON string:
     * {"title": "...", "byline": "...", "html": "...", "ok": true/false}
     */
    val EXTRACTION_JS = """
        (function() {
            try {
                function textLen(el) { return (el.innerText || "").trim().length; }
                function linkLen(el) {
                    var links = el.querySelectorAll('a');
                    var len = 0;
                    for (var i = 0; i < links.length; i++) len += (links[i].innerText || "").length;
                    return len;
                }
                var candidates = document.querySelectorAll('article, main, [role="main"], .post, .article, .entry-content, section, div');
                var best = null, bestScore = 0;
                for (var i = 0; i < candidates.length; i++) {
                    var el = candidates[i];
                    var tl = textLen(el);
                    if (tl < 200) continue;
                    var ll = linkLen(el);
                    var density = ll / Math.max(tl, 1);
                    if (density > 0.5) continue;
                    var pCount = el.querySelectorAll('p').length;
                    var score = tl * (1 - density) + pCount * 20;
                    if (score > bestScore) { bestScore = score; best = el; }
                }
                if (!best) best = document.body;
                var clone = best.cloneNode(true);
                var junk = clone.querySelectorAll('script, style, nav, form, iframe, noscript, .ad, .ads, .advertisement, .share, .social, .comments, button, svg');
                for (var j = 0; j < junk.length; j++) junk[j].parentNode && junk[j].parentNode.removeChild(junk[j]);
                var allowed = ['P','H1','H2','H3','H4','BLOCKQUOTE','UL','OL','LI','IMG','FIGURE','FIGCAPTION','PRE','CODE','EM','STRONG','A','BR','SPAN'];
                (function strip(node) {
                    var kids = Array.prototype.slice.call(node.children || []);
                    for (var k = 0; k < kids.length; k++) {
                        var child = kids[k];
                        strip(child);
                        if (allowed.indexOf(child.tagName) === -1) {
                            while (child.firstChild) node.insertBefore(child.firstChild, child);
                            node.removeChild(child);
                        } else {
                            child.removeAttribute('style');
                            child.removeAttribute('class');
                            child.removeAttribute('onclick');
                        }
                    }
                })(clone);
                var byline = "";
                var bylineEl = document.querySelector('[rel="author"], .byline, .author, meta[name="author"]');
                if (bylineEl) byline = (bylineEl.content || bylineEl.innerText || "").trim();
                var title = (document.querySelector('h1') && document.querySelector('h1').innerText) || document.title || "";
                return JSON.stringify({ ok: true, title: title, byline: byline, html: clone.innerHTML });
            } catch (e) {
                return JSON.stringify({ ok: false, error: String(e) });
            }
        })();
    """.trimIndent()

    data class Article(val title: String, val byline: String, val bodyHtml: String)

    fun parse(rawResult: String?): Article? {
        if (rawResult == null || rawResult == "null") return null
        return runCatching {
            // evaluateJavascript wraps the returned string in JSON quotes, unescape it first.
            val unwrapped = JSONObject("{\"v\":$rawResult}").getString("v")
            val json = JSONObject(unwrapped)
            if (!json.optBoolean("ok", false)) return null
            Article(
                title = json.optString("title").ifBlank { "Article" },
                byline = json.optString("byline"),
                bodyHtml = json.optString("html")
            )
        }.getOrNull()
    }

    fun buildReaderHtml(article: Article, fontSizePercent: Int = 100, darkMode: Boolean = true): String {
        val bg = if (darkMode) "#121016" else "#FAF7F2"
        val fg = if (darkMode) "#EDE7F6" else "#1B1420"
        val muted = if (darkMode) "#9A8FB0" else "#6B5F7A"
        val scale = (fontSizePercent / 100f).coerceIn(0.7f, 2f)
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    body {
                        margin: 0; padding: 24px 20px 64px;
                        background: $bg; color: $fg;
                        font-family: Georgia, 'Times New Roman', serif;
                        font-size: ${(18 * scale).toInt()}px;
                        line-height: 1.7;
                    }
                    h1 { font-family: sans-serif; font-size: ${(26 * scale).toInt()}px; line-height: 1.3; margin-bottom: 4px; }
                    .byline { color: $muted; font-family: sans-serif; font-size: ${(13 * scale).toInt()}px; margin-bottom: 24px; }
                    img, figure { max-width: 100%; height: auto; border-radius: 8px; }
                    a { color: #B388FF; }
                    p { margin: 0 0 18px; }
                    blockquote { border-left: 3px solid #B388FF; margin: 16px 0; padding: 4px 16px; color: $muted; }
                    pre { overflow-x: auto; background: rgba(255,255,255,0.06); padding: 12px; border-radius: 8px; }
                </style>
            </head>
            <body>
                <h1>${article.title}</h1>
                ${if (article.byline.isNotBlank()) "<div class=\"byline\">${article.byline}</div>" else ""}
                ${article.bodyHtml}
            </body>
            </html>
        """.trimIndent()
    }
}
