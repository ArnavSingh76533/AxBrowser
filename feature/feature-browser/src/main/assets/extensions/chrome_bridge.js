(function (config) {
  'use strict';
  if (globalThis.__axExtensionLoaded) return;
  globalThis.__axExtensionLoaded = true;
  const transport = globalThis.AxExtension;
  const pending = new Map(), events = new Map();
  let sequence = 0;
  function event(name) {
    if (events.has(name)) return events.get(name);
    const listeners = new Set();
    const value = {
      addListener(fn) { if (typeof fn !== 'function') throw new TypeError('Expected listener'); listeners.add(fn); },
      removeListener(fn) { listeners.delete(fn); },
      hasListener(fn) { return listeners.has(fn); },
      hasListeners() { return listeners.size > 0; },
      _listeners: listeners
    };
    events.set(name, value); return value;
  }
  function request(method, args) {
    return new Promise((resolve, reject) => {
      const id = String(++sequence);
      const timer = setTimeout(() => { pending.delete(id); reject(new Error(method + ': response timed out')); }, 30000);
      pending.set(id, {resolve, reject, timer});
      try { transport.postMessage(JSON.stringify({v: 1, id, method, args})); }
      catch (error) { clearTimeout(timer); pending.delete(id); reject(error); }
    });
  }
  const chrome = {};
  function callbackResult(promise, cb) {
    if (!cb) return promise;
    promise.then(value => cb(value), error => {
      chrome.runtime.lastError = {message: error.message};
      try { cb(); } finally { delete chrome.runtime.lastError; }
    });
  }
  function method(name) {
    return (...args) => {
      const cb = typeof args[args.length - 1] === 'function' ? args.pop() : null;
      return callbackResult(request(name, args), cb);
    };
  }
  function namespace(name, methods, eventNames) {
    const object = {};
    methods.split(' ').filter(Boolean).forEach(key => { object[key] = method(name + '.' + key); });
    (eventNames || '').split(' ').filter(Boolean).forEach(key => { object[key] = event(name + '.' + key); });
    return new Proxy(object, {get(target, key) {
      if (key in target || typeof key !== 'string' || key === 'then' || key === 'lastError' || key.startsWith('_')) return target[key];
      if (key.startsWith('on')) return {addListener() { throw new Error('Unsupported Chrome event: ' + name + '.' + key); }, removeListener() {}, hasListener() { return false; }, hasListeners() { return false; }};
      return (...args) => {
        const cb = typeof args[args.length - 1] === 'function' ? args.pop() : null;
        return callbackResult(Promise.reject(new Error('Unsupported Chrome API: ' + name + '.' + key)), cb);
      };
    }});
  }
  chrome.runtime = namespace('runtime', 'sendMessage openOptionsPage getPlatformInfo', 'onInstalled onStartup onMessage onSuspend');
  chrome.runtime.id = config.id;
  chrome.runtime.getManifest = () => JSON.parse(JSON.stringify(config.manifest));
  chrome.runtime.getURL = path => new URL(String(path || '').replace(/^\/+/, ''), config.origin + '/').href;
  chrome.runtime.sendMessage = (...args) => {
    const cb = typeof args[args.length - 1] === 'function' ? args.pop() : null;
    if (typeof args[0] === 'string' && args.length > 1 && /^[a-p]{32}$/.test(args[0])) {
      if (args.shift() !== config.id) return callbackResult(Promise.reject(new Error('External extension messaging is not supported')), cb);
    }
    return callbackResult(request('runtime.sendMessage', [args[0]]), cb);
  };
  chrome.storage = {onChanged: event('storage.onChanged')};
  ['local', 'session', 'sync'].forEach(area => { chrome.storage[area] = namespace('storage.' + area, 'get set remove clear getBytesInUse'); });
  chrome.tabs = namespace('tabs', 'query get create update remove reload sendMessage', 'onCreated onUpdated onRemoved onActivated');
  chrome.scripting = namespace('scripting', 'insertCSS removeCSS');
  chrome.scripting.executeScript = (details, cb) => {
    const payload = Object.assign({}, details);
    if (payload.func) { payload.func = Function.prototype.toString.call(payload.func); }
    return callbackResult(request('scripting.executeScript', [payload]), cb);
  };
  chrome.cookies = namespace('cookies', 'get getAll set remove', 'onChanged');
  chrome.windows = namespace('windows', 'get getCurrent getLastFocused getAll');
  chrome.downloads = namespace('downloads', 'download search pause resume cancel', 'onCreated onChanged');
  chrome.notifications = namespace('notifications', 'create clear', 'onClicked onClosed');
  chrome.contextMenus = namespace('contextMenus', 'update remove removeAll', 'onClicked');
  chrome.contextMenus.create = (details, cb) => {
    const id = details.id || ('menu-' + (++sequence));
    callbackResult(request('contextMenus.create', [Object.assign({}, details, {id})]), cb || (() => {}));
    return id;
  };
  chrome.permissions = namespace('permissions', 'getAll contains request remove', 'onAdded onRemoved');
  chrome.action = namespace('action', 'setBadgeText getBadgeText setTitle getTitle setPopup getPopup setBadgeBackgroundColor', 'onClicked');
  chrome.commands = namespace('commands', 'getAll', 'onCommand');
  chrome.history = namespace('history', 'search addUrl deleteUrl deleteAll', 'onVisited onVisitRemoved');
  chrome.bookmarks = namespace('bookmarks', 'get search getTree create update remove', 'onCreated onChanged onRemoved');
  chrome.webNavigation = namespace('webNavigation', '', 'onBeforeNavigate onCompleted onErrorOccurred');
  chrome.i18n = {
    getUILanguage: () => config.locale,
    getMessage(name, substitutions) {
      if (name === '@@extension_id') return config.id;
      if (name === '@@ui_locale') return config.locale;
      const entry = config.messages[name]; if (!entry) return '';
      const args = Array.isArray(substitutions) ? substitutions : [substitutions];
      let message = entry.message || '';
      message = message.replace(/\$([a-z_]+)\$/gi, (_, key) => {
        const placeholders = entry.placeholders || {};
        const actual = Object.keys(placeholders).find(p => p.toLowerCase() === key.toLowerCase());
        return actual ? placeholders[actual].content : '';
      });
      return message.replace(/\$([1-9])/g, (_, n) => String(args[Number(n) - 1] ?? '')).replace(/\$\$/g, '$');
    }
  };
  globalThis.chrome = new Proxy(chrome, {get(target, key) {
    if (key in target || typeof key !== 'string' || key === 'then') return target[key];
    return namespace(key, '');
  }});
  globalThis.browser = globalThis.chrome;
  transport.onmessage = async function (eventObject) {
    const message = JSON.parse(eventObject.data);
    if (message.id) {
      const call = pending.get(message.id); if (!call) return;
      pending.delete(message.id); clearTimeout(call.timer);
      if (message.error) call.reject(new Error(message.error.message)); else call.resolve(message.result);
      return;
    }
    if (message.event === 'runtime.onMessage') {
      let sent = false, asyncResponse = false;
      const respond = value => {
        if (sent) return; sent = true;
        request('runtime.__respond', [message.token, value === undefined ? null : value]).catch(() => {});
      };
      for (const listener of [...event('runtime.onMessage')._listeners]) {
        try {
          const result = listener(message.args[0], message.args[1], respond);
          if (result === true) asyncResponse = true;
          else if (result && typeof result.then === 'function') { asyncResponse = true; result.then(respond, () => respond()); }
        } catch (_) { /* Other listeners still receive the message. */ }
      }
      if (!asyncResponse && !sent) respond();
      return;
    }
    const value = events.get(message.event);
    if (value) for (const listener of [...value._listeners]) {
      try { listener(...(message.args || [])); } catch (error) { console.error(error); }
    }
  };
  // These helpers exist only in this extension's isolated world / private extension WebView.
  globalThis.__axCompleteExecution = (token, result, error) => request('runtime.__executionResult', [token, result === undefined ? null : result, error || null]);
  globalThis.__axBackgroundReady = () => request('runtime.__backgroundReady', []);
  request('runtime.__ready', []).catch(error => console.error(error.message));
})(__AX_CONFIG__);
