package dev.metalboi.mixin;

import com.mojang.blaze3d.platform.Window;
import dev.metalboi.render.MetalBoiPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes Minecraft render at a reduced internal resolution.
 *
 * <p>Every consumer of the framebuffer size - the main render target, the level
 * renderer's auxiliary targets, the projection matrix, the GUI scale and the HUD
 * projection - goes through {@link Window#getWidth()} / {@link Window#getHeight()} or
 * through {@code calculateScale} / {@code setGuiScale}, which read the same two fields.
 * Scaling all four keeps the whole frame self-consistent: the HUD still lays out
 * correctly and still respects the user's GUI scale setting, it is simply rasterised at
 * the lower resolution along with the world.</p>
 *
 * <p>The backing {@code framebufferWidth} / {@code framebufferHeight} fields are left at
 * their true native values. That matters for two reasons: the unscaled size is still
 * needed to build the native-resolution upscale target, and
 * {@link Window#onFramebufferResize} detects a resize by comparing {@code getWidth()}
 * before and after it writes those fields - deriving the scaled value from the fields
 * preserves that comparison exactly.</p>
 */
@Mixin(Window.class)
public abstract class WindowMixin {

    @Shadow
    private int framebufferWidth;

    @Shadow
    private int framebufferHeight;

    @Shadow
    private int guiScaledWidth;

    @Shadow
    private int guiScaledHeight;

    @Inject(method = "getWidth", at = @At("HEAD"), cancellable = true)
    private void metalboi$scaledWidth(CallbackInfoReturnable<Integer> cir) {
        float factor = MetalBoiPipeline.scaleFactor();
        if (factor < 1.0f) {
            cir.setReturnValue(Math.max(1, Math.round(this.framebufferWidth * factor)));
        }
    }

    @Inject(method = "getHeight", at = @At("HEAD"), cancellable = true)
    private void metalboi$scaledHeight(CallbackInfoReturnable<Integer> cir) {
        float factor = MetalBoiPipeline.scaleFactor();
        if (factor < 1.0f) {
            cir.setReturnValue(Math.max(1, Math.round(this.framebufferHeight * factor)));
        }
    }

    /**
     * Mirror of vanilla's implementation, evaluated against the scaled resolution so
     * the same GUI scale setting yields the same on-screen size after upscaling.
     */
    @Inject(method = "calculateScale", at = @At("HEAD"), cancellable = true)
    private void metalboi$scaledGuiScaleLimit(int target, boolean enforceUnicode, CallbackInfoReturnable<Integer> cir) {
        float factor = MetalBoiPipeline.scaleFactor();
        if (factor >= 1.0f) {
            return;
        }

        int width = Math.max(1, Math.round(this.framebufferWidth * factor));
        int height = Math.max(1, Math.round(this.framebufferHeight * factor));

        int scale = 1;
        while (scale != target
                && scale < width
                && scale < height
                && width / (scale + 1) >= 320
                && height / (scale + 1) >= 240) {
            scale++;
        }

        if (enforceUnicode && scale % 2 != 0) {
            scale++;
        }

        cir.setReturnValue(scale);
    }

    /**
     * Vanilla derives {@code guiScaledWidth/Height} from the native fields; redo the
     * division against the scaled resolution so HUD coordinates match the viewport.
     */
    @Inject(method = "setGuiScale", at = @At("TAIL"))
    private void metalboi$rescaleGuiDimensions(int scale, CallbackInfo ci) {
        float factor = MetalBoiPipeline.scaleFactor();
        if (factor >= 1.0f || scale <= 0) {
            return;
        }

        double divisor = scale;
        int width = Math.max(1, Math.round(this.framebufferWidth * factor));
        int height = Math.max(1, Math.round(this.framebufferHeight * factor));

        int scaledWidth = (int) (width / divisor);
        this.guiScaledWidth = width / divisor > scaledWidth ? scaledWidth + 1 : scaledWidth;

        int scaledHeight = (int) (height / divisor);
        this.guiScaledHeight = height / divisor > scaledHeight ? scaledHeight + 1 : scaledHeight;
    }
}
