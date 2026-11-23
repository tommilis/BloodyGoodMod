package dev.skyblockflipper;

public interface InventoryCursorAccess {
    record Position(double x, double y) {}

    Position bgm$takeInventoryCursor();
}
