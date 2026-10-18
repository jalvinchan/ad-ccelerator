# Backlog

Open work only, in suggested order. Current implementation and completed work are
in [README.md](README.md).

1. [ ] **Transcript search and download.** Search available transcript text and
   jump to a spoken passage; allow downloading the available transcript.
   Explore YouTube's existing transcript support first. Decide export format
   and behavior when a transcript is unavailable before implementation.
2. [ ] **Touch lock.** Prevent accidental taps while watching, with an obvious,
   accessible unlock control and a reliable way to exit.
3. [ ] **Device harness refresh.** Update `scripts/verify-gecko.mjs` for the
   joined chip row and the speed sheet, then get it passing on a test device.

Video bookmarks and sleep timer are not planned.

Automatic Skip, desktop mode, release publishing, further ad-delay investigation,
older-Android acceptance, and destructive recovery testing remain outside the
current scope. Search suggestions and history remain owned by YouTube.
