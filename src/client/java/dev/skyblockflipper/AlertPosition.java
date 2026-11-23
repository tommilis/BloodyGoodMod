package dev.skyblockflipper;

enum AlertPosition {
    TOP_LEFT("Top left", 0, false),
    TOP_CENTER("Top center", 1, false),
    TOP_RIGHT("Top right", 2, false),
    BOTTOM_RIGHT("Bottom right", 2, true),
    BOTTOM_CENTER("Bottom center", 1, true),
    BOTTOM_LEFT("Bottom left", 0, true);

    private static final AlertPosition[] POSITIONS = values();
    private final String label;
    private final int column;
    private final boolean bottom;

    AlertPosition(String label, int column, boolean bottom) {
        this.label = label;
        this.column = column;
        this.bottom = bottom;
    }

    AlertPosition next() {
        return POSITIONS[(ordinal() + 1) % POSITIONS.length];
    }

    String label() {
        return label;
    }

    int x(int screenWidth, int panelWidth) {
        int inset = Math.min(10, Math.max(0, (screenWidth - panelWidth) / 2));
        return switch (column) {
            case 0 -> inset;
            case 1 -> (screenWidth - panelWidth) / 2;
            default -> screenWidth - panelWidth - inset;
        };
    }

    int y(int screenHeight, int panelHeight) {
        return bottom ? Math.max(0, screenHeight - panelHeight - 34) : 10;
    }
}
