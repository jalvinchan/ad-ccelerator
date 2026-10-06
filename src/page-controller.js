(() => {
  if (globalThis.YouTubeAdAccelerator) return;
  let enabled = false;
  let suspended = false;
  let appSuspended = false;
  let session = null;
  let player = null;
  let normal = null;
  let scheduled = null;
  let watchdog = null;
  const events = [];
  const now = () => performance.now();

  function report(event, details) {
    const entry = { event, time: new Date().toISOString(), ...details };
    events.push(entry);
    if (events.length > 30) events.shift();
    console.info(`[YouTube Ad Accelerator] ${JSON.stringify(entry)}`);
  }

  function visible(element) {
    if (!element?.isConnected || element.getClientRects().length === 0) return false;
    for (let node = element; node?.nodeType === 1; node = node.parentElement) {
      const style = getComputedStyle(node);
      if (node.hidden || style.display === "none" || style.visibility === "hidden"
        || style.visibility === "collapse" || Number(style.opacity) === 0) return false;
    }
    return true;
  }

  function end(reason) {
    const previous = session;
    session = null;
    player = null;
    if (watchdog !== null) clearInterval(watchdog);
    watchdog = null;
    previous?.finish(reason);
  }

  function reconcile() {
    if (scheduled !== null) clearTimeout(scheduled);
    scheduled = null;
    if (!enabled || suspended || appSuspended) { end(enabled ? "navigation" : "disabled"); return; }
    const candidates = [...document.querySelectorAll(".html5-video-player")]
      .map(element => ({ player: element, video: element.querySelector("video") }))
      .filter(item => visible(item.video))
      .sort((a, b) => {
        const area = item => { const rect = item.video.getBoundingClientRect(); return rect.width * rect.height; };
        return area(b) - area(a);
      });
    const selected = candidates[0];
    const ad = selected?.player.classList.contains("ad-showing");
    if (session && (!ad || selected.player !== player || selected.video !== session.video)) {
      end(ad ? "player-replaced" : "ad-state-cleared");
    }
    if (!selected) return;
    if (!ad) {
      normal = { video: selected.video, rate: selected.video.playbackRate };
      return;
    }
    if (!session) {
      player = selected.player;
      const baseline = normal?.video === selected.video ? normal.rate : selected.video.playbackRate;
      session = new YouTubeAdLabSession(selected.video, baseline, now, report);
      // Only poll during ads: styles and media properties need not mutate the DOM.
      watchdog = setInterval(reconcile, 100);
    }
    session.tick();
  }

  function schedule() {
    if (scheduled === null) scheduled = setTimeout(reconcile, 25);
  }

  const observer = new MutationObserver(schedule);
  observer.observe(document.documentElement, {
    childList: true, subtree: true, attributes: true,
    attributeFilter: ["class", "hidden", "src"],
  });
  for (const event of ["loadedmetadata", "ratechange", "play", "emptied"])
    document.addEventListener(event, schedule, true);
  for (const event of ["yt-navigate-start", "pagehide"]) {
    globalThis.addEventListener(event, () => { suspended = true; end("navigation"); normal = null; });
  }
  for (const event of ["yt-navigate-finish", "pageshow"]) {
    globalThis.addEventListener(event, () => { suspended = false; schedule(); });
  }
  globalThis.YouTubeAdAccelerator = {
    setEnabled(value) { enabled = value === true; reconcile(); },
    setSuspended(value) {
      appSuspended = value === true;
      if (appSuspended) { end("app-paused"); normal = null; }
      else reconcile();
    },
    getStatus() {
      return { enabled, adActive: Boolean(session), rate: session?.video.playbackRate ?? null, events };
    },
  };
})();
