package dev.skyblockflipper.mixin;

import dev.skyblockflipper.CursorResetSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
abstract class CursorPositionMixin {
    @Shadow public abstract Screen screen();

    @Unique private static final long SKYBLOCK_FLIPPER_TRANSITION_NS = 500_000_000L;
    @Unique private ClientLevel skyblockFlipper$level;
    @Unique private long skyblockFlipper$savedAt;
    @Unique private double skyblockFlipper$x;
    @Unique private double skyblockFlipper$y;

    @Inject(method = "setScreen", at = @At("HEAD"))
    private void skyblockFlipper$rememberCursor(Screen next, CallbackInfo callback) {
        Minecraft client = Minecraft.getInstance();
        if (!CursorResetSetting.enabled() || client.level == null
                || (next != null && !(next instanceof AbstractContainerScreen<?>))) {
            skyblockFlipper$level = null;
            return;
        }

        if (screen() instanceof AbstractContainerScreen<?> && !client.mouseHandler.isMouseGrabbed()) {
            skyblockFlipper$level = client.level;
            skyblockFlipper$savedAt = System.nanoTime();
            skyblockFlipper$x = client.mouseHandler.xpos();
            skyblockFlipper$y = client.mouseHandler.ypos();
        }
    }

    @Inject(method = "setScreen", at = @At("RETURN"))
    private void skyblockFlipper$restoreCursor(Screen next, CallbackInfo callback) {
        if (!(next instanceof AbstractContainerScreen<?>) || screen() != next) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        ClientLevel savedLevel = skyblockFlipper$level;
        skyblockFlipper$level = null;
        if (!CursorResetSetting.enabled() || savedLevel == null || savedLevel != client.level
                || System.nanoTime() - skyblockFlipper$savedAt > SKYBLOCK_FLIPPER_TRANSITION_NS
                || client.mouseHandler.isMouseGrabbed()) {
            return;
        }

        var window = client.getWindow();
        if (!window.isFocused()
                || window.getScreenWidth() < 1 || window.getScreenHeight() < 1) {
            return;
        }
        double x = Math.clamp(skyblockFlipper$x, 0, window.getScreenWidth() - 1);
        double y = Math.clamp(skyblockFlipper$y, 0, window.getScreenHeight() - 1);
        if (x == client.mouseHandler.xpos() && y == client.mouseHandler.ypos()) {
            return;
        }

        GLFW.glfwSetCursorPos(window.handle(), x, y);
        var mouse = (MousePositionAccess) client.mouseHandler;
        mouse.skyblockFlipper$setX(x);
        mouse.skyblockFlipper$setY(y);
    }
}
