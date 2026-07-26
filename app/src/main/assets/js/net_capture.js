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

    // --- fetch() ---
    var origFetch = window.fetch;
    if (origFetch) {
        window.fetch = function (input, init) {
            var start = Date.now();
            var url = (typeof input === 'string') ? input : (input && input.url) || '';
            var method = (init && init.method) || (input && input.method) || 'GET';
            var reqBody = (init && init.body) ? trunc(String(init.body)) : '';
            return origFetch.apply(this, arguments).then(function (res) {
                var clone;
                try { clone = res.clone(); } catch (e) { clone = null; }
                var report = function (body) {
                    var headers = {};
                    try { res.headers.forEach(function (v, k) { headers[k] = v; }); } catch (e) {}
                    post({
                        source: 'fetch', url: url, method: method,
                        status: res.status, reqBody: reqBody, respBody: trunc(body || ''),
                        respHeaders: headers, durationMs: Date.now() - start,
                        type: res.headers && res.headers.get ? (res.headers.get('content-type') || '') : ''
                    });
                };
                if (clone) { clone.text().then(report).catch(function () { report(''); }); }
                else { report(''); }
                return res;
            }).catch(function (err) {
                post({ source: 'fetch', url: url, method: method, status: 0,
                    reqBody: reqBody, error: String(err), durationMs: Date.now() - start });
                throw err;
            });
        };
    }

    // --- XMLHttpRequest ---
    var origOpen = XMLHttpRequest.prototype.open;
    var origSend = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function (method, url) {
        this.__ax = { method: method, url: url, start: 0 };
        return origOpen.apply(this, arguments);
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
                    status: xhr.status, reqBody: xhr.__ax.reqBody, respBody: trunc(respBody),
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
})();
