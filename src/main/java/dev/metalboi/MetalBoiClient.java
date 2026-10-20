package dev.metalboi;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entrypoint for MetalBoi.
 *
 * <p>This is intentionally thin: all real work lives in {@link dev.metalboi.render}
 * (the render-scale / upscale pipeline) and {@link dev.metalboi.gui} (the Sodium
 * video-settings integration).</p>
 */
public final class MetalBoiClient implements ClientModInitializer {
    public static final String MOD_ID = "metalboi";
    public static final Logger LOGGER = LoggerFactory.getLogger("MetalBoi");

    @Override
    public void onInitializeClient() {
        LOGGER.info("[MetalBoi] initialising");
    }
}
