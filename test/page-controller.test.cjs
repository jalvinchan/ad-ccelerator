const assert = require("node:assert/strict");
const { test } = require("node:test");
const { readFileSync } = require("node:fs");
const vm = require("node:vm");
const core = readFileSync(require.resolve("../src/ad-session.js"), "utf8");
const controller = readFileSync(require.resolve("../src/page-controller.js"), "utf8");

async function page({ stored = true, rate = 2 } = {}) {
  const timers = new Map();
  const intervals = new Map();
  const navigation = new Map();
  const media = new Map();
  let observe;
  let nextId = 0;
  const rect = { width: 640, height: 360 };
  const player = {
    isConnected: true, nodeType: 1, ad: false,
    classList: { contains: name => name === "ad-showing" && player.ad },
    querySelector: () => video,
    querySelectorAll: () => buttons,
  };
  const video = {
    isConnected: true, nodeType: 1, parentElement: player,
    playbackRate: 2, defaultPlaybackRate: 2, currentTime: 0, currentSrc: "content",
    paused: true, volume: 0.8,
    getClientRects: () => [rect], getBoundingClientRect: () => rect,
  };
  const button = {
    isConnected: true, nodeType: 1, parentElement: player,
    tagName: "BUTTON", disabled: false, hidden: false, clicks: 0,
    getClientRects: () => [rect], getAttribute: () => null,
    click() { this.clicks++; },
  };
  const buttons = [button];
  const candidates = [player];
  const sandbox = vm.createContext({
    URL, location: { href: "https://m.youtube.com/watch?v=first" },
    performance: { now: () => 313 }, Date,
    console: { info() {} },
    getComputedStyle: element => ({ display: element.display ?? "block", visibility: "visible", opacity: "1" }),
    setTimeout(callback) { timers.set(++nextId, callback); return nextId; },
    clearTimeout(id) { timers.delete(id); },
    setInterval(callback) { intervals.set(++nextId, callback); return nextId; },
    clearInterval(id) { intervals.delete(id); },
    addEventListener: (event, callback) => navigation.set(event, callback),
    document: {
      documentElement: {}, querySelectorAll: () => candidates,
      addEventListener: (event, callback) => media.set(event, callback),
    },
    MutationObserver: class { constructor(callback) { observe = callback; } observe() {} },

  });
  vm.runInContext(core, sandbox);
  vm.runInContext(controller, sandbox);
  sandbox.YouTubeAdAccelerator.setEnabled(stored);
  if (rate !== 1) sandbox.YouTubeAdAccelerator.setContentRate(rate, sandbox.YouTubeAdAccelerator.getStatus().videoContext);
  await Promise.resolve();
  return {
    video, player, button, buttons, candidates, sandbox, timers, intervals,
    scan() { observe(); const callbacks = [...timers.values()]; timers.clear(); callbacks.forEach(callback => callback()); },
    speed(value, context = sandbox.YouTubeAdAccelerator.getStatus().videoContext) { return sandbox.YouTubeAdAccelerator.setContentRate(value, context); },
    change(value) { sandbox.YouTubeAdAccelerator.setEnabled(value); },
    navigation(event) { navigation.get(event)(); },
    status() { return sandbox.YouTubeAdAccelerator.getStatus(); },
  };
}

test("controller owns content speed, accelerates an ad, and restores selected speed", async () => {
  const current = await page();
  assert.equal(current.speed(3).accepted, true);
  current.scan();
  assert.equal(current.intervals.size, 0);
  current.player.ad = true;
  current.video.playbackRate = 1; // YouTube resets the shared element for the ad.
  current.button.hidden = true;
  current.scan();
  assert.equal(current.video.playbackRate, 16);
  assert.equal(current.video.paused, true);
  assert.equal(current.video.volume, 0.8);
  assert.equal(current.video.defaultPlaybackRate, 2);
  assert.equal(current.intervals.size, 1);
  current.button.hidden = false;
  current.scan();
  assert.equal(current.button.clicks, 0);
  current.player.ad = false;
  current.scan();
  assert.equal(current.video.playbackRate, 3);
  assert.equal(current.intervals.size, 0);
});

