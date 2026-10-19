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
    YouTubePlayerControls(controller, open, save, changed) {
      state.openSelector = open;
      state.savePosition = save;
      state.positions = [];
      state.controlsChanged = changed;
      return { update() { if (state.reenter) state.controlsChanged(true); }, hide() {}, controlsShown: () => state.controls ?? null,
        setPosition(...args) { state.positions.push(args); },
        setSeekInterval(seconds) { (state.seekIntervals ??= []).push(seconds); } };
    },
    YouTubeAdAccelerator: {
      setEnabled(value) { state.enabled = value; effects.push(['enabled', value]); },
      setSuspended(value) { state.suspended = value; effects.push(['suspended', value]); },
      setContentRate(rate, context) { effects.push(['speed', rate, context]);
        return context === 'current' && rate >= 0.25 && rate <= 4
          ? {accepted: true, pending: false} : {accepted: false, error: 'stale-context'}; },
      seekBy(seconds, context) { effects.push(['seek', seconds, context]); return {accepted: true}; },
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
    setTimeout() { return 0; },
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
    pageListener(type) { return pageEvents.get(type); },
    documentListener(type) { return documentEvents.get(type); },
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


test('seek commands need live revision and a valid interval; interval updates reach the chip', () => {
  const current = host();
  const seeks = () => current.effects.filter(effect => effect[0] === 'seek');
  const seek = {type:'seek', revision:6, videoContext:'current', seconds:10};
  current.send(active(6));
  current.send({...seek, seconds:-15});
  assert.deepEqual(seeks().at(-1), ['seek', -15, 'current']);
  for (const invalid of [{...seek, revision:5}, {...seek, seconds:7}, {...seek, seconds:'10'},
    {...seek, videoContext:1}, {...seek, videoContext:'x'.repeat(201)}]) {
    const before = seeks().length;
    current.send(invalid);
    assert.equal(seeks().length, before);
  }
  current.visibility(true);
  const before = seeks().length;
  current.send(seek);
  assert.equal(seeks().length, before);
  current.send({type:'seek-interval', seconds:30});
  current.send({type:'seek-interval', seconds:12});
  current.send({type:'seek-interval', seconds:'5'});
  assert.deepEqual(current.state.seekIntervals, [30]);
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

test('status carries YouTube control visibility and reports promptly when it changes', () => {
  const current = host();
  current.send(active(4));
  assert.equal(current.effects.at(-1)[1].controlsVisible, null);
  current.state.controls = true;
  current.state.controlsChanged(true);
  assert.equal(current.effects.at(-1)[1].type, 'status');
  assert.equal(current.effects.at(-1)[1].controlsVisible, true);
});

test('re-entrant control changes do not duplicate status reports', () => {
  const current = host();
  current.send(active(4));
  const before = current.effects.filter(effect => effect[0] === 'report' && effect[1].type === 'status').length;
  current.state.reenter = true;
  current.state.controls = true;
  current.state.controlsChanged(true);
  const statuses = current.effects.filter(effect => effect[0] === 'report' && effect[1].type === 'status');
  assert.equal(statuses.length, before + 1);
  assert.equal(statuses.at(-1)[1].controlsVisible, true);
});

test('unchanged status is resent only as a five-second heartbeat', () => {
  const current = host();
  current.send(active(5));
  const statuses = () => current.effects.filter(effect => effect[0] === 'report' && effect[1].type === 'status').length;
  const tick = () => [...current.intervals.values()][0]();
  const before = statuses();
  for (let i = 0; i < 4; i++) tick();
  assert.equal(statuses(), before);
  tick();
  assert.equal(statuses(), before + 1);
  current.state.controls = true;
  tick();
  assert.equal(statuses(), before + 2); // A change is sent at once.
});


test('links are rerouted between layers only after the host names this page\'s layer', () => {
  const click = (current, href, extra = {}) => {
    const event = { button: 0, defaultPrevented: false, target: { closest: () => ({ href }) },
      preventDefault() { this.defaultPrevented = true; }, stopImmediatePropagation() {}, ...extra };
    current.context.click(event);
    return event.defaultPrevented;
  };
  const browse = host('https://m.youtube.com/');
  browse.context.click = event => browse.pageListener('click')(event);
  browse.send(active(1));
  assert.equal(click(browse, 'https://m.youtube.com/watch?v=abc'), false); // No layer named yet.
  browse.send({ type: 'layer', watch: false, mini: false });
  browse.effects.length = 0;
  assert.equal(click(browse, 'https://m.youtube.com/watch?v=abc'), true);
  assert.deepEqual(browse.effects.at(-1)[1], { type: 'open-watch', target: 'https://m.youtube.com/watch?v=abc',
    revision: 1, url: 'https://m.youtube.com/' });
  for (const href of ['https://m.youtube.com/shorts/abc', 'https://m.youtube.com/@channel', 'https://www.youtube.com/watch?v=abc'])
    assert.equal(click(browse, href), false, href);
  assert.equal(click(browse, 'https://m.youtube.com/watch?v=abc', { button: 1 }), false);

  const watch = host('https://m.youtube.com/watch?v=one');
  watch.context.click = event => watch.pageListener('click')(event);
  watch.send(active(4));
  watch.send({ type: 'layer', watch: true, mini: false });
  watch.effects.length = 0;
  for (const href of ['https://m.youtube.com/watch?v=two', 'https://m.youtube.com/watch?v=one#comments'])
    assert.equal(click(watch, href), false, href);
  assert.equal(click(watch, 'https://m.youtube.com/@channel'), true);
  assert.deepEqual(watch.effects.at(-1)[1], { type: 'open-browse', target: 'https://m.youtube.com/@channel',
    revision: 4, url: 'https://m.youtube.com/watch?v=one' });
  watch.send({ ...active(5), paused: true });
  assert.equal(click(watch, 'https://m.youtube.com/@channel'), false); // Paused pages never navigate.
});

test('mini layer hides the in-page chip and applies the mini player style', () => {
  const classes = new Set();
  const appended = [];
  const current = host();
  Object.assign(current.context.document, {
    documentElement: { classList: { toggle: (name, on) => on ? classes.add(name) : classes.delete(name),
      remove: name => classes.delete(name) } },
    head: { append: node => appended.push(node) },
    getElementById: id => appended.find(node => node.id === id) ?? null,
    createElement: () => ({}),
  });
  current.send(active(1));
  current.send({ type: 'layer', watch: true, mini: true });
  assert.equal(classes.has('yt-accelerator-mini'), true);
  assert.equal(appended.length, 1);
  current.send({ type: 'layer', watch: true, mini: false });
  current.send({ type: 'layer', watch: true, mini: true });
  assert.equal(appended.length, 1);
  current.send({ type: 'layer', watch: false, mini: true }); // Only the player layer can be mini.
  assert.equal(classes.has('yt-accelerator-mini'), false);
  current.send({ type: 'layer', watch: true, mini: true });
  current.connections[0].disconnect();
  assert.equal(classes.has('yt-accelerator-mini'), false);
});

test('mini player play/pause commands need the live revision and the mini layer; status carries playback', () => {
  const current = host();
  const video = { paused: false, ended: false, play() { this.paused = false; current.effects.push(['play']); },
    pause() { this.paused = true; current.effects.push(['paused']); } };
  current.context.YouTubeAdAccelerator.getPlayer = () => ({ video });
  current.send(active(2));
  assert.equal(current.effects.filter(effect => effect[0] === 'report').at(-1)[1].playing, true);
  current.send({ type: 'layer', watch: true, mini: false });
  current.effects.length = 0;
  current.send({ type: 'playback', play: false, revision: 2 }); // Full player: YouTube's own controls only.
  assert.equal(video.paused, false);
  current.send({ type: 'layer', watch: true, mini: true });
  for (const message of [{ type: 'playback', play: false, revision: 1 }, { type: 'playback', play: 'no', revision: 2 }])
    current.send(message);
  assert.equal(video.paused, false);
  current.send({ type: 'playback', play: false, revision: 2 });
  assert.equal(video.paused, true);
  current.send({ type: 'playback', play: true, revision: 2 });
  assert.equal(video.paused, false);
});

test('a video playing when the player changes size is resumed if the resize pauses it', () => {
  const current = host();
  const timers = [];
  current.context.setTimeout = callback => timers.push(callback);
  const video = { paused: false, ended: false, play() { this.paused = false; current.effects.push(['play']); } };
  current.context.YouTubeAdAccelerator.getPlayer = () => ({ video });
  current.send(active(1));
  current.send({ type: 'layer', watch: true, mini: false });
  current.send({ type: 'layer', watch: true, mini: true });
  video.paused = true;
  current.effects.length = 0;
  current.documentListener('pause')({ target: video });
  assert.deepEqual(current.effects[0], ['play']);
  video.paused = true;
  timers.forEach(callback => callback());
  assert.equal(video.paused, false);
});

test('mini player playback goes through YouTube\'s player API when it exists, so YouTube does not re-pause it', () => {
  const current = host();
  const calls = [];
  const video = { paused: true, ended: false, play() { calls.push('video.play'); }, pause() { calls.push('video.pause'); } };
  const api = { playVideo() { calls.push('playVideo'); }, pauseVideo() { calls.push('pauseVideo'); } };
  current.context.YouTubeAdAccelerator.getPlayer = () => ({ video, player: { wrappedJSObject: api } });
  current.send(active(1));
  current.send({ type: 'layer', watch: true, mini: true });
  current.send({ type: 'playback', play: true, revision: 1 });
  current.send({ type: 'playback', play: false, revision: 1 });
  assert.deepEqual(calls, ['playVideo', 'pauseVideo']);
});

test('a tapped video autoplays once in a fresh player page; a page that already played ignores the request', () => {
  const fresh = host();
  const calls = [];
  const api = { playVideo() { calls.push('playVideo'); } };
  fresh.context.YouTubeAdAccelerator.getPlayer = () => ({ video: { paused: true }, player: { wrappedJSObject: api } });
  fresh.send(active(1));
  fresh.send({ type: 'layer', watch: true, mini: false, autoplay: true });
  assert.deepEqual(calls, ['playVideo']);
  fresh.documentListener('play')({ target: {} });
  fresh.documentListener('canplay')();
  assert.deepEqual(calls, ['playVideo']); // Played once; YouTube owns it from here.

  const old = host();
  const oldCalls = [];
  old.context.YouTubeAdAccelerator.getPlayer = () => ({ video: { paused: false },
    player: { wrappedJSObject: { playVideo() { oldCalls.push('playVideo'); } } } });
  old.send(active(1));
  old.documentListener('play')({ target: {} });
  old.send({ type: 'layer', watch: true, mini: false, autoplay: true });
  assert.deepEqual(oldCalls, []);
});
