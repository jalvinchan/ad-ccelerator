// Node 22+, a running DEBUG app, and GeckoView's socket forwarded to localhost:6000.
// Uses only the debugger to install a controlled video fixture; no test code is in the APK.
import assert from 'node:assert/strict';
import net from 'node:net';
import { readFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';

const adb = process.env.ADB ?? 'adb';
const device = (...args) => execFileSync(adb, args, {encoding: 'utf8', timeout: 15000});
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const socket = net.connect(6000, '127.0.0.1');
let buffer = Buffer.alloc(0);
const queue = [];
const waiters = [];
function fail(error) {
  for (const item of waiters.splice(0)) { clearTimeout(item.timer); item.reject(error); }
}
socket.on('error', fail);
socket.on('end', () => fail(new Error('Browser debugger disconnected')));
socket.on('data', chunk => {
  buffer = Buffer.concat([buffer, chunk]);
  while (true) {
    const colon = buffer.indexOf(58);
    if (colon < 0) return;
    const length = Number(buffer.subarray(0, colon).toString());
    if (buffer.length < colon + 1 + length) return;
    const message = JSON.parse(buffer.subarray(colon + 1, colon + 1 + length));
    buffer = buffer.subarray(colon + 1 + length);
    const index = waiters.findIndex(item => item.check(message));
    if (index < 0) { queue.push(message); if (queue.length > 400) queue.shift(); }
    else {
      const item = waiters.splice(index, 1)[0];
      clearTimeout(item.timer);
      item.resolve(message);
    }
  }
});
function wait(check) {
  const index = queue.findIndex(check);
  if (index >= 0) return Promise.resolve(queue.splice(index, 1)[0]);
  return new Promise((resolve, reject) => {
    const item = {check, resolve, reject};
    item.timer = setTimeout(() => {
      const index = waiters.indexOf(item);
      if (index >= 0) waiters.splice(index, 1);
      reject(new Error('Browser debugger timed out'));
    }, 15000);
    waiters.push(item);
  });
}
async function send(to, type, params = {}) {
  const pending = wait(message => message.from === to && !message.type);
  const payload = JSON.stringify({to, type, ...params});
  socket.write(Buffer.byteLength(payload) + ':' + payload);
  const response = await pending;
  assert.ok(!response.error, JSON.stringify(response));
  return response;
}
async function evaluate(expression) {
  const {tabs} = await send('root', 'listTabs');
  assert.equal(tabs.length, 1, 'The app should have one browser session');
  const {frame} = await send(tabs[0].actor, 'getTarget');
  const response = await send(frame.consoleActor, 'evaluateJSAsync', {
    text: `JSON.stringify((() => { ${expression} })())`, options: {},
  });
  const result = await wait(message => message.type === 'evaluationResult' && message.resultID === response.resultID);
  assert.ok(!result.hasException, JSON.stringify(result));
  assert.equal(typeof result.result, 'string');
  return JSON.parse(result.result);
}
const state = () => evaluate(`const video = document.querySelector('video');
  return {url:location.href,rate:video?.playbackRate,paused:video?.paused,
    time:video?.currentTime,duration:video?.duration,ready:video?.readyState,
    fullscreen:!!document.fullscreenElement};`);
async function until(check, message) {
  let last;
  for (let attempt = 0; attempt < 100; attempt++) {
    last = await state();
    if (check(last)) return last;
    await delay(100);
  }
  assert.fail(`${message}: ${JSON.stringify(last)}`);
}
function nativeUi() {
  // Do not accidentally tap coordinates from an old dump if UiAutomation is busy.
  for (let attempt = 0; attempt < 3; attempt++) {
    device('shell', 'rm', '-f', '/sdcard/accelerator-window.xml');
    const output = device('shell', 'uiautomator', 'dump', '/sdcard/accelerator-window.xml');
    if (output.includes('dumped to:')) {
      return device('shell', 'cat', '/sdcard/accelerator-window.xml');
    }
  }
  assert.fail('UiAutomation could not produce a fresh UI snapshot');
}
function tap(node) {
  assert.ok(node, 'Expected native or webpage control must be visible');
  const [, x1, y1, x2, y2] = node.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
  device('shell', 'input', 'tap', String((+x1 + +x2) / 2), String((+y1 + +y2) / 2));
}
let switchControl;
// The switch is above the page and status text: playback cannot move its bounds.
// Capture it while media is paused; accelerated media can keep UiAutomation busy.
function toggle() { tap(switchControl); }
function tapText(text) { tap(nativeUi().match(new RegExp(`<node[^>]*text="${text}"[^>]*>`))?.[0]); }
const results = {};
try {
  await wait(message => message.applicationType === 'browser');
  const {addons} = await send('root', 'listAddons');
  assert.ok(addons.some(addon => addon.id === 'accelerator@ad-ccelerator.local'), 'Bundled extension must be installed');
  results.bundledExtension = true;
  // robots.txt has no YouTube player scripts to compete with the local fixture.
  await evaluate('location.href="https://m.youtube.com/robots.txt"; return true;');
  await until(value => value.url === 'https://m.youtube.com/robots.txt', 'Fixture origin did not load');
  const html = readFileSync(new URL('../test/fixtures/player.html', import.meta.url), 'utf8');
  const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];
  await evaluate(`const parsed=new DOMParser().parseFromString(${JSON.stringify(html)},'text/html');
    document.head.innerHTML=parsed.head.innerHTML;
    document.body.innerHTML=parsed.body.innerHTML;
    ${script}
    return true;`);
  await until(value => value.ready >= 2 && Number.isFinite(value.duration), 'Recorded local media did not load');
  let xml = nativeUi();
  assert.ok(!xml.includes('Connecting accelerator'), 'Production extension must be connected');
  switchControl = xml.match(/<node[^>]*class="android.widget.Switch"[^>]*>/)?.[0];
  assert.ok(switchControl);
  const enabled = /class="android.widget.Switch"[^>]*checked="true"/.test(xml);
  if (!enabled) toggle();
  await evaluate('document.querySelector("video").playbackRate=1.5; return true;');
  await delay(200);
  tapText('Play fixture');
  await until(value => !value.paused, 'Video did not play after user tap');
  const normalStart = await state();
  await delay(350);
  const normalEnd = await state();
  assert.equal(normalEnd.rate, 1.5);
  assert.ok(normalEnd.time - normalStart.time > 0.2, 'Normal media clock must advance');
  results.normalContent = true;
  await evaluate('document.querySelector(".html5-video-player").classList.add("ad-showing"); return true;');
  const accelerated = await until(value => [16, 8, 4].includes(value.rate), 'Bundled content script did not accelerate');
  results.acceptedRate = accelerated.rate;
  // Looping the short fixture keeps it playable through native UI checks.
  const start = await state();
  const clock = performance.now();
  await delay(200);
  const end = await state();
  const elapsed = (performance.now() - clock) / 1000;
  const mediaElapsed = (end.time - start.time + end.duration) % end.duration;
  results.mediaSecondsPerSecond = mediaElapsed / elapsed;
  assert.ok(results.mediaSecondsPerSecond > normalEnd.rate * 2, 'Ad media time must advance substantially faster than content');
  toggle();
  await until(value => value.rate === 1.5, 'Native disabling did not restore content speed');
  toggle();
  await until(value => [16, 8, 4].includes(value.rate), 'Native enabling did not reactivate acceleration');
  results.nativeToggle = true;
  // Stop fixture animation so a fresh accessibility snapshot can locate its button.
  // The ad state stays active and its playback rate remains accelerated.
  await evaluate('document.querySelector("video").pause(); return true;');
  tapText('Fullscreen fixture');
  await until(value => value.fullscreen, 'Fullscreen did not open');
  device('shell', 'input', 'keyevent', 'KEYCODE_BACK');
  await until(value => !value.fullscreen && [16, 8, 4].includes(value.rate), 'Back did not exit fullscreen');
  results.fullscreenBack = true;
  tapText('Play fixture');
  await until(value => !value.paused, 'Fixture did not resume before backgrounding');
  device('shell', 'input', 'keyevent', 'KEYCODE_HOME');
  await until(value => value.rate === 1.5 && value.paused, 'Native pause did not restore speed and pause playback');
  device('shell', 'am', 'start', '-n', 'lab.youtube.accelerator/.MainActivity');
  await until(value => [16, 8, 4].includes(value.rate) && value.paused, 'Resume did not restore controller while keeping video paused');
  results.pauseResume = true;
  await evaluate('document.querySelector(".html5-video-player").classList.remove("ad-showing"); return true;');
  await until(value => value.rate === 1.5, 'Ad exit did not restore content speed');
  results.adExit = true;
  await evaluate('location.href="https://support.google.com/youtube/answer/3037019"; return true;');
  await until(value => value.url.startsWith('https://support.google.com/'), 'External HTTPS did not remain in app browser');
  assert.ok(nativeUi().includes('Acceleration runs on YouTube video pages only'));
  results.sameSessionHttps = true;
  console.log(JSON.stringify({fixtureOnly:true,...results}, null, 2));
} finally {
  try { await evaluate('location.href="https://m.youtube.com/"; return true;'); } catch {}
  fail(new Error('Test completed'));
  socket.destroy();
}
