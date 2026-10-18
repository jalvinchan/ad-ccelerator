const assert = require("node:assert/strict");
const { test } = require("node:test");
const { readFileSync } = require("node:fs");
const vm = require("node:vm");
const core = readFileSync(require.resolve("../src/ad-session.js"), "utf8");
const controller = readFileSync(require.resolve("../src/page-controller.js"), "utf8");

async function page({ stored = true, rate = 2, url = "https://m.youtube.com/watch?v=first" } = {}) {
  const timers = new Map();
  const intervals = new Map();
  const navigation = new Map();
  const media = new Map();
  let observe;
  let nextId = 0;
  let clock = 313;
  const rect = { width: 640, height: 360, left: 0, top: 0, right: 640, bottom: 360 };
  const player = {
    isConnected: true, nodeType: 1, ad: false,
    classList: { contains: name => name === "ad-showing" && player.ad },
    contains: target => target === video,
    querySelector: () => video,
    querySelectorAll: () => buttons,
  };
  const video = {
    isConnected: true, nodeType: 1, parentElement: player,
    playbackRate: 2, defaultPlaybackRate: 2, currentTime: 0, duration: 600, currentSrc: "content",
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
    URL, location: { href: url },
    performance: { now: () => clock }, Date,
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
  if (rate !== 1 && !url.includes("/shorts/") && !url.endsWith(".com/")) sandbox.YouTubeAdAccelerator.setContentRate(rate, sandbox.YouTubeAdAccelerator.getStatus().videoContext);
  await Promise.resolve();
  return {
    video, player, button, buttons, candidates, sandbox, timers, intervals,
    advance(ms) { clock += ms; },
    event(type, values = {}) { media.get(type)({ target: video, isPrimary: true, button: 0,
      pointerId: 1, clientX: 100, clientY: 100, ...values }); },
    external(rate) { video.playbackRate = rate; media.get("ratechange")({target: video}); },
    scan() { observe(); const callbacks = [...timers.values()]; timers.clear(); callbacks.forEach(callback => callback()); },
    mutate(records) { observe(records); },
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

test("seek moves content by the chosen interval, clamps to the video, and never seeks ads or Shorts", async () => {
  const current = await page();
  const context = current.status().videoContext;
  const seekBy = (seconds, expectedContext = context) =>
    JSON.parse(JSON.stringify(current.sandbox.YouTubeAdAccelerator.seekBy(seconds, expectedContext)));
  assert.equal(current.status().seekAvailable, true);
  assert.deepEqual(seekBy(10), {accepted: true});
  assert.equal(current.video.currentTime, 10);
  seekBy(-30);
  assert.equal(current.video.currentTime, 0);
  current.video.currentTime = 595;
  seekBy(10);
  assert.equal(current.video.currentTime, 600);
  assert.deepEqual(seekBy(7), {accepted: false, error: "invalid-seek"});
  assert.deepEqual(seekBy("10"), {accepted: false, error: "invalid-seek"});
  assert.deepEqual(seekBy(10, "stale"), {accepted: false, error: "stale-context"});
  current.player.ad = true;
  current.scan();
  assert.deepEqual(seekBy(10, current.status().videoContext), {accepted: false, error: "unavailable"});
  assert.equal(current.status().seekAvailable, false);
  const shorts = await page({url: "https://m.youtube.com/shorts/abc"});
  assert.equal(shorts.status().seekAvailable, false);
  current.player.ad = false;
  current.video.duration = Infinity;
  current.scan();
  assert.equal(current.status().seekAvailable, false);
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
  assert.equal(current.speed(1.05).accepted, true);
  assert.equal(current.video.playbackRate, 1.05);
  assert.equal(current.speed(4).accepted, true);
  current.sandbox.location.href = "https://m.youtube.com/watch?v=second";
  assert.equal(current.speed(3, context).error, "stale-context");
  assert.equal(current.video.playbackRate, 1);
  assert.notEqual(current.status().videoContext, context);
  for (const rate of [0, -1, 16, NaN, Infinity, "2", 1.13, 0.2, 4.05])
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

for (const baseline of [1, 1.5, 2, 2.25]) {
  for (const release of ["pointerup", "pointercancel"]) {
    test(`native hold from ${baseline}× yields through polling and restores on ${release}`, async () => {
      const current = await page({rate: baseline});
      current.event("ratechange"); // Flush the app-written rate event.
      current.event("pointerdown");
      current.advance(600);
      if (baseline !== 2) current.external(2);
      for (let i = 0; i < 5; i++) {
        assert.equal(current.status().contentRate, baseline);
        assert.equal(current.status().contentAppliedRate, null);
        assert.equal(current.video.playbackRate, 2);
      }
      current.event(release);
      current.external(1); // YouTube settles at its own default before our bounded restoration.
      assert.equal(current.status().contentRate, baseline);
      current.scan();
      assert.equal(current.video.playbackRate, baseline);
      assert.equal(current.status().contentAppliedRate, baseline);
    });
  }
}

test("native menu choices are remembered without polling writes; rejection never retries", async () => {
  const current = await page({rate: 1});
  current.external(1.1);
  for (let i = 0; i < 5; i++) assert.equal(current.status().contentRate, 1.1);
  current.player.ad = true;
  current.scan();
  current.player.ad = false;
  current.scan();
  assert.equal(current.video.playbackRate, 1.1);
  let writes = 0;
  Object.defineProperty(current.video, "playbackRate", { get: () => 1.1,
    set() { writes++; throw new Error("unsupported"); } });
  assert.equal(current.speed(2.25).error, "rate-rejected");
  for (let i = 0; i < 5; i++) current.status();
  assert.equal(writes, 1);
});

for (const interruption of ["ad", "navigation", "replacement", "background"]) {
  test(`pending hold restoration is invalidated by ${interruption}`, async () => {
    const current = await page({rate: 1.5});
    current.event("ratechange");
    current.event("pointerdown");
    current.external(2);
    current.event("pointerup");
    const stale = [...current.timers.values()].at(-1);
    if (interruption === "ad") { current.player.ad = true; current.status(); }
    if (interruption === "navigation") {
      current.navigation("yt-navigate-start");
      current.sandbox.location.href = "https://m.youtube.com/watch?v=next";
      current.navigation("yt-navigate-finish"); current.status();
    }
    if (interruption === "replacement") {
      current.player.querySelector = () => ({...current.video, playbackRate: 1}); current.status();
    }
    if (interruption === "background") current.sandbox.YouTubeAdAccelerator.setSuspended(true);
    const before = current.video.playbackRate;
    stale(); // Even an already queued callback must be harmless.
    assert.equal(current.video.playbackRate, before);
    if (interruption === "navigation") assert.equal(before, 1);
  });
}

test("Shorts and previews get no default writes; Shorts reject commands and restore native speed after ads", async () => {
  for (const url of ["https://m.youtube.com/shorts/first", "https://m.youtube.com/"]) {
    const current = await page({url});
    assert.equal(current.video.playbackRate, 2);
    assert.equal(current.status().contentSpeedAvailable, false);
    assert.equal(current.speed(3).accepted, false);
    current.external(1.5);
    current.event("pointerdown");
    current.external(2);
    current.event("pointercancel");
    current.external(1.5); // Native Shorts restoration owns the write.
    current.scan();
    assert.equal(current.video.playbackRate, 1.5);
    current.event("ratechange");
    current.event("pointerdown");
    current.event("pointermove", {clientY: 200});
    current.event("pointerup");
    assert.equal(current.timers.size, 0);
    current.player.ad = true; current.scan();
    assert.equal(current.video.playbackRate, 16);
    current.player.ad = false; current.scan();
    assert.equal(current.video.playbackRate, 1.5);
  }
});

test("short contacts, swipes and controls do not identify holds", async () => {
  const current = await page();
  current.event("ratechange");
  current.event("pointerdown"); current.advance(100); current.event("pointerup");
  current.external(1.25);
  assert.equal(current.status().contentRate, 1.25);
  current.event("pointerdown", {target: {closest: () => ({})}});
  current.external(2);
  assert.equal(current.status().contentRate, 2);
  current.event("pointerdown"); current.event("pointermove", {clientX: 150});
  current.advance(600); current.event("pointerup");
  assert.notEqual(current.status().contentAppliedRate, null);
});

test("a newer app selection during a hold wins over native restoration", async () => {
  const current = await page({rate: 1.5});
  current.event("ratechange"); current.event("pointerdown"); current.external(2);
  assert.equal(current.speed(3).pending, true);
  assert.equal(current.video.playbackRate, 2);
  current.event("pointerup"); current.external(1.5); current.scan();
  assert.equal(current.video.playbackRate, 3);
  assert.equal(current.status().contentRate, 3);
});

test("Shorts hold/restoration and stable selection never write content speed", async () => {
  const current = await page({url: "https://m.youtube.com/shorts/first"});
  let actual = 1.5;
  let writes = 0;
  Object.defineProperty(current.video, "playbackRate", {get: () => actual, set(value) {writes++; actual = value;}});
  current.event("ratechange");
  current.event("pointerdown"); actual = 2; current.event("ratechange");
  current.advance(600); current.status(); current.event("pointerup");
  actual = 1.5; current.event("ratechange"); current.scan();
  assert.equal(writes, 0);
  assert.equal(current.status().contentRate, 1.5);
  assert.equal(current.speed(3).accepted, false);
  current.player.ad = true; current.scan();
  current.player.ad = false; current.scan();
  assert.equal(actual, 1.5);
  assert.equal(writes, 2); // One acceleration, one ad restoration.
});

for (const baseline of [1.5, 2, 2.25]) {
  test(`mobile sibling hold surface restores ${baseline}× without owning the gesture`, async () => {
    const current = await page({rate: baseline});
    const container = {};
    current.player.closest = selector => selector === ".player-container" ? container : null;
    const overlay = {closest: selector => selector === ".player-container" ? container : null};
    current.event("ratechange");
    current.event("pointerdown", {target: overlay});
    current.advance(600);
    if (baseline !== 2) current.external(2);
    assert.equal(current.status().contentRate, baseline);
    assert.equal(current.status().contentAppliedRate, null);
    current.event("pointerup", {target: overlay});
    current.external(1); current.scan();
    assert.equal(current.video.playbackRate, baseline);
  });
}

test("mutations that cannot touch the selected video are ignored", async () => {
  const current = await page();
  current.scan();
  current.timers.clear();
  current.mutate([{ type: "attributes", target: { nodeName: "DIV", classList: { contains: () => false }, contains: () => false } }]);
  assert.equal(current.timers.size, 0);
  current.mutate([{ type: "attributes", target: { nodeName: "VIDEO" } }]);
  assert.equal(current.timers.size, 1);
  current.scan(); // Flush the debounce latch before the next observation.
  current.mutate([{ type: "attributes", target: { nodeName: "DIV", contains: node => node === current.player } }]);
  assert.equal(current.timers.size, 1);
  current.scan();
  current.mutate([{ type: "childList", target: {} }]);
  assert.equal(current.timers.size, 1);
});

test("sibling gesture recognition excludes native controls, unrelated overlays and outside touches", async () => {
  for (const kind of ["control", "unrelated", "outside"]) {
    const current = await page({rate: 1.5});
    const container = {};
    current.player.closest = () => container;
    const target = {closest: selector => selector === ".player-container"
      ? kind === "unrelated" ? {} : container : kind === "control" ? {} : null};
    current.event("ratechange");
    current.event("pointerdown", {target, clientX: kind === "outside" ? 700 : 100});
    current.external(2);
    assert.equal(current.status().contentRate, 2);
    assert.equal(current.status().contentAppliedRate, 2);
  }
});
