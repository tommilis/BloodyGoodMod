package dev.skyblockflipper;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.io.IOException;

final class FlipSettingsScreen extends Screen {
    private static final String[] FIELD_LABELS = {
            "Scan interval (seconds)", "Minimum buy price", "Maximum buy price", "Minimum profit"
    };

    @FunctionalInterface
    interface SaveSettings {
        void save(FlipSettings settings, InputConstants.Key key) throws IOException;
    }

    private enum Tab { AH_FLIPS, OTHER }

    private final String[] values;
    private final SaveSettings saveSettings;
    private final java.util.function.Consumer<Screen> openHistory;
    private boolean scrollableTooltips;
    private boolean rescueMission;
    private Tab tab = Tab.AH_FLIPS;
    private boolean automatic;
    private boolean noCursorReset;
    private boolean previewPosition;
    private AlertPosition position;
    private boolean capturingKey;
    private InputConstants.Key key;
    private Button keyButton;
    private int left;
    private int top;
    private int panelWidth;
    private int sidebarWidth;
    private int contentLeft;
    private int contentWidth;
    private String error = "";

    FlipSettingsScreen(FlipSettings settings, InputConstants.Key key, SaveSettings saveSettings, java.util.function.Consumer<Screen> openHistory) {
        super(Component.literal("BloodyGoodMod"));
        this.automatic = settings.automatic();
        this.noCursorReset = settings.noCursorReset();
        this.position = settings.alertPosition();
        this.key = key;
        this.saveSettings = saveSettings;
        this.openHistory = openHistory;
        this.scrollableTooltips = settings.scrollableTooltips();
        this.rescueMission = settings.rescueMission();
        this.values = new String[] {
                Integer.toString(settings.seconds()),
                Long.toString(settings.minPrice()),
                settings.maxPrice() == Long.MAX_VALUE ? "" : Long.toString(settings.maxPrice()),
                Long.toString(settings.minProfit())
        };
    }

