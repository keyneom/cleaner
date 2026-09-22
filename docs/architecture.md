# Cleaner — on-device live content filter

Sideloaded Android app that mirrors the screen through a safety-gated accessibility overlay.
Clicks pass through to the real app; the user only sees filtered pixels.

## Layers

1. **DNS (never loaded)** — Cloudflare Family (`family.cloudflare-dns.com`), NextDNS, or CleanBrowsing via Private DNS instructions or local `DnsVpnService`.
2. **Visual mirror (never shown raw)** — `TYPE_ACCESSIBILITY_OVERLAY` + single-app `MediaProjection` at 30 Hz + on-device vision model.
3. **Audio (delayed mirror)** — `AudioPlaybackCapture` copy, keyword spotting, replay through accessibility audio with the same presentation delay `D` as video.

## v1 vision model

Not [OpenJev](https://huggingface.co/openjev/openjev). OpenJev is an open-weights *decision* model (Jev-shaped: one forward pass, a probability over labels, including screenshot decisions). The public checkpoint is about 54 GB and is measured around 210 ms on an H100. That cannot hit a phone frame budget.

The on-device equivalent of that *shape* — one forward pass, a score plus boxes, no token decoding — is a tiny detector:

**Primary:** [NudeNet](https://github.com/notAI-tech/NudeNet) detector exported to TFLite (`320n` class), or MobileNetV2 NSFW classifier as fast-path gate.

| Stage | Model | Role |
|-------|-------|------|
| Gate | `nsfw_mobilenet_v2_140_224` (TFLite, ~5 MB) | Whole-frame reject in ~5–15 ms; skip detector on obvious safe frames |
| Localize | NudeNet / YOLO-nano detector (TFLite) | Bounding boxes for region cover |

Runtime: TensorFlow Lite with GPU delegate (`Interpreter.Options.addDelegate(GpuDelegate())`).
Input downscaled to 224–320 px before inference. Frame skip via 64-bit perceptual hash (aHash).

## Never-seen compositor (latest frame, not a queue)

```
Real app (input native)
    ↓ MediaProjection acquireLatestImage
LatestFrameInbox (at most one waiting frame; older waiting frames are dropped)
    ↓ one classifier thread
Overlay keeps the last finished composite until the new one is ready
```

A slow model lowers the update rate. It does not stack delay, because frames that arrive while classification is in progress replace the single waiting slot instead of lining up. Unclassified pixels are never published. If a frame is unsafe and has no boxes, the previous composite stays up.

`takeScreenshotOfWindow` is fallback only (~3 fps, API 34+).

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
