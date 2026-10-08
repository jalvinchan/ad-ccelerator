(() => {
  if (globalThis.YouTubeAdAccelerator) return;
  const rates = [0.25, 0.5, 0.75, 1, 1.25, 1.5, 1.75, 2, 2.25, 3, 4];
  const documentId = `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
  let enabled = false;
  let suspended = false;
  let appSuspended = false;
  let session = null;
  let player = null;
  let selected = null;
  let scheduled = null;
  let watchdog = null;
  let videoKey = null;
  let generation = 0;
  let contentRate = 1;
  let contentError = null;
  let failedVideo = null;
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

  // Media sources change for ads. The public page URL, not currentSrc, owns identity.
  function pageKey() {
    const url = new URL(location.href);
    const id = url.searchParams.get("v");
    if (id) return `video:${id}`;
    const path = url.pathname.match(/^\/(?:shorts|live|embed)\/([^/]+)/);
    return path ? `video:${path[1]}` : url.pathname;
  }

  function end(reason) {
    const previous = session;
    session = null;
    player = null;
    if (watchdog !== null) clearInterval(watchdog);
    watchdog = null;
    previous?.finish(reason);
  }

  function refreshContext() {
    const key = pageKey();
    if (key === videoKey) return;
    end("video-changed");
    videoKey = key;
    generation++;
    contentRate = 1;
    contentError = null;
    failedVideo = null;
  }

  function applyContent(video) {
    if (failedVideo === video) return false;
    try {
      if (video.playbackRate !== contentRate) video.playbackRate = contentRate;
      if (video.playbackRate !== contentRate) throw new Error("Player did not accept the content rate");
      contentError = null;
      return true;
    } catch (error) {
      failedVideo = video; // Do not spin on a rejecting player; retry on a command/replacement/metadata.
      contentError = "rate-rejected";
      report("content-rate-rejected", { rate: contentRate, error: error.message });
      return false;
    }
  }

  function reconcile() {
    if (scheduled !== null) clearTimeout(scheduled);
    scheduled = null;
    refreshContext();
    if (suspended || appSuspended) { end("app-paused"); selected = null; return; }
    const candidates = [...document.querySelectorAll(".html5-video-player")]
      .map(element => ({ player: element, video: element.querySelector("video") }))
      .filter(item => visible(item.video))
      .sort((a, b) => {
        const area = item => { const rect = item.video.getBoundingClientRect(); return rect.width * rect.height; };
        return area(b) - area(a);
      });
    selected = candidates[0] ?? null;
    const ad = selected?.player.classList.contains("ad-showing");
    if (session && (!enabled || !ad || selected.player !== player || selected.video !== session.video)) {
      end(!enabled ? "disabled" : ad ? "player-replaced" : "ad-state-cleared");
    }
    if (!selected) return;
    if (!ad) { applyContent(selected.video); return; }
    // Content selection remains independent of the acceleration toggle and ad media.
    if (!enabled) return;
    if (!session) {
      player = selected.player;
      session = new YouTubeAdLabSession(selected.video, contentRate, now, report);
      watchdog = setInterval(reconcile, 100);
    }
    session.tick();
  }

  function schedule() {
    if (scheduled === null) scheduled = setTimeout(reconcile, 25);
  }
  function context() { return selected ? `${documentId}:${generation}` : null; }

  const observer = new MutationObserver(schedule);
  observer.observe(document.documentElement, {
    childList: true, subtree: true, attributes: true,
    attributeFilter: ["class", "hidden", "src"],
  });
  for (const event of ["loadedmetadata", "ratechange", "play", "emptied"])
    document.addEventListener(event, () => {
      if (event === "loadedmetadata") failedVideo = null;
      schedule();
    }, true);
  for (const event of ["yt-navigate-start", "pagehide"]) {
    globalThis.addEventListener(event, () => { suspended = true; end("navigation"); selected = null; });
  }
  for (const event of ["yt-navigate-finish", "pageshow", "popstate"]) {
    globalThis.addEventListener(event, () => { suspended = false; schedule(); });
  }
  globalThis.YouTubeAdAccelerator = {
    setEnabled(value) { enabled = value === true; reconcile(); },
    setSuspended(value) { appSuspended = value === true; reconcile(); },
    setContentRate(rate, expectedContext) {
      reconcile(); // Reject commands that raced URL navigation or a hidden/removed player.
      if (!rates.includes(rate)) return { accepted: false, error: "invalid-rate" };
      if (!context() || expectedContext !== context()) return { accepted: false, error: "stale-context" };
      const previous = contentRate;
      contentRate = rate;
      contentError = null;
      failedVideo = null;
      const ad = selected.player.classList.contains("ad-showing");
      if (session) session.baseline = rate;
      if (!ad && !applyContent(selected.video)) {
        contentRate = previous;
        return { accepted: false, error: "rate-rejected" };
      }
      return { accepted: true, pending: ad };
    },
    getPlayer() { return selected; },
    getStatus() {
      reconcile();
      return { enabled, adActive: Boolean(selected?.player.classList.contains("ad-showing")),
        rate: session?.video.playbackRate ?? null, contentRate,
        contentAppliedRate: selected && !selected.player.classList.contains("ad-showing")
          ? selected.video.playbackRate : null,
        contentError, videoContext: context(), events };
    },
  };
})();
