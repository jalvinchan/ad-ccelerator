const assert = require("node:assert/strict");
const { test } = require("node:test");
const { readFileSync } = require("node:fs");
const vm = require("node:vm");
const context = vm.createContext({});
vm.runInContext(readFileSync(require.resolve("../src/ad-session.js"), "utf8"), context);
const AdSession = context.YouTubeAdLabSession;

function fixture(max = 16) {
  let clock = 0;
  let rate = 2;
  const events = [];
  const video = {
    currentTime: 0, currentSrc: "ad-one", paused: true, defaultPlaybackRate: 2,
    muted: false, volume: 0.7, preservesPitch: true,
    get playbackRate() { return rate; },
    set playbackRate(value) { if (value > max) throw new Error("Unsupported rate"); rate = value; },
  };
  const session = new AdSession(video, 2, () => clock, (event, details) => events.push({ event, ...details }));
  return { video, session, events, at(value) { clock = value; } };
}

test("16× is temporary and restores the pre-ad rate without changing other media state", () => {
  const { video, session, events } = fixture();
  assert.equal(video.playbackRate, 16);
  assert.equal(video.defaultPlaybackRate, 2);
  assert.equal(video.paused, true);
  assert.equal(video.volume, 0.7);
  assert.equal(video.muted, false);
  assert.equal(video.preservesPitch, true);
  session.finish("ad-state-cleared");
  assert.equal(video.playbackRate, 2);
  session.finish("disabled");
  assert.equal(events.filter(entry => entry.event === "ad-end").length, 1);
});

test("unsupported speeds fall back and completely rejected rates preserve normal speed", () => {
  for (const [max, expected] of [[8, 8], [4, 4], [2, 2]]) {
    const { video, session, events } = fixture(max);
    assert.equal(video.playbackRate, expected);
    session.tick();
    session.finish("disabled");
    assert.equal(video.playbackRate, 2);
    assert.equal(events.some(entry => entry.event === "ad-speed"), max >= 4);
  }
});

test("finishing records actual elapsed time and no later tick can change speed", () => {
  const { video, session, events, at } = fixture();
  at(2813);
  video.currentTime = 30;
  session.finish("ad-state-cleared");
  session.tick();
  assert.equal(video.playbackRate, 2);
  assert.equal(events.at(-1).elapsedSeconds, 2.813);
  assert.equal(events.at(-1).restoredRate, 2);
});

test("player rate resets have bounded retries and source teardown restores content speed", () => {
  const { session, video, events } = fixture();
  for (let i = 0; i < 5; i++) { video.playbackRate = 1; session.tick(); }
  assert.equal(events.filter(entry => entry.event === "rate-reapplied").length, 3);
  assert.equal(events.filter(entry => entry.event === "acceleration-stopped").length, 1);
  assert.equal(video.playbackRate, 1);
  session.finish("navigation");
  assert.equal(video.playbackRate, 2);
});

test("restoration errors are reported and do not cause repeated restoration", () => {
  const { video, session, events } = fixture();
  Object.defineProperty(video, "playbackRate", { get: () => 16, set() { throw new Error("Media detached"); } });
  session.finish("player-replaced");
  session.finish("disabled");
  assert.equal(events.filter(entry => entry.event === "restore-error").length, 1);
  assert.equal(events.filter(entry => entry.event === "ad-end").length, 1);
});
