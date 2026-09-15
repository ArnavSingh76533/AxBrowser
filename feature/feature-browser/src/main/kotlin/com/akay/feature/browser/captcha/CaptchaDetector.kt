package com.akay.feature.browser.captcha

import org.json.JSONArray
import org.json.JSONObject

/**
 * One captcha found on the page, with everything needed to act on it.
 *
 * [tapX]/[tapY] are viewport-relative CSS pixels of the widget's checkbox (or of the widget
 * itself for interactive challenges). They exist because the checkbox of every major provider
 * lives inside a **cross-origin iframe**: same-document JS cannot click into it, so the only
 * way through is a real native tap dispatched into the WebView at those coordinates.
 */
data class CaptchaWidget(
    val type: String,
    val provider: String,
    /** checkbox | challenge | interactive | ocr | interstitial */
    val kind: String,
    val sitekey: String,
    val selector: String,
    val tapX: Float,
    val tapY: Float,
    val visible: Boolean,
    val solved: Boolean
) {
    val label: String
        get() = when (type) {
            "recaptcha_v2" -> "reCAPTCHA v2"
            "recaptcha_v3" -> "reCAPTCHA v3 (invisible)"
            "hcaptcha" -> "hCaptcha"
            "turnstile" -> "Cloudflare Turnstile"
            "cloudflare_challenge" -> "Cloudflare interstitial challenge"
            "geetest" -> "GeeTest"
            "image_captcha" -> "Image / text captcha"
            "slider" -> "Slider / puzzle captcha"
            else -> type
        }

    /** True when a solver service can produce a token for this widget. Interactive and OCR
     *  captchas have no token API - they need a human (see the agent's ask_user handoff). */
    val tokenSolvable: Boolean
        get() = sitekey.isNotBlank() && type in setOf("recaptcha_v2", "recaptcha_v3", "hcaptcha", "turnstile")
}

object CaptchaDetector {

