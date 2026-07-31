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
}
