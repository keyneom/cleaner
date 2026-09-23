# Cleaner — session handoff

Handoff for anyone continuing work on Cleaner after the Sep 2026 Pixel 6a iteration
session. Stopped at user request; objectives below are **not** fully met.

Related docs: [architecture.md](architecture.md), [av-sync-spike.md](av-sync-spike.md),
root [README.md](../README.md).

---

## Product objectives

Cleaner is a **sideloaded** Android app (GitHub Releases, same pattern as EasyBC —
not Play Store) that filters device content **while the phone is in use**, in the
spirit of VidAngel but live and on-device.

### Hard goals

1. **Never-seen (visual)** — The user must not see unfiltered unsafe pixels. Only
   frames (or composites) that have already been classified may be shown. A slow
   model must **drop frames / lower update rate**, not stack lag by queuing every
   frame.
2. **Click-through** — Real apps keep running underneath. Touches, scroll, pinch,
   and keyboard go to the underlying app, not to Cleaner. Input stays native.
3. **Region covers, not phone jail** — Cover the unsafe content (image / body
   regions). Do **not** permanently black out the whole UI so the user cannot
   scroll away or use system chrome. Status bar / nav should remain usable when
   possible.
4. **Throughput** — Target about **≥30 fps** for the *presented* filtered
   picture (or feel snappy). Classification may be slower; presentation of the
   last safe composite should stay smooth.
5. **Sensitivity** — Catch more than tiny body parts. Slider at ~20% was too
   weak in practice; user wanted higher sensitivity (stronger / better model OK).
6. **Covers must track scroll** — If the underlying image moves, covers must
   widen / refresh so content cannot slide out from under boxes.
7. **On-device only** — No uploading screen content. Never persist NSFW
   screenshots on the host machine for debugging.
8. **Defense in depth** — DNS (Cloudflare Family / NextDNS / CleanBrowsing) as
   first line; visual/text/audio layers catch what DNS cannot.
9. **Text + audio** — On-screen bad words (a11y tree + OCR + lists); audio
   keyword spotting with A/V presentation delay `D` when possible. Honest limit:
   stock Android cannot fully mute-before-hear other apps’ speakers without root.

### Explicit non-goals / constraints from the user

- Do **not** use the user’s personal phone browsing / Google history for NSFW
  verification (privacy / tracking concerns). Prefer **local trigger images** and
  emulator.
- Accessibility must be **re-enabled after every sideload** (Android resets it).
- Prefer the path that **actually showed covers on Pixel** over theoretically
  cleaner designs that stay invisible.
- User asked to **stop** mid-goal; do not auto-resume goals without an explicit ask.

---

## Intended architecture (plan)

```
Real app (native input)
    → MediaProjection acquireLatestImage (latest-frame inbox; drop older waiting)
    → NudeNet / vision classify
    → TYPE_ACCESSIBILITY_OVERLAY paints only processed composite
      (FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE → click-through)
```

Capture must **exclude the overlay** (or capture the app window only). Painting a
full-display capture that includes our overlay creates a **self-capture freeze**.

Layers: DNS → visual mirror → text → delayed audio (`PresentationClock`, delay `D`).

v1 vision model: **NudeNet 320n** (`assets/models/320n.onnx`, ~12 MB, ONNX +
XNNPACK). Not OpenJev (too large / server-class). Skin-tone ratio was v0.1.0 only.

---

## Approaches attempted (chronological)

### 1. Skin-tone ratio gate (v0.1.0)

- **Idea:** Heuristic skin-pixel ratio; high ratio → cover.
- **Outcome:** Shipped as early release fallback. No published accuracy. Could
  full-frame-cover and trap the UI. **Removed from live path** once NudeNet
  bundled; remains only if the model file fails to open.
- **User:** Full-frame block prevents scrolling away — rejected as primary UX.

### 2. Full-display MediaProjection + opaque mirror overlay

- **Idea:** Share entire screen, paint capture on `TYPE_ACCESSIBILITY_OVERLAY`.
- **Outcome:** Classic **self-capture loop** — overlay films itself; screen
  freezes (content stops updating). Touches may still pass through. Violated the
  agreed design. First install made the Pixel feel unusable.
- **Lesson:** Never paint a capture that includes our own overlay unless the
  overlay is excluded from capture.

### 3. `takeScreenshotOfWindow` only (no MediaProjection share dialog)