test("Android controls work without Chrome APIs and restore speed on toggle and app pause", async () => {
  const current = await page();
  current.player.ad = true;
  current.scan();
  assert.equal(current.video.playbackRate, 16);
  current.change(false);
  assert.equal(current.video.playbackRate, 2);
  current.change(true);
  assert.equal(current.video.playbackRate, 16);
  current.sandbox.YouTubeAdAccelerator.setSuspended(true);
  assert.equal(current.video.playbackRate, 2);
  assert.equal(current.intervals.size, 0);
  // Page lifecycle events must never override the native app's paused state.
  current.navigation("pageshow");
  current.navigation("yt-navigate-finish");
  current.scan();
  assert.equal(current.video.playbackRate, 2);
  current.sandbox.YouTubeAdAccelerator.setSuspended(false);
  assert.equal(current.video.playbackRate, 16);
  current.player.ad = false;
  current.scan();
  assert.equal(current.video.playbackRate, 2);
  vm.runInContext(controller, current.sandbox);
  assert.equal(current.status().events.filter(event => event.event === "ad-start").length, 3);
});

test("ad acceleration never searches for or clicks Skip or advertiser controls", async () => {
  const current = await page();
  current.player.querySelectorAll = () => assert.fail("Ad acceleration must not inspect clickable controls");
  current.player.ad = true;
  for (const time of [0, 5, 10, 20]) {
    current.video.currentTime = time;
    current.scan();
  }
  assert.equal(current.video.playbackRate, 16);
  assert.equal(current.button.clicks, 0);
  assert.equal(current.status().events.some(entry => entry.event.startsWith("skip")), false);
});

test("disabling or navigating immediately restores speed and stops the watchdog", async () => {
  for (const action of [current => current.change(false), current => current.navigation("yt-navigate-start")]) {
    const current = await page();
    current.player.ad = true;
    current.button.hidden = true;
    current.scan();
    action(current);
    assert.equal(current.video.playbackRate, 2);
    assert.equal(current.intervals.size, 0);
    current.scan();
    assert.equal(current.video.playbackRate, 2);
    assert.equal(current.status().rate, null);
  }
});

test("removing a player restores its rate and reinjection does not register duplicate controls", async () => {
  const current = await page();
  current.player.ad = true;
  current.scan();
  current.video.isConnected = false;
  current.candidates.length = 0;
  current.scan();
  assert.equal(current.video.playbackRate, 2);
  assert.equal(current.intervals.size, 0);
  vm.runInContext(controller, current.sandbox);
  assert.equal(current.intervals.size, 0);
  assert.equal(current.status().events.filter(entry => entry.event === "ad-start").length, 1);
});

test("saved disable starts without acceleration", async () => {
  const current = await page({ stored: false });
  current.player.ad = true;
  current.scan();
  assert.equal(current.video.playbackRate, 2);
  assert.equal(current.intervals.size, 0);
  assert.equal(current.status().enabled, false);
});

test("ad toggles restore the selected speed and retain only 30 diagnostics", async () => {
  const current = await page({ stored: false });
  current.player.ad = true;
  current.speed(1.5);
  for (let i = 0; i < 15; i++) {
    current.change(true);
    assert.equal(current.video.playbackRate, 16);
    current.change(false);
    assert.equal(current.video.playbackRate, 1.5);
  }
  assert.equal(current.status().events.length, 30);
});

test("hidden players are left alone and replacing an ad video restores the original", async () => {
  const current = await page();
  current.player.ad = true;
  current.player.display = "none";
  current.scan();
  assert.equal(current.video.playbackRate, 2);
  current.player.display = "block";
  current.scan();
  assert.equal(current.video.playbackRate, 16);
  const replacement = { ...current.video, playbackRate: 1.5 };
  current.player.querySelector = () => replacement;
  current.scan();
  assert.equal(current.video.playbackRate, 2);
  assert.equal(replacement.playbackRate, 16);
  current.change(false);
  assert.equal(replacement.playbackRate, 2);
});

