// Page geometry and player-control visibility belong here; the native host owns the selector.
(() => {
  globalThis.YouTubePlayerControls = function (controller, openSelector, savePosition = () => {}, controlsChanged = () => {}) {
    const host = document.createElement("div");
    const shadow = host.attachShadow({ mode: "closed" });
    const SVG_NS = "http://www.w3.org/2000/svg";
    // Built node by node: YouTube enforces Trusted Types, which rejects HTML string assignment.
    function seekIcon(direction) {
      const svg = document.createElementNS(SVG_NS, "svg");
      const attributes = { viewBox: "0 0 30 30", width: "30", height: "30", fill: "none", stroke: "white",
        "stroke-width": "2", "stroke-linecap": "round", "stroke-linejoin": "round", "aria-hidden": "true" };
      for (const [name, value] of Object.entries(attributes)) svg.setAttribute(name, value);
      const paths = direction < 0 ? ["M8.5 9.5A9 9 0 1 1 6 15", "M8.5 4.5v5h5"] : ["M21.5 9.5A9 9 0 1 0 24 15", "M21.5 4.5v5h-5"];
      for (const d of paths) {
        const path = document.createElementNS(SVG_NS, "path");
        path.setAttribute("d", d);
        svg.append(path);
      }
      svg.style.cssText = "position:absolute;left:9px;top:9px;pointer-events:none";
      return svg;
    }
    // One dark pill; segments are transparent so the dividers read as a single control.
    const SEGMENT = "all:initial;box-sizing:border-box;position:relative;display:grid;place-items:center;height:48px;padding:0 10px;background:transparent;color:white;font:600 14px sans-serif;text-align:center;cursor:pointer;touch-action:none;user-select:none;-webkit-user-select:none";
    const button = document.createElement("button");
    button.type = "button";
    button.style.cssText = SEGMENT + ";min-width:60px;border-left:1px solid rgba(255,255,255,.22);border-right:1px solid rgba(255,255,255,.22)";
    const row = document.createElement("div");
    const back = document.createElement("button");
    const forward = document.createElement("button");
    const backNumber = document.createElement("span");
    const forwardNumber = document.createElement("span");
    const flash = document.createElement("div");
    row.style.cssText = "display:flex;align-items:center;height:48px;border-radius:24px;overflow:hidden;background:rgba(24,24,24,.85);transition:transform .15s";
    back.style.cssText = forward.style.cssText = SEGMENT + ";width:48px;padding:0";
    back.type = forward.type = "button";
    backNumber.style.cssText = forwardNumber.style.cssText = "position:relative;top:1px;font:700 10px sans-serif;color:white;pointer-events:none";
    flash.style.cssText = "position:absolute;top:52px;left:50%;transform:translateX(-50%);padding:4px 10px;border-radius:12px;background:rgba(24,24,24,.85);color:white;font:600 12px sans-serif;white-space:nowrap;pointer-events:none;opacity:0;transition:opacity .15s";
    back.append(seekIcon(-1), backNumber);
    forward.append(seekIcon(1), forwardNumber);
    row.append(back, button, forward);
    shadow.append(row, flash);
    host.style.cssText = "position:fixed;z-index:2147483647;display:none;transition:opacity .2s";
    let state = null;
    let active = false;
    let revealedUntil = 0;
    let previousContext = null;
    let seekInterval = 10;
    let flashTimer = null;
    const SEEK_SECONDS = [5, 10, 15, 30];
    // Free placement as fractions of the space the chip can travel within the video; null keeps the default spot.
    let position = null;
    let drag = null;
    let suppressClickUntil = 0;
    const HOLD_MS = 400;
    const SLOP_PX = 10;

    function controlsVisible(player) {
      const controls = player.querySelector(".ytp-chrome-controls");
      if (!controls || !controls.getClientRects().length) return false;
      for (let node = controls; node && node !== player; node = node.parentElement) {
        const style = getComputedStyle(node);
        if (style.display === "none" || style.visibility === "hidden" || Number(style.opacity) === 0) return false;
      }
      return true;
    }
    // m.youtube.com draws its controls in an overlay outside the player; the chip shows and hides with it.
    let observedOverlay = null;
    let reportedControls;
    const overlayObserver = globalThis.MutationObserver ? new MutationObserver(() => paint()) : null;
    // Null when YouTube's overlay is absent; the fullscreen button then falls back to its own timer.
    function controlsShown() {
      const overlay = mobileControls();
      return overlay ? mobileControlsVisible(overlay) : null;
    }
    function mobileControls() {
      const overlay = document.querySelector?.("#player-control-overlay") ?? null;
      if (overlay !== observedOverlay && overlayObserver) {
        overlayObserver.disconnect();
        if (overlay) overlayObserver.observe(overlay, { attributes: true, attributeFilter: ["class"] });
      }
      observedOverlay = overlay;
      return overlay;
    }
    function mobileControlsVisible(overlay) {
      const content = overlay.querySelector(".player-controls-content");
      return Boolean(content) && getComputedStyle(content).visibility !== "hidden";
    }
    function hide() {
      cancelDrag();
      clearTimeout(flashTimer);
      flash.style.opacity = "0";
      host.style.display = "none";
    }
    function clamp(value) { return Math.min(1, Math.max(0, value)); }
    function chipSize() {
      const box = host.getBoundingClientRect?.();
      return { width: box?.width || 48, height: box?.height || 48 };
    }
    function place(rect) {
      // The 68px right margin keeps clear of YouTube's cast/menu icons; equals the old spot for a 48px chip.
      if (!position) return { left: rect.right - chipSize().width - 68, top: rect.top + 4 };
      const { width, height } = chipSize();
      return { left: rect.left + position.x * Math.max(0, rect.width - width),
        top: rect.top + position.y * Math.max(0, rect.height - height) };
    }
    function paint() {
      const shown = controlsShown();
      if (shown !== reportedControls) { reportedControls = shown; controlsChanged(shown); }
      const selected = controller.getPlayer();
      const url = new URL(location.href);
      const videoOpen = (url.pathname === "/watch" && url.searchParams.has("v"))
        || /^\/(?:live|embed)\/[^/]+/.test(url.pathname);
      if (selected?.video.closest?.("ytd-reel-video-renderer, ytm-reel-item-renderer") || !videoOpen || !active || !state?.videoContext || !selected || document.hidden || document.fullscreenElement) {
        hide(); return;
      }
      const { video, player } = selected;
      const rect = video.getBoundingClientRect();
      // Leave space for YouTube's upper-right menu/cast controls and keep away from captions below.
      if (!video.isConnected || !player.isConnected || rect.width < 180 || rect.height < 96 || rect.top < 0 || rect.top + 56 > innerHeight
        || rect.right < 120 || rect.right > innerWidth) { hide(); return; }
      if (!host.isConnected) document.documentElement.append(host);
      const controlsHidden = player.classList.contains("ytp-autohide")
        || player.classList.contains("ytp-hide-controls");
      const visible = drag?.active || button.matches(":focus-visible") || (shown !== null ? shown
        : video.paused || video.ended || Date.now() < revealedUntil || (!controlsHidden && controlsVisible(player)));
      host.style.display = "block";
      host.style.opacity = visible ? "1" : "0";
      host.style.pointerEvents = visible ? "auto" : "none";
      const spot = place(rect);
      host.style.left = `${spot.left}px`;
      host.style.top = `${spot.top}px`;
      button.tabIndex = visible ? 0 : -1;
      button.setAttribute("aria-hidden", String(!visible));
      back.tabIndex = forward.tabIndex = visible ? 0 : -1;
      back.setAttribute("aria-hidden", String(!visible));
      forward.setAttribute("aria-hidden", String(!visible));
    }
    function labelSeek() {
      backNumber.textContent = forwardNumber.textContent = String(seekInterval);
      back.setAttribute("aria-label", `Back ${seekInterval} seconds`);
      forward.setAttribute("aria-label", `Forward ${seekInterval} seconds`);
    }
    function flashSeek(direction) {
      flash.textContent = `${direction < 0 ? "−" : "+"}${seekInterval} s`;
      flash.style.opacity = "1";
      clearTimeout(flashTimer);
      flashTimer = setTimeout(() => { flash.style.opacity = "0"; }, 600);
    }
    for (const [element, direction] of [[back, -1], [forward, 1]]) {
      element.addEventListener("click", event => {
        event.stopPropagation();
        if (!active || !state?.seekAvailable || !state.videoContext) return;
        if (controller.seekBy(direction * seekInterval, state.videoContext)?.accepted) flashSeek(direction);
        revealedUntil = Date.now() + 3000;
        paint();
      });
      element.addEventListener("contextmenu", event => event.preventDefault());
    }
    labelSeek();
    function cancelDrag() {
      if (!drag) return;
      clearTimeout(drag.timer);
      row.style.transform = "";
      drag = null;
    }
    // Long-press picks the chip up so a plain tap still opens the selector.
    button.addEventListener("pointerdown", event => {
      if (!event.isPrimary || drag) return;
      const pointerId = event.pointerId;
      drag = { pointerId, startX: event.clientX, startY: event.clientY, active: false,
        timer: setTimeout(() => {
          if (drag?.pointerId !== pointerId) return;
          drag.active = true;
          drag.left = parseFloat(host.style.left) || 0;
          drag.top = parseFloat(host.style.top) || 0;
          row.style.transform = "scale(1.15)";
          try { button.setPointerCapture?.(pointerId); } catch {}
          paint();
        }, HOLD_MS) };
    });
    button.addEventListener("pointermove", event => {
      if (event.pointerId !== drag?.pointerId) return;
      const dx = event.clientX - drag.startX;
      const dy = event.clientY - drag.startY;
      if (!drag.active) { if (Math.hypot(dx, dy) > SLOP_PX) cancelDrag(); return; }
      event.preventDefault();
      const selected = controller.getPlayer();
      if (!selected) return;
      // Dropping past the video edge snaps the chip back inside it.
      const rect = selected.video.getBoundingClientRect();
      const { width, height } = chipSize();
      position = {
        x: rect.width > width ? clamp((drag.left + dx - rect.left) / (rect.width - width)) : 0,
        y: rect.height > height ? clamp((drag.top + dy - rect.top) / (rect.height - height)) : 0,
      };
      paint();
    });
    function endDrag(event) {
      if (event.pointerId !== drag?.pointerId) return;
      const moved = drag.active;
      cancelDrag();
      if (!moved) return;
      suppressClickUntil = Date.now() + 500; // The release after a drag is not a tap.
      revealedUntil = Date.now() + 3000;
      if (position) savePosition(position.x, position.y);
      paint();
    }
    button.addEventListener("pointerup", endDrag);
    button.addEventListener("pointercancel", endDrag);
    button.addEventListener("lostpointercapture", endDrag);
    button.addEventListener("contextmenu", event => event.preventDefault());
    button.addEventListener("click", event => {
      event.stopPropagation();
      if (Date.now() < suppressClickUntil) return;
      if (active && state?.videoContext) openSelector(state.videoContext);
    });
    // Mobile player controls may use different classes. Interaction reveals the chip briefly.
    document.addEventListener("pointerdown", event => {
      const selected = controller.getPlayer();
      if (selected?.player.contains(event.target)) { revealedUntil = Date.now() + 3000; paint(); }
    }, true);
    document.addEventListener("play", () => { revealedUntil = Date.now() + 3000; paint(); }, true);
    document.addEventListener("pause", paint, true);
    globalThis.addEventListener("scroll", paint, true);
    globalThis.addEventListener("resize", paint);
    return {
      update(next, running) {
        state = next;
        active = running;
        if (state.videoContext !== previousContext) {
          previousContext = state.videoContext;
          revealedUntil = Date.now() + 3000;
        }
        const rejected = state.contentError || (state.contentAppliedRate != null && state.contentAppliedRate !== state.contentRate);
        button.textContent = rejected ? "! ×" : `${state.contentRate}×`;
        button.setAttribute("aria-label", rejected ? "Speed unavailable" : `Playback speed ${state.contentRate}×`);
        const seekOff = !state.seekAvailable;
        back.disabled = forward.disabled = seekOff;
        back.style.opacity = forward.style.opacity = seekOff ? ".4" : "";
        paint();
      },
      controlsShown,
      setSeekInterval(seconds) {
        if (!SEEK_SECONDS.includes(seconds)) return;
        seekInterval = seconds;
        labelSeek();
      },
      setPosition(x, y) {
        position = x == null ? null : { x: clamp(x), y: clamp(y) };
        paint();
      },
      hide,
    };
  };
})();
