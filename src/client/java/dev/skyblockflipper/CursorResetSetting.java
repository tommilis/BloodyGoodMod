package dev.skyblockflipper;

/** Updated when the client loads or saves its settings. */
public final class CursorResetSetting {
    static boolean enabled;

    private CursorResetSetting() {}

    public static boolean enabled() {
        return enabled;
    }
}
