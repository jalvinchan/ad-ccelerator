const assert = require('node:assert/strict');
const { test } = require('node:test');
const { readFileSync } = require('node:fs');
const vm = require('node:vm');
const code = readFileSync(require.resolve('../src/player-controls.js'), 'utf8');

function controls() {
  let time = 0;
  const elements = [];
  const events = new Map();
  const make = () => {
    const element = { style: {}, attributes: {}, events: {}, isConnected: false,
      append(child) { child.isConnected = true; },
      attachShadow() { return make(); },
      setAttribute(key, value) { this.attributes[key] = value; },
      addEventListener(type, handler) { this.events[type] = handler; },
      matches() { return false; },
    };
    return element;
  };
  const rect = { width: 360, height: 220, top: 0, right: 360 };
  const player = { isConnected: true, classList: { contains: () => false },
    querySelector: () => null, contains: target => target === video };
  const video = { isConnected: true, paused: false, getBoundingClientRect: () => rect };
  const document = { documentElement: make(), hidden: false, fullscreenElement: null,
    createElement() { const element = make(); elements.push(element); return element; },
    addEventListener(type, callback) { events.set(type, callback); } };
  const location = {href:"https://m.youtube.com/watch?v=fixture"};
  const context = vm.createContext({ URL, location, document, innerWidth: 360, innerHeight: 640,
    Date: { now: () => time }, addEventListener(type, callback) { events.set(type, callback); } });
  vm.runInContext(code, context);
  const requests = [];
  const ui = context.YouTubePlayerControls({ getPlayer: () => ({ player, video }) }, value => requests.push(value));
  const state = { videoContext: 'first', contentRate: 2.25, contentAppliedRate: 2.25, contentError: null };
  return { ui, state, location, host: elements[0], button: elements[1], requests, video, rect, document, events,
    tick(value) { time = value; ui.update(state, true); } };
}

test('chip follows video geometry, fades during playback, reveals with interaction and opens current context', () => {
  const current = controls();
  current.tick(0);
  assert.equal(current.button.textContent, '2.25×');
  assert.equal(current.host.style.left, '244px');
  assert.equal(current.host.style.opacity, '1');
  current.tick(4000);
  assert.equal(current.host.style.opacity, '0');
  assert.equal(current.button.tabIndex, -1);
  current.events.get('pointerdown')({target: current.video});
  assert.equal(current.host.style.opacity, '1');
  current.button.events.click({stopPropagation() {}});
  assert.deepEqual(current.requests, ['first']);
  current.video.paused = true;
  current.tick(8000);
  assert.equal(current.host.style.opacity, '1');
  current.rect.top = 40;
  current.events.get('scroll')();
  assert.equal(current.host.style.top, '44px');
});

test('chip hides outside an active visible video and reports rejected speed accessibly', () => {
  const current = controls();
  current.state.contentError = 'rate-rejected';
  current.tick(0);
  assert.equal(current.button.attributes['aria-label'], 'Speed unavailable');
  current.ui.update(current.state, false);
  assert.equal(current.host.style.display, 'none');
  current.button.events.click({stopPropagation() {}});
  assert.equal(current.requests.length, 0);
  current.document.fullscreenElement = {};
  current.tick(0);
  assert.equal(current.host.style.display, 'none');
  current.document.fullscreenElement = null;
  current.rect.top = -100;
  current.tick(0);
  assert.equal(current.host.style.display, 'none');
  current.rect.top = 0;
  current.location.href = 'https://m.youtube.com/';
  current.tick(0);
  assert.equal(current.host.style.display, 'none');
  current.location.href = 'https://m.youtube.com/watch?v=fixture';
  current.state.videoContext = null;
  current.tick(0);
  assert.equal(current.host.style.display, 'none');
});

test('Shorts have no speed chip and holds display the remembered selection', () => {
  const current = controls();
  current.state.contentAppliedRate = null;
  current.tick(0);
  assert.equal(current.button.textContent, '2.25×');
  current.location.href = 'https://m.youtube.com/shorts/fixture';
  current.tick(0);
  assert.equal(current.host.style.display, 'none');
});
