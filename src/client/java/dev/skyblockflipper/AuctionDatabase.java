package dev.skyblockflipper;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import org.sqlite.JDBC;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

final class AuctionDatabase {
    private static final long SALES_RETENTION = Duration.ofHours(24).toMillis();
    private static final long FLIP_RETENTION = Duration.ofDays(30).toMillis();
    // Any usable active snapshot is at most two minutes old.
    private static final long SOLD_LOOKBACK = Duration.ofMinutes(10).toMillis();

    record Sales(Map<HypixelAuctions.Item, FlipMarket.Price> prices, Map<HypixelAuctions.Item, FlipMarket.Price> unitPrices,
                 Set<String> sold, Set<String> auctionItemIds, int count) {}
    record SeenFlip(FlipMarket.Candidate candidate, long seenAt) {}

    private final Path path;
    private final SaleHistory legacy;

    AuctionDatabase(Path configDirectory) {
        path = configDirectory.resolve("bloodygoodmod-auctions.sqlite");
        legacy = new SaleHistory(configDirectory.resolve("skyblock-flipper-sales.nbt"));
    }

    private Connection open() throws SQLException, IOException {
        Files.createDirectories(path.getParent());
        var connection = new JDBC().connect("jdbc:sqlite:" + path.toAbsolutePath(), new Properties());
        try (var statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA foreign_keys = ON");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS variants (
                        variant TEXT PRIMARY KEY, item_count INTEGER NOT NULL, attributes BLOB NOT NULL
                    )""");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS sales (
                        id TEXT PRIMARY KEY, variant TEXT NOT NULL REFERENCES variants(variant),
                        price INTEGER NOT NULL, sold_at INTEGER NOT NULL
                    )""");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS sales_recent ON sales(sold_at DESC)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS sales_variant_time ON sales(variant, sold_at DESC, id)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY)");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS flips (
                        id TEXT PRIMARY KEY, name TEXT NOT NULL, buy INTEGER NOT NULL,
                        resale INTEGER NOT NULL, profit INTEGER NOT NULL, samples INTEGER NOT NULL,
                        ends_at INTEGER NOT NULL, seen_at INTEGER NOT NULL
                    )""");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS flips_recent ON flips(seen_at DESC)");
            return connection;
        } catch (SQLException | RuntimeException failure) {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    int updateSales(List<FlipMarket.Sale<HypixelAuctions.Item>> incoming, long now) throws IOException {
        try (var connection = open()) {
            connection.setAutoCommit(false);
            boolean imported;
            try (var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT 1 FROM meta WHERE key = 'legacy_imported'")) {
                imported = result.next();
            }
            if (!imported) {
                insertSales(connection, legacy.load(), now);
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate("INSERT INTO meta VALUES ('legacy_imported')");
                }
            }
            int added = insertSales(connection, incoming, now);
            try (var expiration = connection.prepareStatement("DELETE FROM sales WHERE sold_at < ?")) {
                expiration.setLong(1, now - SALES_RETENTION);
                if (expiration.executeUpdate() > 0) {
                    try (var statement = connection.createStatement()) {
                        statement.executeUpdate("""
                                DELETE FROM variants WHERE NOT EXISTS (
                                    SELECT 1 FROM sales WHERE sales.variant = variants.variant
                                )""");
                    }
                }
            }
            connection.commit();
            return added;
        } catch (SQLException failure) {
            throw new IOException("Could not update the local sales database.", failure);
        }
    }

    private static int insertSales(Connection connection, List<FlipMarket.Sale<HypixelAuctions.Item>> sales, long now)
            throws SQLException, IOException {
        int added = 0;
        Map<HypixelAuctions.Item, String> keys = new HashMap<>();
        try (var variant = connection.prepareStatement("INSERT OR IGNORE INTO variants VALUES (?, ?, ?)");
             var sale = connection.prepareStatement("INSERT OR IGNORE INTO sales VALUES (?, ?, ?, ?)")) {
            for (var row : sales) {
                if (row.soldAt() < now - SALES_RETENTION || row.soldAt() > now) {
                    continue;
                }
                var item = row.item();
                String key = keys.get(item);
                if (key == null) {
                    key = variantKey(item);
                    keys.put(item, key);
                    variant.setString(1, key);
                    variant.setInt(2, item.count());
                    var data = new ByteArrayOutputStream();
                    NbtIo.writeCompressed(item.attributes(), data);
                    variant.setBytes(3, data.toByteArray());
                    variant.executeUpdate();
                }
                sale.setString(1, row.id());
                sale.setString(2, key);
                sale.setLong(3, row.price());
                sale.setLong(4, row.soldAt());
                added += sale.executeUpdate();
            }
        }
        return added;
    }

    Sales readSales(long now) throws IOException {
        try (var connection = open()) {
            var prices = new HashMap<HypixelAuctions.Item, FlipMarket.Price>();
            var auctionItemIds = new HashSet<String>();
            var unitSales = new HashMap<HypixelAuctions.Item, List<FlipMarket.Sale<HypixelAuctions.Item>>>();
            try (var query = connection.prepareStatement("""
                    WITH recent AS (
                        SELECT id, variant, price, sold_at,
                               row_number() OVER (PARTITION BY variant ORDER BY sold_at DESC, id) AS rank
                        FROM sales WHERE sold_at > ? AND sold_at <= ?
                    )
                    SELECT recent.*, variants.item_count, variants.attributes
                    FROM recent JOIN variants USING (variant)
                    WHERE rank <= ?
                    ORDER BY variant, rank
                    """)) {
                query.setLong(1, now - FlipMarket.PRICING_WINDOW_MS);
                query.setLong(2, now);
                query.setInt(3, FlipMarket.MAX_COMPARABLE_SALES);
                try (var rows = query.executeQuery()) {
                    var group = new ArrayList<FlipMarket.Sale<HypixelAuctions.Item>>();
                    String key = null;
                    HypixelAuctions.Item item = null;
                    while (rows.next()) {
                        String current = rows.getString("variant");
                        if (!current.equals(key)) {
                            addPrice(prices, item, group, now);
                            group.clear();
                            key = current;
                            var data = new ByteArrayInputStream(rows.getBytes("attributes"));
                            var attributes = NbtIo.readCompressed(data, NbtAccounter.create(4L * 1024 * 1024));
                            item = new HypixelAuctions.Item(rows.getInt("item_count"), attributes);
                            String itemId = item.marketId();
                            if (!itemId.isBlank()) {
                                auctionItemIds.add(itemId);
                            }
                        }
                        var sale = new FlipMarket.Sale<>(rows.getString("id"), item,
                                rows.getLong("price"), rows.getLong("sold_at"));
                        group.add(sale);
                        var unit = item.unitKey();
                        if (unit != null && sale.price() >= item.count()) {
                            unitSales.computeIfAbsent(unit, ignored -> new ArrayList<>()).add(
                                    new FlipMarket.Sale<>(sale.id(), unit, sale.price() / item.count(), sale.soldAt()));
                        }
                    }
                    addPrice(prices, item, group, now);
                }
            }
            var unitPrices = new HashMap<HypixelAuctions.Item, FlipMarket.Price>();
            unitSales.forEach((item, sales) -> {
                sales.sort(java.util.Comparator.comparingLong(
                        (FlipMarket.Sale<HypixelAuctions.Item> sale) -> sale.soldAt()).reversed()
                        .thenComparing(FlipMarket.Sale::id));
                addPrice(unitPrices, item, sales.subList(0, Math.min(sales.size(), FlipMarket.MAX_COMPARABLE_SALES)), now);
            });
            var sold = new HashSet<String>();
            try (var query = connection.prepareStatement("SELECT id FROM sales WHERE sold_at >= ?")) {
                query.setLong(1, now - SOLD_LOOKBACK);
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        sold.add(rows.getString(1));
                    }
                }
            }
            try (var statement = connection.createStatement();
                 var row = statement.executeQuery("SELECT count(*) FROM sales")) {
                return new Sales(Map.copyOf(prices), Map.copyOf(unitPrices), Set.copyOf(sold), Set.copyOf(auctionItemIds), row.getInt(1));
            }
        } catch (SQLException failure) {
            throw new IOException("Could not read the local sales database.", failure);
        }
    }

    private static void addPrice(Map<HypixelAuctions.Item, FlipMarket.Price> prices, HypixelAuctions.Item item,
                                 List<FlipMarket.Sale<HypixelAuctions.Item>> group, long now) {
        var price = FlipMarket.price(group, now);
        if (price != null) {
            prices.put(item, price);
        }
    }

    void recordFlips(List<FlipMarket.Candidate> flips, long now) throws IOException {
        try (var connection = open()) {
            connection.setAutoCommit(false);
            try (var insert = connection.prepareStatement("INSERT OR IGNORE INTO flips VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                for (var flip : flips) {
                    insert.setString(1, flip.id());
                    insert.setString(2, flip.name());
                    insert.setLong(3, flip.buy());
                    insert.setLong(4, flip.resale());
                    insert.setLong(5, flip.profit());
                    insert.setInt(6, flip.sales());
                    insert.setLong(7, flip.endsAt());
                    insert.setLong(8, now);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            try (var expiration = connection.prepareStatement("DELETE FROM flips WHERE seen_at < ?")) {
                expiration.setLong(1, now - FLIP_RETENTION);
                expiration.executeUpdate();
            }
            connection.commit();
        } catch (SQLException failure) {
            throw new IOException("Could not save flip history.", failure);
        }
    }

    List<SeenFlip> recentFlips(long now) throws IOException {
        try (var connection = open();
             var query = connection.prepareStatement("""
                     SELECT * FROM flips WHERE seen_at >= ?
                     ORDER BY seen_at DESC, profit DESC, id LIMIT 100
                     """)) {
            query.setLong(1, now - FLIP_RETENTION);
            var flips = new ArrayList<SeenFlip>();
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    flips.add(new SeenFlip(new FlipMarket.Candidate(rows.getString("id"), rows.getString("name"),
                            rows.getLong("buy"), rows.getLong("resale"), rows.getLong("profit"),
                            rows.getInt("samples"), rows.getLong("ends_at")), rows.getLong("seen_at")));
                }
            }
            return List.copyOf(flips);
        } catch (SQLException failure) {
            throw new IOException("Could not read flip history.", failure);
        }
    }

    private static String variantKey(HypixelAuctions.Item item) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            addString(digest, Integer.toString(item.count()));
            hashTag(digest, item.attributes());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void hashTag(MessageDigest digest, Tag tag) {
        digest.update(tag.getId());
        switch (tag) {
            case CompoundTag compound -> {
                addString(digest, Integer.toString(compound.size()));
                for (String key : compound.keySet().stream().sorted().toList()) {
                    addString(digest, key);
                    hashTag(digest, compound.get(key));
                }
            }
            case ListTag list -> {
                addString(digest, Integer.toString(list.size()));
                for (Tag child : list) {
                    hashTag(digest, child);
                }
            }
            default -> addString(digest, tag.toString());
        }
    }

    private static void addString(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        int length = bytes.length;
        digest.update((byte) (length >>> 24));
        digest.update((byte) (length >>> 16));
        digest.update((byte) (length >>> 8));
        digest.update((byte) length);
        digest.update(bytes);
    }
}
