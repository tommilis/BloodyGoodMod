package dev.skyblockflipper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

record FlipSettings(boolean automatic, int seconds, long minPrice, long maxPrice, long minProfit,
                    AlertPosition alertPosition, boolean noCursorReset, boolean scrollableTooltips, boolean rescueMission) {
    static final int MIN_SECONDS = 10;
    static final int MAX_SECONDS = 3600;
    static final FlipSettings DEFAULT = new FlipSettings(true, 60, 0, Long.MAX_VALUE, 0, AlertPosition.TOP_RIGHT,
            false, false, false);

    FlipSettings {
        if (seconds < MIN_SECONDS || seconds > MAX_SECONDS) {
            throw new IllegalArgumentException("Scan interval must be 10–3600 seconds.");
        }
        if (minPrice < 0 || maxPrice < 1 || minPrice > maxPrice) {
            throw new IllegalArgumentException("Enter a valid minimum and maximum buy price.");
        }
        if (minProfit < 0) {
            throw new IllegalArgumentException("Minimum profit must be zero or more.");
        }
    }

    static FlipSettings load(Path path) throws IOException {
        if (!Files.exists(path)) {
            return DEFAULT;
        }

        Properties values = new Properties();
        try (var reader = Files.newBufferedReader(path)) {
            values.load(reader);
        }

        AlertPosition position;
        try {
            position = AlertPosition.valueOf(values.getProperty("alertPosition", DEFAULT.alertPosition().name()));
        } catch (IllegalArgumentException invalidPosition) {
            position = DEFAULT.alertPosition();
        }
        return new FlipSettings(
                Boolean.parseBoolean(values.getProperty("automatic", "true")),
                Integer.parseInt(values.getProperty("seconds", "60")),
                Long.parseLong(values.getProperty("minPrice", "0")),
                Long.parseLong(values.getProperty("maxPrice", Long.toString(Long.MAX_VALUE))),
                Long.parseLong(values.getProperty("minProfit", "0")), position,
                Boolean.parseBoolean(values.getProperty("noCursorReset", "false")),
                Boolean.parseBoolean(values.getProperty("scrollableTooltips", "false")),
                Boolean.parseBoolean(values.getProperty("rescueMission", "false")));
    }

    void save(Path path) throws IOException {
        Properties values = new Properties();
        values.setProperty("automatic", Boolean.toString(automatic));
        values.setProperty("seconds", Integer.toString(seconds));
        values.setProperty("minPrice", Long.toString(minPrice));
        values.setProperty("maxPrice", Long.toString(maxPrice));
        values.setProperty("minProfit", Long.toString(minProfit));
        values.setProperty("alertPosition", alertPosition.name());
        values.setProperty("noCursorReset", Boolean.toString(noCursorReset));

        values.setProperty("scrollableTooltips", Boolean.toString(scrollableTooltips));
        values.setProperty("rescueMission", Boolean.toString(rescueMission));

        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), ".skyblock-flipper-settings-", ".tmp");
        try {
            try (var writer = Files.newBufferedWriter(temporary)) {
                values.store(writer, "BloodyGoodMod");
            }
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
