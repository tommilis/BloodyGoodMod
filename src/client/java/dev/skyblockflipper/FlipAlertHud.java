package dev.skyblockflipper;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

final class FlipAlertHud {
    static final long DURATION_MS = 5_000;

    private FlipAlertHud() {}

    static void draw(GuiGraphicsExtractor graphics, Font font, AlertPosition position,
                     String item, String profit, long remaining) {
        int width = Math.min(182, graphics.guiWidth() - 8);
        if (width < 90 || graphics.guiHeight() < 52) {
            return;
        }
        int height = 40;
        int x = position.x(graphics.guiWidth(), width);
        int y = position.y(graphics.guiHeight(), height);
        int progress = (int) ((width - 2) * Math.min(remaining, DURATION_MS) / DURATION_MS);
        String timer = ((remaining + 999) / 1000) + "s";

        graphics.fill(x, y, x + width, y + height, 0xF0181B20);
        graphics.fill(x, y, x + 2, y + height, 0xFFFFAA00);
        graphics.text(font, "New flip", x + 8, y + 6, 0xFFFFAA00);
        graphics.text(font, timer, x + width - 8 - font.width(timer), y + 6, 0xFFE7D4A5);

        int itemWidth = width - font.width(profit) - 24;
        String title = font.width(item) <= itemWidth ? item
                : font.plainSubstrByWidth(item, itemWidth - font.width("…")) + "…";
        graphics.text(font, title, x + 8, y + 21, 0xFFFFFFFF);
        graphics.text(font, profit, x + width - 8 - font.width(profit), y + 21, 0xFF55FF55);
        graphics.fill(x + 2, y + height - 2, x + width, y + height, 0xFF3C3C3C);
        if (progress > 0) {
            graphics.fill(x + 2, y + height - 2, x + 2 + progress, y + height, 0xFFFFAA00);
        }
    }
}
