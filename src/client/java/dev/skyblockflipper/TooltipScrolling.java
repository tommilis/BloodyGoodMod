package dev.skyblockflipper;

import dev.skyblockflipper.mixin.TooltipTextAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;

import java.util.List;

public final class TooltipScrolling {
    static boolean enabled;
    private static Screen screen;
    private static int anchorX;
    private static int anchorY;
    private static int signature;
    private static int offset;
    private static int maxOffset;
    private static long drawnAt;

    private TooltipScrolling() {}

    public static List<ClientTooltipComponent> fit(Font font, List<ClientTooltipComponent> lines,
                                                   int x, int y, int screenHeight) {
        Screen current = Minecraft.getInstance().gui.screen();
        if (!enabled || current == null) {
            return lines;
        }
        int available = Math.max(1, screenHeight - 20);
        int[] heights = lines.stream().mapToInt(line -> Math.max(1, line.getHeight(font))).toArray();
        int remaining = 0;
        int lastPage = heights.length;
        while (lastPage > 0 && remaining + heights[lastPage - 1] <= available) {
            remaining += heights[--lastPage];
        }
        if (lastPage == 0 || lines.size() < 2) {
            return lines;
        }
        int hash = 1;
        for (var line : lines) {
            hash = 31 * hash + line.getClass().hashCode();
            if (line instanceof TooltipTextAccess text) {
                int[] lineHash = {1};
                text.bgm$text().accept((index, style, codePoint) -> {
                    lineHash[0] = 31 * lineHash[0] + codePoint;
                    return true;
                });
                hash = 31 * hash + lineHash[0];
            }
        }
        long now = System.nanoTime();
        if (screen != current || signature != hash || now - drawnAt > 200_000_000L) {
            offset = 0;
        }
        screen = current;
        signature = hash;
        var window = Minecraft.getInstance().getWindow();
        var mouse = Minecraft.getInstance().mouseHandler;
        anchorX = (int) (mouse.xpos() * window.getGuiScaledWidth() / Math.max(1, window.getScreenWidth()));
        anchorY = (int) (mouse.ypos() * window.getGuiScaledHeight() / Math.max(1, window.getScreenHeight()));
        // Only wheel-scroll a tooltip anchored near the pointer.
        drawnAt = Math.abs(x - anchorX) <= 24 && Math.abs(y - anchorY) <= 24 ? now : 0;
        maxOffset = Math.min(lastPage, heights.length - 1);
        offset = Math.min(offset, maxOffset);
        int end = offset + 1;
        int used = heights[offset];
        while (end < heights.length && used + heights[end] <= available) {
            used += heights[end++];
        }
        return lines.subList(offset, end);
    }

    public static boolean scroll(double delta) {
        Minecraft client = Minecraft.getInstance();
        if (!enabled || delta == 0 || screen == null || screen != client.gui.screen()
                || System.nanoTime() - drawnAt > 200_000_000L) {
            return false;
        }
        var window = client.getWindow();
        var mouse = client.mouseHandler;
        double x = mouse.xpos() * window.getGuiScaledWidth() / Math.max(1, window.getScreenWidth());
        double y = mouse.ypos() * window.getGuiScaledHeight() / Math.max(1, window.getScreenHeight());
        if (Math.abs(x - anchorX) > 4 || Math.abs(y - anchorY) > 4) {
            return false;
        }
        offset = Math.clamp(offset + (delta > 0 ? -3 : 3), 0, maxOffset);
        return true;
    }
}
