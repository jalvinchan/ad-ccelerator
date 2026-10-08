// Page geometry and player-control visibility belong here; the native host owns the selector.
(() => {
  globalThis.YouTubePlayerControls = function (controller, openSelector) {
    const host = document.createElement("div");
    const shadow = host.attachShadow({ mode: "closed" });
    const button = document.createElement("button");
    button.type = "button";
    button.style.cssText = "all:initial;box-sizing:border-box;display:block;min-width:48px;height:48px;padding:0 8px;border-radius:24px;background:rgba(24,24,24,.85);color:white;font:600 14px sans-serif;text-align:center;cursor:pointer;touch-action:manipulation";
    shadow.append(button);
    host.style.cssText = "position:fixed;z-index:2147483647;display:none;transition:opacity .2s";
    let state = null;
    let active = false;
    let revealedUntil = 0;
    let previousContext = null;

    function controlsVisible(player) {
      const controls = player.querySelector(".ytp-chrome-controls");
      if (!controls || !controls.getClientRects().length) return false;
      for (let node = controls; node && node !== player; node = node.parentElement) {
        const style = getComputedStyle(node);
        if (style.display === "none" || style.visibility === "hidden" || Number(style.opacity) === 0) return false;
      }
      return true;
    }
    function hide() { host.style.display = "none"; }
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
      const visible = video.paused || video.ended || Date.now() < revealedUntil || button.matches(":focus-visible") || (!controlsHidden && controlsVisible(player));
      host.style.display = "block";
      host.style.opacity = visible ? "1" : "0";
      host.style.pointerEvents = visible ? "auto" : "none";
      host.style.left = `${rect.right - 116}px`;
      host.style.top = `${rect.top + 4}px`;
      button.tabIndex = visible ? 0 : -1;
      button.setAttribute("aria-hidden", String(!visible));
    }
    button.addEventListener("click", event => {
      event.stopPropagation();
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
      hide,
    };
  };
})();
