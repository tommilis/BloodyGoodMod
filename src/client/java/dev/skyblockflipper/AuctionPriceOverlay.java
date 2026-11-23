package dev.skyblockflipper;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

import java.text.NumberFormat;
import java.util.Locale;
import java.util.Objects;

final class AuctionPriceOverlay {
    private static final int ITEM_SLOT = 13;
    private final HypixelAuctions auctions;
    private Screen screen;
    private String suggestedText;
    private String binText;
    private FlipMarket.ListingPrice displayedEstimate;

    AuctionPriceOverlay(HypixelAuctions auctions) {
        this.auctions = auctions;
    }

    static boolean isAuctionSetup(Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?>)) {
            return false;
        }
        String name = screen.getTitle().getString();
        return name.startsWith("Create BIN Auction") || name.startsWith("Create Auction");
    }

    void tick(Minecraft client, boolean connected, long now) {
        Screen current = client.gui.screen();
        HypixelAuctions.Item selected = null;
        if (connected && isAuctionSetup(current)
                && current instanceof AbstractContainerScreen<?> container) {
            var menu = container.getMenu();
            if (menu.slots.size() > ITEM_SLOT) {
                selected = HypixelAuctions.itemOf(menu.getSlot(ITEM_SLOT).getItem()).orElse(null);
            }
        }
        screen = current;
        if (selected == null) {
            suggestedText = null;
            binText = null;
            displayedEstimate = null;
            return;
        }

        var estimate = auctions.listingPrice(selected, now);
        if (Objects.equals(displayedEstimate, estimate)) {
            return;
        }
        displayedEstimate = estimate;
        if (estimate.lowestBin() > 0) {
            binText = "Lowest BIN: " + coins(estimate.lowestBin()) + " coins";
        } else {
            binText = estimate.freshSnapshot() ? "Lowest BIN: no comparable listing"
                    : "Lowest BIN: waiting for a fresh snapshot";
        }

        if (estimate.suggestedCoins() == 0) {
            suggestedText = "Suggested price: not enough recent sales";
        } else {
            String label = estimate.lowestBin() > 0 ? "Suggested price: " : "Recent sale estimate: ";
            suggestedText = label + coins(estimate.suggestedCoins()) + " coins (" + estimate.sales() + " sales)";
        }
    }

    boolean hasSelectedItem() {
        return displayedEstimate != null;
    }

    void draw(Minecraft client, Screen current, GuiGraphicsExtractor graphics) {
        if (screen != current || displayedEstimate == null) {
            return;
        }
        int center = graphics.guiWidth() / 2;
        int binWidth = client.font.width(binText);
        int suggestionWidth = client.font.width(suggestedText);
        int width = Math.max(binWidth, suggestionWidth);
        graphics.fill(center - width / 2 - 6, 5, center + (width + 1) / 2 + 6, 33, 0xED181B20);
        graphics.text(client.font, binText, center - binWidth / 2, 9,
                displayedEstimate.lowestBin() > 0 ? 0xFFFFFFFF : 0xFFD0D0D0);
        graphics.text(client.font, suggestedText, center - suggestionWidth / 2, 21,
                displayedEstimate.suggestedCoins() > 0 ? 0xFFFFCF65 : 0xFFD0D0D0);
    }

    private static String coins(long value) {
        return NumberFormat.getIntegerInstance(Locale.US).format(value);
    }
}
