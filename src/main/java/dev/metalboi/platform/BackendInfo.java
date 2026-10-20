package dev.metalboi.platform;

import dev.metalboi.MetalBoi;
import dev.metalboi.config.MetalBoiConfig;

import java.util.Locale;

/**
 * Honest reporting of which graphics backend Minecraft is actually running on.
 *
 * <p><b>Why this exists.</b> Minecraft Java renders through LWJGL's OpenGL bindings.
 * Java has no Metal bindings, so a mod cannot make the game speak Metal natively; the
 * only routes to Metal are Vulkan&#8594;MoltenVK or OpenGL&#8594;MoltenGL, i.e. exactly
 * the translation layers that cost performance.</p>
 *
 * <p>The useful fact is the flip side of that: on macOS the game is already running on
 * Apple's own native OpenGL driver, with <i>no</i> translation layer in the path. So
 * MetalBoi does not pretend to swap backends - it reports the truth and then extracts
 * the frame rate that the native path can actually deliver (render scale + upscaling).</p>
 */
public final class BackendInfo {

    /** Coarse classification of the GL implementation we ended up on. */
    public enum Kind {
        /** Apple's own deprecated-but-native OpenGL driver. No translation layer. */
        APPLE_NATIVE_OPENGL,
        /** A real Metal/Vulkan translation layer is in the path. */
        TRANSLATED,
        /** Software rasteriser - this will be slow regardless of settings. */
        SOFTWARE,
        /** Any other vendor's native OpenGL driver (Windows/Linux). */
        NATIVE_OPENGL,
        /** Queried before the GL context existed. */
        UNKNOWN
    }

    private static volatile Snapshot snapshot = Snapshot.unknown();
    private static volatile boolean logged;

    private BackendInfo() {
    }

    /**
     * Probe and log the backend exactly once, on the first frame that reaches the
     * present path - by then the GL context is guaranteed to exist.
     */
    public static void logOnce() {
        if (logged || !MetalBoiConfig.get().shouldLogBackendInfo()) {
            logged = true;
            return;
        }
        logged = true;

        Snapshot s = refresh();
        MetalBoi.LOGGER.info("[MetalBoi] backend: {}", s.summary());
        MetalBoi.LOGGER.info("[MetalBoi] api={}, device={}", s.apiDescription(), s.backendDescription());
        MetalBoi.LOGGER.info("[MetalBoi] {}", s.explanation());
        if (s.kind() == Kind.TRANSLATED) {
            MetalBoi.LOGGER.warn(
                    "[MetalBoi] a graphics translation layer is in the render path - this usually costs more "
                            + "frame rate than running the platform's native OpenGL driver.");
        }
    }

    /** Immutable report captured from the render thread once GL is current. */
    public record Snapshot(
            Kind kind,
            String vendor,
            String renderer,
            String version,
            String apiDescription,
            String backendDescription,
            boolean macOS,
            int maxTextureSize
    ) {
        static Snapshot unknown() {
            return new Snapshot(Kind.UNKNOWN, "unknown", "unknown", "unknown", "unknown", "unknown", isMacOs(), 0);
        }

        /** Human-readable one-liner, safe to log. */
        public String summary() {
            return "%s | vendor=%s | renderer=%s | version=%s".formatted(
                    this.kind.name().toLowerCase(Locale.ROOT).replace('_', ' '),
                    this.vendor, this.renderer, this.version);
        }

        /**
         * @return the honest explanation shown in the GUI for this backend.
         */
        public String explanation() {
            return switch (this.kind) {
                case APPLE_NATIVE_OPENGL ->
                        "Apple native OpenGL driver. There is no OpenGL-to-Metal translation layer here - "
                                + "this is already the zero-overhead path. Apple caps it at OpenGL 4.1, so the "
                                + "wins available are resolution and filtering, which is what MetalBoi does.";
                case TRANSLATED ->
                        "A translation layer is in the render path. Every draw call is being converted to "
                                + "another API before it reaches the GPU, which usually costs more than it saves.";
                case SOFTWARE ->
                        "Software rasterisation. The GPU is not being used, so frame rate will be poor at any "
                                + "resolution. Lowering render scale is the most effective fix available.";
                case NATIVE_OPENGL ->
                        "Native OpenGL driver for this platform. No translation layer is involved.";
                case UNKNOWN ->
                        "The OpenGL context is not available yet.";
            };
        }
    }

    public static Snapshot get() {
        return snapshot;
    }

    public static boolean isMacOs() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("mac") || os.contains("darwin") || os.contains("os x");
    }

    /**
     * Probe the live GL context. Must be called on the render thread after GL init;
     * never throws, so it is safe to call from GUI code.
     */
    public static Snapshot refresh() {
        try {
            String vendor = safe(() -> org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VENDOR));
            String renderer = safe(() -> org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER));
            String version = safe(() -> org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VERSION));
            String api = safe(BackendInfo::apiDescription);
            String backend = safe(BackendInfo::backendDescription);
            int maxTex = safeInt(() -> org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_MAX_TEXTURE_SIZE));

            Snapshot s = new Snapshot(classify(vendor, renderer, version), vendor, renderer, version,
                    api, backend, isMacOs(), maxTex);
            snapshot = s;
            return s;
        } catch (Throwable t) {
            MetalBoi.LOGGER.warn("[MetalBoi] backend probe failed", t);
            snapshot = Snapshot.unknown();
            return snapshot;
        }
    }

    private static Kind classify(String vendor, String renderer, String version) {
        String all = (vendor + " " + renderer + " " + version).toLowerCase(Locale.ROOT);

        if (all.contains("llvmpipe") || all.contains("softpipe") || all.contains("software rasterizer")
                || all.contains("mesa offscreen") || all.contains("microsoft basic render driver")) {
            return Kind.SOFTWARE;
        }
        if (all.contains("moltengl") || all.contains("moltenvk") || all.contains("zink")
                || all.contains("angle") || all.contains("gl4es")) {
            return Kind.TRANSLATED;
        }
        if (all.contains("apple")) {
            return Kind.APPLE_NATIVE_OPENGL;
        }
        return Kind.NATIVE_OPENGL;
    }

    /** Minecraft's own description of the rendering API (e.g. "OpenGL 4.1"). */
    private static String apiDescription() {
        return com.mojang.blaze3d.systems.RenderSystem.getApiDescription();
    }

    /** Minecraft's own description of the backend device. */
    private static String backendDescription() {
        return com.mojang.blaze3d.systems.RenderSystem.getBackendDescription();
    }

    private static String safe(java.util.function.Supplier<String> supplier) {
        try {
            String value = supplier.get();
            return value == null ? "unknown" : value;
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static int safeInt(java.util.function.IntSupplier supplier) {
        try {
            return supplier.getAsInt();
        } catch (Throwable t) {
            return 0;
        }
    }
}
