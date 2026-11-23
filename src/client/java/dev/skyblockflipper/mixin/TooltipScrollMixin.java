package dev.skyblockflipper.mixin;

import dev.skyblockflipper.TooltipScrolling;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
abstract class TooltipScrollMixin {
    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void bgm$scrollTooltip(long window, double horizontal, double vertical, CallbackInfo callback) {
        if (window == Minecraft.getInstance().getWindow().handle() && TooltipScrolling.scroll(vertical)) {
            callback.cancel();
        }
    }
}
