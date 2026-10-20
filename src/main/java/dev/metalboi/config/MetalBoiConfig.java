package dev.metalboi.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.metalboi.MetalBoi;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * MetalBoi's persisted settings.
 *
 * <p>Stored as {@code config/metalboi.json}. The object is a plain POJO so Gson can
 * (de)serialise it directly; all access from the render thread goes through the
 * {@code volatile} snapshot installed by {@link #apply()}.</p>
 */
public final class MetalBoiConfig {
    public static final String FILE_NAME = "metalboi.json";

    /** Minimum render scale, in percent. Below this the game is unplayable. */
    public static final int MIN_SCALE_PERCENT = 25;
    /** Maximum render scale, in percent. 100 == native window resolution. */
    public static final int MAX_SCALE_PERCENT = 100;
    public static final int SCALE_STEP = 5;

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .create();

    private static MetalBoiConfig instance;

    // ---- persisted fields (names matter: they are the JSON keys) ----

    /** Master toggle for MetalBoi's renderer path. */
    private boolean rendererEnabled = false;

    /** Internal render resolution as a percentage of the window's framebuffer size. */
    private int renderScalePercent = MAX_SCALE_PERCENT;

    /** Filter used when presenting the scaled image. */
    private Upscaler upscaler = Upscaler.FSR1;

    /** RCAS/CAS strength, 0 (off) .. 100 (maximum). */
    private int sharpnessPercent = 60;

    /** Emit a one-line backend report to the log on client start. */
    private boolean logBackendInfo = true;

    // ---- accessors ----

    public static synchronized MetalBoiConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    public boolean isRendererEnabled() {
        return this.rendererEnabled;
    }

    public void setRendererEnabled(boolean rendererEnabled) {
        this.rendererEnabled = rendererEnabled;
    }

    public int getRenderScalePercent() {
        return clampScale(this.renderScalePercent);
    }

    public void setRenderScalePercent(int renderScalePercent) {
        this.renderScalePercent = clampScale(renderScalePercent);
    }

    public Upscaler getUpscaler() {
        return this.upscaler == null ? Upscaler.FSR1 : this.upscaler;
    }

    public void setUpscaler(Upscaler upscaler) {
        this.upscaler = upscaler;
    }

    public int getSharpnessPercent() {
        return Math.clamp(this.sharpnessPercent, 0, 100);
    }

    public void setSharpnessPercent(int sharpnessPercent) {
        this.sharpnessPercent = Math.clamp(sharpnessPercent, 0, 100);
    }

    public boolean shouldLogBackendInfo() {
        return this.logBackendInfo;
    }

    public void setLogBackendInfo(boolean logBackendInfo) {
        this.logBackendInfo = logBackendInfo;
    }

    /**
     * @return true when the render-scale pipeline should actually engage. At 100%
     *         scale with a non-shader upscaler there is nothing to do, so vanilla's
     *         own present path is left completely untouched.
     */
    public boolean isPipelineActive() {
        if (!this.rendererEnabled) {
            return false;
        }
        return this.getRenderScalePercent() < MAX_SCALE_PERCENT || this.getUpscaler().usesShader();
    }

    /**
     * Scale one native-resolution axis down to the internal render resolution.
     *
     * @param nativeSize window framebuffer size in pixels
     * @return internal size, always at least 1 pixel
     */
    public int scaleAxis(int nativeSize) {
        if (!this.isPipelineActive()) {
            return nativeSize;
        }
        int scaled = Math.round(nativeSize * (this.getRenderScalePercent() / 100.0f));
        return Math.max(1, scaled);
    }

    public static int clampScale(int percent) {
        int clamped = Math.clamp(percent, MIN_SCALE_PERCENT, MAX_SCALE_PERCENT);
        // Snap to the step so the slider and the stored value always agree.
        int snapped = MIN_SCALE_PERCENT + Math.round((clamped - MIN_SCALE_PERCENT) / (float) SCALE_STEP) * SCALE_STEP;
        return Math.clamp(snapped, MIN_SCALE_PERCENT, MAX_SCALE_PERCENT);
    }

    // ---- persistence ----

    public static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    private static MetalBoiConfig load() {
        Path path = configPath();
        if (Files.exists(path)) {
            try {
                String json = Files.readString(path, StandardCharsets.UTF_8);
                MetalBoiConfig parsed = GSON.fromJson(json, MetalBoiConfig.class);
                if (parsed != null) {
                    parsed.normalise();
                    MetalBoi.LOGGER.info("[MetalBoi] loaded config from {}", path);
                    return parsed;
                }
            } catch (Exception e) {
                MetalBoi.LOGGER.error("[MetalBoi] could not parse {}, falling back to defaults", path, e);
            }
        }

        MetalBoiConfig fresh = new MetalBoiConfig();
        fresh.save();
        return fresh;
    }

    private void normalise() {
        this.renderScalePercent = clampScale(this.renderScalePercent);
        this.sharpnessPercent = Math.clamp(this.sharpnessPercent, 0, 100);
        if (this.upscaler == null) {
            this.upscaler = Upscaler.FSR1;
        }
    }

    public void save() {
        Path path = configPath();
        try {
            Path parent = path.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(this), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException | UnsupportedOperationException e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            MetalBoi.LOGGER.error("[MetalBoi] could not write config to {}", path, e);
        }
    }
}
