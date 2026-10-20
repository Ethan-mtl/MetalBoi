package dev.metalboi.mixin;

import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.metalboi.platform.BackendInfo;
import dev.metalboi.render.MetalBoiPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Routes the main render target's present through MetalBoi's upscale pass.
 *
 * <p>Only the {@link MainTarget} is intercepted, and only when the pipeline is active
 * and the target's size matches what MetalBoi scaled it to. Anything else - MetalBoi's
 * own native-resolution target, or the main target temporarily resized for a panoramic
 * screenshot - falls straight through to vanilla.</p>
 */
@Mixin(RenderTarget.class)
public abstract class RenderTargetMixin {

    @Inject(method = "blitToScreen", at = @At("HEAD"), cancellable = true)
    private void metalboi$presentWithUpscale(CallbackInfo ci) {
        BackendInfo.logOnce();

        Object self = this;
        if (!(self instanceof MainTarget)) {
            return;
        }
        if (!MetalBoiPipeline.isActive()) {
            return;
        }
        if (MetalBoiPipeline.presentWithUpscale((RenderTarget) self)) {
            ci.cancel();
        }
    }
}
