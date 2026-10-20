package dev.metalboi.config;

import java.util.Locale;

/**
 * The filter used when the internally-rendered (scaled) image is presented at the
 * window's native resolution.
 *
 * <p>Nearest and bilinear reuse Minecraft's own blit pipeline and differ only in the
 * texture sampler, so they are effectively free. The other three run a single
 * fullscreen fragment pass on top of the scaled image.</p>
 */
public enum Upscaler {
    /**
     * Hard pixel replication via {@code GL_NEAREST}. Zero blur, maximum crispness,
     * very visible blockiness below roughly 85% scale. Best for a deliberately
     * retro look and the cheapest of all options.
     */
    NEAREST,

    /**
     * Linear interpolation via {@code GL_LINEAR}. This is what Minecraft itself does
     * when the framebuffer and window sizes differ, so it is the familiar baseline.
     */
    BILINEAR,

    /**
     * Separable Catmull-Rom bicubic (Mitchell-Netravali with B=0, C=0.5), 16 taps.
     * Noticeably sharper than bilinear with no visible ringing. Good default.
     */
    BICUBIC,

    /**
     * Separable Lanczos-2 resampling, 16 taps. The sharpest of the pure resampling
     * filters; introduces slight ringing on very high-contrast edges (block outlines,
     * text) in exchange for crisper detail.
     */
    LANCZOS,

    /**
     * Catmull-Rom bicubic with an increased negative lobe (Mitchell-Netravali with
     * B=0 and C driven by the sharpening slider). Same 16-tap cost as bicubic, but
     * the extra edge emphasis counteracts the softness introduced by rendering at a
     * lower internal resolution.
     */
    SHARP;

    /**
     * @return true when this mode needs MetalBoi's own shader pass rather than
     *         Minecraft's built-in blit.
     */
    public boolean usesShader() {
        return this == BICUBIC || this == LANCZOS || this == SHARP;
    }

    /** Lowercase identifier, used for translation keys and pipeline locations. */
    public String id() {
        return this.name().toLowerCase(Locale.ROOT);
    }
}
