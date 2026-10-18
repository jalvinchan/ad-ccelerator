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
      controls.update(state, !paused && !document.hidden);
      const { enabled, adActive, rate, contentRate, contentAppliedRate, contentError, videoContext, contentSpeedAvailable, seekAvailable } = state;
      const message = { type: "status", enabled, adActive, rate, contentRate,
        contentAppliedRate, contentError, videoContext, contentSpeedAvailable, seekAvailable, url: location.href, revision, paused,
        controlsVisible: controls.controlsShown() };
      const text = JSON.stringify(message);
      // Unchanged status is resent only every fifth tick, so native state still recovers if it reset.
      if (text === lastStatus && !(heartbeat && ++unchangedTicks >= 5)) return;
      lastStatus = text;
      unchangedTicks = 0;
      port.postMessage(message);
    } finally { reporting = false; }
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
      if (message?.type === "seek") {
        if (paused || document.hidden || message.revision !== revision
          || typeof message.videoContext !== "string" || message.videoContext.length > 200
          || typeof message.seconds !== "number" || ![5, 10, 15, 30].includes(Math.abs(message.seconds))) return;
        controller.seekBy(message.seconds, message.videoContext);
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
    timer = setInterval(() => report(true), 1000);
    report();
  }

  document.addEventListener("visibilitychange", () => { apply(); report(); });
  globalThis.addEventListener("pagehide", () => {
    paused = true;
    apply();
    if (port) port.disconnect();
    disconnect();
  });
  globalThis.addEventListener("pageshow", connect);
  connect();
})();
