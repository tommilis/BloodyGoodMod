package dev.skyblockflipper;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

final class FlipHistoryScreen extends Screen {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.US)
            .withZone(ZoneId.systemDefault());
    private static final int ROW_HEIGHT = 42;
    private final Screen parent;
    private List<AuctionDatabase.SeenFlip> flips = List.of();
    private String status = "Loading…";
    private int offset;

    FlipHistoryScreen(Screen parent) {
        super(Component.literal("Flip History"));
        this.parent = parent;
    }

    void loaded(List<AuctionDatabase.SeenFlip> flips, String status) {
        this.flips = flips;
        this.status = status;
        offset = Math.min(offset, maxOffset());
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.literal("Back"), button -> onClose())
                .bounds(width / 2 - 45, height - 28, 90, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        int panelWidth = Math.min(440, width - 16);
        int left = (width - panelWidth) / 2;
        int bottom = height - 35;
        graphics.fill(left, 8, left + panelWidth, bottom, 0xF0181818);
        graphics.centeredText(font, title, width / 2, 16, 0xFFFFFFFF);
        graphics.centeredText(font, flips.isEmpty() ? "Detected opportunities" : status, width / 2, 29, 0xFFAAAAAA);
        if (flips.isEmpty()) {
            graphics.centeredText(font, status, width / 2, 52, 0xFFCCCCCC);
            return;
        }

        offset = Math.min(offset, maxOffset());
        for (int index = offset; index < Math.min(flips.size(), offset + visibleRows()); index++) {
            var entry = flips.get(index);
            var flip = entry.candidate();
            int y = 44 + (index - offset) * ROW_HEIGHT;
            graphics.fill(left + 5, y, left + panelWidth - 5, y + ROW_HEIGHT - 2, 0xFF292929);
            String time = DATE.format(Instant.ofEpochMilli(entry.seenAt()));
            int timeWidth = font.width(time);
            graphics.text(font, font.plainSubstrByWidth(flip.name(), Math.max(0, panelWidth - timeWidth - 24)),
                    left + 10, y + 3, 0xFFFFFFFF);
            graphics.text(font, time, left + panelWidth - timeWidth - 10, y + 3, 0xFFAAAAAA);
            String details = "Buy " + coins(flip.buy()) + "   Resale " + coins(flip.resale())
                    + "   Profit +" + coins(flip.profit());
            graphics.text(font, font.plainSubstrByWidth(details, panelWidth - 20),
                    left + 10, y + 15, 0xFF9BEBA3);
            graphics.text(font, font.plainSubstrByWidth(entry.outcome(), panelWidth - 20), left + 10, y + 28, 0xFFBBBBBB);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (vertical != 0 && maxOffset() > 0 && mouseY >= 44 && mouseY < height - 35) {
            offset = Math.clamp(offset + (vertical > 0 ? -3 : 3), 0, maxOffset());
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    private int visibleRows() {
        return Math.max(0, (height - 79) / ROW_HEIGHT);
    }

    private int maxOffset() {
        return Math.max(0, flips.size() - visibleRows());
    }

    private static String coins(long amount) {
        return NumberFormat.getIntegerInstance(Locale.US).format(amount);
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.gui.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
