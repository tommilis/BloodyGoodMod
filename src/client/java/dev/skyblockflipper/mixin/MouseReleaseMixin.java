package dev.skyblockflipper.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.Window;
import dev.skyblockflipper.InventoryCursorAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(MouseHandler.class)
abstract class MouseReleaseMixin {
    @WrapOperation(method = "releaseMouse", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/platform/InputConstants;grabOrReleaseMouse(Lcom/mojang/blaze3d/platform/Window;IDD)V"))
    private void bgm$releaseAtInventoryCursor(Window window, int mode, double x, double y,
                                               Operation<Void> original) {
        var client = Minecraft.getInstance();
        var position = ((InventoryCursorAccess) client.gui).bgm$takeInventoryCursor();
        if (position == null) {
            original.call(window, mode, x, y);
            return;
        }

        original.call(window, mode, position.x(), position.y());
        // GLFW can restore the capture position when it switches back to a visible cursor.
        GLFW.glfwSetCursorPos(window.handle(), position.x(), position.y());
        var mouse = (MousePositionAccess) client.mouseHandler;
        mouse.skyblockFlipper$setX(position.x());
        mouse.skyblockFlipper$setY(position.y());
    }
}
