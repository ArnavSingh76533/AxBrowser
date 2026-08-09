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

    /**
     * Reverse-engineers a repeated listing (product cards, search results, table rows, ...) into
     * structured JSON records in one shot, instead of the agent scraping one field at a time.
     * [itemSelector] matches each repeated container; each entry in [fields] is fieldName -> a
     * selector *relative to the container*, optionally suffixed "@attr" to pull an attribute
     * (e.g. "a.title@href", "img@src") instead of text content.
     */
    fun scrapeStructured(itemSelector: String, fields: Map<String, String>): String {
        val escapedContainer = itemSelector.replace("\\", "\\\\").replace("'", "\\'")
        val fieldsJs = fields.entries.joinToString(",\n") { (name, sel) ->
            val (rawSel, attr) = if (sel.contains("@")) sel.substringBeforeLast("@") to sel.substringAfterLast("@") else sel to null
            val escSel = rawSel.replace("\\", "\\\\").replace("'", "\\'")
            val nameJson = org.json.JSONObject.quote(name)
            if (attr != null) {
                val escAttr = attr.replace("\\", "\\\\").replace("'", "\\'")
                "$nameJson: (function(scope){ var e = scope.querySelector('$escSel'); return e ? (e.getAttribute('$escAttr')||'') : ''; })(item)"
            } else {
                "$nameJson: (function(scope){ var e = scope.querySelector('$escSel'); return e ? (e.innerText||e.textContent||'').trim().replace(/\\s+/g,' ') : ''; })(item)"
            }
        }
        return """
            (function() {
                try {
                    var items = Array.prototype.slice.call(document.querySelectorAll('$escapedContainer'));
                    var out = [];
                    for (var i = 0; i < items.length && out.length < 40; i++) {
                        var item = items[i];
                        out.push({
                            $fieldsJs
                        });
                    }
                    return JSON.stringify(out);
                } catch (e) {
                    return JSON.stringify([{ error: 'JS Error: ' + e }]);
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
