package dev.skyblockflipper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

record FlipSettings(boolean automatic, int seconds, long minPrice, long maxPrice, long minProfit,
                    AlertPosition alertPosition, boolean noCursorReset, boolean scrollableTooltips, boolean rescueMission,
                    int minRoi, int minComparableSales, int maxListingMinutes) {
    static final int MIN_SECONDS = 3;
    static final int MAX_SECONDS = 3600;
    static final FlipSettings DEFAULT = new FlipSettings(true, 3, 0, Long.MAX_VALUE, 0, AlertPosition.TOP_RIGHT,
            false, false, false, 0, 0, 3);

    FlipSettings {
        if (seconds < MIN_SECONDS || seconds > MAX_SECONDS) {
            throw new IllegalArgumentException("Scan interval must be 3–3600 seconds.");
        }
        if (minPrice < 0 || maxPrice < 1 || minPrice > maxPrice) {
            throw new IllegalArgumentException("Enter a valid minimum and maximum buy price.");
        }
        if (minRoi < 0 || minComparableSales < 0 || minComparableSales > FlipMarket.MAX_COMPARABLE_SALES) {
            throw new IllegalArgumentException("ROI must be nonnegative and comparable sales must be 0–64.");
        }
        if (maxListingMinutes < 1 || maxListingMinutes > 60) throw new IllegalArgumentException("Listing age must be 1–60 minutes.");
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
        String previousInterval = values.getProperty("seconds", "60");
        String pollInterval = values.getProperty("pollSeconds", previousInterval.equals("60") ? "3" : previousInterval);
        return new FlipSettings(
                Boolean.parseBoolean(values.getProperty("automatic", "true")),
                Integer.parseInt(pollInterval),
                Long.parseLong(values.getProperty("minPrice", "0")),
                Long.parseLong(values.getProperty("maxPrice", Long.toString(Long.MAX_VALUE))),
                Long.parseLong(values.getProperty("minProfit", "0")), position,
                Boolean.parseBoolean(values.getProperty("noCursorReset", "false")),
                Boolean.parseBoolean(values.getProperty("scrollableTooltips", "false")),
                Boolean.parseBoolean(values.getProperty("rescueMission", "false")),
                Integer.parseInt(values.getProperty("minRoi", "0")),
                Integer.parseInt(values.getProperty("minComparableSales", "0")), Integer.parseInt(values.getProperty("maxListingMinutes", "3")));
    }

    void save(Path path) throws IOException {
        Properties values = new Properties();
        values.setProperty("automatic", Boolean.toString(automatic));
        values.setProperty("pollSeconds", Integer.toString(seconds));
        values.setProperty("minPrice", Long.toString(minPrice));
        values.setProperty("maxPrice", Long.toString(maxPrice));
        values.setProperty("minProfit", Long.toString(minProfit));
        values.setProperty("alertPosition", alertPosition.name());
        values.setProperty("noCursorReset", Boolean.toString(noCursorReset));

        values.setProperty("scrollableTooltips", Boolean.toString(scrollableTooltips));
        values.setProperty("rescueMission", Boolean.toString(rescueMission));
        values.setProperty("minRoi", Integer.toString(minRoi));
        values.setProperty("minComparableSales", Integer.toString(minComparableSales));

        values.setProperty("maxListingMinutes", Integer.toString(maxListingMinutes));
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
