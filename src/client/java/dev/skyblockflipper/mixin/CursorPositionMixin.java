package dev.skyblockflipper.mixin;

import dev.skyblockflipper.CursorResetSetting;
import dev.skyblockflipper.InventoryCursorAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
abstract class CursorPositionMixin implements InventoryCursorAccess {
    @Shadow public abstract Screen screen();

    @Unique private static final long SKYBLOCK_FLIPPER_TRANSITION_NS = 500_000_000L;
    @Unique private ClientLevel skyblockFlipper$level;
    @Unique private long skyblockFlipper$savedAt;
    @Unique private Position bgm$savedPosition;

    @Inject(method = "setScreen", at = @At("HEAD"))
    private void skyblockFlipper$rememberCursor(Screen next, CallbackInfo callback) {
        Minecraft client = Minecraft.getInstance();
        var window = client.getWindow();
        if (!CursorResetSetting.enabled() || client.level == null || !window.isFocused()
                || (next != null && !(next instanceof AbstractContainerScreen<?>))) {
            bgm$savedPosition = null;
            skyblockFlipper$level = null;
            return;
        }

        if (screen() instanceof AbstractContainerScreen<?> && !client.mouseHandler.isMouseGrabbed()) {
            try (var stack = MemoryStack.stackPush()) {
                var x = stack.mallocDouble(1);
                var y = stack.mallocDouble(1);
                GLFW.glfwGetCursorPos(window.handle(), x, y);
                bgm$savedPosition = new Position(x.get(0), y.get(0));
                skyblockFlipper$level = client.level;
                skyblockFlipper$savedAt = System.nanoTime();
            }
        }
    }

    @Inject(method = "setScreen", at = @At("RETURN"))
    private void skyblockFlipper$restoreCursor(Screen next, CallbackInfo callback) {
        if (!(next instanceof AbstractContainerScreen<?>) || screen() != next) {
            return;
        }

        var position = bgm$takeInventoryCursor();
        if (position == null) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        GLFW.glfwSetCursorPos(client.getWindow().handle(), position.x(), position.y());
        var mouse = (MousePositionAccess) client.mouseHandler;
        mouse.skyblockFlipper$setX(position.x());
        mouse.skyblockFlipper$setY(position.y());
    }

    @Override
    public Position bgm$takeInventoryCursor() {
        if (!(screen() instanceof AbstractContainerScreen<?>)) {
            return null;
        }

        Minecraft client = Minecraft.getInstance();
        var savedPosition = bgm$savedPosition;
        var savedLevel = skyblockFlipper$level;
        bgm$savedPosition = null;
        skyblockFlipper$level = null;
        var window = client.getWindow();
        if (!CursorResetSetting.enabled() || savedPosition == null || savedLevel != client.level
                || System.nanoTime() - skyblockFlipper$savedAt > SKYBLOCK_FLIPPER_TRANSITION_NS
                || client.mouseHandler.isMouseGrabbed() || !window.isFocused()
                || window.getScreenWidth() < 1 || window.getScreenHeight() < 1) {
            return null;
        }
        return new Position(Math.clamp(savedPosition.x(), 0, window.getScreenWidth() - 1),
                Math.clamp(savedPosition.y(), 0, window.getScreenHeight() - 1));
    }
}
