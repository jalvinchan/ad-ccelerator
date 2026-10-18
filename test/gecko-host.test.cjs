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
    YouTubePlayerControls(controller, open, save) {
      state.openSelector = open;
      state.savePosition = save;
      state.positions = [];
      return { update() {}, hide() {}, setPosition(...args) { state.positions.push(args); } };
    },
    YouTubeAdAccelerator: {
      setEnabled(value) { state.enabled = value; effects.push(['enabled', value]); },
      setSuspended(value) { state.suspended = value; effects.push(['suspended', value]); },
      setContentRate(rate, context) { effects.push(['speed', rate, context]);
        return context === 'current' && [0.25, 0.5, 0.75, 1, 1.25, 1.5, 1.75, 2, 2.25, 3, 4].includes(rate)
          ? {accepted: true, pending: false} : {accepted: false, error: 'stale-context'}; },
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
  assert.deepEqual({enabled:current.state.enabled, suspended:current.state.suspended}, { enabled: false, suspended: true });
  for (const message of [null, {}, { ...active(1), enabled: 'true' }, active(1.1), { ...active(1), type: 'eval' }]) {
    current.send(message);
    assert.deepEqual({enabled:current.state.enabled, suspended:current.state.suspended}, { enabled: false, suspended: true });
  }
  current.send(active(1));
  assert.deepEqual({enabled:current.state.enabled, suspended:current.state.suspended}, { enabled: true, suspended: false });
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
  assert.deepEqual({enabled:current.state.enabled, suspended:current.state.suspended}, { enabled: false, suspended: true });
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
  assert.deepEqual({enabled:current.state.enabled, suspended:current.state.suspended}, { enabled: false, suspended: true });
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

test('speed messages require live revision, typed values, and unique command IDs; rejection is explicit', () => {
  const current = host();
  current.send(active(2));
  const command = {type:'content-speed', revision:2, commandId:1, videoContext:'current', rate:2};
  for (const invalid of [{...command, revision:1}, {...command, rate:'2'}, {...command, commandId:1.5},
    {...command, videoContext:{}}, {...command, videoContext:'x'.repeat(201)}]) current.send(invalid);
  assert.equal(current.effects.filter(effect => effect[0] === 'speed').length, 0);
  current.send(command);
  current.send(command);
  assert.equal(current.effects.filter(effect => effect[0] === 'speed').length, 1);
  current.send({...command, commandId:2, videoContext:'old'});
  assert.equal(current.effects.filter(effect => effect[1]?.type === 'speed-result').at(-1)[1].accepted, false);
  current.send({...active(3), paused:true});
  current.send({...command, revision:3, commandId:3});
  assert.equal(current.effects.filter(effect => effect[0] === 'speed').length, 2);
});


test('video chip requests the native selector only while the host is active', () => {
  const current = host();
  current.state.openSelector('current');
  assert.equal(current.effects.filter(effect => effect[1]?.type === 'open-speed-selector').length, 0);
  current.send(active(7));
  current.state.openSelector('current');
  const request = current.effects.at(-1)[1];
  assert.equal(request.type, 'open-speed-selector');
  assert.equal(request.revision, 7);
  assert.equal(request.videoContext, 'current');
  assert.equal(request.url, 'https://m.youtube.com/watch?v=fixture');
  current.visibility(true);
  current.state.openSelector('current');
  assert.equal(current.effects.filter(effect => effect[1]?.type === 'open-speed-selector').length, 1);
});

test('chip position syncs both ways with typed fractions only', () => {
  const current = host();
  current.state.savePosition(0.25, 1);
  assert.equal(current.effects.filter(effect => effect[1]?.type === 'chip-position').length, 0);
  current.send(active(3));
  current.state.savePosition(0.25, 1);
  assert.deepEqual(current.effects.at(-1)[1], { type: 'chip-position', x: 0.25, y: 1, revision: 3,
    url: 'https://m.youtube.com/watch?v=fixture' });
  for (const [x, y] of [[0.5, 0], [null, null], ['0.5', 0], [1.5, 0], [0.5, undefined], [null, 0]]) {
    current.send({ type: 'chip-position', x, y });
  }
  assert.deepEqual(current.state.positions.map(args => JSON.parse(JSON.stringify(args))), [[0.5, 0], [null]]);
});
