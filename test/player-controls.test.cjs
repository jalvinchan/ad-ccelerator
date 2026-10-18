const assert = require('node:assert/strict');
const { test } = require('node:test');
const { readFileSync } = require('node:fs');
const vm = require('node:vm');
const code = readFileSync(require.resolve('../src/player-controls.js'), 'utf8');

function controls() {
  let time = 0;
  const timers = new Map();
  const observers = [];
  const elements = [];
  const events = new Map();
  const make = () => {
    const element = { style: {}, attributes: {}, events: {}, isConnected: false,
      append(child) { child.isConnected = true; },
      attachShadow() { return make(); },
      setAttribute(key, value) { this.attributes[key] = value; },
      addEventListener(type, handler) { this.events[type] = handler; },
      matches() { return false; },
      setPointerCapture() {},
    };
    return element;
  };
  const rect = { width: 360, height: 220, top: 0, left: 0, right: 360 };
  const player = { isConnected: true, classList: { contains: () => false },
    querySelector: () => null, contains: target => target === video };
  const video = { isConnected: true, paused: false, getBoundingClientRect: () => rect };
  const document = { documentElement: make(), hidden: false, fullscreenElement: null, overlay: null,
    querySelector(selector) { return selector === '#player-control-overlay' ? this.overlay : null; },
    createElement() { const element = make(); elements.push(element); return element; },
    addEventListener(type, callback) { events.set(type, callback); } };
  const location = {href:"https://m.youtube.com/watch?v=fixture"};
  const context = vm.createContext({ URL, location, document, innerWidth: 360, innerHeight: 640,
    Date: { now: () => time }, Math, getComputedStyle: element => element.style,
    MutationObserver: class { constructor(callback) { observers.push(callback); } observe() {} disconnect() {} },
    setTimeout(callback) { const id = timers.size + 1; timers.set(id, callback); return id; },
    clearTimeout(id) { timers.delete(id); },
    addEventListener(type, callback) { events.set(type, callback); } });
  vm.runInContext(code, context);
  const requests = [];
  const saved = [];
  const ui = context.YouTubePlayerControls({ getPlayer: () => ({ player, video }) }, value => requests.push(value),
    (x, y) => saved.push([x, y]));
  const state = { videoContext: 'first', contentRate: 2.25, contentAppliedRate: 2.25, contentError: null };
  return { ui, state, location, observers, host: elements[0], button: elements[1], requests, saved, video, rect, document, events,
    hold() { const pending = [...timers.values()]; timers.clear(); pending.forEach(callback => callback()); },
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

test('long-press drags the chip freely, snaps it inside the video, saves it and skips the release tap', () => {
  const current = controls();
  current.tick(0);
  const pointer = (type, clientX, clientY) => current.button.events[type]({ isPrimary: true, pointerId: 1,
    clientX, clientY, preventDefault() {} });
  pointer('pointerdown', 270, 20);
  pointer('pointermove', 290, 20); // Moving before the hold completes is a scroll, not a drag.
  current.hold();
  assert.equal(current.host.style.left, '244px');
  pointer('pointerup', 290, 20);
  pointer('pointerdown', 270, 20);
  current.hold();
  pointer('pointermove', 100, 100);
  assert.equal(current.host.style.left, '74px');
  assert.equal(current.host.style.top, '84px');
  pointer('pointermove', -500, 900);
  pointer('pointerup', -500, 900);
  assert.equal(current.host.style.left, '0px');
  assert.equal(current.host.style.top, '172px');
  assert.deepEqual(current.saved, [[0, 1]]);
  current.button.events.click({stopPropagation() {}});
  assert.equal(current.requests.length, 0);
  current.tick(1000);
  current.button.events.click({stopPropagation() {}});
  assert.deepEqual(current.requests, ['first']);
  current.rect.top = 40;
  current.events.get('scroll')();
  assert.equal(current.host.style.top, '212px');
  current.ui.setPosition(0.5, 0);
  assert.deepEqual([current.host.style.left, current.host.style.top], ['156px', '40px']);
  current.ui.setPosition(null);
  assert.equal(current.host.style.left, '244px');
});

test('losing pointer capture ends the drag and does not leave the chip stuck', () => {
  const current = controls();
  current.tick(0);
  const pointer = (type, pointerId, clientX, clientY) => current.button.events[type]({ isPrimary: true, pointerId,
    clientX, clientY, preventDefault() {} });
  pointer('pointerdown', 1, 270, 20);
  current.hold();
  pointer('pointermove', 1, 100, 100);
  current.button.events.lostpointercapture({ pointerId: 1 });
  assert.equal(current.saved.length, 1);
  assert.ok(Math.abs(current.saved[0][0] - 74 / (360 - 48)) < 1e-9);
  assert.ok(Math.abs(current.saved[0][1] - 84 / (220 - 48)) < 1e-9);
  const left = current.host.style.left;
  pointer('pointerdown', 2, 270, 20);
  current.hold();
  pointer('pointermove', 2, 290, 20);
  assert.notEqual(current.host.style.left, left);
});

test('on mobile YouTube the chip shows and hides with the player control overlay', () => {
  const current = controls();
  const content = { style: { visibility: 'hidden' } };
  current.document.overlay = { querySelector: () => content };
  current.video.paused = true;
  current.tick(0);
  assert.equal(current.host.style.opacity, '0'); // Follows YouTube, not the pause or reveal timer.
  content.style.visibility = 'visible';
  current.observers[0]();
  assert.equal(current.host.style.opacity, '1');
  content.style.visibility = 'hidden';
  current.observers[0]();
  assert.equal(current.host.style.opacity, '0');
  assert.equal(current.button.tabIndex, -1);
});
