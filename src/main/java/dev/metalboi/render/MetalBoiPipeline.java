package dev.metalboi.render;

import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.metalboi.MetalBoi;
import dev.metalboi.config.MetalBoiConfig;
import dev.metalboi.config.Upscaler;
import dev.metalboi.mixin.WindowAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;

import java.util.OptionalInt;

/**
 * The render-scale / upscale pipeline.
 *
 * <h2>How render scale works here</h2>
 *
 * <p>Minecraft sizes its main render target from {@link Window#getWidth()} and
 * {@link Window#getHeight()}, and derives the GUI scale from the same two numbers.
 * MetalBoi's {@code WindowMixin} scales those getters by the configured percentage, so
 * the whole frame - terrain, entities, particles <i>and</i> HUD - is rasterised at the
 * lower internal resolution while every consumer of the size stays self-consistent.</p>
 *
 * <p>That is where the frame rate comes from: at 70% scale the GPU shades roughly half
 * as many pixels. On a Retina display, where the framebuffer is already twice the
 * logical size in each axis, the saving is correspondingly larger.</p>
 *
 * <h2>Why the present pass has to be replaced</h2>
 *
 * <p>{@code RenderTarget#blitToScreen()} ends up in
 * {@code GlCommandEncoder#presentTexture(GpuTextureView)}, which blits the colour
 * texture to the window's default framebuffer using <b>identical source and destination
 * rectangles</b>. It never scales. Left alone, a downscaled main target would therefore
 * be painted 1:1 into the bottom-left corner of the window.</p>
 *
 * <p>So {@code RenderTargetMixin} intercepts the main target's blit and routes it
 * through {@link #presentWithUpscale(RenderTarget)}: the scaled image is first resampled
 * into a MetalBoi-owned target at true native size using the chosen filter, and that
 * target is then presented 1:1 by vanilla's own code path.</p>
 *
 * <p>All state changes go through Minecraft's {@code RenderPass} / {@code RenderPipeline}
 * abstraction rather than raw GL calls, so {@code GlStateManager}'s cached state is
 * never desynchronised.</p>
 */
public final class MetalBoiPipeline {

    /** Scale applied to {@code Window#getWidth/Height}. 1.0 means untouched. */
    private static volatile float scaleFactor = 1.0f;
    private static volatile boolean active = false;
    private static volatile Upscaler activeUpscaler = Upscaler.BILINEAR;

    /** MetalBoi's own native-resolution target that the upscale pass renders into. */
    private static TextureTarget upscaleTarget;
    private static int targetWidth = -1;
    private static int targetHeight = -1;

    private MetalBoiPipeline() {
    }