    /**
     * Walks the page for known captcha markers. Deliberately conservative and fully
     * try/catch-wrapped: a detection failure must never break the page it is inspecting.
     */
    val DETECT_JS = """
        (function () {
            function rectOf(el) {
                try {
                    var r = el.getBoundingClientRect();
                    return { x: r.left, y: r.top, w: r.width, h: r.height };
                } catch (e) { return null; }
            }
            function pathOf(el) {
                try {
                    var parts = [];
                    var cur = el;
                    var depth = 0;
                    while (cur && cur.nodeType === 1 && depth < 20) {
                        var tag = cur.tagName.toLowerCase();
                        if (cur.id) { parts.unshift('#' + cur.id); break; }
                        var parent = cur.parentNode;
                        if (!parent || parent.nodeType !== 1) { parts.unshift(tag); break; }
                        var same = Array.prototype.filter.call(parent.children, function (c) { return c.tagName === cur.tagName; });
                        var idx = same.indexOf(cur) + 1;
                        parts.unshift(same.length > 1 ? (tag + ':nth-of-type(' + idx + ')') : tag);
                        cur = parent;
                        depth++;
                    }
                    return parts.join(' > ');
                } catch (e) { return ''; }
            }
            function sitekeyFrom(sel) {
                try {
                    var el = document.querySelector(sel);
                    if (el) return el.getAttribute('data-sitekey') || '';
                } catch (e) {}
                return '';
            }
            function kFromSrc(srcValue) {
                try { return new URL(srcValue, location.href).searchParams.get('k') || ''; } catch (e) { return ''; }
            }
            function renderKeyFromScripts() {
                try {
                    var scripts = document.querySelectorAll('script[src]');
                    for (var i = 0; i < scripts.length; i++) {
                        var src = scripts[i].getAttribute('src') || '';
                        if (src.indexOf('recaptcha') === -1) continue;
                        var key = new URL(src, location.href).searchParams.get('render');
                        if (key) return key;
                    }
                } catch (e) {}
                return '';
            }
            function fieldValue(name) {
                try {
                    var el = document.querySelector('[name="' + name + '"]');
                    return el && el.value ? String(el.value) : '';
                } catch (e) { return ''; }
            }
            function firstMatch(selectors) {
                for (var i = 0; i < selectors.length; i++) {
                    try {
                        var el = document.querySelector(selectors[i]);
                        if (el) return el;
                    } catch (e) {}
                }
                return null;
            }

            var widgets = [];
            function add(type, provider, kind, sitekey, el, tapDx) {
                var r = el ? rectOf(el) : null;
                var visible = !!(r && r.w > 0 && r.h > 0);
                widgets.push({
                    type: type, provider: provider, kind: kind, sitekey: sitekey || '',
                    selector: el ? pathOf(el) : '',
                    x: r ? r.x : 0, y: r ? r.y : 0, w: r ? r.w : 0, h: r ? r.h : 0,
                    tapX: r ? (r.x + (tapDx === undefined ? r.w / 2 : tapDx)) : 0,
                    tapY: r ? (r.y + r.h / 2) : 0,
                    visible: visible
                });
            }

            // --- reCAPTCHA v2 (v2 checkbox / anchor iframe) ---
            var anchor = firstMatch([
                'iframe[src*="recaptcha"][src*="anchor"]',
                'iframe[title*="reCAPTCHA"]',
                'iframe[title*="recaptcha"]'
            ]);
            if (anchor) {
                var anchorKey = kFromSrc(anchor.getAttribute('src') || '');
                if (!anchorKey) anchorKey = sitekeyFrom('.g-recaptcha[data-sitekey]');
                // The "I'm not a robot" checkbox sits ~28px in from the anchor's left edge.
                add('recaptcha_v2', 'google', 'checkbox', anchorKey, anchor, 28);
            } else {
                var holder = firstMatch(['.g-recaptcha[data-sitekey]', '[data-sitekey][class*="g-recaptcha"]']);
                if (holder) add('recaptcha_v2', 'google', 'checkbox', holder.getAttribute('data-sitekey') || '', holder, 28);
            }

            // --- reCAPTCHA v3 (invisible; sitekey only in the api.js ?render= param) ---
            var v3key = renderKeyFromScripts();
            if (v3key && !anchor) add('recaptcha_v3', 'google', 'challenge', v3key, null, 0);

            // --- hCaptcha ---
            var hc = firstMatch([
                'iframe[src*="hcaptcha.com"][src*="checkbox"]',
                'iframe[title*="hCaptcha"]',
                'iframe[title*="hcaptcha"]',
                '.h-captcha[data-sitekey]',
                '[data-sitekey][class*="h-captcha"]'
            ]);
            if (hc) {
                var hcKey = sitekeyFrom('.h-captcha[data-sitekey]');
                if (!hcKey) hcKey = kFromSrc(hc.getAttribute('src') || '');
                add('hcaptcha', 'hcaptcha', 'checkbox', hcKey, hc, 28);
            }

            // --- Cloudflare Turnstile ---
            var ts = firstMatch([
                '.cf-turnstile[data-sitekey]',
                'iframe[src*="challenges.cloudflare.com"]',
                'div[data-sitekey][class*="turnstile"]'
            ]);
            if (ts) {
                var tsKey = sitekeyFrom('.cf-turnstile[data-sitekey]');
                add('turnstile', 'cloudflare', 'checkbox', tsKey, ts, 28);
            }

            // --- Cloudflare interstitial ("Just a moment…") ---
            var cfChallenge = firstMatch([
                '#challenge-running', '#cf-challenge-running', '.cf-browser-verification',
                '#challenge-form', '#cf-wrapper'
            ]);
            var cfTitle = (document.title || '').toLowerCase();
            if (cfChallenge || cfTitle.indexOf('just a moment') !== -1 || cfTitle.indexOf('attention required') !== -1) {
                add('cloudflare_challenge', 'cloudflare', 'interstitial', '', cfChallenge, undefined);
            }

            // --- GeeTest ---
            var gt = firstMatch(['[class*="geetest_holder"]', '[class*="geetest_panel"]', '.geetest_widget']);
            if (gt) add('geetest', 'geetest', 'interactive', (gt.getAttribute && gt.getAttribute('data-gt')) || '', gt, undefined);

            // --- Slider / puzzle widgets (Alibaba noCaptcha, common "drag to verify") ---
            var slider = firstMatch([
                '#nc_1_wrapper', '.nc-container', '[class*="slide-verify"]', '[class*="slideVerify"]',
                '[class*="puzzle-captcha"]', '[class*="captcha-slider"]', '[class*="drag"] [class*="verify"]'
            ]);
            if (slider) add('slider', 'unknown', 'interactive', '', slider, undefined);

            // --- Plain image / text captcha ---
            var image = firstMatch([
                'img[src*="captcha" i]', 'img[id*="captcha" i]', 'img[class*="captcha" i]',
                'canvas[id*="captcha" i]', 'img[src*="verify" i][src*="code" i]'
            ]);
            var input = firstMatch(['input[name*="captcha" i]', 'input[id*="captcha" i]', 'input[name*="verify" i]']);
            if (image || input) add('image_captcha', 'unknown', 'ocr', '', image || input, undefined);

            var solved = {
                recaptcha: fieldValue('g-recaptcha-response') !== '',
                hcaptcha: fieldValue('h-captcha-response') !== '',
                turnstile: fieldValue('cf-turnstile-response') !== ''
            };
            widgets.forEach(function (w) {
                if (w.type === 'recaptcha_v2' || w.type === 'recaptcha_v3') w.solved = solved.recaptcha;
                else if (w.type === 'hcaptcha') w.solved = solved.hcaptcha;
                else if (w.type === 'turnstile') w.solved = solved.turnstile;
            });

            return JSON.stringify({
                url: location.href,
                title: document.title || '',
                widgets: widgets,
                providers: {
                    grecaptcha: typeof window.grecaptcha !== 'undefined',
                    hcaptcha: typeof window.hcaptcha !== 'undefined',
                    turnstile: typeof window.turnstile !== 'undefined'
                }
            });
        })();
    """.trimIndent()

