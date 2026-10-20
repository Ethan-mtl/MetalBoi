package dev.metalboi.render;

import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.metalboi.MetalBoi;
import dev.metalboi.config.MetalBoiConfig;
import dev.metalboi.config.Upscaler;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * Builds and caches the {@link RenderPipeline}s used by MetalBoi's upscale pass.
 *
 * <p>Every pipeline mirrors Minecraft's own {@code TRACY_BLIT}: the vanilla
 * {@code core/screenquad} vertex shader (which generates a fullscreen triangle from
 * {@code gl_VertexID}) plus a MetalBoi fragment shader, with no depth test, no depth
 * write and the {@code EMPTY} vertex format.</p>
 *
 * <p>Pipelines are validated with {@link CompiledRenderPipeline#isValid()} the first
 * time they are used. If a shader fails to resolve or compile - for example because a
 * resource reload is still in flight - {@link #get(Upscaler)} returns {@code null} and
 * the caller falls back to Minecraft's own blit rather than presenting a broken frame.</p>
 */
public final class UpscalePipelines {

    /** Mitchell-Netravali C for the plain bicubic mode (Catmull-Rom). */
    private static final float C_BICUBIC = 0.5f;
    /** Upper bound of C used by the sharpening slider. */
    private static final float C_MAX = 1.0f;

    /** Sharpening is quantised so a slider drag cannot create unbounded pipelines. */
    private static final int SHARPNESS_QUANTUM = 5;

    private static final Map<Integer, RenderPipeline> MITCHELL_CACHE = new HashMap<>();
    private static final Map<Integer, Boolean> MITCHELL_VALID = new HashMap<>();

    private static RenderPipeline lanczos;
    private static boolean lanczosChecked;
    private static boolean lanczosValid;

    private UpscalePipelines() {
    }

    /**
     * @return the pipeline for the given mode, or {@code null} when it could not be
     *         compiled and the caller should fall back to vanilla blitting.
     */
    public static RenderPipeline get(Upscaler upscaler) {
        return switch (upscaler) {
            case BICUBIC -> mitchell(C_BICUBIC);
            case SHARP -> mitchell(sharpnessToC(MetalBoiConfig.get().getSharpnessPercent()));
            case LANCZOS -> lanczos();
            case NEAREST, BILINEAR -> null;
        };
    }

    /** Maps the 0..100 sharpening setting onto Mitchell-Netravali's C parameter. */
    public static float sharpnessToC(int sharpnessPercent) {
        int clamped = Math.max(0, Math.min(100, sharpnessPercent));
        int quantised = Math.round(clamped / (float) SHARPNESS_QUANTUM) * SHARPNESS_QUANTUM;
        float t = quantised / 100.0f;
        return C_BICUBIC + (C_MAX - C_BICUBIC) * t;
    }

    private static RenderPipeline mitchell(float c) {
        int key = Math.round(c * 1000.0f);

        RenderPipeline cached = MITCHELL_CACHE.get(key);
        if (cached == null) {
            cached = build(
                    "mitchell_" + key,
                    Identifier.fromNamespaceAndPath(MetalBoi.MOD_ID, "core/metalboi_mitchell"),
                    c);
            MITCHELL_CACHE.put(key, cached);
        }

        Boolean valid = MITCHELL_VALID.get(key);
        if (valid == null) {
            valid = validate(cached);
            MITCHELL_VALID.put(key, valid);
            if (!valid) {
                MetalBoi.LOGGER.error("[MetalBoi] bicubic upscale shader (C={}) failed to compile; falling back to bilinear", c);
            }
        }
        return valid ? cached : null;
    }

    private static RenderPipeline lanczos() {
        if (!lanczosChecked) {
            lanczos = build(
                    "lanczos",
                    Identifier.fromNamespaceAndPath(MetalBoi.MOD_ID, "core/metalboi_lanczos"),
                    -1.0f);
            lanczosValid = validate(lanczos);
            lanczosChecked = true;
            if (!lanczosValid) {
                MetalBoi.LOGGER.error("[MetalBoi] Lanczos upscale shader failed to compile; falling back to bilinear");
            }
        }
        return lanczosValid ? lanczos : null;
    }

    private static RenderPipeline build(String locationSuffix, Identifier fragmentShader, float mitchellC) {
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath(MetalBoi.MOD_ID, "pipeline/upscale_" + locationSuffix))
                .withVertexShader("core/screenquad")
                .withFragmentShader(fragmentShader)
                .withSampler("InSampler")
                .withDepthWrite(false)
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                .withColorWrite(true, false)
                .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES);

        if (mitchellC >= 0.0f) {
            builder.withShaderDefine("METALBOI_C", mitchellC);
        }

        return builder.build();
    }

    /**
     * Pre-compile so a bad shader is detected once, up front, instead of silently
     * presenting a broken frame every tick.
     */
    private static boolean validate(RenderPipeline pipeline) {
        try {
            CompiledRenderPipeline compiled = RenderSystem.getDevice().precompilePipeline(pipeline);
            return compiled != null && compiled.isValid();
        } catch (Throwable t) {
            MetalBoi.LOGGER.error("[MetalBoi] could not pre-compile pipeline {}", pipeline.getLocation(), t);
            return false;
        }
    }

    /** Drop cached validation state; used after a resource reload. */
    public static void invalidate() {
        MITCHELL_CACHE.clear();
        MITCHELL_VALID.clear();
        lanczos = null;
        lanczosChecked = false;
        lanczosValid = false;
    }
}
