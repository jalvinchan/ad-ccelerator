# Backlog

Open work only, in suggested order. Current implementation and completed work are
in [README.md](README.md); measured results are in [Verification](docs/VERIFICATION.md).

1. [ ] **Explore speed-control design — next decision.** Compare these two
   alternatives before choosing a design; neither is selected for implementation:
   - **B: Speed wheel.** Scroll through consistent 0.25× increments, making
     2.25× an ordinary stop between 2× and 2.5×.
   - **C: Slider + fine adjustment.** Use a 0.25×–4× slider with whole-speed labels,
     an exact rate readout, and ±0.05× buttons.
   Use the same selected design in normal and fullscreen modes.
2. [ ] **Transcript search and download.** Search available transcript text and
   jump to a spoken passage; allow downloading the available transcript.
   Explore YouTube's existing transcript support first. Decide export format
   and behavior when a transcript is unavailable before implementation.
3. [ ] **Touch lock.** Prevent accidental taps while watching, with an obvious,
   accessible unlock control and a reliable way to exit.
4. [ ] **Seek interval.** Allow choosing a jump interval, such as 5, 10, or
   30 seconds, while preserving YouTube's native gestures and ad safeguards.

Video bookmarks and sleep timer are not planned.

Automatic Skip, desktop mode, release publishing, further ad-delay investigation,
older-Android acceptance, and destructive recovery testing remain outside the
current scope. Search suggestions and history remain owned by YouTube.
