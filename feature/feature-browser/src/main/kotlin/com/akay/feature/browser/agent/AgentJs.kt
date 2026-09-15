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

    private fun escapeSelector(selector: String) = selector.replace("\\", "\\\\").replace("'", "\\'")
    private fun escapeText(text: String) = text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")

    /** Clicks the first element matching [selector]. */
    fun clickSelector(selector: String): String = """
        (function() {
            var el = document.querySelector('${escapeSelector(selector)}');
            if (!el) return 'false';
            el.scrollIntoView({block: 'center'});
            el.click();
            return 'true';
        })();
    """.trimIndent()

    /** Types [text] into the first element matching [selector], using the framework-compatible
     *  native-setter trick so React/Vue-controlled inputs (which override the plain .value setter)
     *  actually pick up the change instead of silently ignoring a direct DOM mutation. */
    fun typeIntoSelector(selector: String, text: String): String = """
        (function() {
            var el = document.querySelector('${escapeSelector(selector)}');
            if (!el) return 'false';
            el.focus();
            var proto = el.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
            var setter = Object.getOwnPropertyDescriptor(proto, 'value') && Object.getOwnPropertyDescriptor(proto, 'value').set;
            if (setter) { setter.call(el, '${escapeText(text)}'); } else { el.value = '${escapeText(text)}'; }
            el.dispatchEvent(new Event('input', {bubbles: true}));
            el.dispatchEvent(new Event('change', {bubbles: true}));
            return 'true';
        })();
    """.trimIndent()

    /** Scrolls [selector] into view, or scrolls the page by [pixels] when no selector is given. */
    fun scrollTo(selector: String?, pixels: Int?): String = if (!selector.isNullOrBlank()) {
        """
            (function() {
                var el = document.querySelector('${escapeSelector(selector)}');
                if (!el) return 'false';
                el.scrollIntoView({block: 'center', behavior: 'smooth'});
                return 'true';
            })();
        """.trimIndent()
    } else {
        """
            (function() {
                window.scrollBy(0, ${pixels ?: 800});
                return 'true';
            })();
        """.trimIndent()
    }

    /** Checks (once) whether [selector] currently exists in the DOM - the Kotlin side polls this in a loop. */
    fun elementExists(selector: String): String = """
        (function() { return document.querySelector('${escapeSelector(selector)}') ? 'true' : 'false'; })();
    """.trimIndent()

    // ---------- Playwright-MCP-shaped PageSnapshot engine ----------

    /**
     * Walks interactive DOM (buttons, links, inputs, selects, textareas, [role], [onclick],
     * headings/labels for context) and emits a compact JSON array:
     *   { role, name, value?, ref, path, extra? }
     * - ref: stable per-page id ("s12") Kotlin keeps in a Map<ref, path>.
     * - path: unique nth-of-type CSS chain — the only durable handle across evaluateJavascript
     *   calls (JS object refs die between calls).
     * - Pentest extras: flags input[type=password], surfaces CSRF-looking hidden fields, and
     *   reports <form action> targets so the agent can reason about login forms without a DOM dump.
     */
    // Not `const` on purpose: trimIndent() is a runtime call, so a compile-time constant is impossible.
    val SNAPSHOT = """
        (function() {
            function cssPath(el) {
                var parts = [];
                var cur = el;
                var depth = 0;
                while (cur && cur.nodeType === 1 && depth < 20) {
                    var tag = cur.tagName.toLowerCase();
                    if (cur.id) { parts.unshift('#' + cur.id); break; }
                    var parent = cur.parentNode;
                    if (!parent || parent.nodeType !== 1) { parts.unshift(tag); break; }
                    var same = Array.prototype.filter.call(parent.children, function(c) { return c.tagName === cur.tagName; });
                    var idx = same.indexOf(cur) + 1;
                    if (same.length > 1) parts.unshift(tag + ':nth-of-type(' + idx + ')');
                    else parts.unshift(tag);
                    cur = parent;
                    depth++;
                }
                return parts.join(' > ');
            }
            function visible(el) {
                var r = el.getBoundingClientRect();
                if (r.width === 0 && r.height === 0) {
                    // hidden inputs are still interesting (csrf) - keep them, mark them
                    return el.type === 'hidden';
                }
                var style = window.getComputedStyle(el);
                return style.visibility !== 'hidden' && style.display !== 'none';
            }
            function nameOf(el) {
                var n = el.getAttribute('aria-label') || el.getAttribute('alt') ||
                        el.getAttribute('placeholder') || el.getAttribute('name') ||
                        (el.innerText || el.textContent || '').trim().replace(/\\s+/g, ' ');
                return String(n || '').slice(0, 80);
            }
            function roleOf(el) {
                var explicit = el.getAttribute('role');
                if (explicit) return explicit;
                var tag = el.tagName.toLowerCase();
                if (tag === 'a' && el.hasAttribute('href')) return 'link';
                if (tag === 'button' || (tag === 'input' && (el.type === 'submit' || el.type === 'button'))) return 'button';
                if (tag === 'input' || tag === 'textarea') return el.type === 'hidden' ? 'hidden-input' : 'textbox';
                if (tag === 'select') return 'combobox';
                if (tag === 'option') return 'option';
                if (tag === 'img') return 'image';
                if (['h1','h2','h3','h4','h5','h6'].indexOf(tag) !== -1) return 'heading';
                return tag;
            }
            var selectors = 'a[href], button, input, select, textarea, [role], [onclick], h1, h2, h3, label, form';
            var els = Array.prototype.slice.call(document.querySelectorAll(selectors));
            var out = [];
            var ref = 0;
            var forms = Array.prototype.slice.call(document.querySelectorAll('form')).map(function(f) {
                return { form_action: f.getAttribute('action') || '', form_method: (f.getAttribute('method') || 'GET').toUpperCase() };
            });
            for (var i = 0; i < els.length && out.length < 400; i++) {
                var el = els[i];
                if (!visible(el)) continue;
                var role = roleOf(el);
                var node = { role: role, name: nameOf(el), ref: 's' + (++ref), path: cssPath(el) };
                if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') {
                    node.value = String(el.value || '').slice(0, 60);
                    if (el.type === 'password') node.extra = 'password-field';
                    if (el.type === 'hidden' && /csrf|token|nonce|state/i.test(el.name || el.id || '')) {
                        node.extra = 'likely-csrf: ' + String(el.value || '').slice(0, 40);
                    }
                }
                if (el.tagName === 'SELECT') {
                    node.value = String(el.value || '').slice(0, 40);
                    node.options = Array.prototype.slice.call(el.options).slice(0, 12).map(function(o) { return o.value || o.text.slice(0, 20); });
                }
                out.push(node);
            }
            return JSON.stringify({ url: location.href, title: document.title, nodes: out, forms: forms });
        })();
    """.trimIndent()

    /** Clicks the element at [path] (nth-of-type CSS chain from a snapshot). */
    fun clickByPath(path: String): String = """
        (function() {
            var el = document.querySelector('${escapeSelector(path)}');
            if (!el) return 'notfound';
            el.scrollIntoView({block: 'center'});
            ['pointerdown','mousedown','pointerup','mouseup','click'].forEach(function(t) {
                el.dispatchEvent(new MouseEvent(t, {bubbles: true, cancelable: true, view: window}));
            });
            if (typeof el.click === 'function') el.click();
            return 'ok';
        })();
    """.trimIndent()

    /** Types [text] into the element at [path], framework-compatible via native setters. */
    fun typeByPath(path: String, text: String): String = """
        (function() {
            var el = document.querySelector('${escapeSelector(path)}');
            if (!el) return 'notfound';
            el.focus();
            var proto = el.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
            var setter = Object.getOwnPropertyDescriptor(proto, 'value') && Object.getOwnPropertyDescriptor(proto, 'value').set;
            if (setter) { setter.call(el, '${escapeText(text)}'); } else { el.value = '${escapeText(text)}'; }
            el.dispatchEvent(new Event('input', {bubbles: true}));
            el.dispatchEvent(new Event('change', {bubbles: true}));
            return 'ok';
        })();
    """.trimIndent()

    /** Selects [values] on the <select> at [path] and dispatches change. */
    fun selectOptionByPath(path: String, values: List<String>): String {
        val valsJson = org.json.JSONArray(values).toString().replace("'", "\\'")
        return """
            (function() {
                var el = document.querySelector('${escapeSelector(path)}');
                if (!el) return 'notfound';
                var vals = $valsJson;
                var applied = [];
                Array.prototype.forEach.call(el.options, function(o) { o.selected = vals.indexOf(o.value) !== -1 || vals.indexOf(o.text) !== -1; });
                el.dispatchEvent(new Event('input', {bubbles: true}));
                el.dispatchEvent(new Event('change', {bubbles: true}));
                return 'ok';
            })();
        """.trimIndent()
    }

    /** Hovers the element at [path] (mouseover/mouseenter/mousemove). */
    fun hoverByPath(path: String): String = """
        (function() {
            var el = document.querySelector('${escapeSelector(path)}');
            if (!el) return 'notfound';
            el.scrollIntoView({block: 'center'});
            ['mouseover','mouseenter','mousemove'].forEach(function(t) {
                el.dispatchEvent(new MouseEvent(t, {bubbles: true, cancelable: true, view: window}));
            });
            return 'ok';
        })();
    """.trimIndent()

    /** Presses a key (keydown/keypress/keyup) on the focused/element at [path]; Enter also submits the form when possible. */
    fun pressKeyByPath(path: String, key: String): String {
        val keyJson = org.json.JSONObject.quote(key)
        return """
            (function() {
                var el = document.querySelector('${escapeSelector(path)}') || document.activeElement;
                if (!el) return 'notfound';
                var k = $keyJson;
                var opts = {bubbles: true, cancelable: true, key: k, code: k.length === 1 ? ('Key' + k.toUpperCase()) : k, keyCode: k === 'Enter' ? 13 : (k === 'Tab' ? 9 : k.charCodeAt(0))};
                ['keydown','keypress','keyup'].forEach(function(t) { el.dispatchEvent(new KeyboardEvent(t, opts)); });
                if (k === 'Enter' && el.form && typeof el.form.requestSubmit === 'function') { el.form.requestSubmit(); }
                return 'ok';
            })();
        """.trimIndent()
    }

    /** Waits (in-page, up to [timeoutMs]) for [text] to appear in body innerText. */
    fun waitForText(text: String, timeoutMs: Int): String {
        val textJson = org.json.JSONObject.quote(text)
        return """
            (function() {
                var needle = $textJson.toLowerCase();
                var deadline = Date.now() + $timeoutMs;
                return new Promise(function(resolve) {
                    function check() {
                        if ((document.body ? document.body.innerText : '').toLowerCase().indexOf(needle) !== -1) { resolve('found'); return; }
                        if (Date.now() > deadline) { resolve('timeout'); return; }
                        setTimeout(check, 200);
                    }
                    check();
                });
            })();
        """.trimIndent()
    }

    /** Reads back the CSRF/hidden-field/form summary from the last snapshot's interest areas (cheap re-ask). */
    val STORAGE_DUMP = """
        (function() {
            function dump(s) { try { return JSON.stringify(JSON.parse(JSON.stringify(s || {}))).slice(0, 4000); } catch (e) { return String(s).slice(0, 4000); } }
            return JSON.stringify({
                origin: location.origin,
                localStorage: dump(window.localStorage),
                sessionStorage: dump(window.sessionStorage)
            });
        })();
    """.trimIndent()
}
