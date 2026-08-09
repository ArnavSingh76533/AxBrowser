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
    var origFetch = window.fetch;
    if (origFetch) {
        window.fetch = function (input, init) {
            var start = Date.now();
            var url = (typeof input === 'string') ? input : (input && input.url) || '';
            var method = (init && init.method) || (input && input.method) || 'GET';
            var reqBody = (init && init.body) ? trunc(String(init.body)) : '';
            var reqHeaders = headersToMap(init && init.headers);
            if (input && typeof input !== 'string' && input.headers) {
                var reqHdrsFromRequest = headersToMap(input.headers);
                for (var k in reqHdrsFromRequest) if (!(k in reqHeaders)) reqHeaders[k] = reqHdrsFromRequest[k];
            }
            return origFetch.apply(this, arguments).then(function (res) {
                var clone;
                try { clone = res.clone(); } catch (e) { clone = null; }
                var report = function (body) {
                    var headers = {};
                    try { res.headers.forEach(function (v, k) { headers[k] = v; }); } catch (e) {}
                    post({
                        source: 'fetch', url: url, method: method,
                        status: res.status, reqBody: reqBody, reqHeaders: reqHeaders,
                        respBody: trunc(body || ''),
                        respHeaders: headers, durationMs: Date.now() - start,
                        type: res.headers && res.headers.get ? (res.headers.get('content-type') || '') : ''
                    });
                };
                if (clone) { clone.text().then(report).catch(function () { report(''); }); }
                else { report(''); }
                return res;
            }).catch(function (err) {
                post({ source: 'fetch', url: url, method: method, status: 0,
                    reqBody: reqBody, reqHeaders: reqHeaders, error: String(err), durationMs: Date.now() - start });
                throw err;
            });
        };
    }

    // --- XMLHttpRequest ---
    var origOpen = XMLHttpRequest.prototype.open;
    var origSend = XMLHttpRequest.prototype.send;
    var origSetHeader = XMLHttpRequest.prototype.setRequestHeader;
    XMLHttpRequest.prototype.open = function (method, url) {
        this.__ax = { method: method, url: url, start: 0, reqHeaders: {} };
        return origOpen.apply(this, arguments);
    };
    XMLHttpRequest.prototype.setRequestHeader = function (name, value) {
        if (this.__ax) this.__ax.reqHeaders[name] = value;
        return origSetHeader.apply(this, arguments);
    };
    XMLHttpRequest.prototype.send = function (body) {
        var xhr = this;
        if (xhr.__ax) {
            xhr.__ax.start = Date.now();
            xhr.__ax.reqBody = body ? trunc(String(body)) : '';
            xhr.addEventListener('loadend', function () {
                var respBody = '';
                try {
                    if (xhr.responseType === '' || xhr.responseType === 'text') respBody = xhr.responseText;
                } catch (e) {}
                post({
                    source: 'xhr', url: xhr.__ax.url, method: xhr.__ax.method,
                    status: xhr.status, reqBody: xhr.__ax.reqBody, reqHeaders: xhr.__ax.reqHeaders,
                    respBody: trunc(respBody),
                    respHeaders: parseHeaders(xhr.getAllResponseHeaders()),
                    durationMs: Date.now() - xhr.__ax.start,
                    type: xhr.getResponseHeader ? (xhr.getResponseHeader('content-type') || '') : ''
                });
            });
        }
        return origSend.apply(this, arguments);
    };

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
    var OrigWebSocket = window.WebSocket;
    if (OrigWebSocket) {
        window.WebSocket = function (url, protocols) {
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
        window.WebSocket.prototype = OrigWebSocket.prototype;
        window.WebSocket.CONNECTING = OrigWebSocket.CONNECTING;
        window.WebSocket.OPEN = OrigWebSocket.OPEN;
        window.WebSocket.CLOSING = OrigWebSocket.CLOSING;
        window.WebSocket.CLOSED = OrigWebSocket.CLOSED;
    }
})();
