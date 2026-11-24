package dev.skyblockflipper.mixin;

import dev.skyblockflipper.MarketLookup;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
abstract class MarketLookupKeyMixin {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void bgm$lookup(long window, int action, KeyEvent event, CallbackInfo callback) {
        var client = Minecraft.getInstance();
        if (window == client.getWindow().handle() && MarketLookup.onKey(client, action, event)) {
            callback.cancel();
        }
    }
}
