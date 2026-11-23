package dev.skyblockflipper.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MouseHandler.class)
public interface MousePositionAccess {
    @Accessor("xpos")
    void skyblockFlipper$setX(double x);

    @Accessor("ypos")
    void skyblockFlipper$setY(double y);
}
