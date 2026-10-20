package dev.metalboi.config;

import java.util.Locale;

/**
 * The filter used when the internally-rendered (scaled) image is presented at the
 * window's native resolution.
 *
 * <p>Everything here runs as a plain GLSL fragment pass on the final blit, so it
 * costs a fraction of a millisecond even at 4K.</p>
 */
public enum Upscaler {
    /**
     * Vanilla behaviour: let {@code glBlitFramebuffer} stretch with
     * {@code GL_LINEAR}. Cheapest possible option, softest result.
     */
    BILINEAR,

    /**
     * Hard pixel doubling via {@code GL_NEAREST}. Zero blur, maximum sharpness,
     * very visible blockiness below ~85% scale.
     */
    NEAREST,

    /**
     * Bilinear sampling followed by a single-pass AMD CAS (Contrast Adaptive
     * Sharpening). Cheap and noticeably crisper than bilinear.
     */
    SHARP,

    /**
     * AMD FidelityFX Super Resolution 1.0: EASU (edge-adaptive spatial upscale)
     * plus RCAS (robust contrast adaptive sharpening). Best quality of the
     * non-temporal options, still a single fullscreen pass.
     *
     * <p>MetalBoi uses spatial upscalers only on purpose: temporal upscalers need
     * motion vectors and a history buffer, which Minecraft's renderer does not
     * expose to mods.</p>
     */
    FSR1;

    /**
     * @return true when this mode needs the custom shader pass rather than a
     *         plain framebuffer blit.
     */
    public boolean usesShader() {
        return this == SHARP || this == FSR1;
    }

    public String id() {
        return this.name().toLowerCase(Locale.ROOT);
    }
}
