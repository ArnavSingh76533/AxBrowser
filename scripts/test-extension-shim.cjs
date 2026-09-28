const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../feature/feature-browser/src/main/assets/extensions/chrome_bridge.js'), 'utf8');
function context(handler = () => ({result: null})) {
  const requests = [];
  const bridge = {postMessage(raw) {
    const request = JSON.parse(raw); requests.push(request);
    const response = handler(request);
    if (response) queueMicrotask(() => bridge.onmessage({data: JSON.stringify({id: request.id, ...response})}));
  }};
  const sandbox = vm.createContext({AxExtension: bridge, URL, console, setTimeout: (fn, ms) => { const t = setTimeout(fn, ms); t.unref(); return t; }, clearTimeout});
  vm.runInContext(source.replace('__AX_CONFIG__', JSON.stringify({id: 'a'.repeat(32), origin: 'https://test.ax-extension.invalid', locale: 'en', manifest: {manifest_version: 3, name: 'Sample'}, messages: {greeting: {message: 'Hello $USER$', placeholders: {user: {content: '$1'}}}}})), sandbox);
  return {sandbox, chrome: sandbox.chrome, requests, bridge};
}
test('Promise API sends versioned requests without caller-supplied extension identity', async () => {
  const {chrome, requests} = context(r => ({result: r.method === 'storage.local.get' ? {theme: 'dark'} : null}));
  assert.equal((await chrome.storage.local.get('theme')).theme, 'dark');
  const request = requests.find(r => r.method === 'storage.local.get');
  assert.equal(request.v, 1); assert.equal(request.extensionId, undefined); assert.deepEqual(request.args, ['theme']);
});
test('runtime.lastError exists only during failed callback', async () => {
  const {chrome} = context(() => ({error: {message: 'Permission required'}}));
  await new Promise(resolve => chrome.tabs.get(1, result => {
    assert.equal(result, undefined); assert.equal(chrome.runtime.lastError.message, 'Permission required'); resolve();
  }));
  assert.equal(chrome.runtime.lastError, undefined);
});
test('unsupported APIs reject without a native dispatch', async () => {
  const {chrome, requests} = context();
  await assert.rejects(chrome.webRequest.getBlocked(), /Unsupported Chrome API/);
  assert.equal(requests.some(r => r.method === 'webRequest.getBlocked'), false);
});
test('manifest getter is synchronous and returns a copy', () => {
  const {chrome} = context(); const manifest = chrome.runtime.getManifest(); manifest.name = 'changed';
  assert.equal(chrome.runtime.getManifest().name, 'Sample');
  assert.equal(chrome.runtime.getURL('popup.html'), 'https://test.ax-extension.invalid/popup.html');
});
test('event listeners can be removed and do not receive duplicate registration', async () => {
  const {chrome, bridge} = context(); let count = 0; const fn = () => count++;
  chrome.tabs.onActivated.addListener(fn); chrome.tabs.onActivated.addListener(fn);
  await bridge.onmessage({data: JSON.stringify({event: 'tabs.onActivated', args: [{tabId: 1}]})});
  assert.equal(count, 1); chrome.tabs.onActivated.removeListener(fn);
  assert.equal(chrome.tabs.onActivated.hasListeners(), false);
});
test('runtime messages accept asynchronous listener responses', async () => {
  const {chrome, bridge, requests} = context();
  chrome.runtime.onMessage.addListener(async message => ({answer: message.question + 1}));
  await bridge.onmessage({data: JSON.stringify({event: 'runtime.onMessage', args: [{question: 41}, {}], token: 'native-token'})});
  await new Promise(setImmediate);
  assert.deepEqual(requests.find(r => r.method === 'runtime.__respond').args, ['native-token', {answer: 42}]);
});
test('executeScript preserves function source and arguments', async () => {
  const {chrome, requests} = context();
  await chrome.scripting.executeScript({target: {tabId: 2}, func: x => x + 1, args: [2]});
  const details = requests.find(r => r.method === 'scripting.executeScript').args[0];
  assert.equal(typeof details.func, 'string'); assert.deepEqual(details.args, [2]);
});
test('i18n resolves case-insensitive named placeholders', () => {
  const {chrome} = context(); assert.equal(chrome.i18n.getMessage('greeting', ['Ax']), 'Hello Ax');
  assert.equal(chrome.i18n.getMessage('missing'), '');
});
test('cross-extension messaging is rejected locally', async () => {
  const {chrome} = context();
  await assert.rejects(chrome.runtime.sendMessage('b'.repeat(32), {hello: true}), /External extension messaging/);
});
test('unsupported events fail explicitly at registration', () => {
  const {chrome} = context();
  assert.throws(() => chrome.webRequest.onBeforeRequest.addListener(() => {}), /Unsupported Chrome event/);
});
test('the injected URL matcher preserves host boundaries and literal query characters', () => {
  const kotlin = fs.readFileSync(path.join(__dirname, '../feature/feature-browser/src/main/kotlin/com/akay/feature/browser/extensions/ContentScriptManager.kt'), 'utf8');
  const matcher = kotlin.match(/private val MATCH_JS = """([\s\S]*?)"""/)[1].replaceAll("${'$'}", '$');
  function match(url, pattern) {
    const sandbox = vm.createContext({location: {href: url}, URL});
    vm.runInContext(matcher + ';globalThis.result=matches(' + JSON.stringify(pattern) + ')', sandbox);
    return sandbox.result;
  }
  assert.equal(match('https://a.example.com/path?a=1', 'https://*.example.com/path?a=*'), true);
  assert.equal(match('https://a.example.com/pathXa=1', 'https://*.example.com/path?a=*'), false);
  assert.equal(match('https://example.com.evil.test/path', 'https://*.example.com/*'), false);
  assert.equal(match('file:///secret', '<all_urls>'), false);
});