    @Override
    protected void init() {
        panelWidth = Math.min(width - 8, 352);
        sidebarWidth = panelWidth < 310 ? 62 : 76;
        left = (width - panelWidth) / 2;
        top = Math.max(4, (height - 198) / 2);
        contentLeft = left + sidebarWidth + 8;
        contentWidth = left + panelWidth - 8 - contentLeft;

        addTab(Tab.AH_FLIPS, "AH Flips", 24);
        addTab(Tab.OTHER, "Other", 48);
        addRenderableWidget(Button.builder(Component.literal("History"), button -> openHistory.accept(this))
                .bounds(left + 5, top + 72, sidebarWidth - 10, 20).build());
        if (tab == Tab.AH_FLIPS) {
            addAuctionWidgets();
        } else {
            addRenderableWidget(Button.builder(cursorLabel(), button -> {
                noCursorReset = !noCursorReset;
                button.setMessage(cursorLabel());
            }).bounds(contentLeft, top + 22, contentWidth, 20).build());
            addRenderableWidget(Button.builder(tooltipLabel(), button -> {
                scrollableTooltips = !scrollableTooltips;
                button.setMessage(tooltipLabel());
            }).bounds(contentLeft, top + 84, contentWidth, 20).build());
            addRenderableWidget(Button.builder(rescueLabel(), button -> {
                rescueMission = !rescueMission;
                button.setMessage(rescueLabel());
            }).bounds(contentLeft, top + 127, contentWidth, 20).build());
        }

        int half = (contentWidth - 8) / 2;
        addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> onClose())
                .bounds(contentLeft, top + 173, half, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Save"), button -> save())
                .bounds(contentLeft + half + 8, top + 173, contentWidth - half - 8, 20).build());
    }

    private void addTab(Tab destination, String label, int y) {
        var button = addRenderableWidget(Button.builder(Component.literal(label), clicked -> {
            tab = destination;
            capturingKey = false;
            error = "";
            rebuildWidgets();
        }).bounds(left + 5, top + y, sidebarWidth - 10, 20).build());
        button.active = tab != destination;
    }

    private void addAuctionWidgets() {
        int half = (contentWidth - 8) / 2;
        addRenderableWidget(Button.builder(autoLabel(), button -> {
            automatic = !automatic;
            button.setMessage(autoLabel());
        }).bounds(contentLeft, top + 22, half, 20).build());
        addRenderableWidget(Button.builder(positionLabel(), button -> {
            position = position.next();
            previewPosition = true;
            button.setMessage(positionLabel());
        }).bounds(contentLeft + half + 8, top + 22, contentWidth - half - 8, 20).build());

        int fieldWidth = Math.min(120, contentWidth / 2);
        for (int index = 0; index < FIELD_LABELS.length; index++) {
            int fieldIndex = index;
            var field = new EditBox(font, contentLeft + contentWidth - fieldWidth, top + 47 + index * 21,
                    fieldWidth, 18, Component.literal(FIELD_LABELS[index]));
            field.setMaxLength(19);
            field.setValue(values[index]);
            field.setResponder(value -> values[fieldIndex] = value);
            addRenderableWidget(field);
        }

        int keyWidth = Math.min(97, contentWidth / 3);
        int clearX = contentLeft + contentWidth - 44;
        keyButton = addRenderableWidget(Button.builder(keyLabel(), button -> {
            capturingKey = true;
            button.setMessage(keyLabel());
        }).bounds(clearX - keyWidth - 5, top + 132, keyWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Clear"), button -> {
            capturingKey = false;
            key = InputConstants.UNKNOWN;
            keyButton.setMessage(keyLabel());
        }).bounds(clearX, top + 132, 44, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        if (tab == Tab.AH_FLIPS && previewPosition) {
            FlipAlertHud.draw(graphics, font, position, "Notification preview", "+724k", FlipAlertHud.DURATION_MS);
        }
        graphics.fill(left, top, left + panelWidth, top + 198, 0xFF181818);
        graphics.fill(left, top + 18, left + sidebarWidth, top + 198, 0xFF242424);
        int selectedY = top + (tab == Tab.AH_FLIPS ? 24 : 48);
        graphics.fill(left + 2, selectedY, left + 4, selectedY + 20, 0xFFFFAA00);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, top + 7, 0xFFFFFFFF);

        String hint;
        if (tab == Tab.AH_FLIPS) {
            for (int index = 0; index < FIELD_LABELS.length; index++) {
                drawLabel(graphics, FIELD_LABELS[index], top + 52 + index * 21,
                        contentWidth - Math.min(120, contentWidth / 2) - 4);
            }
            drawLabel(graphics, "Latest auction key", top + 138,
                    contentWidth - Math.min(97, contentWidth / 3) - 53);
            hint = "Interval 10–3600s · Blank max = unlimited";
        } else {
            drawLabel(graphics, "Keep the cursor in place when you move", top + 51, contentWidth);
            drawLabel(graphics, "between inventory and chest menus.", top + 64, contentWidth);
            drawLabel(graphics, "Use the mouse wheel on tall tooltips.", top + 111, contentWidth);
            hint = "Rescue Mission waypoints on the Crimson Isle";
        }

        if (!error.isEmpty()) {
            hint = error;
        }
        if (!hint.isEmpty()) {
            drawLabel(graphics, hint, top + 157, contentWidth, error.isEmpty() ? 0xFFB0B0B0 : 0xFFFF7777);
        }
    }

    private void drawLabel(GuiGraphicsExtractor graphics, String label, int y, int availableWidth) {
        drawLabel(graphics, label, y, availableWidth, 0xFFE0E0E0);
    }

    private void drawLabel(GuiGraphicsExtractor graphics, String label, int y, int availableWidth, int color) {
        String text = font.width(label) <= availableWidth ? label
                : font.plainSubstrByWidth(label, Math.max(0, availableWidth - font.width("…"))) + "…";
        graphics.text(font, text, contentLeft, y, color);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (capturingKey) {
            InputConstants.Key pressed = InputConstants.getKey(event);
            if (pressed.getValue() != InputConstants.KEY_ESCAPE) {
                key = pressed;
            }
            capturingKey = false;
            keyButton.setMessage(keyLabel());
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private Component autoLabel() {
        return Component.literal("Auto alerts: " + (automatic ? "On" : "Off"));
    }

    private Component cursorLabel() {
        return Component.literal("No cursor reset: " + (noCursorReset ? "On" : "Off"));
    }

    private Component tooltipLabel() {
        return Component.literal("Scrollable tooltips: " + (scrollableTooltips ? "On" : "Off"));
    }

    private Component rescueLabel() {
        return Component.literal("Rescue mission: " + (rescueMission ? "On" : "Off"));
    }

    private Component positionLabel() {
        return Component.literal("Alert: " + position.label());
    }

    private Component keyLabel() {
        return capturingKey ? Component.literal("Press a key") : key.getDisplayName();
    }

    private void save() {
        try {
            var settings = new FlipSettings(automatic, Integer.parseInt(values[0]),
                    values[1].isEmpty() ? 0 : Long.parseLong(values[1]),
                    values[2].isEmpty() ? Long.MAX_VALUE : Long.parseLong(values[2]),
                    values[3].isEmpty() ? 0 : Long.parseLong(values[3]), position, noCursorReset, scrollableTooltips,
                    rescueMission);
            saveSettings.save(settings, key);
            onClose();
        } catch (NumberFormatException invalidNumber) {
            showAuctionError("Enter whole numbers within the supported range.");
        } catch (IllegalArgumentException invalidSettings) {
            showAuctionError(invalidSettings.getMessage());
        } catch (IOException failure) {
            error = "Could not save settings.";
        }
    }

    private void showAuctionError(String message) {
        error = message;
        if (tab != Tab.AH_FLIPS) {
            tab = Tab.AH_FLIPS;
            rebuildWidgets();
        }
    }
}
