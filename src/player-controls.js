// Page geometry and player-control visibility belong here; the native host owns the selector.
(() => {
  globalThis.YouTubePlayerControls = function (controller, openSelector, savePosition = () => {}) {
    const host = document.createElement("div");
    const shadow = host.attachShadow({ mode: "closed" });
    const button = document.createElement("button");
    button.type = "button";
    button.style.cssText = "all:initial;box-sizing:border-box;display:block;min-width:48px;height:48px;padding:0 8px;border-radius:24px;background:rgba(24,24,24,.85);color:white;font:600 14px sans-serif;text-align:center;cursor:pointer;touch-action:none;user-select:none;-webkit-user-select:none;transition:transform .15s";
    shadow.append(button);
    host.style.cssText = "position:fixed;z-index:2147483647;display:none;transition:opacity .2s";
    let state = null;
    let active = false;
    let revealedUntil = 0;
    let previousContext = null;
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
    function hide() { cancelDrag(); host.style.display = "none"; }
    function clamp(value) { return Math.min(1, Math.max(0, value)); }
    function chipSize() {
      const box = host.getBoundingClientRect?.();
      return { width: box?.width || 48, height: box?.height || 48 };
    }
    function place(rect) {
      if (!position) return { left: rect.right - 116, top: rect.top + 4 };
      const { width, height } = chipSize();
      return { left: (rect.left ?? rect.right - rect.width) + position.x * Math.max(0, rect.width - width),
        top: rect.top + position.y * Math.max(0, rect.height - height) };
    }
    function paint() {
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
      const visible = drag?.active || video.paused || video.ended || Date.now() < revealedUntil || button.matches(":focus-visible") || (!controlsHidden && controlsVisible(player));
      host.style.display = "block";
      host.style.opacity = visible ? "1" : "0";
      host.style.pointerEvents = visible ? "auto" : "none";
      const spot = place(rect);
      host.style.left = `${spot.left}px`;
      host.style.top = `${spot.top}px`;
      button.tabIndex = visible ? 0 : -1;
      button.setAttribute("aria-hidden", String(!visible));
    }
    function cancelDrag() {
      if (!drag) return;
      clearTimeout(drag.timer);
      button.style.transform = "";
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
          button.style.transform = "scale(1.15)";
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
      const left = rect.left ?? rect.right - rect.width;
      position = {
        x: rect.width > width ? clamp((drag.left + dx - left) / (rect.width - width)) : 0,
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
        paint();
      },
      setPosition(x, y) {
        position = x == null ? null : { x: clamp(x), y: clamp(y) };
        paint();
      },
      hide,
    };
  };
})();