test("reinjection during an ad preserves a single session and watchdog", async () => {
  const current = await page();
  current.player.ad = true;
  current.scan();
  vm.runInContext(core, current.sandbox);
  vm.runInContext(controller, current.sandbox);
  assert.equal(current.intervals.size, 1);
  assert.equal(current.status().events.filter(event => event.event === "ad-start").length, 1);
  current.change(false);
  assert.equal(current.video.playbackRate, 2);
});

test("fresh documents and different videos start at 1×; stale commands cannot change them", async () => {
  const current = await page({ rate: 1 });
  assert.equal(current.video.playbackRate, 1);
  const context = current.status().videoContext;
  assert.equal(current.speed(4).accepted, true);
  current.sandbox.location.href = "https://m.youtube.com/watch?v=second";
  assert.equal(current.speed(3, context).error, "stale-context");
  assert.equal(current.video.playbackRate, 1);
  assert.notEqual(current.status().videoContext, context);
  for (const rate of [0, -1, 16, NaN, Infinity, "2", 1.1])
    assert.equal(current.speed(rate).error, "invalid-rate");
  assert.equal(current.video.playbackRate, 1);
});

test("speed changes during ads apply afterward, across consecutive ads and replacements", async () => {
  const current = await page();
  const context = current.status().videoContext;
  current.player.ad = true;
  current.scan();
  assert.equal(current.speed(2.25).pending, true);
  assert.equal(current.video.playbackRate, 16);
  const replacement = { ...current.video, playbackRate: 1 };
  current.player.querySelector = () => replacement;
  current.scan();
  assert.equal(current.video.playbackRate, 2.25);
  assert.equal(replacement.playbackRate, 16);
  assert.equal(current.status().videoContext, context);
  current.player.ad = false;
  current.scan();
  assert.equal(replacement.playbackRate, 2.25);
  current.player.ad = true;
  current.scan();
  assert.equal(replacement.playbackRate, 16);
  current.player.ad = false;
  current.scan();
  assert.equal(replacement.playbackRate, 2.25);
});

test("content speed works with acceleration off, survives pause and same-video navigation", async () => {
  const current = await page({ stored: false });
  current.speed(0.75);
  const context = current.status().videoContext;
  assert.equal(current.video.playbackRate, 0.75);
  current.player.ad = true;
  current.video.playbackRate = 1;
  current.scan();
  assert.equal(current.speed(4).pending, true);
  assert.equal(current.video.playbackRate, 1);
  current.sandbox.YouTubeAdAccelerator.setSuspended(true);
  assert.equal(current.speed(2, context).error, "stale-context");
  current.navigation("pageshow");
  current.sandbox.YouTubeAdAccelerator.setSuspended(false);
  current.player.ad = false;
  current.scan();
  assert.equal(current.video.playbackRate, 4);
  current.navigation("yt-navigate-start");
  current.sandbox.location.href += "&list=playlist";
  current.navigation("yt-navigate-finish");
  current.scan();
  assert.equal(current.video.playbackRate, 4);
  assert.equal(current.status().videoContext, context);
});

test("rejected content rates are reported, including an accepted ad-time choice rejected on restoration", async () => {
  const current = await page({ rate: 1 });
  let actual = 1;
  Object.defineProperty(current.video, "playbackRate", {
    configurable: true, get: () => actual,
    set(value) { if (value === 2.25) throw new Error("unsupported"); actual = value; },
  });
  assert.equal(current.speed(2.25).error, "rate-rejected");
  assert.equal(current.status().contentRate, 1);
  assert.equal(current.status().contentError, "rate-rejected");
  assert.equal(current.video.playbackRate, 1);
  current.player.ad = true;
  current.scan();
  assert.equal(current.speed(2.25).accepted, true);
  current.player.ad = false;
  current.scan();
  assert.equal(current.status().contentError, "rate-rejected");
  assert.notEqual(current.status().contentAppliedRate, 2.25);
  assert.equal(current.speed(1.25).accepted, true);
  assert.equal(current.status().contentError, null);
});
