(function () {
    if (window.__axNetCapture) return;
    window.__axNetCapture = true;
    if (typeof window.AxNet === 'undefined') return; // native bridge not attached

    function post(obj) {
        try { window.AxNet.log(JSON.stringify(obj)); } catch (e) {}
    }

    function trunc(s) {
        if (typeof s !== 'string') return '';
        return s.length > 4096 ? s.slice(0, 4096) + '…[truncated]' : s;
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

    // --- fetch() ---
    // Wrapped in a function so the exact same hook can be applied to any same-origin realm
    // (the top page, and now also same-origin iframes below) - not just `window`.
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

            return origFetch.apply(this, arguments).then(function (res) {
                var clone;
                try { clone = res.clone(); } catch (e) { clone = null; }
                var respBodyPromise = clone ? clone.text().catch(function () { return ''; }) : Promise.resolve('');
                Promise.all([respBodyPromise, reqBodyPromise]).then(function (results) {
                    var respText = results[0];
                    var reqBodyText = results[1];
                    var headers = {};
                    try { res.headers.forEach(function (v, k) { headers[k] = v; }); } catch (e) {}
                    post({
                        source: 'fetch', url: url, method: method,
                        status: res.status, reqBody: trunc(reqBodyText || ''), reqHeaders: reqHeaders,
                        respBody: trunc(respText || ''),
                        respHeaders: headers, durationMs: Date.now() - start,
                        type: res.headers && res.headers.get ? (res.headers.get('content-type') || '') : ''
                    });
                });
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
        XHR.prototype.open = function (method, url) {
            this.__ax = { method: method, url: url, start: 0, reqHeaders: {} };
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
                    var respBody = '';
                    try {
                        if (xhr.responseType === '' || xhr.responseType === 'text') respBody = xhr.responseText;
                    } catch (e) {}
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
            return origSend.apply(this, arguments);
        };
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

    function patchRealm(win) {
        try { patchFetch(win); } catch (e) {}
        try { patchXhr(win); } catch (e) {}
        try { patchWebSocket(win); } catch (e) {}
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
                '          resp.clone().text().then(function(bt) {',
                '            try {',
                '              __axC.postMessage({source:"worker-fetch", url:url, method:method, status:resp.status,',
                '                reqBody:String(reqBody).slice(0,4096), reqHeaders:(init && init.headers)||{},',
                '                respBody:bt.slice(0,4096), respHeaders:rh, durationMs:Date.now()-t0,',
                '                mimeType:resp.headers.get("content-type")||""});',
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
