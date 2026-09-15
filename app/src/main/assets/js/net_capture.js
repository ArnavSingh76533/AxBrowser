(function () {
    if (window.__axNetCapture) return;
    window.__axNetCapture = true;
    if (typeof window.AxNet === 'undefined') return; // native bridge not attached

    var MAX_BODY = 4096;

    function post(obj) {
        try { window.AxNet.log(JSON.stringify(obj)); } catch (e) {}
    }

    function trunc(s, limit) {
        if (typeof s !== 'string') return '';
        var max = limit || MAX_BODY;
        return s.length > max ? s.slice(0, max) + '…[truncated]' : s;
    }

    // Normalizes any fetch body shape into text for capture. Handles the common case
    // (string, e.g. JSON.stringify(payload)) synchronously, and async-decodes the rest
    // (Blob, ArrayBuffer/TypedArray, URLSearchParams, FormData) via a Promise so nothing
    // falls back to an empty body just because it wasn't a plain string.
    function coerceBodyToText(body) {
        if (body === undefined || body === null) return Promise.resolve('');
        if (typeof body === 'string') return Promise.resolve(body);
        try {
            if (typeof URLSearchParams !== 'undefined' && body instanceof URLSearchParams) {
                return Promise.resolve(body.toString());
            }
            if (typeof FormData !== 'undefined' && body instanceof FormData) {
                var parts = [];
                body.forEach(function (v, k) {
                    parts.push(k + '=' + (typeof v === 'string' ? v : '[file: ' + (v && v.name) + ']'));
                });
                return Promise.resolve(parts.join('&'));
            }
            if (typeof Blob !== 'undefined' && body instanceof Blob) {
                return body.text().catch(function () { return '[unreadable blob body]'; });
            }
            if (typeof ArrayBuffer !== 'undefined' && (body instanceof ArrayBuffer || ArrayBuffer.isView(body))) {
                try {
                    var buf = body instanceof ArrayBuffer ? body : body.buffer;
                    return Promise.resolve(new TextDecoder('utf-8', { fatal: false }).decode(buf));
                } catch (e) { return Promise.resolve('[binary body]'); }
            }
            if (typeof ReadableStream !== 'undefined' && body instanceof ReadableStream) {
                // Streamed request bodies can't be read without consuming them out from under
                // the real fetch call; nothing safe to capture here.
                return Promise.resolve('[streamed body - not capturable]');
            }
        } catch (e) {}
        try { return Promise.resolve(String(body)); } catch (e) { return Promise.resolve(''); }
    }

    // Normalizes fetch()'s Headers | array-of-pairs | plain-object into a plain map,
    // so custom auth/session headers (Authorization, X-Api-Key, X-CSRF-Token, ...)
    // actually make it into the captured request instead of being silently dropped.
    function headersToMap(h) {
        var out = {};
        if (!h) return out;
        try {
            if (typeof Headers !== 'undefined' && h instanceof Headers) {
                h.forEach(function (v, k) { out[k] = v; });
            } else if (Array.isArray(h)) {
                h.forEach(function (pair) { if (pair && pair.length >= 2) out[pair[0]] = pair[1]; });
            } else if (typeof h === 'object') {
                Object.keys(h).forEach(function (k) { out[k] = String(h[k]); });
            }
        } catch (e) {}
        return out;
    }

    function parseHeaders(raw) {
        var out = {};
        if (!raw) return out;
        raw.trim().split(/[\r\n]+/).forEach(function (line) {
            var i = line.indexOf(':');
            if (i > 0) out[line.slice(0, i).trim()] = line.slice(i + 1).trim();
        });
        return out;
    }

    // Decodes an XHR response body for whatever responseType the page used. v7 only ever
    // read responseText, so every modern JSON API (responseType = 'json') was captured with
    // a permanently EMPTY response body - the exact "body not captured" complaint.
    function readXhrBody(xhr) {
        try {
            var rt = xhr.responseType;
            if (rt === '' || rt === 'text') return xhr.responseText || '';
            if (rt === 'json') return xhr.response ? JSON.stringify(xhr.response) : '';
            if (rt === 'arraybuffer' && xhr.response) {
                try { return new TextDecoder('utf-8', { fatal: false }).decode(xhr.response); }
                catch (e) { return '[binary response, ' + (xhr.response.byteLength || 0) + ' bytes]'; }
            }
            if (rt === 'blob') {
                try { return '[blob response, ' + (xhr.response.size || 0) + ' bytes - not read]'; }
                catch (e) { return '[blob response]'; }
            }
            if (rt === 'document' && xhr.responseXML) {
                try { return new XMLSerializer().serializeToString(xhr.responseXML); }
                catch (e) { return '[document response]'; }
            }
        } catch (e) {}
        return '';
    }

    // Reads up to N frames / M characters of a streaming response (SSE, chunked NDJSON)
    // off a *clone*, then resolves. Without this, clone().text() on a text/event-stream
    // never resolves - the body was silently lost forever (and the clone leaked).
    function readEventStream(clone, done) {
        var out = [];
        var total = 0;
        var frames = 0;
        var finished = false;
        function finish() {
            if (finished) return;
            finished = true;
            done(out.join(''));
        }
        var reader = null;
        try { reader = clone.body && clone.body.getReader ? clone.body.getReader() : null; } catch (e) { reader = null; }
        if (!reader) { finish(); return; }
        // Hard stop so a never-ending stream still reports what it managed to capture.
        setTimeout(finish, 15000);
        (function pump() {
            reader.read().then(function (r) {
                if (finished) return;
                if (r.done) { finish(); return; }
                try {
                    var chunk = new TextDecoder('utf-8', { fatal: false }).decode(r.value);
                    out.push(chunk);
                    total += chunk.length;
                    frames++;
                } catch (e) {}
                if (frames >= 20 || total >= MAX_BODY) {
                    try { reader.cancel(); } catch (e) {}
                    finish();
                    return;
                }
                pump();
            }).catch(function () { finish(); });
        })();
    }

    // Reads a response's body text (or accumulated stream frames) and logs one row.
    function logFetchResponse(res, url, method, reqHeaders, reqBodyText, start) {
        var ct = '';
        try { ct = res.headers && res.headers.get ? (res.headers.get('content-type') || '') : ''; } catch (e) {}
        var headers = {};
        try { res.headers.forEach(function (v, k) { headers[k] = v; }); } catch (e) {}
        var clone = null;
        try { clone = res.clone(); } catch (e) { clone = null; }
        var respP;
        if (!clone) {
            respP = Promise.resolve('');
        } else if (ct.indexOf('text/event-stream') !== -1) {
            respP = new Promise(function (resolve) { readEventStream(clone, resolve); });
        } else {
            respP = clone.text().catch(function () { return ''; });
        }
        Promise.all([respP, reqBodyText || Promise.resolve('')]).then(function (results) {
            post({
                source: 'fetch', url: url, method: method,
                status: res.status, reqBody: trunc(results[1] || ''), reqHeaders: reqHeaders,
                respBody: trunc(results[0] || ''),
                respHeaders: headers, durationMs: Date.now() - start,
                type: ct
            });
        });
    }

    // --- Intercept: hold → decide -------------------------------------------------
    // The switch lives in the Intercept tab; native mirrors it here. When it is on, a
    // request is parked BEFORE it is sent: the pending call is posted to native
    // (AxNet.hold), and the real fetch/XHR only runs once native pushes a decision back
    // through window.__axInterceptDecide(...). This is what makes the tab's Forward /
    // Drop / edit-body buttons do anything at all.
    window.__axInterceptOn = false;
    window.__axMatchReplace = [];
    var parked = {};
    var parkSeq = 0;

    window.__axSetIntercept = function (on) { window.__axInterceptOn = !!on; };

    window.__axSetMatchReplace = function (json) {
        try { window.__axMatchReplace = typeof json === 'string' ? JSON.parse(json) : (json || []); }
        catch (e) { window.__axMatchReplace = []; }
    };

    window.__axInterceptDecide = function (json) {
        var d = null;
        try { d = typeof json === 'string' ? JSON.parse(json) : json; } catch (e) { return; }
        if (!d || !d.id) return;
        var entry = parked[d.id];
        if (!entry) return;
        entry.resolve(d);
    };

    function parkAndWait(payload, timeoutMs) {
        return new Promise(function (resolve) {
            var id = 'axp' + (++parkSeq);
            var settled = false;
            function settle(decision) {
                if (settled) return;
                settled = true;
                clearTimeout(safety);
                delete parked[id];
                resolve(decision);
            }
            // Safety net: a forgotten Intercept tab must never hang a site forever.
            var safety = setTimeout(function () { settle({ action: 'forward', id: id }); }, timeoutMs || 120000);
            parked[id] = { resolve: settle };
            var msg = {
                source: 'intercept', id: id, url: payload.url, method: payload.method,
                reqHeaders: payload.headers || {}, reqBody: trunc(payload.body || ''),
                status: null, respBody: '', respHeaders: {}, durationMs: 0, type: 'intercept'
            };
            try {
                if (window.AxNet && typeof window.AxNet.hold === 'function') window.AxNet.hold(JSON.stringify(msg));
                else throw new Error('no hold bridge');
            } catch (e) {
                settle({ action: 'forward', id: id });
            }
        });
    }

    function applyBodyRules(body, type) {
        var out = body === undefined || body === null ? '' : String(body);
        var list = window.__axMatchReplace || [];
        for (var i = 0; i < list.length; i++) {
            var r = list[i];
            if (!r || r.type !== type) continue;
            try {
                if (r.regex) out = out.replace(new RegExp(r.match, 'g'), r.replace);
                else out = out.split(String(r.match)).join(String(r.replace));
            } catch (e) {}
        }
        return out;
    }

    function applyHeaderRules(headers) {
        var out = {};
        var src = headers || {};
        Object.keys(src).forEach(function (k) { out[k] = src[k]; });
        var list = window.__axMatchReplace || [];
        for (var i = 0; i < list.length; i++) {
            var r = list[i];
            if (!r || r.type !== 'REPLACE_REQ_HEADER') continue;
            var raw = String(r.match);
            var idx = raw.indexOf(':');
            var name = (idx > 0 ? raw.slice(0, idx) : raw).trim();
            if (name) out[name] = String(r.replace);
        }
        return out;
    }

    // --- fetch() ---
    // Wrapped in a function so the exact same hook can be applied to any same-origin realm
    // (the top page, and same-origin iframes below) - not just `window`.
    function patchFetch(win) {
        var origFetch = win.fetch;
        if (!origFetch) return;
        win.fetch = function (input, init) {
            var start = Date.now();
            var isRequestObj = typeof win.Request !== 'undefined' && input instanceof win.Request;
            var url = (typeof input === 'string') ? input : (input && input.url) || '';
            var method = (init && init.method) || (input && input.method) || 'GET';
            var reqHeaders = headersToMap(init && init.headers);
            if (isRequestObj && input.headers) {
                var reqHdrsFromRequest = headersToMap(input.headers);
                for (var k in reqHdrsFromRequest) if (!(k in reqHeaders)) reqHeaders[k] = reqHdrsFromRequest[k];
            }

            // Body can arrive via init.body (string/Blob/FormData/URLSearchParams/ArrayBuffer)
            // or embedded in a Request object with no init at all - the latter needs a *clone*
            // since Request.body is a one-shot ReadableStream and reading it directly would
            // starve the real fetch() call below of its own body.
            var reqBodyPromise;
            if (init && init.body !== undefined && init.body !== null) {
                reqBodyPromise = coerceBodyToText(init.body);
            } else if (isRequestObj) {
                reqBodyPromise = (function () {
                    try { return input.clone().text().catch(function () { return ''; }); }
                    catch (e) { return Promise.resolve(''); }
                })();
            } else {
                reqBodyPromise = Promise.resolve('');
            }

            var intercepting = window.__axInterceptOn === true;
            // Match&Replace rules apply whether or not intercept is on - that is the whole
            // point of a silent rule (auth-header surgery, patching a body field) versus the
            // blocking Intercept tab. They only cost anything when a rule actually matches.
            var rulesExist = (window.__axMatchReplace || []).length > 0;

            if (intercepting || rulesExist) {
                var self = this;
                return reqBodyPromise.then(function (captured) {
                    var ruleBody = applyBodyRules(captured || '', 'REPLACE_REQ_BODY');
                    var ruleHeaders = applyHeaderRules(reqHeaders);
                    var bodyChanged = ruleBody !== (captured || '');
                    var headersChanged = (function () {
                        var keys = Object.keys(ruleHeaders);
                        for (var i = 0; i < keys.length; i++) {
                            if (reqHeaders[keys[i]] !== ruleHeaders[keys[i]]) return true;
                        }
                        return false;
                    })();
                    if (!intercepting && !bodyChanged && !headersChanged) {
                        // Rules are configured but none of them match this call: send it exactly
                        // as the page wrote it, so no behaviour changes for unrelated traffic.
                        return origFetch.apply(self, arguments).then(function (res) {
                            logFetchResponse(res, url, method, reqHeaders, Promise.resolve(captured || ''), start);
                            return res;
                        }).catch(function (err) {
                            post({ source: 'fetch', url: url, method: method, status: 0,
                                reqBody: trunc(captured || ''), reqHeaders: reqHeaders, error: String(err), durationMs: Date.now() - start });
                            throw err;
                        });
                    }
                    var park = intercepting
                        ? parkAndWait({ url: url, method: method, headers: reqHeaders, body: ruleBody })
                        : Promise.resolve(null);
                    return park.then(function (d) {
                        if (d && d.action === 'drop') {
                            // Honest drop: the request never goes out, exactly like Burp's Drop.
                            return Promise.reject(new TypeError('AxBrowser: request dropped by intercept'));
                        }
                        var finalUrl = (d && d.url) || url;
                        var finalMethod = (d && d.method) || method;
                        var finalHeaders = d && d.headers ? applyHeaderRules(d.headers) : ruleHeaders;
                        var finalBody = d && d.body !== undefined && d.body !== null ? d.body : ruleBody;
                        var opts = { method: finalMethod, headers: finalHeaders };
                        if (init && init.credentials) opts.credentials = init.credentials;
                        if (init && init.mode) opts.mode = init.mode;
                        if (init && init.cache) opts.cache = init.cache;
                        if (init && init.redirect) opts.redirect = init.redirect;
                        if (init && init.referrer) opts.referrer = init.referrer;
                        if (init && init.integrity) opts.integrity = init.integrity;
                        if (init && init.signal) opts.signal = init.signal;
                        if (init && init.keepalive) opts.keepalive = init.keepalive;
                        if (finalBody && !/^(GET|HEAD)$/i.test(finalMethod)) opts.body = finalBody;
                        return origFetch.call(self, finalUrl, opts).then(function (res) {
                            // Log what actually went out (edited body/headers included), not the
                            // page's original call - otherwise the Network tab and get_curl would
                            // disagree with the wire.
                            logFetchResponse(res, finalUrl, finalMethod, finalHeaders, Promise.resolve(finalBody || ''), start);
                            return res;
                        });
                    });
                }).catch(function (err) {
                    if (String(err && err.message).indexOf('dropped by intercept') === -1) {
                        post({ source: 'fetch', url: url, method: method, status: 0,
                            reqBody: '', reqHeaders: reqHeaders, error: String(err), durationMs: Date.now() - start });
                    }
                    throw err;
                });
            }

            // No interception, no rules: send the original call untouched, capture alongside it.
            return origFetch.apply(this, arguments).then(function (res) {
                logFetchResponse(res, url, method, reqHeaders, reqBodyPromise, start);
                return res;
            }).catch(function (err) {
                reqBodyPromise.then(function (reqBodyText) {
                    post({ source: 'fetch', url: url, method: method, status: 0,
                        reqBody: trunc(reqBodyText || ''), reqHeaders: reqHeaders, error: String(err), durationMs: Date.now() - start });
                });
                throw err;
            });
        };
    }

    // --- XMLHttpRequest ---
    function patchXhr(win) {
        var XHR = win.XMLHttpRequest;
        if (!XHR) return;
        var origOpen = XHR.prototype.open;
        var origSend = XHR.prototype.send;
        var origSetHeader = XHR.prototype.setRequestHeader;
        XHR.prototype.open = function (method, url, async, user, password) {
            this.__ax = {
                method: method, url: url, start: 0, reqHeaders: {},
                async: async === undefined ? true : async, user: user, password: password
            };
            return origOpen.apply(this, arguments);
        };
        XHR.prototype.setRequestHeader = function (name, value) {
            if (this.__ax) this.__ax.reqHeaders[name] = value;
            return origSetHeader.apply(this, arguments);
        };
        XHR.prototype.send = function (body) {
            var xhr = this;
            if (xhr.__ax) {
                xhr.__ax.start = Date.now();
                xhr.__ax.rawBody = body;
                xhr.addEventListener('loadend', function () {
                    var respBody = readXhrBody(xhr);
                    coerceBodyToText(xhr.__ax.rawBody).then(function (reqBodyText) {
                        post({
                            source: 'xhr', url: xhr.__ax.url, method: xhr.__ax.method,
                            status: xhr.status, reqBody: trunc(reqBodyText || ''), reqHeaders: xhr.__ax.reqHeaders,
                            respBody: trunc(respBody),
                            respHeaders: parseHeaders(xhr.getAllResponseHeaders()),
                            durationMs: Date.now() - xhr.__ax.start,
                            type: xhr.getResponseHeader ? (xhr.getResponseHeader('content-type') || '') : ''
                        });
                    });
                });
            }
            // Match&Replace on the non-intercepting path. Bodies are only rewritable when they
            // arrived as a string (JSON/form-encoded - the overwhelming majority); an exotic
            // Blob/stream body is left exactly as the page built it rather than re-serialized.
            if (window.__axInterceptOn !== true && xhr.__ax) {
                var rulesExist = (window.__axMatchReplace || []).length > 0;
                if (rulesExist) {
                    if (typeof body === 'string' && body.length) {
                        var ruledBody = applyBodyRules(body, 'REPLACE_REQ_BODY');
                        if (ruledBody !== body) { body = ruledBody; xhr.__ax.rawBody = ruledBody; }
                    }
                    var ruledHeaders = applyHeaderRules(xhr.__ax.reqHeaders);
                    Object.keys(ruledHeaders).forEach(function (k) {
                        if (xhr.__ax.reqHeaders[k] !== ruledHeaders[k]) {
                            try { origSetHeader.call(xhr, k, ruledHeaders[k]); } catch (e) {}
                            xhr.__ax.reqHeaders[k] = ruledHeaders[k];
                        }
                    });
                }
                return origSend.call(xhr, body);
            }
            if (window.__axInterceptOn === true && xhr.__ax) {
                coerceBodyToText(body).then(function (text) {
                    return parkAndWait({
                        url: xhr.__ax.url, method: xhr.__ax.method, headers: xhr.__ax.reqHeaders,
                        body: applyBodyRules(text || '', 'REPLACE_REQ_BODY')
                    });
                }).then(function (d) {
                    if (!d || d.action === 'drop') {
                        try { xhr.abort(); } catch (e) {}
                        return;
                    }
                    var url = d.url || xhr.__ax.url;
                    var method = d.method || xhr.__ax.method;
                    var headers = applyHeaderRules(d.headers || xhr.__ax.reqHeaders);
                    var payload = d.body !== undefined && d.body !== null ? d.body : body;
                    try {
                        var urlChanged = url !== xhr.__ax.url;
                        var methodChanged = String(method).toUpperCase() !== String(xhr.__ax.method).toUpperCase();
                        if (urlChanged || methodChanged) {
                            origOpen.call(xhr, method, url, xhr.__ax.async, xhr.__ax.user, xhr.__ax.password);
                            Object.keys(headers).forEach(function (k) {
                                try { origSetHeader.call(xhr, k, headers[k]); } catch (e) {}
                            });
                        } else {
                            Object.keys(headers).forEach(function (k) {
                                if (xhr.__ax.reqHeaders[k] !== headers[k]) {
                                    try { origSetHeader.call(xhr, k, headers[k]); } catch (e) {}
                                }
                            });
                        }
                    } catch (e) {}
                    try { origSend.call(xhr, /^(GET|HEAD)$/i.test(method) ? undefined : payload); } catch (e) {}
                }).catch(function () { try { origSend.call(xhr, body); } catch (e) {} });
                return;
            }
            return origSend.apply(this, arguments);
        };
    }

    // --- navigator.sendBeacon ---
    // Beacons (analytics, "read receipt", tracking pings) never touch fetch/XHR, so they
    // were completely invisible before. Body is read from a copy - the beacon itself is
    // still handed to the browser untouched.
    function patchBeacon(win) {
        try {
            if (!win.navigator || typeof win.navigator.sendBeacon !== 'function') return;
            var orig = win.navigator.sendBeacon.bind(win.navigator);
            win.navigator.sendBeacon = function (url, data) {
                var start = Date.now();
                try {
                    coerceBodyToText(data).then(function (text) {
                        post({
                            source: 'beacon', url: String(url), method: 'POST', status: null,
                            reqBody: trunc(text || ''), reqHeaders: {}, respBody: '', respHeaders: {},
                            durationMs: Date.now() - start, type: 'application/beacon'
                        });
                    });
                } catch (e) {}
                return orig.apply(win.navigator, arguments);
            };
        } catch (e) {}
    }

    // --- WebSocket ---
    // Captures connection open (url + protocols) and each frame sent/received so the
    // agent can reverse-engineer realtime APIs (live prices, chat, notifications, ...)
    // the same way it does REST/GraphQL - previously invisible to the dev console.
    function patchWebSocket(win) {
        var OrigWebSocket = win.WebSocket;
        if (!OrigWebSocket) return;
        win.WebSocket = function (url, protocols) {
            var ws = protocols !== undefined ? new OrigWebSocket(url, protocols) : new OrigWebSocket(url);
            post({ source: 'websocket', url: String(url), method: 'OPEN', status: null,
                reqBody: '', respBody: '', durationMs: 0,
                type: 'websocket', wsDirection: 'open' });

            ws.addEventListener('message', function (evt) {
                var data = evt && evt.data;
                var text = (typeof data === 'string') ? data : '[binary frame]';
                post({ source: 'websocket', url: String(url), method: 'MESSAGE', status: null,
                    reqBody: '', respBody: trunc(text), durationMs: 0,
                    type: 'websocket', wsDirection: 'recv' });
            });

            var origWsSend = ws.send.bind(ws);
            ws.send = function (data) {
                var text = (typeof data === 'string') ? data : '[binary frame]';
                post({ source: 'websocket', url: String(url), method: 'SEND', status: null,
                    reqBody: trunc(text), respBody: '', durationMs: 0,
                    type: 'websocket', wsDirection: 'send' });
                return origWsSend(data);
            };

            ws.addEventListener('close', function (evt) {
                post({ source: 'websocket', url: String(url), method: 'CLOSE', status: evt ? evt.code : null,
                    reqBody: '', respBody: '', durationMs: 0,
                    type: 'websocket', wsDirection: 'close' });
            });

            return ws;
        };
        win.WebSocket.prototype = OrigWebSocket.prototype;
        win.WebSocket.CONNECTING = OrigWebSocket.CONNECTING;
        win.WebSocket.OPEN = OrigWebSocket.OPEN;
        win.WebSocket.CLOSING = OrigWebSocket.CLOSING;
        win.WebSocket.CLOSED = OrigWebSocket.CLOSED;
    }

    // --- EventSource (SSE) ---
    // Server-sent-events connections are a second realtime channel sites use for feeds,
    // notifications and (increasingly) GraphQL subscriptions. Not a request/response pair,
    // so frames are logged individually - same shape as WebSocket frames.
    function patchEventSource(win) {
        var Orig = win.EventSource;
        if (!Orig) return;
        win.EventSource = function (url, config) {
            var es = config !== undefined ? new Orig(url, config) : new Orig(url);
            var target = String(url);
            post({ source: 'sse', url: target, method: 'OPEN', status: null, reqBody: '', respBody: '',
                reqHeaders: {}, respHeaders: {}, durationMs: 0,
                type: 'text/event-stream', wsDirection: 'open' });
            es.addEventListener('message', function (evt) {
                post({ source: 'sse', url: target, method: 'MESSAGE', status: null, reqBody: '',
                    respBody: trunc((evt && evt.data) || ''), reqHeaders: {}, respHeaders: {},
                    durationMs: 0, type: 'text/event-stream', wsDirection: 'recv' });
            });
            es.addEventListener('error', function () {
                post({ source: 'sse', url: target, method: 'ERROR', status: null, reqBody: '',
                    respBody: '', reqHeaders: {}, respHeaders: {}, durationMs: 0,
                    type: 'text/event-stream', wsDirection: 'close' });
            });
            return es;
        };
        win.EventSource.prototype = Orig.prototype;
        win.EventSource.CONNECTING = Orig.CONNECTING;
        win.EventSource.OPEN = Orig.OPEN;
        win.EventSource.CLOSED = Orig.CLOSED;
    }

    function patchRealm(win) {
        try { patchFetch(win); } catch (e) {}
        try { patchXhr(win); } catch (e) {}
        try { patchWebSocket(win); } catch (e) {}
        try { patchEventSource(win); } catch (e) {}
        try { patchBeacon(win); } catch (e) {}
    }

    patchRealm(window);

    // --- native <form> submissions ---
    // A plain HTML form (method="post", no JS) never touches fetch/XHR at all, so the hooks
    // above never see it - and Android's WebView gives shouldInterceptRequest no way to read a
    // POST body either. This is the one case we CAN still capture: read the form's fields here,
    // before the browser hands off to its native submit, and log them as the request body so
    // get_curl isn't left with an empty payload for login forms and the like.
    document.addEventListener('submit', function (evt) {
        try {
            var form = evt.target;
            if (!form || form.tagName !== 'FORM') return;
            var method = (form.getAttribute('method') || 'GET').toUpperCase();
            var action = form.getAttribute('action') || location.href;
            var url = new URL(action, location.href).toString();
            var fd = new FormData(form);
            var parts = [];
            fd.forEach(function (v, k) {
                parts.push(encodeURIComponent(k) + '=' + encodeURIComponent(typeof v === 'string' ? v : '[file: ' + (v && v.name) + ']'));
            });
            post({
                source: 'form-submit', url: url, method: method, status: null,
                reqBody: trunc(parts.join('&')), reqHeaders: { 'Content-Type': 'application/x-www-form-urlencoded' },
                respBody: '', respHeaders: {}, durationMs: 0, type: 'application/x-www-form-urlencoded'
            });
        } catch (e) {}
    }, true);

    // --- Worker-issued fetch capture (best-effort, defensive) ---
    // Some sites issue their real API calls from inside a dedicated Worker instead of the main
    // page - a known technique to dodge exactly this kind of page-script network capture (seen
    // on sites running a proof-of-work/anti-bot challenge, which is typically computed off the
    // main thread for performance). window.fetch above only patches the main page; a worker has
    // its own separate self.fetch, and no bridge back to native code at all.
    //
    // This tries to inject the same style of capture hook into same-origin worker scripts before
    // they run, by re-fetching the worker's source, prepending a hook, and constructing the real
    // worker from a Blob URL instead. Capture data is relayed back via a dedicated BroadcastChannel
    // - NOT the worker's own postMessage/onmessage channel - specifically so this never touches,
    // filters, or risks interfering with however the page actually talks to its own worker.
    //
    // Every failure mode (cross-origin script, CORS, module workers, BroadcastChannel missing,
    // any thrown error) falls back to the exact original, completely unmodified Worker - this
    // only ever adds capture on top of working sites, never risks breaking one.
    if (typeof window.Worker !== 'undefined' && typeof window.BroadcastChannel !== 'undefined') {
        try {
            var OrigWorker = window.Worker;
            var CAPTURE_CHANNEL = '__axnet_worker_capture__';
            var listenChannel = new BroadcastChannel(CAPTURE_CHANNEL);
            listenChannel.onmessage = function (e) { try { post(e.data); } catch (err) {} };

            var WORKER_SHIM = [
                '(function(){try{',
                '  var __axC = new BroadcastChannel(' + JSON.stringify(CAPTURE_CHANNEL) + ');',
                '  var __axOF = self.fetch;',
                '  if (typeof __axOF === "function") {',
                '    self.fetch = function(input, init) {',
                '      var url = typeof input === "string" ? input : (input && input.url) || "";',
                '      var method = (init && init.method) || (input && input.method) || "GET";',
                '      var reqBody = (init && init.body) || "";',
                '      var t0 = Date.now();',
                '      return __axOF.apply(self, arguments).then(function(resp) {',
                '        try {',
                '          var rh = {}; resp.headers.forEach(function(v,k){ rh[k]=v; });',
                '          var __ct = resp.headers.get("content-type") || "";',
                '          var __clone = null; try { __clone = resp.clone(); } catch (e) {}',
                '          if (__clone) __clone.text().then(function(bt) {',
                '            try {',
                '              __axC.postMessage({source:"worker-fetch", url:url, method:method, status:resp.status,',
                '                reqBody:String(reqBody).slice(0,4096), reqHeaders:(init && init.headers)||{},',
                '                respBody:bt.slice(0,4096), respHeaders:rh, durationMs:Date.now()-t0,',
                '                mimeType:__ct});',
                '            } catch(e) {}',
                '          }).catch(function(){});',
                '        } catch(e) {}',
                '        return resp;',
                '      });',
                '    };',
                '  }',
                '}catch(e){}})();',
                ''
            ].join('\n');

            window.Worker = function (scriptURL, options) {
                // Module workers can't have arbitrary code prepended (ES module syntax requires
                // import statements at the top of the file) - pass those through untouched.
                if (options && options.type === 'module') return new OrigWorker(scriptURL, options);

                var real = null, ready = false;
                var queuedMessages = [], queuedListeners = [];
                var userOnMessage = null, userOnError = null, userOnMessageError = null;

                function finish(w) {
                    real = w;
                    queuedListeners.forEach(function (l) { real.addEventListener(l[0], l[1], l[2]); });
                    if (userOnMessage) real.onmessage = userOnMessage;
                    if (userOnError) real.onerror = userOnError;
                    if (userOnMessageError) real.onmessageerror = userOnMessageError;
                    queuedMessages.forEach(function (args) { real.postMessage.apply(real, args); });
                    ready = true;
                }

                try {
                    var resolvedUrl = new URL(scriptURL, location.href).toString();
                    fetch(resolvedUrl).then(function (r) {
                        if (!r.ok) throw new Error('fetch failed: ' + r.status);
                        return r.text();
                    }).then(function (src) {
                        var blob = new Blob([WORKER_SHIM + src], { type: 'application/javascript' });
                        var blobUrl = URL.createObjectURL(blob);
                        finish(new OrigWorker(blobUrl, options));
                    }).catch(function () {
                        // Cross-origin, CORS-blocked, network error - fall back untouched, no capture.
                        finish(new OrigWorker(scriptURL, options));
                    });
                } catch (e) {
                    finish(new OrigWorker(scriptURL, options));
                }

                var proxy = {
                    postMessage: function () {
                        if (ready) real.postMessage.apply(real, arguments); else queuedMessages.push(arguments);
                    },
                    terminate: function () { if (ready) real.terminate(); },
                    addEventListener: function (type, fn, opts) {
                        if (ready) real.addEventListener(type, fn, opts); else queuedListeners.push([type, fn, opts]);
                    },
                    removeEventListener: function (type, fn, opts) { if (ready) real.removeEventListener(type, fn, opts); },
                    dispatchEvent: function (evt) { return ready ? real.dispatchEvent(evt) : false; },
                    get onmessage() { return userOnMessage; },
                    set onmessage(fn) { userOnMessage = fn; if (ready) real.onmessage = fn; },
                    get onerror() { return userOnError; },
                    set onerror(fn) { userOnError = fn; if (ready) real.onerror = fn; },
                    get onmessageerror() { return userOnMessageError; },
                    set onmessageerror(fn) { userOnMessageError = fn; if (ready) real.onmessageerror = fn; }
                };
                // So `instanceof Worker` still holds true for code that checks it, even though
                // this is a plain proxy object rather than a real Worker instance under the hood.
                Object.setPrototypeOf(proxy, OrigWorker.prototype);
                return proxy;
            };
            window.Worker.prototype = OrigWorker.prototype;
        } catch (e) { /* leave window.Worker completely untouched on any setup error */ }
    }

    // --- same-origin iframe capture ---
    // A cross-origin iframe (ads, embeds, payment widgets from another domain) is fundamentally
    // inaccessible to page JS - that's the browser's same-origin policy working as intended, not
    // something to work around, and this doesn't try to. A SAME-origin iframe, though, shares JS
    // accessibility with the parent page the same way a same-origin popup would, and today those
    // requests are invisible to the capture above because it only ever ran in the top frame.
    //
    // Every accessible-property read below is wrapped in try/catch: a cross-origin iframe throws
    // a SecurityError on the very first property access (win.fetch), which is caught silently -
    // exactly the same fallback shape as the Worker hook above. This can only ever add capture on
    // top of a working page, never break one, since it never touches iframe navigation or content,
    // only patches functions inside realms it can already read from.
    (function () {
        var patchedWindows = (typeof WeakSet !== 'undefined') ? new WeakSet() : null;
        function alreadyPatched(win) {
            if (!patchedWindows) return false;
            try { return patchedWindows.has(win); } catch (e) { return false; }
        }
        function markPatched(win) {
            if (patchedWindows) { try { patchedWindows.add(win); } catch (e) {} }
        }

        function tryPatchIframe(iframe, depth) {
            if (depth > 4) return; // bound recursion for deeply nested same-origin iframes
            try {
                var win = iframe.contentWindow;
                if (!win || alreadyPatched(win)) return;
                // This line is the actual same-origin check: reading .fetch on a cross-origin
                // contentWindow throws immediately, before any patching happens.
                var probe = win.fetch;
                markPatched(win);
                patchRealm(win);
                scanForIframes(win.document, depth + 1);
            } catch (e) { /* cross-origin or not yet navigated - skip silently */ }
        }

        function scanForIframes(doc, depth) {
            try {
                var frames = doc.querySelectorAll('iframe');
                for (var i = 0; i < frames.length; i++) {
                    var frame = frames[i];
                    tryPatchIframe(frame, depth);
                    // Same-origin iframes still already loaded when this script first ran get
                    // caught by the immediate attempt above; ones that load/navigate afterward
                    // (or weren't same-origin yet at insertion time) get a second chance here.
                    frame.addEventListener('load', function () { tryPatchIframe(this, depth); });
                }
            } catch (e) {}
        }

        try {
            scanForIframes(document, 0);
            if (typeof MutationObserver !== 'undefined') {
                var observer = new MutationObserver(function (mutations) {
                    mutations.forEach(function (m) {
                        m.addedNodes && m.addedNodes.forEach(function (node) {
                            if (!node || node.nodeType !== 1) return;
                            if (node.tagName === 'IFRAME') {
                                tryPatchIframe(node, 0);
                                node.addEventListener('load', function () { tryPatchIframe(this, 0); });
                            } else if (node.querySelectorAll) {
                                scanForIframes(node, 0);
                            }
                        });
                    });
                });
                observer.observe(document.documentElement || document, { childList: true, subtree: true });
            }
        } catch (e) { /* leave the page completely untouched on any setup error */ }
    })();
})();
