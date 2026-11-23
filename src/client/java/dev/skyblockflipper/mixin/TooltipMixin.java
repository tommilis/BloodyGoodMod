package dev.skyblockflipper.mixin;

import dev.skyblockflipper.TooltipScrolling;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.List;

@Mixin(GuiGraphicsExtractor.class)
abstract class TooltipMixin {
    @Shadow public abstract int guiHeight();

    @ModifyVariable(method = "tooltip", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private List<ClientTooltipComponent> bgm$fitLines(List<ClientTooltipComponent> lines,
                                                     Font font, List<ClientTooltipComponent> original,
                                                     int x, int y) {
        return TooltipScrolling.fit(font, lines, x, y, guiHeight());
    }
}
