# MetalBoi

A Fabric client mod for **Minecraft 1.21.11** that adds an internal-resolution
(render scale) changer with a selectable upscaler, reports the graphics backend the
game is *actually* running on, and puts all of it in a **MetalBoi tab inside Sodium's
video settings screen**.

---

## First, the honest answer about Metal

The request was to force Minecraft onto Metal with no OpenGL-to-Metal translation.
That is not a thing a mod can do, and it is worth being precise about why:

- Minecraft Java renders through **LWJGL's OpenGL bindings**. Java has no Metal
  bindings, and LWJGL ships none.
- The only routes from Java to Metal are **Vulkan → MoltenVK** or
  **OpenGL → MoltenGL** — i.e. exactly the translation layers you wanted to avoid.
  The other route is a full native renderer rewrite in Objective-C/Metal, which is a
  different game engine, not a mod.
- And the premise turns out to be inverted: on macOS Minecraft is already running on
  **Apple's own native OpenGL driver** (capped at GL 4.1). There is no
  OpenGL-to-Metal translation layer in the path to remove. Apple's GL driver *is* the
  zero-overhead path.

So MetalBoi does not pretend to swap backends. It **reports the truth** about the
backend — including detecting when a translation layer like MoltenGL, MoltenVK, Zink or
ANGLE *is* in the path, which costs you performance — and then goes after the setting
that actually moves frame rate on macOS: **internal render resolution**.

That is a real and large win. On a Retina display the default framebuffer is already
2× in each axis, so the GPU is shading 4× the pixels of the logical window. Dropping
render scale to 70% roughly halves the shading work, which is where nearly all of your
frame time goes at high resolutions.

## What it does

| Setting | Values | Effect |
| --- | --- | --- |
| **MetalBoi Renderer** | On / Off | Master toggle. Its tooltip is a live backend report (vendor, renderer, GL version, and whether a translation layer is involved). |
| **Render Scale** | 25–100%, step 5 | Internal resolution as a percentage of the window framebuffer. 100% = untouched. |
| **Upscaler** | Nearest, Bilinear, Bicubic, Lanczos 2, Sharpened bicubic | Filter used to present the scaled image at native size. |
| **Sharpening Strength** | 0–100%, step 5 | Only for *Sharpened bicubic*; pushes the kernel's negative lobe past neutral Catmull-Rom. |
| **Log Backend Info** | On / Off | Writes the backend report to `latest.log` on the first rendered frame. |

Stored in `config/metalboi.json`, so it is fully usable without Sodium installed.

## How it works

**Render scale.** Minecraft sizes its main render target from `Window#getWidth()` /
`getHeight()`, and derives GUI scale from the same two numbers. `WindowMixin` scales
those getters (and re-derives `calculateScale` / `setGuiScale` against the scaled
resolution), so terrain, entities, particles *and* the HUD are all rasterised at the
lower internal resolution while staying mutually consistent — the HUD still lays out
correctly and still honours your GUI scale setting. The unscaled native size is kept in
the backing fields, which preserves `Window#onFramebufferResize`'s before/after resize
detection exactly.

**Upscaling.** This is the part that has to be custom. `RenderTarget#blitToScreen()`
ends up in `GlCommandEncoder#presentTexture`, which blits using **identical source and
destination rectangles** — it never scales. A downscaled main target would be painted
1:1 into the bottom-left corner of the window. So `RenderTargetMixin` intercepts the
main target's blit and resamples it into a MetalBoi-owned target at true native size
first; vanilla then presents that 1:1 as normal.

Everything goes through Minecraft's own `RenderPass` / `RenderPipeline` abstraction
(the vanilla `core/screenquad` fullscreen-triangle vertex shader plus a MetalBoi
fragment shader) rather than raw GL calls, so `GlStateManager`'s cached state is never
desynchronised. Nearest and Bilinear reuse Minecraft's `TRACY_BLIT` pipeline and differ
only in the sampler.

Custom pipelines are validated with `CompiledRenderPipeline#isValid()` on first use; if
a shader ever fails to compile, MetalBoi falls back to vanilla blitting and disables
itself rather than presenting a broken frame.

### Upscalers

- **Nearest** — `GL_NEAREST`. Cheapest, no blur, blocky below ~85%.
- **Bilinear** — `GL_LINEAR`. What Minecraft does natively; the familiar baseline.
- **Bicubic** — separable Catmull-Rom (Mitchell-Netravali, B=0, C=0.5), 16 taps.
  Sharper than bilinear with no ringing. Default.
- **Lanczos 2** — separable Lanczos-2, 16 taps. Sharpest pure resampler; slight ringing
  on very high-contrast edges.
- **Sharpened bicubic** — Catmull-Rom with C driven from 0.5 to 1.0 by the slider. Same
  16-tap cost as bicubic; best at low render scales where softness is the complaint.

All three shader modes are spatial (single-pass) resamplers. Temporal upscaling (FSR2,
XeSS, TAAU) is deliberately **not** offered: it needs motion vectors and a history
buffer, which Minecraft's renderer does not expose to mods, and faking it produces
ghosting worse than the softness it removes.

## Installing

Drop `metalboi-*.jar` into `.minecraft/mods` alongside Fabric Loader for 1.21.11.
Sodium is optional but recommended — it is what gives you the in-game settings tab.
MetalBoi does **not** require Fabric API.

## Building

```bash
./gradlew build
```

The jar lands in `build/libs/`. Requires network access to Maven Central,
`maven.fabricmc.net`, `libraries.minecraft.net` and `maven.caffeinemc.net`.

## Known limitations

- **The HUD is scaled too.** At 70% render scale, GUI text is resampled along with the
  world. That is inherent to internal-resolution scaling without a separate full-res
  GUI composite pass; the sharper upscalers recover most of it, and raising GUI scale by
  one step compensates for the perceived size change.
- **Screenshots and the F2 capture** are taken at internal resolution.
- **Panoramic screenshots** (`grabPanoramixScreenshot`) temporarily resize the main
  target; MetalBoi detects the size mismatch and steps aside so vanilla handles them.
- **Tested against** Minecraft 1.21.11, Fabric Loader 0.19.2 and Sodium 0.8.14. The
  `fabric.mod.json` pins `minecraft: 1.21.11` because the hooks are specific to this
  version's render pipeline.

## License

MIT. See [LICENSE](LICENSE).
