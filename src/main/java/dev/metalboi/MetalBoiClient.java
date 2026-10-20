package dev.metalboi;

import dev.metalboi.config.MetalBoiConfig;
import dev.metalboi.platform.BackendInfo;
import dev.metalboi.render.MetalBoiPipeline;
import net.fabricmc.api.ClientModInitializer;

/**
 * Client entrypoint for MetalBoi.
 *
 * <p>Fabric invokes this before the {@code Minecraft} instance exists, which is exactly
 * what the render-scale path needs: the scale factor must be in place before
 * {@code Window} is constructed so the very first main render target is allocated at the
 * configured internal resolution.</p>
 */
public final class MetalBoiClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        MetalBoiConfig config = MetalBoiConfig.get();

        MetalBoiPipeline.applyConfig();

        MetalBoi.LOGGER.info(
                "[MetalBoi] loaded: renderer={} scale={}% upscaler={} sharpness={}%",
                config.isRendererEnabled(),
                config.getRenderScalePercent(),
                config.getUpscaler(),
                config.getSharpnessPercent());

        if (BackendInfo.isMacOs()) {
            MetalBoi.LOGGER.info(
                    "[MetalBoi] macOS detected. Minecraft Java renders through LWJGL's OpenGL bindings, which "
                            + "on macOS means Apple's own native OpenGL driver (capped at 4.1) - there is no "
                            + "OpenGL-to-Metal translation layer in the path to remove. MetalBoi therefore reports "
                            + "the backend it finds and spends its effort on the setting that actually moves frame "
                            + "rate here: internal render resolution.");
        }

        Runtime.getRuntime().addShutdownHook(new Thread(MetalBoiPipeline::shutdown, "MetalBoi-Shutdown"));
    }
}
