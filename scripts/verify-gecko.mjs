// Node 22+, a running DEBUG app, and GeckoView's socket forwarded to localhost:6000.
// Uses only the debugger to install a controlled video fixture; no test code is in the APK.
import assert from 'node:assert/strict';
import net from 'node:net';
import { readFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';

const adb = process.env.ADB ?? 'adb';
const device = (...args) => execFileSync(adb, args, {encoding: 'utf8', timeout: 15000});
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const socket = net.connect(Number(process.env.GECKO_PORT ?? 6000), '127.0.0.1');
let buffer = Buffer.alloc(0);
let interrupted = false;
for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, () => {
  interrupted = true;
  fail(new Error('Acceptance interrupted'));
});
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
function wait(check, label = "event") {
  const index = queue.findIndex(check);
  if (index >= 0) return Promise.resolve(queue.splice(index, 1)[0]);
  return new Promise((resolve, reject) => {
    const item = {check, resolve, reject};
    item.timer = setTimeout(() => {
      const index = waiters.indexOf(item);
      if (index >= 0) waiters.splice(index, 1);
      reject(new Error(`Browser debugger timed out: ${label}`));
    }, 15000);
    waiters.push(item);
  });
}
async function send(to, type, params = {}) {
  if (interrupted) throw new Error("Acceptance interrupted");
  const pending = wait(message => message.from === to && !message.type, `${to}/${type}`);
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
  const result = await wait(message => message.type === 'evaluationResult' && message.resultID === response.resultID, "evaluationResult");
  assert.ok(!result.hasException, JSON.stringify(result));
  assert.equal(typeof result.result, 'string');
  return JSON.parse(result.result);
}
const state = () => evaluate(`const video = document.querySelector('video');
  return {url:location.href,rate:video?.playbackRate,paused:video?.paused,
    time:video?.currentTime,duration:video?.duration,ready:video?.readyState,
    fullscreen:!!document.fullscreenElement,width:innerWidth,height:innerHeight,
    oldDocument:window.acceptanceOldDocument === true,
    pullMarker:window.pullFixture === true, scrollY,
    pageReady:document.readyState,sourceReady:!!video?.getAttribute("src")};`);
