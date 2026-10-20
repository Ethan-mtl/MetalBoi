package dev.metalboi.gui;

import dev.metalboi.MetalBoi;
import dev.metalboi.config.MetalBoiConfig;
import dev.metalboi.config.Upscaler;
import dev.metalboi.platform.BackendInfo;
import dev.metalboi.render.MetalBoiPipeline;
import dev.metalboi.render.UpscalePipelines;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.ConfigState;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ModOptionsBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionGroupBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionPageBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Registers MetalBoi's settings inside Sodium's video-settings screen.
 *
 * <p>Wired up through the {@code sodium:config_api_user} entrypoint in
 * {@code fabric.mod.json}. Sodium discovers this class itself, so it is only ever loaded
 * when Sodium is present - MetalBoi's renderer still works without it, the settings just
 * have to be edited in {@code config/metalboi.json}.</p>
 *
 * <p>The result is a "MetalBoi" tab in Sodium's options screen with two groups: the
 * renderer toggle (whose tooltip doubles as a live backend report) and the resolution /
 * upscaling controls.</p>
 */
public final class MetalBoiSodiumConfig implements ConfigEntryPoint {

    private static final Identifier ID_RENDERER_ENABLED = id("renderer_enabled");
    private static final Identifier ID_RENDER_SCALE = id("render_scale");
    private static final Identifier ID_UPSCALER = id("upscaler");
    private static final Identifier ID_SHARPNESS = id("sharpness");
    private static final Identifier ID_LOG_BACKEND = id("log_backend");

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MetalBoi.MOD_ID, path);
    }

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        MetalBoiConfig config = MetalBoiConfig.get();

        ModOptionsBuilder mod = builder.registerOwnModOptions();
        OptionPageBuilder page = builder.createOptionPage();
        page.setName(Component.translatable("metalboi.gui.page"));

        page.addOptionGroup(backendGroup(builder, config));
        page.addOptionGroup(resolutionGroup(builder, config));

        mod.addPage(page);
    }

    private static OptionGroupBuilder backendGroup(ConfigBuilder builder, MetalBoiConfig config) {
        OptionGroupBuilder group = builder.createOptionGroup();
        group.setName(Component.translatable("metalboi.gui.group.backend"));

        group.addOption(builder.createBooleanOption(ID_RENDERER_ENABLED)
                .setName(Component.translatable("metalboi.gui.option.renderer_enabled"))
                // Doubles as the backend report: this is the honest answer to "is this
                // running on Metal?" and it is refreshed whenever the screen rebuilds.
                .setTooltip(value -> backendTooltip())
                .setDefaultValue(false)
                .setBinding(config::setRendererEnabled, config::isRendererEnabled)
                .setImpact(OptionImpact.HIGH)
                .setApplyHook(state -> apply(config))
                .setStorageHandler(config::save));

        group.addOption(builder.createBooleanOption(ID_LOG_BACKEND)
                .setName(Component.translatable("metalboi.gui.option.log_backend"))
                .setTooltip(Component.translatable("metalboi.gui.option.log_backend.tooltip"))
                .setDefaultValue(true)
                .setBinding(config::setLogBackendInfo, config::shouldLogBackendInfo)
                .setStorageHandler(config::save));

        return group;
    }

    private static OptionGroupBuilder resolutionGroup(ConfigBuilder builder, MetalBoiConfig config) {
        OptionGroupBuilder group = builder.createOptionGroup();
        group.setName(Component.translatable("metalboi.gui.group.resolution"));

        group.addOption(builder.createIntegerOption(ID_RENDER_SCALE)
                .setName(Component.translatable("metalboi.gui.option.render_scale"))
                .setTooltip(Component.translatable("metalboi.gui.option.render_scale.tooltip"))
                .setEnabledProvider(MetalBoiSodiumConfig::rendererEnabled, ID_RENDERER_ENABLED)
                .setDefaultValue(MetalBoiConfig.MAX_SCALE_PERCENT)
                .setRange(MetalBoiConfig.MIN_SCALE_PERCENT, MetalBoiConfig.MAX_SCALE_PERCENT, MetalBoiConfig.SCALE_STEP)
                .setValueFormatter(value -> Component.literal(value + "%"))
                .setBinding(config::setRenderScalePercent, config::getRenderScalePercent)
                .setImpact(OptionImpact.HIGH)
                .setApplyHook(state -> apply(config))
                .setStorageHandler(config::save));

        group.addOption(builder.createEnumOption(ID_UPSCALER, Upscaler.class)
                .setName(Component.translatable("metalboi.gui.option.upscaler"))
                .setTooltip(upscaler -> Component.translatable("metalboi.gui.option.upscaler.tooltip." + upscaler.id()))
                .setEnabledProvider(MetalBoiSodiumConfig::rendererEnabled, ID_RENDERER_ENABLED)
                .setDefaultValue(Upscaler.BICUBIC)
                .setElementNameProvider(upscaler -> Component.translatable("metalboi.gui.upscaler." + upscaler.id()))
                .setBinding(config::setUpscaler, config::getUpscaler)
                .setImpact(OptionImpact.LOW)
                .setApplyHook(state -> apply(config))
                .setStorageHandler(config::save));

        group.addOption(builder.createIntegerOption(ID_SHARPNESS)
                .setName(Component.translatable("metalboi.gui.option.sharpness"))
                .setTooltip(Component.translatable("metalboi.gui.option.sharpness.tooltip"))
                .setEnabledProvider(MetalBoiSodiumConfig::sharpnessEnabled, ID_RENDERER_ENABLED, ID_UPSCALER)
                .setDefaultValue(60)
                .setRange(0, 100, 5)
                .setValueFormatter(value -> Component.literal(value + "%"))
                .setBinding(config::setSharpnessPercent, config::getSharpnessPercent)
                .setImpact(OptionImpact.LOW)
                .setApplyHook(state -> apply(config))
                .setStorageHandler(config::save));

        return group;
    }

    private static Boolean rendererEnabled(ConfigState state) {
        return state.readBooleanOption(ID_RENDERER_ENABLED);
    }

    private static Boolean sharpnessEnabled(ConfigState state) {
        return state.readBooleanOption(ID_RENDERER_ENABLED)
                && state.readEnumOption(ID_UPSCALER, Upscaler.class) == Upscaler.SHARP;
    }

    private static void apply(MetalBoiConfig config) {
        config.save();
        UpscalePipelines.invalidate();
        MetalBoiPipeline.applyConfig();
    }

    private static Component backendTooltip() {
        BackendInfo.Snapshot snapshot = BackendInfo.refresh();
        return Component.translatable("metalboi.gui.option.renderer_enabled.tooltip")
                .append("\n\n")
                .append(Component.translatable("metalboi.gui.backend.header"))
                .append("\n")
                .append(Component.literal(snapshot.kind().name().replace('_', ' ').toLowerCase(java.util.Locale.ROOT)))
                .append("\n")
                .append(Component.literal(snapshot.renderer()))
                .append("\n")
                .append(Component.literal(snapshot.version()))
                .append("\n\n")
                .append(Component.literal(snapshot.explanation()));
    }
}
