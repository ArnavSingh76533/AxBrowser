package com.akay.feature.browser.agent

object AgentJs {
    const val GET_PAGE_TEXT = "(function(){return (document.body ? document.body.innerText : '') || '';})();"

    const val GET_LINKS = """
        (function() {
            var links = Array.prototype.slice.call(document.querySelectorAll('a[href]'));
            var out = [];
            for (var i = 0; i < links.length && out.length < 60; i++) {
                var el = links[i];
                var rect = el.getBoundingClientRect();
                if (rect.width === 0 && rect.height === 0) continue;
                var text = (el.innerText || el.textContent || '').trim().replace(/\s+/g, ' ').slice(0, 90);
                var href = el.href;
                if (!text || !href) continue;
                out.push({ text: text, href: href });
            }
            return JSON.stringify(out);
        })();
    """

    fun clickLinkContaining(text: String): String {
        val escaped = text.replace("\\", "\\\\").replace("'", "\\'")
        return """
            (function() {
                var target = '$escaped'.toLowerCase();
                var links = Array.prototype.slice.call(document.querySelectorAll('a[href]'));
                for (var i = 0; i < links.length; i++) {
                    var t = (links[i].innerText || links[i].textContent || '').toLowerCase();
                    if (t.indexOf(target) !== -1) { links[i].click(); return 'true'; }
                }
                return 'false';
            })();
        """.trimIndent()
    }

    /** CSS-selector scrape. Returns JSON array of strings: text content, or the given attribute if [attribute] is non-null. */
    fun scrape(selector: String, attribute: String?): String {
        val escapedSelector = selector.replace("\\", "\\\\").replace("'", "\\'")
        val attrExpr = if (attribute.isNullOrBlank()) {
            "(el.innerText || el.textContent || '').trim().replace(/\\s+/g, ' ')"
        } else {
            val escapedAttr = attribute.replace("\\", "\\\\").replace("'", "\\'")
            "(el.getAttribute('$escapedAttr') || '')"
        }
        return """
            (function() {
                try {
                    var els = Array.prototype.slice.call(document.querySelectorAll('$escapedSelector'));
                    var out = [];
                    for (var i = 0; i < els.length && out.length < 50; i++) {
                        var el = els[i];
                        var val = $attrExpr;
                        if (val) out.push(String(val).slice(0, 200));
                    }
                    return JSON.stringify(out);
                } catch (e) {
                    return JSON.stringify(['Error: ' + e]);
                }
            })();
        """.trimIndent()
    }

    /** Runs an arbitrary JS expression/statement block and stringifies whatever it evaluates to. */
    fun runJs(code: String): String {
        // The code is executed as-is inside a function body so both bare
        // expressions ("document.title") and multi-statement snippets with
        // an explicit return work.
        return """
            (function() {
                try {
                    var __result = (function() { $code })();
                    if (__result === undefined) return 'undefined';
                    if (typeof __result === 'object') return JSON.stringify(__result).slice(0, 4000);
                    return String(__result).slice(0, 4000);
                } catch (e) {
                    return 'JS Error: ' + e;
                }
            })();
        """.trimIndent()
    }
}
