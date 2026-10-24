// Runs in the extension's isolated world, not in YouTube's JavaScript world.
(() => {
  if (globalThis.YouTubeAcceleratorHost) return;
  if (window !== window.top || ![
    "https://m.youtube.com", "https://www.youtube.com",
  ].includes(location.origin) || new URL(location.href).username || new URL(location.href).password) return;
  globalThis.YouTubeAcceleratorHost = true;
  const controller = globalThis.YouTubeAdAccelerator;
  let port = null;
  let timer = null;
  let enabled = false;
  let paused = true;
  let revision = -1;
  let lastCommandId = 0;
  let reporting = false;
  let lastStatus = null;
  let unchangedTicks = 0;
  // Which native layer shows this page: null until the host says, so links are never rerouted blind.
  let layer = null;
  // A playing video keeps playing when the player changes size; YouTube may pause it on the resize.
  let keepPlaying = null;
  let keepPlayingUntil = 0;
  // A video opened by a tap loads as a fresh page, which YouTube does not autoplay; the host asks for it instead.
  let autoplayUntil = 0;
  let played = false;
  const MINI_CLASS = "yt-accelerator-mini";
  // The page keeps its full size in the mini player, because YouTube pauses a player it sees shrink. The host
  // draws only the top 16:9 strip scaled down, so the player is pinned there and its own controls are hidden.
  const MINI_CSS = `html.${MINI_CLASS}, html.${MINI_CLASS} body { overflow: hidden !important; background: #000 !important; }
    html.${MINI_CLASS} .player-container, html.${MINI_CLASS} #player-container-id {
      position: fixed !important; top: 0 !important; left: 0 !important; width: 100vw !important;
      height: 56.25vw !important; max-height: none !important; margin: 0 !important; padding: 0 !important;
      transform: none !important; z-index: 2147483646 !important; background: #000 !important; }
    html.${MINI_CLASS} #player-control-overlay { display: none !important; }
    html.${MINI_CLASS} .player-container *:not(video):not(:has(video)) { visibility: hidden !important; }`;

  const controls = globalThis.YouTubePlayerControls(controller, videoContext => {
    if (port && !paused && !document.hidden) port.postMessage({ type: "open-speed-selector",
      videoContext, revision, url: location.href });
  }, (x, y) => {
    if (port && !paused && !document.hidden) port.postMessage({ type: "chip-position", x, y, revision, url: location.href });
  }, () => { if (port) report(); });

  function apply() {
    const suspended = paused || document.hidden;
    controller.setSuspended(suspended);
    controller.setEnabled(enabled);
    if (suspended) document.querySelectorAll("video").forEach(video => video.pause());
  }

  function report(heartbeat = false) {
    // controls.update() can call back here when YouTube's controls change; the outer call already sends it.
    if (!port || reporting) return;
    reporting = true;
    try {
      const state = controller.getStatus();
      controls.update(state, !paused && !document.hidden && !layer?.mini);
      const { enabled, adActive, rate, contentRate, contentAppliedRate, contentError, videoContext, contentSpeedAvailable, seekAvailable } = state;
      const message = { type: "status", enabled, adActive, rate, contentRate,
        contentAppliedRate, contentError, videoContext, contentSpeedAvailable, seekAvailable, url: location.href, revision, paused,
        controlsVisible: controls.controlsShown(), playing: playing() };
      const text = JSON.stringify(message);
      // Unchanged status is resent only every fifth tick, so native state still recovers if it reset.
      if (text === lastStatus && !(heartbeat && ++unchangedTicks >= 5)) return;
      lastStatus = text;
      unchangedTicks = 0;
      port.postMessage(message);
    } finally { reporting = false; }
  }

  const watchUrl = url => url.origin === location.origin && ((url.pathname === "/watch" && Boolean(url.searchParams.get("v")))
    || /^\/live\/[^/]+\/?$/.test(url.pathname));

  function currentVideo() { return controller.getPlayer?.()?.video ?? null; }
  function playing() { const video = currentVideo(); return Boolean(video && !video.paused && !video.ended); }

  // Through YouTube's player API when present: its own state says paused, and it re-pauses a video that
  // was started underneath it on the next timeupdate.
  function setPlaying(play) {
    const api = controller.getPlayer?.()?.player?.wrappedJSObject;
    const method = api?.[play ? "playVideo" : "pauseVideo"];
    if (typeof method === "function") { method.call(api); return; }
    const video = currentVideo();
    if (play) video?.play()?.catch?.(() => {});
    else video?.pause();
  }

  function resume() {
    const video = keepPlaying;
    if (!video || Date.now() > keepPlayingUntil || paused || document.hidden || !video.paused) return;
    setPlaying(true);
  }

  function autoplay() {
    if (played || Date.now() > autoplayUntil || !layer?.watch || layer.mini || paused || document.hidden
      || !currentVideo()) return;
    setPlaying(true);
  }

  function setLayer(next) {
    if (layer && next.mini !== layer.mini && playing()) {
      keepPlaying = currentVideo();
      keepPlayingUntil = Date.now() + 1500;
      setTimeout(resume, 300);
    }
    layer = next;
    const root = document.documentElement;
    if (!root) return;
    if (layer.mini && !document.getElementById?.(MINI_CLASS)) {
      const style = document.createElement("style");
      style.id = MINI_CLASS;
      style.textContent = MINI_CSS;
      (document.head ?? root).append(style);
    }
    root.classList.toggle(MINI_CLASS, layer.mini);
  }

  function disconnect() {
    port = null;
    controls.hide();
    clearInterval(timer);
    timer = null;
    enabled = false;
    paused = true;
    revision = -1;
    lastCommandId = 0;
    lastStatus = null;
    unchangedTicks = 0;
    layer = null;
    keepPlaying = null;
    document.documentElement?.classList.remove(MINI_CLASS);
    apply();
  }

  function connect() {
    if (port) return;
    lastStatus = null;
    apply(); // Fail closed until the native host supplies its current state.
    const connection = browser.runtime.connectNative("accelerator");
    port = connection;
    connection.onMessage.addListener(message => {
      if (port !== connection) return;
      if (message?.type === "content-speed") {
        if (paused || document.hidden || message.revision !== revision
          || !Number.isSafeInteger(message.commandId) || message.commandId <= lastCommandId
          || typeof message.videoContext !== "string" || message.videoContext.length > 200
          || typeof message.rate !== "number") return;
        lastCommandId = message.commandId;
        const result = controller.setContentRate(message.rate, message.videoContext);
        connection.postMessage({ type: "speed-result", commandId: message.commandId,
          revision, videoContext: message.videoContext, ...result });
        report();
        return;
      }
      if (message?.type === "seek-interval") {
        if ([5, 10, 15, 30].includes(message.seconds)) controls.setSeekInterval(message.seconds);
        return;
      }
      if (message?.type === "default-speed") {
        if (typeof message.rate === "number") controller.setDefaultRate?.(message.rate);
        return;
      }
      if (message?.type === "seek") {
        if (paused || document.hidden || message.revision !== revision
          || typeof message.videoContext !== "string" || message.videoContext.length > 200
          || typeof message.seconds !== "number" || ![5, 10, 15, 30].includes(Math.abs(message.seconds))) return;
        controller.seekBy(message.seconds, message.videoContext);
        report();
        return;
      }
      if (message?.type === "layer") {
        if (typeof message.watch === "boolean" && typeof message.mini === "boolean")
          setLayer({ watch: message.watch, mini: message.watch && message.mini });
        // Only a page that has never played honours it, so the page being replaced ignores it.
        if (message.autoplay === true && message.watch && !played) {
          autoplayUntil = Date.now() + 15000;
          autoplay();
        }
        report();
        return;
      }
      // The mini player's own play/pause button; the full player keeps YouTube's controls.
      if (message?.type === "playback") {
        const video = currentVideo();
        if (paused || document.hidden || message.revision !== revision || typeof message.play !== "boolean"
          || !layer?.mini || !video) return;
        keepPlaying = null;
        setPlaying(message.play);
        report();
        return;
      }
      if (message?.type === "chip-position") {
        const fraction = value => typeof value === "number" && value >= 0 && value <= 1;
        if (message.x === null && message.y === null) controls.setPosition(null);
        else if (fraction(message.x) && fraction(message.y)) controls.setPosition(message.x, message.y);
        return;
      }
      if (message?.type !== "state"
        || typeof message.enabled !== "boolean" || typeof message.paused !== "boolean"
        || !Number.isSafeInteger(message.revision) || message.revision <= revision) return;
      revision = message.revision;
      enabled = message.enabled;
      paused = message.paused;
      apply();
      report(); // Acknowledges restoration and video pause before native suspension.
    });
    connection.onDisconnect.addListener(() => { if (port === connection) disconnect(); });
    timer = setInterval(() => { autoplay(); report(true); }, 1000);
    report();
  }

  // The host keeps the browse page alive under a separate player layer: videos open there, other pages here.
  globalThis.addEventListener("click", event => {
    if (!port || !layer || paused || document.hidden || event.defaultPrevented || event.button !== 0
      || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
    const link = event.target?.closest?.("a[href]");
    if (!link) return;
    let target;
    try { target = new URL(link.href, location.href); } catch { return; }
    if (target.protocol !== "https:") return;
    const here = new URL(location.href);
    if (layer.watch ? watchUrl(target) || (target.origin === here.origin && target.pathname === here.pathname
      && target.search === here.search) : !watchUrl(target)) return;
    event.preventDefault();
    event.stopImmediatePropagation();
    port.postMessage({ type: layer.watch ? "open-browse" : "open-watch", target: target.href, revision, url: location.href });
  }, true);
  document.addEventListener("visibilitychange", () => { apply(); report(); });
  for (const type of ["play", "pause"]) document.addEventListener(type, event => {
    if (type === "play") played = true;
    if (type === "pause" && event?.target === keepPlaying) resume();
    report();
  }, true);
  for (const type of ["loadedmetadata", "canplay"]) document.addEventListener(type, autoplay, true);
  globalThis.addEventListener("pagehide", () => {
    paused = true;
    apply();
    if (port) port.disconnect();
    disconnect();
  });
  globalThis.addEventListener("pageshow", connect);
  connect();
})();
