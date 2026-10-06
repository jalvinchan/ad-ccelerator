// Node 22+ and a running DEBUG app, with its WebView forwarded to localhost:9222.
// Exercises production injection and native controls against local media.
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { execFileSync } from "node:child_process";

const adb = process.env.ADB ?? "adb";
const linksOnly = process.argv.includes("--links-only");
const fixtureUrl = "https://m.youtube.com/accelerator-acceptance-fixture";
const html = linksOnly ? Buffer.from('<!doctype html><html lang="en"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Link fixture</title><p>Local browser handoff fixture.</p></html>') :
  readFileSync(new URL("../test/fixtures/player.html", import.meta.url));
const pages = await (await fetch("http://127.0.0.1:9222/json/list")).json();
const page = pages.find(item => item.type === "page" && item.url.startsWith("https://m.youtube.com/"));
assert.ok(page, "Launch the debug app and forward its WebView inspection socket first");
const socket = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((resolve, reject) => {
  socket.addEventListener("open", resolve, {once: true});
  socket.addEventListener("error", reject, {once: true});
});
let nextId = 0;
const pending = new Map();
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const device = (...args) => execFileSync(adb, args, {encoding: "utf8", timeout: 15000});

socket.addEventListener("message", async event => {
  const message = JSON.parse(event.data);
  if (message.id) {
    pending.get(message.id)?.(message);
    pending.delete(message.id);
  } else if (message.method === "Fetch.requestPaused") {
    await send("Fetch.fulfillRequest", {
      requestId: message.params.requestId, responseCode: 200,
      responseHeaders: [{name: "Content-Type", value: "text/html; charset=utf-8"}],
      body: html.toString("base64"),
    });
  }
});

async function send(method, params = {}) {
  const id = ++nextId;
  const response = new Promise(resolve => pending.set(id, resolve));
  socket.send(JSON.stringify({id, method, params}));
  const timeout = setTimeout(() => pending.get(id)?.({error: {message: `${method} timed out`}}), 15000);
  try {
    const result = await response;
    assert.ok(!result.error, JSON.stringify(result.error));
    return result.result;
  } finally { clearTimeout(timeout); pending.delete(id); }
}

async function evaluate(expression, userGesture = false) {
  const response = await send("Runtime.evaluate", {expression, userGesture, awaitPromise: true, returnByValue: true});
  assert.ok(!response.exceptionDetails, JSON.stringify(response.exceptionDetails));
  return response.result.value;
}

const state = () => evaluate(`(() => {
  const v = document.querySelector('video');
  return {rate:v?.playbackRate,paused:v?.paused,time:v?.currentTime,ready:v?.readyState,
    status:window.YouTubeAdAccelerator?.getStatus(),fullscreen:!!document.fullscreenElement};
})()`);

async function until(check, message) {
  let last;
  for (let i = 0; i < 150; i++) {
    const value = await state();
    last = value;
    if (check(value)) return value;
    await delay(100);
  }
  assert.fail(`${message}: ${JSON.stringify(last)}`);
}

function nativeUi() {
  device("shell", "uiautomator", "dump", "/sdcard/accelerator-window.xml");
  return device("shell", "cat", "/sdcard/accelerator-window.xml");
}

function tapNative(node) {
  assert.ok(node, "Expected native control must be visible");
  const [, x1, y1, x2, y2] = node.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
  device("shell", "input", "tap", String((+x1 + +x2) / 2), String((+y1 + +y2) / 2));
}

function toggle() {
  const xml = nativeUi();
  const node = xml.match(/<node[^>]*class="android.widget.Switch"[^>]*>/)?.[0];
  tapNative(node);
}

async function checkBrowserHandoff() {
  let browserOpened = false;
  for (let attempt = 0; attempt < 20 && !browserOpened; attempt++) {
    await delay(200);
    const activities = device("shell", "dumpsys", "activity", "activities");
    browserOpened = /topResumedActivity=/.test(activities) &&
      !/topResumedActivity=.*lab\.youtube\.accelerator\//.test(activities);
  }
  assert.ok(browserOpened, "Browser action must leave the WebView app");
  // Close a browser first-run window without accepting setup or signing in.
  // That flow can block am start -W's launch-complete signal.
  device("shell", "input", "keyevent", "KEYCODE_BACK");
  device("shell", "am", "start", "-n", "lab.youtube.accelerator/.MainActivity");
}

