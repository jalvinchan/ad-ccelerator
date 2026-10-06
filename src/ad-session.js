// Classic script shared by the extension, Android WebView, and local fixture.
(() => {
  class AdSession {
    constructor(video, baseline, now, report) {
      this.video = video;
      this.baseline = baseline;
      this.now = now;
      this.report = report;
      this.started = now();
      this.target = null;
      this.overrides = 0;
      this.changedRate = false;
      this.finished = false;
      report("ad-start", { baseline, mediaTime: video.currentTime });
      for (const rate of [16, 8, 4]) {
        try {
          video.playbackRate = rate;
          if (video.playbackRate !== rate) throw new Error("Player did not accept the rate");
          this.target = rate;
          this.changedRate = true;
          report("ad-speed", { rate });
          break;
        } catch (error) {
          report("rate-rejected", { rate, error: error.message });
        }
      }
    }

    tick() {
      if (this.finished) return;
      const video = this.video;
      if (this.target !== null && video.playbackRate !== this.target) {
        // Do not fight a player or another extension indefinitely.
        if (this.overrides++ < 3) {
          try {
            video.playbackRate = this.target;
            if (video.playbackRate !== this.target) throw new Error("Player reset the rate");
            this.report("rate-reapplied", { rate: this.target });
          } catch (error) {
            this.report("acceleration-stopped", { error: error.message });
            this.target = null;
          }
        } else {
          this.report("acceleration-stopped", { error: "Repeated playback-rate resets" });
          this.target = null;
        }
      }
    }

    finish(reason) {
      if (this.finished) return;
      this.finished = true;
      // Restore even if YouTube reset the element while switching back to content.
      if (this.changedRate) {
        try { this.video.playbackRate = this.baseline; }
        catch (error) { this.report("restore-error", { error: error.message }); }
      }
      this.report("ad-end", {
        reason, elapsedSeconds: Number(((this.now() - this.started) / 1000).toFixed(3)),
        restoredRate: this.video.playbackRate,
      });
    }
  }
  globalThis.YouTubeAdLabSession = AdSession;
})();