    /**
     * Re-read the config and push the new scale into Minecraft.
     *
     * <p>Safe to call before the client exists (the resize is simply skipped) and safe
     * to call from the config screen's apply hook.</p>
     */
    public static void applyConfig() {
        MetalBoiConfig config = MetalBoiConfig.get();
        boolean enabled = config.isRendererEnabled();

        activeUpscaler = enabled ? config.getUpscaler() : Upscaler.BILINEAR;
        scaleFactor = enabled ? config.getRenderScalePercent() / 100.0f : 1.0f;
        active = enabled && (scaleFactor < 1.0f || activeUpscaler.usesShader());

        MetalBoi.LOGGER.info(
                "[MetalBoi] renderer={} scale={}% upscaler={} active={}",
                enabled, config.getRenderScalePercent(), activeUpscaler, active);

        // The upscale target is sized in true native pixels; if the scale changed it has
        // to be rebuilt, and the main target has to be reallocated at the new size.
        releaseTarget();
        UpscalePipelines.invalidate();

        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft != null && minecraft.getWindow() != null) {
                minecraft.resizeDisplay();
            }
        } catch (Throwable t) {
            MetalBoi.LOGGER.warn("[MetalBoi] could not resize the display after applying config", t);
        }
    }

    /** Called on the render thread by {@code WindowMixin}; must never touch the Window. */
    public static float scaleFactor() {
        return scaleFactor;
    }

    public static boolean isActive() {
        return active;
    }

    public static Upscaler activeUpscaler() {
        return activeUpscaler;
    }

    /**
     * Present the main render target through MetalBoi's upscale pass.
     *
     * @param mainTarget the target being presented (always the {@link MainTarget})
     * @return true when MetalBoi presented the frame and the caller should cancel
     *         vanilla's blit; false to leave vanilla's behaviour completely untouched
     */
    public static boolean presentWithUpscale(RenderTarget mainTarget) {
        if (!active) {
            return false;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return false;
        }
        Window window = minecraft.getWindow();
        if (window == null) {
            return false;
        }

        int nativeWidth = ((WindowAccessor) (Object) window).metalboi$getNativeFramebufferWidth();
        int nativeHeight = ((WindowAccessor) (Object) window).metalboi$getNativeFramebufferHeight();
        if (nativeWidth <= 0 || nativeHeight <= 0) {
            return false;
        }

        int sourceWidth = mainTarget.width;
        int sourceHeight = mainTarget.height;

        // Only take over when the target really is ours. Vanilla temporarily resizes the
        // main target for panoramic screenshots; intercepting that would corrupt it.
        if (sourceWidth != expectedAxis(nativeWidth) || sourceHeight != expectedAxis(nativeHeight)) {
            return false;
        }

        GpuTextureView source = mainTarget.getColorTextureView();
        if (source == null || source.isClosed()) {
            return false;
        }

        try {
            if (!ensureTarget(nativeWidth, nativeHeight) || upscaleTarget == null) {
                return false;
            }
            GpuTextureView destination = upscaleTarget.getColorTextureView();
            if (destination == null || destination.isClosed()) {
                return false;
            }

            Upscaler upscaler = activeUpscaler;
            RenderPipeline pipeline;
            GpuSampler sampler;

            if (upscaler.usesShader()) {
                RenderPipeline custom = UpscalePipelines.get(upscaler);
                if (custom != null) {
                    pipeline = custom;
                    // The shaders compute exact texel centres, so point-sample the source.
                    sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
                } else {
                    pipeline = RenderPipelines.TRACY_BLIT;
                    sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
                }
            } else {
                pipeline = RenderPipelines.TRACY_BLIT;
                sampler = RenderSystem.getSamplerCache()
                        .getClampToEdge(upscaler == Upscaler.NEAREST ? FilterMode.NEAREST : FilterMode.LINEAR);
            }

            try (RenderPass pass = RenderSystem.getDevice()
                    .createCommandEncoder()
                    .createRenderPass(() -> "MetalBoi upscale", destination, OptionalInt.empty())) {
                pass.setPipeline(pipeline);
                RenderSystem.bindDefaultUniforms(pass);
                pass.bindTexture("InSampler", source, sampler);
                pass.draw(0, 3);
            }

            // Native size in, native size out: vanilla's 1:1 present is now correct.
            upscaleTarget.blitToScreen();
            return true;
        } catch (Throwable t) {
            MetalBoi.LOGGER.error("[MetalBoi] upscale present failed; disabling the pipeline", t);
            active = false;
            scaleFactor = 1.0f;
            releaseTarget();
            return false;
        }
    }

    private static int expectedAxis(int nativeSize) {
        return Math.max(1, Math.round(nativeSize * scaleFactor));
    }

    private static boolean ensureTarget(int width, int height) {
        if (upscaleTarget != null && targetWidth == width && targetHeight == height) {
            return true;
        }
        releaseTarget();
        upscaleTarget = new TextureTarget("MetalBoi Upscale", width, height, false);
        targetWidth = width;
        targetHeight = height;
        return true;
    }

    private static void releaseTarget() {
        if (upscaleTarget != null) {
            try {
                upscaleTarget.destroyBuffers();
            } catch (Throwable t) {
                MetalBoi.LOGGER.warn("[MetalBoi] failed to release the upscale target", t);
            }
            upscaleTarget = null;
        }
        targetWidth = -1;
        targetHeight = -1;
    }

    /** Release GPU resources on shutdown. */
    public static void shutdown() {
        releaseTarget();
    }
}