const results = {};
try {
  await send("Fetch.enable", {patterns: [{urlPattern: fixtureUrl, requestStage: "Request"}]});
  await send("Page.navigate", {url: fixtureUrl});
  let current = await until(s => s.status && (linksOnly || s.ready >= 2), "Native script injection or media loading failed");
  results.nativeInjection = true;
  if (!linksOnly) {
    if (!current.status.enabled) { toggle(); await until(s => s.status.enabled, "Toggle did not enable"); }
    // A new media source can reset its element rate, just as YouTube can.
    await evaluate("document.querySelector('video').playbackRate=1.5");
    await until(s => s.rate === 1.5, "Content speed could not be selected");
    // The inspection command supplies the same user activation required by a tap.
    await evaluate("document.querySelector('video').play().then(()=>true)", true);
    const normalStart = await state();
    await delay(300);
    const normalEnd = await state();
    assert.ok(normalEnd.time - normalStart.time > 0.2, "Media must actually play");
    assert.equal(normalEnd.rate, 1.5);
    results.normalContent = true;

    await evaluate("document.querySelector('.html5-video-player').classList.add('ad-showing')");
    const accelerated = await until(s => s.status.adActive && [16, 8, 4].includes(s.rate), "Ad signal did not accelerate");
    const target = accelerated.rate;
    results.acceptedRate = target;
    const clock = performance.now();
    await delay(300);
    const after = await state();
    results.mediaSecondsPerWallSecond = Number(((after.time - accelerated.time) / ((performance.now() - clock) / 1000)).toFixed(2));
    // Buffering and software decode can limit achieved speed below the assigned
    // rate. Require substantially faster actual media time than normal content.
    assert.ok(results.mediaSecondsPerWallSecond > normalEnd.rate * 2,
      `Media time did not accelerate as expected: ${JSON.stringify({results, accelerated, after})}`);
    await evaluate("document.querySelector('video').pause()");

    toggle();
    await until(s => !s.status.enabled && s.rate === 1.5 && !s.status.adActive, "Native disable did not restore speed");
    toggle();
    await until(s => s.status.enabled && s.rate === target, "Native re-enable did not accelerate");
    results.nativeToggle = true;

    await evaluate("document.querySelector('video').play().then(()=>true)", true);
    device("shell", "input", "keyevent", "KEYCODE_HOME");
    await until(s => s.paused && s.rate === 1.5 && !s.status.adActive, "Native pause did not restore and pause media");
    await evaluate("dispatchEvent(new Event('pageshow'));dispatchEvent(new Event('yt-navigate-finish'))");
    assert.equal((await state()).rate, 1.5);
    device("shell", "am", "start", "-W", "-n", "lab.youtube.accelerator/.MainActivity");
    current = await until(s => s.status.adActive && s.rate === target, "Native resume did not reapply controller state");
    assert.equal(current.paused, true, "Returning to the app must not explicitly resume media");
    results.nativePauseResume = true;

    await evaluate("document.querySelector('video').requestFullscreen().then(()=>true)", true);
    await until(s => s.fullscreen, "Fullscreen did not open");
    assert.equal((await state()).rate, target, "Fullscreen must preserve the selected ad session");
    device("shell", "input", "keyevent", "KEYCODE_BACK");
    await until(s => !s.fullscreen, "Back did not exit fullscreen");
    results.fullscreenBack = true;

    await evaluate("document.querySelector('.html5-video-player').classList.remove('ad-showing')");
    await until(s => !s.status.adActive && s.rate === 1.5, "Ad exit did not restore content speed");
    results.adExit = true;
  }

  await evaluate("location.href='intent://example.com/'");
  await delay(200);
  assert.equal(await evaluate("location.href"), fixtureUrl, "Arbitrary schemes must stay blocked");
  await evaluate("location.href='https://support.google.com/youtube/answer/3037019'");
  let ui = nativeUi();
  assert.ok(ui.includes('Open support.google.com in your browser?'), "An ungestured external link must offer confirmation");
  assert.equal(await evaluate("location.href"), fixtureUrl, "External content must not load in the app");
  tapNative(ui.match(/<node[^>]*text="Cancel"[^>]*>/i)?.[0]);
  await delay(300); // Allow Android's queued dialog-dismiss callback to complete.
  results.externalNavigationSafety = true;

  await evaluate("location.href='https://accounts.google.com/ServiceLogin?service=youtube'");
  ui = nativeUi();
  tapNative(ui.match(/<node[^>]*text="Open browser"[^>]*>/i)?.[0]);
  await checkBrowserHandoff();
  results.confirmedExternalLink = true;

  ui = nativeUi();
  tapNative(ui.match(/<node[^>]*text="BROWSER"[^>]*>/)?.[0]);
  await checkBrowserHandoff();
  results.browserButton = true;
  console.log(JSON.stringify({fixtureOnly: true, ...results}, null, 2));
} finally {
  await send("Fetch.disable").catch(() => {});
  await send("Page.navigate", {url: "https://m.youtube.com/"}).catch(() => {});
  socket.close();
}