async function until(check, message) {
  let last;
  const deadline = Date.now() + 45000;
  for (let attempt = 0; attempt < 100 && Date.now() < deadline; attempt++) {
    try { last = await state(); }
    catch (error) {
      // A navigation can replace an actor between listTabs and getTarget.
      if (!/tabDestroyed|noSuchActor|Browser debugger timed out: .*consoleActor/.test(error.message)) throw error;
      await delay(100);
      continue;
    }
    if (check(last)) return last;
    await delay(100);
  }
  assert.fail(`${message}: ${JSON.stringify(last)}`);
}
async function navigate(url) {
  try { await evaluate('window.acceptanceOldDocument=true; return true;'); }
  catch (error) {
    // Gecko can retain a dead console actor after a process switch. Navigation does not need it.
    if (!/Browser debugger timed out: .*consoleActor/.test(error.message)) throw error;
  }
  const {tabs} = await send('root', 'listTabs');
  const {frame} = await send(tabs[0].actor, 'getTarget');
  await send(frame.actor, 'navigateTo', {url});
  await until(value => value.url.startsWith(url) && value.pageReady === 'complete' && !value.oldDocument,
    'Native debugger navigation did not finish');
}
function requestedOrientation() {
  const dump = device('shell','dumpsys','activity','activities');
  return dump.match(/requestedOrientation=(\S+)/)?.[1] ?? dump.match(/mRequestedOrientation=(-?\d+)/)?.[1];
}
async function tapFixture(id, label) {
  await evaluate(`document.getElementById(${JSON.stringify(id)}).scrollIntoView({block:"center"}); return true;`);
  tapText(label);
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
function tapText(text) {
  const escaped = text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  let xml;
  for (let attempt = 0; attempt < 3; attempt++) {
    xml = nativeUi();
    const node = xml.match(new RegExp(`<node[^>]*text="${escaped}"[^>]*>`))?.[0];
    if (node) { tap(node); return; }
  }
  const buttons = [...xml.matchAll(/<node[^>]*class="android.widget.Button"[^>]*>/g)]
    .map(match => match[0].match(/text="([^"]*)"/)?.[1]);
  assert.fail(`Control ${text} was not visible; buttons: ${JSON.stringify(buttons)}`);
}
async function chooseSpeed(fromLabel, rate) {
  tapText(fromLabel);
  const slider = nativeUi().match(/<node[^>]*class="android.widget.SeekBar"[^>]*>/)?.[0];
  assert.ok(slider, 'Speed slider must exist');
  const [, x1, y1, x2, y2] = slider.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/).map(Number);
  // Tap near the target on the track, then reach it exactly with the ±0.05× steps.
  const fraction = (rate - 0.25) / 3.75;
  device('shell', 'input', 'tap', String(Math.round(x1 + fraction * (x2 - x1))), String(Math.round((y1 + y2) / 2)));
  const readout = () => Number(nativeUi().match(/<node[^>]*text="(\d+(?:\.\d+)?)×"/)?.[1]);
  for (let attempt = 0; attempt < 3; attempt++) {
    const steps = Math.round((rate - readout()) * 20);
    if (steps === 0) break;
    for (let i = 0; i < Math.abs(steps); i++) tapText(steps > 0 ? '+0.05×' : '−0.05×');
  }
  assert.equal(readout(), rate, 'Speed readout must show the requested rate');
  tapText('Done');
  assert.ok(!nativeUi().includes('android.widget.SeekBar'), 'Speed selector must dismiss after Done');
}
const results = {};
try {
  await wait(message => message.applicationType === "browser", "greeting");
  const {addons} = await send('root', 'listAddons');
  assert.ok(addons.some(addon => addon.id === 'accelerator@ad-ccelerator.local'), 'Bundled extension must be installed');
  results.bundledExtension = true;
  results.previousRotation = device('shell','settings','get','system','accelerometer_rotation').trim();
  results.previousUserRotation = device('shell','settings','get','system','user_rotation').trim();
  device('shell','settings','put','system','accelerometer_rotation','0');
  device('shell','settings','put','system','user_rotation','0');
  // robots.txt has no YouTube player scripts to compete with the local fixture.
  console.log('Preparing controlled fixture');
  await navigate('https://m.youtube.com/robots.txt');
  await until(value => value.width < value.height, 'Portrait lock did not settle');
  const screen = device('shell','wm','size').match(/Physical size: (\d+)x(\d+)/);
  assert.ok(screen, 'Emulator screen size must be available');
  const swipe = () => device('shell','input','swipe', String(+screen[1] / 2), String(+screen[2] * .4),
    String(+screen[1] / 2), String(+screen[2] * .75), '500');
  console.log('Checking fresh-page refresh');
  await evaluate('window.pullFixture=true; return true;');
  swipe();
  await until(value => !value.pullMarker && value.pageReady === 'complete', 'Pull on a freshly loaded top page did not reload');
  await evaluate('window.pullFixture=true; document.body.style.minHeight="3000px"; scrollTo(0,300); return true;');
  await until(value => value.scrollY >= 200, 'Scroll fixture did not move below the top');
  swipe();
  await delay(600);
  assert.equal((await state()).pullMarker, true, 'Pull begun below top must not reload');
  await evaluate('scrollTo(0,0); return true;');
  await until(value => value.scrollY === 0, 'Scroll fixture did not return to top');
  await delay(300);
  swipe();
  await until(value => !value.pullMarker && value.pageReady === 'complete', 'Pull at top did not reload');
  results.pullToRefresh = true;
  console.log("Passed top-only pull-to-refresh");
  const html = readFileSync(new URL('../test/fixtures/player.html', import.meta.url), 'utf8');
  const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];
  await evaluate(`const parsed=new DOMParser().parseFromString(${JSON.stringify(html)},'text/html');
    history.replaceState(null,"","/watch?v=controlled-fixture");
    document.head.innerHTML=parsed.head.innerHTML;
    document.body.innerHTML=parsed.body.innerHTML;
    ${script}
    return true;`);
  await until(value => value.sourceReady, 'Local recorder did not finish');
  // Gecko may defer decoding until a user gesture. Waiting for readyState first deadlocks the test.
  await tapFixture('play-fixture', 'Play fixture');
  await until(value => value.ready >= 2 && Number.isFinite(value.duration) && !value.paused,
    'Recorded local media did not load after Play');
  await evaluate('document.querySelector("video").pause(); return true;');
  let xml = nativeUi();
  assert.ok(!xml.includes('android.widget.Switch'), 'No acceleration switch');
  assert.ok(!xml.includes('android.widget.EditText'), 'No native address field');
  assert.ok(!xml.includes('Acceleration on') && !xml.includes('Recent searches'), 'No routine banners');
  assert.ok(!xml.includes('text="HOME"') && !xml.includes('text="RELOAD"') && !xml.includes('text="SPEED 1×"'), 'No normal toolbar');
  results.noToolbar = true;
  await until(value => value.rate === 1, 'Fresh document must start at 1×');
  await delay(1100); // Wait for the native status/context.
  await chooseSpeed('Playback speed 1×', 2.25);
  await until(value => value.rate === 2.25, 'Native content-speed command did not apply');
  results.nativeContentSpeed = true;
  console.log("Passed native content-speed selection");
  await tapFixture('play-fixture', 'Play fixture');
  await until(value => !value.paused, 'Video did not play after user tap');
  const normalStart = await state();
  await delay(350);
  const normalEnd = await state();
  assert.equal(normalEnd.rate, 2.25);
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
  results.alwaysOnAcceleration = true;
  console.log("Passed always-on ad acceleration");
  // Stop fixture animation so a fresh accessibility snapshot can locate its button.
  // The ad state stays active and its playback rate remains accelerated.
  await evaluate('document.querySelector("video").pause(); return true;');
  const beforeFullscreenOrientation = requestedOrientation();
  await evaluate('window.fixtureDocument = "retained"; return true;');
  await tapFixture('fullscreen-fixture', 'Fullscreen fixture');
  await until(value => value.fullscreen, 'Fullscreen did not open');
  await until(value => value.width > value.height, 'Fullscreen rotation did not reach landscape');
  assert.ok(['SCREEN_ORIENTATION_SENSOR_LANDSCAPE','6'].includes(requestedOrientation()),
    'Fullscreen must request sensor-landscape, overriding portrait lock');
  const viewport = await evaluate('return {width:innerWidth,height:innerHeight,marker:window.fixtureDocument};');
  assert.ok(viewport.width > viewport.height, 'Fullscreen viewport must be landscape');
  assert.equal(viewport.marker, 'retained', 'Rotation must retain the document');
  // Android's first-fullscreen onboarding can cover the native chip on a fresh emulator.
  if (nativeUi().includes('text="Got it"')) tapText('Got it');
  await chooseSpeed('2.25×', 2.3);
  assert.ok([16,8,4].includes((await state()).rate), 'Ad-time selection must not change acceleration');
  results.fullscreenSpeedDuringAd = true;
  device('shell', 'input', 'keyevent', 'KEYCODE_BACK');
  await until(value => !value.fullscreen && [16, 8, 4].includes(value.rate), 'Back did not exit fullscreen');
  assert.equal(requestedOrientation(), beforeFullscreenOrientation,
    'Exit must restore the previous orientation policy');
  results.fullscreenBack = true;
  console.log("Passed portrait-lock fullscreen, speed selection, Back and document retention");
  await tapFixture('play-fixture', 'Play fixture');
  await until(value => !value.paused, 'Fixture did not resume before backgrounding');
  device('shell', 'input', 'keyevent', 'KEYCODE_HOME');
  await until(value => value.rate === 2.3 && value.paused, 'Native pause did not restore speed and pause playback');
  device('shell', 'am', 'start', '-n', 'lab.youtube.accelerator/.MainActivity');
  await until(value => [16, 8, 4].includes(value.rate) && value.paused, 'Resume did not restore controller while keeping video paused');
  results.pauseResume = true;
  console.log("Passed background/resume restoration");
  await evaluate('document.querySelector(".html5-video-player").classList.remove("ad-showing"); return true;');
  await until(value => value.rate === 2.3, 'Ad exit did not restore content speed');
  results.adExit = true;
  await navigate('https://support.google.com/youtube/answer/3037019');
  assert.ok(!nativeUi().includes('text="2.3×"'), 'No speed chip outside trusted YouTube');
  results.sameSessionHttps = true;
  const {previousRotation, previousUserRotation, ...passed} = results;
  console.log(JSON.stringify({fixtureOnly:true,...passed}, null, 2));
} finally {
  if (results.previousUserRotation) device('shell','settings','put','system','user_rotation',results.previousUserRotation);
  if (results.previousRotation) device('shell','settings','put','system','accelerometer_rotation',results.previousRotation);
  try { await navigate('https://m.youtube.com/'); } catch {}
  fail(new Error('Test completed'));
  socket.destroy();
}