    fun parse(rawJson: String): List<CaptchaWidget> {
        val arr = runCatching { JSONArray(rawJson) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            CaptchaWidget(
                type = o.optString("type"),
                provider = o.optString("provider"),
                kind = o.optString("kind"),
                sitekey = o.optString("sitekey"),
                selector = o.optString("selector"),
                tapX = o.optDouble("tapX", 0.0).toFloat(),
                tapY = o.optDouble("tapY", 0.0).toFloat(),
                visible = o.optBoolean("visible", false),
                solved = o.optBoolean("solved", false)
            )
        }
    }

    /** Parses the whole detect payload; [parse] is the widget-only helper. */
    fun parseEnvelope(rawJson: String): List<CaptchaWidget> {
        val obj = runCatching { JSONObject(rawJson) }.getOrNull() ?: return emptyList()
        return parse(obj.optJSONArray("widgets")?.toString() ?: "[]")
    }

    /** Reads the solved-state of provider response fields, so a solve attempt can be verified
     *  instead of assumed to have worked. */
    val SOLVED_STATE_JS = """
        (function () {
            function v(name) {
                try { var el = document.querySelector('[name="' + name + '"]'); return el && el.value ? String(el.value) : ''; }
                catch (e) { return ''; }
            }
            var rec = v('g-recaptcha-response');
            var hc = v('h-captcha-response');
            var ts = v('cf-turnstile-response');
            var title = (document.title || '').toLowerCase();
            return JSON.stringify({
                recaptcha: rec.length > 0, recaptchaTokenLength: rec.length,
                hcaptcha: hc.length > 0, turnstile: ts.length > 0,
                cloudflareChallenge: title.indexOf('just a moment') !== -1,
                url: location.href
            });
        })();
    """.trimIndent()
}
