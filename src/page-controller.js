(() => {
  if (globalThis.YouTubeAdAccelerator) return;
  // The selector offers 0.25× through 4× in 0.05× steps.
  const selectableRate = rate => typeof rate === "number" && rate >= 0.25 && rate <= 4
    && Math.abs(rate * 20 - Math.round(rate * 20)) < 1e-6;
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
  let needsContent = true;
  let wasAd = false;
  let gesture = null;
  let settling = null;
  const writtenRates = new WeakMap();
  const validRate = rate => Number.isFinite(rate) && rate > 0 && rate <= 16;
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

  function shorts() {
    return /^\/shorts\//.test(new URL(location.href).pathname)
      || Boolean(selected?.video.closest?.("ytd-reel-video-renderer, ytm-reel-item-renderer"));
  }
  function normalVideo() {
    const url = new URL(location.href);
    return !shorts() && ((url.pathname === "/watch" && url.searchParams.has("v"))
      || /^\/(?:live|embed)\/[^/]+/.test(url.pathname));
  }
  function invalidateGesture() {
    gesture = null;
    if (settling !== null) clearTimeout(settling);
    settling = null;
  }
  function holding() {
    if (gesture && !gesture.moved && gesture.baseline === 2 && now() - gesture.started >= 500)
      gesture.hold = true;
    return Boolean(gesture?.hold);
  }

  function end(reason) {
    const previous = session;
    session = null;
    player = null;
    if (watchdog !== null) clearInterval(watchdog);
    watchdog = null;
    if (previous) {
      writtenRates.set(previous.video, previous.baseline);
      previous.finish(reason);
    }
  }

  function refreshContext() {
    const key = pageKey();
    if (key === videoKey) return;
    invalidateGesture();
    end("video-changed");
    videoKey = key;
    generation++;
    contentRate = 1;
    contentError = null;
    failedVideo = null;
    needsContent = true;
    wasAd = false;
  }

  function applyContent(video) {
    if (failedVideo === video) return false;
    try {
      if (video.playbackRate !== contentRate) {
        writtenRates.set(video, contentRate);
        video.playbackRate = contentRate;
      }
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
    if (suspended || appSuspended || document.hidden) {
      invalidateGesture(); end("app-paused"); selected = null; needsContent = true; return;
    }
    const candidates = [...document.querySelectorAll(".html5-video-player")]
      .map(element => ({ player: element, video: element.querySelector("video") }))
      .filter(item => visible(item.video))
      .sort((a, b) => {
        const area = item => { const rect = item.video.getBoundingClientRect(); return rect.width * rect.height; };
        return area(b) - area(a);
      });
    const next = candidates[0] ?? null;
    if (next?.video !== selected?.video || next?.player !== selected?.player) {
      invalidateGesture();
      needsContent = true;
      failedVideo = null;
    }
    selected = next;
    const ad = selected?.player.classList.contains("ad-showing");
    if (session && (!enabled || !ad || selected.player !== player || selected.video !== session.video)) {
      end(!enabled ? "disabled" : ad ? "player-replaced" : "ad-state-cleared");
    }
    if (!selected) return;
    if (ad) invalidateGesture();
    if (!ad) {
      if (wasAd || (needsContent && normalVideo())) applyContent(selected.video);
      else if (needsContent && validRate(selected.video.playbackRate)) contentRate = selected.video.playbackRate;
      needsContent = false;
      wasAd = false;
      return;
    }
    wasAd = true;
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

  // YouTube restyles the whole page constantly; only changes that can touch the chosen video matter.
  // Anything missed here is still caught by the 1 s status poll, which calls reconcile().
  function relevantMutation(record) {
    if (record.type !== "attributes" || !selected) return true;
    const target = record.target;
    return target.nodeName === "VIDEO" || Boolean(target.classList?.contains("html5-video-player"))
      || Boolean(target.contains?.(selected.player));
  }
  const observer = new MutationObserver(records => {
    if (!records?.length || records.some(relevantMutation)) schedule();
  });
  observer.observe(document.documentElement, {
    childList: true, subtree: true, attributes: true,
    attributeFilter: ["class", "hidden", "src"],
  });
  document.addEventListener("ratechange", event => {
    const video = event.target;
    if (video !== selected?.video) return;
    const rate = video.playbackRate;
    if (writtenRates.has(video)) {
      const expected = writtenRates.get(video);
      writtenRates.delete(video);
      if (rate === expected) return;
    }
    if (suspended || appSuspended || document.hidden || session
      || selected.player.classList.contains("ad-showing") || wasAd || needsContent) { schedule(); return; }
    if (gesture && !gesture.moved && rate === 2) gesture.hold = true;
    if (!holding() && validRate(rate)) {
      contentRate = rate;
      contentError = null;
      failedVideo = null;
    }
    schedule();
  }, true);
  for (const event of ["loadedmetadata", "play", "emptied"])
    document.addEventListener(event, () => {
      if (event === "loadedmetadata") failedVideo = null;
      schedule();
    }, true);

  // Observe YouTube's gesture; never capture pointers or suppress its handlers.
  document.addEventListener("pointerdown", event => {
    reconcile();
    if (gesture || !selected || event.isPrimary === false || event.button !== 0
      || selected.player.classList.contains("ad-showing")) return;
    const rect = selected.video.getBoundingClientRect();
    // Mobile YouTube mounts its native controls beside, rather than inside, the player.
    const container = selected.player.closest?.(".player-container");
    const onPlayer = selected.player.contains(event.target)
      || (container && event.target.closest?.(".player-container") === container);
    if (event.clientX < rect.left || event.clientX > rect.right
      || event.clientY < rect.top || event.clientY > rect.bottom
      || !onPlayer
      || event.target.closest?.("button, a, input, select, textarea, [role=button], [role=slider], [role=menuitem], .ytp-chrome-controls, .ytp-settings-menu")) return;
    gesture = { id: event.pointerId, video: selected.video, baseline: contentRate,
      started: now(), x: event.clientX, y: event.clientY, moved: false, hold: false };
  }, { capture: true, passive: true });
  document.addEventListener("pointermove", event => {
    if (gesture?.id === event.pointerId && Math.hypot(event.clientX - gesture.x, event.clientY - gesture.y) > 12)
      gesture.moved = true;
  }, { capture: true, passive: true });
  for (const event of ["pointerup", "pointercancel"])
    document.addEventListener(event, value => {
      if (gesture?.id !== value.pointerId || settling !== null) return;
      if (!holding()) { invalidateGesture(); return; }
      const pending = gesture;
      settling = setTimeout(() => {
        if (gesture !== pending) return;
        settling = null;
        reconcile();
        if (gesture !== pending || selected?.video !== pending.video) return;
        gesture = null;
        if (normalVideo()) applyContent(pending.video);
        else if (validRate(pending.video.playbackRate)
          && (pending.video.playbackRate !== 2 || pending.baseline === 2))
          contentRate = pending.video.playbackRate;
      }, 250);
    }, { capture: true, passive: true });
  document.addEventListener("visibilitychange", () => { reconcile(); }, true);
  for (const event of ["yt-navigate-start", "pagehide"]) {
    globalThis.addEventListener(event, () => { suspended = true; invalidateGesture(); end("navigation"); selected = null; needsContent = true; });
  }
  for (const event of ["yt-navigate-finish", "pageshow", "popstate"]) {
    globalThis.addEventListener(event, () => { suspended = false; schedule(); });
  }
  globalThis.YouTubeAdAccelerator = {
    setEnabled(value) { enabled = value === true; reconcile(); },
    setSuspended(value) { appSuspended = value === true; reconcile(); },
    setContentRate(rate, expectedContext) {
      reconcile(); // Reject commands that raced URL navigation or a hidden/removed player.
      if (!selectableRate(rate)) return { accepted: false, error: "invalid-rate" };
      rate = Math.round(rate * 20) / 20;
      if (!context() || expectedContext !== context()) return { accepted: false, error: "stale-context" };
      if (shorts() || !normalVideo()) return { accepted: false, error: "unavailable" };
      const temporary = holding();
      if (!temporary) invalidateGesture();
      const previous = contentRate;
      contentRate = rate;
      contentError = null;
      failedVideo = null;
      const ad = selected.player.classList.contains("ad-showing");
      if (session) session.baseline = rate;
      if (temporary) {
        gesture.baseline = rate;
        return { accepted: true, pending: true };
      }
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
        rate: session?.video.playbackRate ?? null, contentRate, contentSpeedAvailable: normalVideo(),
        contentAppliedRate: selected && !holding() && !selected.player.classList.contains("ad-showing")
          ? selected.video.playbackRate : null,
        contentError, videoContext: context(), events };
    },
  };
})();