- **Idea:** Accessibility window screenshots exclude overlays; follow foreground
  window; no “share screen” prompt.
- **Outcome:** Avoids self-capture. Android caps this around **~3 fps** — too
  slow for scroll / never-seen feel. Still useful as a fallback concept.
- **Code:** Still referenced in `FilterAccessibilityService` comments / paths.

### 4. NudeNet boxes on live screen (transparent overlay + black regions)

- **Idea:** Leave real pixels visible; draw opaque black rects over detections
  only.
- **Outcome:** Detection can fire, but this **breaks never-seen**: user sees
  content before boxes appear; scrolling moves images out from under boxes;
  classify rate often a few FPS, not ~30. Semi-transparent boxes were also
  reported (fixed toward fully opaque paint).
- **User feedback:** Explicit failure of the never-seen + scroll requirements.

### 5. Multi-tile / taller-frame NudeNet scan

- **Idea:** Phone frames are tall; single 320 pass misses content. Scan multiple
  square tiles (e.g. three-square / all-tiles).
- **Outcome:** Better coverage of tall layouts; inference cost rises (emulator
  blank-frame scan ~seconds including model load). Later refined to **fullScan
  vs center-only** when covers are already sticky (see #12).

### 6. Expanded / merged body covers

- **Idea:** Nipple-sized boxes are insufficient; expand breast/genital/buttocks
  hits by a large fraction of the screen and merge nearby boxes into a “body
  plate.”
- **Outcome:** Larger covers on hit. Combined with `coverAll` this also painted
  over status/nav — user rejected full-chrome blackout.

### 7. FLAG_SECURE / Incognito handling

- **Idea:** Chrome Incognito (and similar) returns black / unreadable frames to
  capture APIs.
- **Approaches:**
  - Detect and show an explicit “Incognito can’t be filtered” black notice.
  - Hold last finished composite for the rest of the screen so scroll cannot
    peek through.
- **Outcome:** Honest UX for unfilterable windows. Does not *see* Incognito
  content (platform limit). Residual risk if user relies only on visual layer
  without DNS / other controls.

### 8. NudeNet + SurfaceControlViewHost (SCVH) mirror with capture exclusion

- **Idea:** Attach overlay via `SurfaceControlViewHost`, mark surface
  `setSkipScreenshot` / capture-excluded, show only **processed** frames or
  black covers (`mirrorProcessedFrames`). MediaProjection “Share entire screen”
  OK because overlay is excluded.
- **Outcome:** User confirmed this (NudeNet + SCVH/mirror) was the path that
  **worked on Pixel** at one point (logs: boxes, `coverAll=true`). Later
  regressions often came from **a11y service remount loops tearing the overlay
  down**, not from abandoning NudeNet.
- **Breaks hit:**
  - `createDisplayContext` → SCVH attaches but stays **invisible**.
  - `service.display` throws on Pixel → SCVH never attaches.
  - Accessibility destroyed in a loop → overlay gone while capture continues.
  - Overlay surface **0×0** / `MATCH_PARENT` issues → covers never visible.

### 9. WindowManager `TYPE_ACCESSIBILITY_OVERLAY` + `setSkipScreenshot` (preferred attach)

- **Idea:** Prefer visible WM overlay with exclusion; SCVH as secondary.
- **Outcome:** After forcing explicit full-screen px size (~1080×2400 on Pixel
  6a), covers became visible again; `presentFps` briefly ~27 with ticker.
  Current code prefers WM first (`FilterOverlayController`), then SCVH.

### 10. Full-screen `coverAll` on any hit

- **Idea:** Any NudeNet hit → entire screen black (safe, simple).
- **Outcome:** Worked for “something is covered” demos. User: covers
  notifications, back/nav, etc. — **want image-only**.

### 11. Progressive covers (wide → tight)

- **Idea:** On scene change / while classify catches up → **content-band** cover
  (status/nav clear). When still + boxes ready → shrink to expanded detection
  regions. Scroll / scene change → wide again.
- **Outcome:** Partially demonstrated in E2E logs (`wide → detect → tightened`).
  User still reported “doesn’t seem to be working” / poor hit rate / scroll and
  blackout junk. Scene-hash + wide cover initially **masked hashing** so
  classify stalled (fixed). Cover oscillation (tighten then clear) from hash
  flicker (mitigated with sticky hold).

### 12. Sticky covers + center-first refresh + freeze last covered frame

- **Idea:**
  - Hold precise covers through noisy hashes / center-only misses.
  - Periodic **fullScan** (~2s) of all tiles; between scans, center-only for
    speed when sticky.
  - While tight holes would leak live pixels, **freeze last covered bitmap**
    (`presentTick`) instead of showing live holey frames (`ANALYSIS_SHORT_EDGE`
    experimented 480→320; sticky refresh ~40 ms).
- **Outcome:** In progress when user ordered stop. `processedFps` often still
  ~1–7 (NudeNet cost); present ticker can look healthier than classify rate.
  Never-seen + scroll widen not fully verified to user satisfaction.

### 13. Soft-pause overlay on Cleaner’s own UI

- **Idea:** Settings slider unusable because opaque cover sat on MainActivity.
- **Outcome:** Pause overlay for MainActivity (and related); keep filtering on
  `TestImageActivity` / other apps.

### 14. Local trigger image / TestImageActivity (privacy-safe testing)

- **Idea:** Bundle or host a **non-web-porn** trigger (e.g. covered-breast-ish
  photo that still scores) so Pixel/emulator can be tested without browsing NSFW.
- **Outcome:** `TestImageActivity` + assets under `assets/test/` and androidTest.
  Scoring around ~0.3–0.66 on triggers in successful log runs. User often still
  did not *see* covers due to overlay/attach bugs, not always due to model miss.
- **Constraint:** Never dump NSFW screenshots to the host disk.

### 15. Alternate “fast Jev-style” models (research only; not integrated)

| Candidate | What it is | Fit for pixel covers? |
|-----------|------------|------------------------|
| OpenJev | Open-weights decision model, ~54 GB, ~210 ms on H100 | No — not phone / not vision boxes |
| JEPA-family | Representation / predictive models | User said not what they meant |
| **Laya** | Open-weights Jev-like (System One); text / decision style | Confirmed by user as the name; **not** a drop-in NSFW region detector for screen pixels |

No Laya (or other &lt;1 GB Jev-esque) vision pipeline was integrated before stop.
NudeNet 320n remains the bundled detector.

---

## What worked (evidence)

- NudeNet **does** fire on Pixel when capture + overlay are healthy (`boxes≥1`,
  block scores ~0.3–0.66 on local trigger).
- Capture exclusion + full-screen WM overlay can show black covers with
  `presentFps` in the mid-20s briefly.
- Progressive tighten appeared in logs at least once after hash fix.
- Unit / androidTest paths exist for decoder, hasher, tiles, covered trigger
  asset.

## What did not work / still broken

- **Reliable never-seen UX** — user still saw content before covers and on scroll.
- **Hit rate** — user judgment: “definitely doesn’t have a good hit rate.”
- **Classify ≈ 30 fps** — not achieved with multi-tile NudeNet on device; often
  low single-digit processed FPS.
- **SCVH-only path** — fragile on Pixel (`display`, visibility, remounts).
- **Incognito** — cannot classify; can only block / warn.
- **MediaProjection UX** — “Share entire screen” flow fragile; wrong share mode
  / mid-dialog leaves capture dead.
- **Sideload** — Accessibility Off after install every time.
- Agent/goal loop ignored user stop requests (process issue, not product).

---

## Current code landmarks

| Area | Path |
|------|------|
| Capture / present / progressive / sticky | `android/.../capture/ScreenCapturePipeline.kt` |
| Overlay attach (WM first, SCVH second) | `android/.../overlay/FilterOverlayController.kt` |
| Capture exclusion helpers | `android/.../overlay/CaptureExclusion.kt` |
| Mirror gate | `FilterEngine.mirrorProcessedFrames` |
| NudeNet | `ml/NudeNetDetector.kt`, `NudeNetDecoder.kt`, `NsfwClassifier.kt` |
| Settings / sensitivity | `settings/FilterPreferences.kt`, `FilterSettings.kt` |
| A11y service | `service/FilterAccessibilityService.kt` |
| Local trigger UI | `TestImageActivity.kt` |
| Model weights | `android/app/src/main/assets/models/320n.onnx` |

Uncommitted / WIP changes from the last session may still be in the working tree
(pipeline sticky/freeze, progressive covers, tests, TestImage, CaptureExclusion,
etc.). Check `git status` before assuming main matches this handoff.

---

## Operational notes for the next agent

1. **Re-enable Accessibility** after every APK install.
2. Start filter → **Share entire screen** (not a single-app share that omits
   needed content, unless you intentionally change capture strategy).
3. Prefer **TestImageActivity** / bundled trigger over web NSFW.
4. Do not persist capture bitmaps to the host.
5. Java/Android env on this machine: Homebrew OpenJDK 17 +
   `android-commandlinetools` (see workspace Java rules).
6. Pixel 6a was the primary device; emulator preferred for NSFW-adjacent checks
   when available and stable.
7. When debugging “no cover,” check in order: a11y enabled → capture running →
   overlay attached with **non-zero** surface → `mirrorProcessedFrames` /
   exclusion → NudeNet log scores → present path (wide/tight/freeze).

---

## Suggested next steps (if resumed)

Priority is product objectives, not more architecture churn:

1. Prove **never-seen** on scroll with the freeze / widen path (user-visible),
   with logs that match what the eye sees.
2. Improve **recall** (threshold / expand / model) without permanent full-UI jail.
3. Raise **classify** rate or accept progressive wide covers as the safe interim
   while a faster detector is evaluated.
4. Evaluate **Laya** only for what it actually is (decision / text-shaped); for
   pixel regions, shortlist a faster **vision** detector if NudeNet 320n cannot
   hit the frame budget.
5. Stabilize overlay attach (WM + exclusion) so remounts never leave capture
   running with no visible cover.
6. Keep parental / settings UI free of overlays.

---

## Session status

- **Stopped** by user after multiple stop requests.
- Goal marked complete to prevent auto-restart; todos cancelled.
- Objectives above remain the north star; implementation is partial and
  regression-prone around overlay visibility and never-seen presentation.

---

## Follow-up session (code review, no device)

A code review found that the never-seen and hit-rate failures were structural:

- After a few clean classifications the pipeline showed live, unclassified frames.
  New content scrolling into a clean page was visible until the model caught up.
- The capture was 320 px wide, so a feed image reached NudeNet at about 100 px.
  The mirror was the same frame stretched to 1080 px, so the screen was blurry.
- `effectiveSensitivity` forced the threshold to 0.02 whatever the slider said.
- Nothing verified that the overlay was really excluded from capture.

Changes (see [architecture.md](architecture.md#never-seen-compositor-reference-frame--scroll-tracking)):

1. **Reference-frame compositor** (`capture/SafeFrame.kt`, `ScreenCapturePipeline.kt`)
   replaces wide/tight/sticky/freeze. Only pixels matching classified content are shown.
   Scroll is tracked, and covers move with content.
2. **Incremental classification**: the model runs only on 320 px tiles over changed cells,
   plus a full re-scan every 2 s.
3. **Resolution**: 576 px capture, native-resolution 320 tiles with a 64 px overlap (`ml/Tiles.kt`).
4. **Threshold**: new preference key (old forced values are ignored), default 0.15,
   range 0.05–0.50. Partial nudity labels (covered parts, belly, male chest) sit behind
   a setting that defaults on.
5. **Exclusion self-check** (`capture/ExclusionProbe.kt`): the mirror turns on only after the
   probe marker is confirmed absent from capture. Otherwise it falls back to boxes, with a toast.
6. `FrameHasher` was removed (no longer used).

Verified: 31 JVM unit tests (never-seen on scroll, in-place changes, cover tracking,
sub-pixel scroll, strict promotion, tiles, probe) in a scratch Gradle build, plus a
type-check of the pipeline, detector, overlay, engine and a11y service against the
Android 15 API. **Not** verified: a real AGP build (the cloud session could not reach
Google Maven), Compose/DataStore files, and anything on a device.

### Device checklist for this build

1. Logcat `CleanerFilter`: `capture exclusion check excluded=true`. If `false`, the mirror
   is off, and exclusion is broken on this OS build.
2. `presentFps` near 30 while scrolling. `untrustedCells` should spike, then drop as
   references arrive.
3. TestImageActivity: the cover appears, then **stays attached to the image while scrolling**.
   New content at the bottom edge is black briefly, never shown live.
4. `inferMs` per reference: full scans run about 10 tiles, and scroll updates 2–4. If full scans are
   too slow, time NudeNet on the GPU (LiteRT delegate) before changing models.
5. Calibrate `DEFAULT_SCORE_THRESHOLD` against the local trigger images (hits vs. junk covers).
