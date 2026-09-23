# Cleaner — on-device live content filter

Sideloaded Android app that mirrors the screen through a safety-gated accessibility overlay.
Clicks pass through to the real app; the user only sees filtered pixels.

## Layers

1. **DNS (never loaded)** — Cloudflare Family (`family.cloudflare-dns.com`), NextDNS, or CleanBrowsing via Private DNS instructions or local `DnsVpnService`.
2. **Visual mirror (never shown raw)** — `TYPE_ACCESSIBILITY_OVERLAY` + single-app `MediaProjection` at 30 Hz + on-device vision model.
3. **Audio (delayed mirror)** — `AudioPlaybackCapture` copy, keyword spotting, replay through accessibility audio with the same presentation delay `D` as video.

## v1 vision model

Not [OpenJev](https://huggingface.co/openjev/openjev). OpenJev is an open-weights *decision* model (Jev-shaped: one forward pass, a probability over labels, including screenshot decisions). The public checkpoint is about 54 GB and is measured around 210 ms on an H100. That cannot hit a phone frame budget.

The on-device model is [NudeNet](https://github.com/notAI-tech/NudeNet) **320n** (YOLOv8n, 320px), bundled from the MIT-licensed `nudenet` 3.4.2 package as `assets/models/320n.onnx` (about 12 MB). Runtime is ONNX Runtime with XNNPACK. It returns boxes for exposed breasts, genitals, buttocks, and anus. Faces, feet, and belly are ignored.

NudeNet's own detector keeps a box when its score is at least 0.25, then applies non-maximum suppression at IoU 0.45. That is the app default. The authors do not publish precision, recall, or mAP for this 320n checkpoint. The larger 640m model is the one they describe as more accurate; 320n is the one that fits a phone frame budget (on the order of 15–40 ms per frame on a recent phone GPU/CPU, so multiple updates per second). v0.1.0 did not include these weights and used a skin-tone ratio instead.

## Never-seen compositor (reference frame + scroll tracking)

```
Real app (input native)
    ↓ MediaProjection, 576 px short side, newest image only (≤ 30 fps)
Capture thread: compare frame to the last classified frame ("reference")
    ↓ estimate vertical scroll, then check each 16 px cell
Overlay shows only cells that match classified pixels; covers move with them
    ↘ LatestFrameInbox (one waiting frame)
      Classifier thread: run NudeNet only on 320 px tiles over changed cells,
      then promote that frame to be the new reference
```

The rule: a pixel reaches the screen only if it matches a pixel the classifier has already seen (`capture/SafeFrame.kt`).

- **STILL** cells are unchanged since the reference, so they are shown live.
- **SHIFTED** cells are reference content moved by the scroll. They are shown live, and covers move with them.
- **UNTRUSTED** cells are new or changed. If the frame did not scroll, the reference's pixels are shown there (video plays at classify rate). If it scrolled, they are black until classified.

Scrolling therefore stays at capture rate, and only the newly exposed strip waits on the model. The check allows for sub-pixel resampling: each sample must fall within the range of the reference at the source row and its neighbours. Promotion to a new reference uses a stricter tolerance, so slow in-place changes cannot chain past the model. Every tile is also re-scanned every 2 s.

Covers stick to unchanged content. A cover whose content changed in place is held for 6 s after its last detection, so intermittent misses on video do not flicker. On a full scan, a cover the model has not confirmed for 6 s is dropped.

The overlay is excluded from capture through a hidden API. Each attach is verified before mirroring: the overlay draws a magenta/green checker marker, and if the marker shows up in captured frames, the mirror stays off and the app falls back to boxes over the live screen (and tells the user). See `capture/ExclusionProbe.kt`.

`takeScreenshotOfWindow` is fallback only (~3 fps, API 34+).

## A/V sync

Shared `PresentationClock` (`elapsedRealtimeNanos`):

- `D` default 50 ms (configurable 33–100 ms).
- Video frames tagged at capture time `t`, shown at `t + D`.
- Audio PCM buffered for `D`, KWS on 20 ms hops, muted segments replaced with silence, replay on `USAGE_ASSISTANCE_ACCESSIBILITY`.

If speaker intercept fails: **leaky mode** (real-time audio, delayed video) or tighten `D` ≤ 40 ms.

## Limits

- No intercept of `FLAG_SECURE` / DRM surfaces.
- `AudioPlaybackCapture` is a copy; apps can opt out.
- CPU-only devices may not sustain 30 fps; GPU delegate required for target throughput.
