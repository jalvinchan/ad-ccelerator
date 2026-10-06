const assert = require('node:assert/strict');
const { test } = require('node:test');
const { readFileSync } = require('node:fs');
const vm = require('node:vm');
const code = readFileSync(require.resolve('../src/gecko-host.js'), 'utf8');

function host(url = 'https://m.youtube.com/watch?v=fixture', framed = false) {
  const pageEvents = new Map();
  const documentEvents = new Map();
  const connections = [];
  const intervals = new Map();
  const effects = [];
  const state = { enabled: false, suspended: false };
  const context = vm.createContext({
    URL,
    location: { href: url, origin: new URL(url).origin },
    YouTubeAdAccelerator: {
      setEnabled(value) { state.enabled = value; effects.push(['enabled', value]); },
      setSuspended(value) { state.suspended = value; effects.push(['suspended', value]); },
      getStatus() { return { enabled: state.enabled, adActive: false, rate: null }; },
    },
    document: {
      hidden: false,
      querySelectorAll: () => [{ pause() { effects.push(['pause']); } }],
      addEventListener: (type, callback) => documentEvents.set(type, callback),
    },
    addEventListener: (type, callback) => pageEvents.set(type, callback),
    setInterval(callback) { const id = intervals.size + 1; intervals.set(id, callback); return id; },
    clearInterval(id) { intervals.delete(id); },
    browser: { runtime: { connectNative(name) {
      assert.equal(name, 'accelerator');
      const connection = {
        onMessage: { addListener(callback) { connection.receive = callback; } },
        onDisconnect: { addListener(callback) { connection.closed = callback; } },
        postMessage(message) { effects.push(['report', JSON.parse(JSON.stringify(message))]); },
        disconnect() { connection.closed(); },
      };
      connections.push(connection);
      return connection;
    } } },
  });
  vm.runInContext('globalThis.window = {}', context);
  vm.runInContext(framed ? 'window.top = {}' : 'window.top = window', context);
  vm.runInContext(code, context);
  return {
    context, state, effects, connections, intervals,
    send(message) { connections.at(-1).receive(message); },
    page(type) { pageEvents.get(type)(); },
    visibility(hidden) { context.document.hidden = hidden; documentEvents.get('visibilitychange')(); },
  };
}
const active = revision => ({ type: 'state', enabled: true, paused: false, revision });

test('extension stays suspended until a valid native state arrives; reinjection does not duplicate connections', () => {
  const current = host();
  assert.deepEqual(current.state, { enabled: false, suspended: true });
  for (const message of [null, {}, { ...active(1), enabled: 'true' }, active(1.1), { ...active(1), type: 'eval' }]) {
    current.send(message);
    assert.deepEqual(current.state, { enabled: false, suspended: true });
  }
  current.send(active(1));
  assert.deepEqual(current.state, { enabled: true, suspended: false });
  vm.runInContext(code, current.context);
  assert.equal(current.connections.length, 1);
});

test('native pause is acknowledged after suspension and media pause, and stale state cannot reactivate it', () => {
  const current = host();
  current.send(active(1));
  current.effects.length = 0;
  current.send({ ...active(2), paused: true });
  assert.deepEqual(current.effects.map(effect => effect[0]), ['suspended', 'enabled', 'pause', 'report']);
  assert.equal(current.effects.at(-1)[1].paused, true);
  assert.equal(current.effects.at(-1)[1].revision, 2);
  current.send(active(1));
  current.send(active(2));
  current.visibility(false);
  assert.equal(current.state.suspended, true);
  current.send(active(3));
  assert.equal(current.state.suspended, false);
  assert.equal(current.effects.at(-1)[0], 'report'); // Never calls video.play().
});

test('hidden documents remain suspended despite native resume; disconnect disables and stops reporting', () => {
  const current = host();
  current.send(active(1));
  current.visibility(true);
  current.send(active(2));
  assert.equal(current.state.suspended, true);
  current.visibility(false);
  assert.equal(current.state.suspended, false);
  current.connections[0].disconnect();
  assert.deepEqual(current.state, { enabled: false, suspended: true });
  assert.equal(current.intervals.size, 0);
  current.connections[0].receive(active(3));
  assert.equal(current.state.enabled, false);
});

test('page restoration reconnects and waits for host state instead of reviving previous acceleration', () => {
  const current = host();
  current.send(active(5));
  current.page('pagehide');
  current.page('pageshow');
  assert.equal(current.connections.length, 2);
  assert.equal(current.intervals.size, 1);
  assert.deepEqual(current.state, { enabled: false, suspended: true });
  current.send(active(6));
  assert.equal(current.state.enabled, true);
});

test('no native connection from other origins, unusual ports, credential URLs, or subframes', () => {
  for (const url of ['https://accounts.google.com/', 'https://m.youtube.com.evil.test/',
    'http://m.youtube.com/', 'https://m.youtube.com:8443/', 'https://user:pass@m.youtube.com/']) {
    assert.equal(host(url).connections.length, 0);
  }
  assert.equal(host('https://m.youtube.com/', true).connections.length, 0);
});
