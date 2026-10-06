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

  function apply() {
    const suspended = paused || document.hidden;
    controller.setSuspended(suspended);
    controller.setEnabled(enabled);
    if (suspended) document.querySelectorAll("video").forEach(video => video.pause());
  }

  function report() {
    if (!port) return;
    const { enabled, adActive, rate } = controller.getStatus();
    port.postMessage({ type: "status", enabled, adActive, rate, revision, paused });
  }

  function disconnect() {
    port = null;
    clearInterval(timer);
    timer = null;
    enabled = false;
    paused = true;
    revision = -1;
    apply();
  }

  function connect() {
    if (port) return;
    apply(); // Fail closed until the native host supplies its current state.
    const connection = browser.runtime.connectNative("accelerator");
    port = connection;
    connection.onMessage.addListener(message => {
      if (port !== connection || message?.type !== "state"
        || typeof message.enabled !== "boolean" || typeof message.paused !== "boolean"
        || !Number.isSafeInteger(message.revision) || message.revision <= revision) return;
      revision = message.revision;
      enabled = message.enabled;
      paused = message.paused;
      apply();
      report(); // Acknowledges restoration and video pause before native suspension.
    });
    connection.onDisconnect.addListener(() => { if (port === connection) disconnect(); });
    timer = setInterval(report, 1000);
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
