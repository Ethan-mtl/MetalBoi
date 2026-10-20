package dev.metalboi.mixin;

import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the window's true framebuffer size in physical pixels.
 *
 * <p>{@code WindowMixin} scales {@link Window#getWidth()} and {@link Window#getHeight()},
 * so the unscaled values are only reachable through the backing fields. MetalBoi needs
 * them to size its native-resolution upscale target.</p>
 */
@Mixin(Window.class)
public interface WindowAccessor {

    @Accessor("framebufferWidth")
    int metalboi$getNativeFramebufferWidth();

    @Accessor("framebufferHeight")
    int metalboi$getNativeFramebufferHeight();
}
