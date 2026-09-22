# A/V sync spike notes

Measured targets for the Cleaner pipeline on device:

| Metric | Target | Implementation |
|--------|--------|----------------|
| Overlay throughput | ≥ 30 fps | `ScreenCapturePipeline` + GPU TFLite delegate |
| Presentation delay D | 33–100 ms | `PresentationClock` shared by video + audio |
| `takeScreenshotOfWindow` | ~3 fps max | Not used on hot path |
| Lip sync (ITU-R BT.1359) | audio not >45 ms ahead | `FilteredAudioPipeline` delays replay by D |

## Spike checklist

1. Run filter on YouTube scroll; log `PipelineMetrics.fps` in overlay debug builds.
2. Compare `AudioMode.SYNCED_DELAY` vs `LEAKY_REALTIME`.
3. Attempt `AudioPolicy` LOOPBACK|RENDER via reflection (optional; documented as non-v1).

## Expected results

- Mid-range GPU phone: 25–35 fps with hash skip and 224px input.
- CPU-only fallback heuristic: 12–20 fps; user should lower target or disable visual filter.

Audio ducking (`STREAM_MUSIC` volume 0) + accessibility track replay is the v1 sync strategy.
Hidden speaker intercept APIs are not required for MVP.
