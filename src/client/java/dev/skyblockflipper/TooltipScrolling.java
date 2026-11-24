package dev.skyblockflipper;

import dev.skyblockflipper.mixin.TooltipTextAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;

import java.util.List;

public final class TooltipScrolling {
    private static final int VERTICAL_MARGIN = 20;
    private static final int FIRST_LINE_GAP = 2;
    private static final long HOVER_TIMEOUT_NS = 350_000_000L;
    private static final double POINTER_TOLERANCE = 8;

    static boolean enabled;
    private static Screen screen;
    private static double anchorX;
    private static double anchorY;
    private static int signature;
    private static int offset;
    private static int maxOffset;
    private static long drawnAt;

    private TooltipScrolling() {}

    public static List<ClientTooltipComponent> fit(Font font, List<ClientTooltipComponent> lines, int screenHeight) {
        Minecraft client = Minecraft.getInstance();
        Screen current = client.gui.screen();
        if (!enabled || current == null || lines.size() < 2) {
            return lines;
        }
        int available = Math.max(1, screenHeight - VERTICAL_MARGIN - FIRST_LINE_GAP);
        int[] heights = lines.stream().mapToInt(line -> Math.max(1, line.getHeight(font))).toArray();
        int lastPage = lastPageStart(heights, available);
        if (lastPage == 0) {
            return lines;
        }
        int hash = 1;
        // The title identifies the item even when a mod adds changing values to later lines.
        for (var line : lines.subList(0, Math.min(2, lines.size()))) {
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
        var mouse = client.mouseHandler;
        var window = client.getWindow();
        double x = mouse.getScaledXPos(window);
        double y = mouse.getScaledYPos(window);
        if (screen != current || signature != hash || now - drawnAt > HOVER_TIMEOUT_NS || pointerMoved(x, y)) {
            offset = 0;
        }
        screen = current;
        signature = hash;
        anchorX = x;
        anchorY = y;
        drawnAt = now;
        maxOffset = lastPage;
        offset = Math.min(offset, maxOffset);
        return lines.subList(offset, pageEnd(heights, available, offset));
    }

    static int lastPageStart(int[] heights, int available) {
        int start = heights.length - 1;
        int used = heights[start];
        while (start > 0 && used + heights[start - 1] <= available) {
            used += heights[--start];
        }
        return start;
    }

    static int pageEnd(int[] heights, int available, int offset) {
        int end = offset + 1;
        int used = heights[offset];
        while (end < heights.length && used + heights[end] <= available) {
            used += heights[end++];
        }
        return end;
    }

    private static boolean pointerMoved(double x, double y) {
        return Math.abs(x - anchorX) > POINTER_TOLERANCE || Math.abs(y - anchorY) > POINTER_TOLERANCE;
    }

    public static boolean scroll(double delta) {
        Minecraft client = Minecraft.getInstance();
        if (!enabled || delta == 0 || screen == null || screen != client.gui.screen()
                || System.nanoTime() - drawnAt > HOVER_TIMEOUT_NS) {
            return false;
        }
        var window = client.getWindow();
        var mouse = client.mouseHandler;
        if (pointerMoved(mouse.getScaledXPos(window), mouse.getScaledYPos(window))) {
            return false;
        }
        offset = Math.clamp(offset + (delta > 0 ? -3 : 3), 0, maxOffset);
        return true;
    }
}
