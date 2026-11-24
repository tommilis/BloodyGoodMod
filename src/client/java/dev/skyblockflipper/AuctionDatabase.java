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
    private static final long FLIP_RETENTION = Duration.ofDays(30).toMillis();
    // Any usable active snapshot is at most two minutes old.
    private static final long SOLD_LOOKBACK = Duration.ofMinutes(10).toMillis();

    record Sales(Map<HypixelAuctions.Item, FlipMarket.Price> prices, Map<HypixelAuctions.Item, FlipMarket.Price> unitPrices,
                 Map<HypixelAuctions.Item, Map<Integer, Integer>> stackSales, Set<String> sold, Set<String> auctionItemIds, int count) {}
    record SeenFlip(FlipMarket.Candidate candidate, long seenAt, String outcome) {}
    record SaleEvent(String auction, String variant, String itemUuid, String buyer, String seller, long price, long time) {}

    private List<SaleEvent> lastBatch = List.of();
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
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS flip_outcomes (
                        id TEXT PRIMARY KEY REFERENCES flips(id) ON DELETE CASCADE,
                        variant TEXT, method TEXT, player TEXT, sold_at INTEGER, buy_paid INTEGER,
                        owned_uuid TEXT, target_at INTEGER, resale_paid INTEGER, resale_at INTEGER, chat_resale INTEGER
                    )""");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS outcome_variant ON flip_outcomes(variant)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS outcome_owned ON flip_outcomes(owned_uuid)");
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
                if (row.soldAt() > now) {
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
            var exactSales = new HashMap<HypixelAuctions.Item, List<FlipMarket.Sale<HypixelAuctions.Item>>>();
            var auctionItemIds = new HashSet<String>();
            var stackSales = new HashMap<HypixelAuctions.Item, Map<Integer, Integer>>();
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
                    String key = null;
                    HypixelAuctions.Item item = null;
                    while (rows.next()) {
                        String current = rows.getString("variant");
                        if (!current.equals(key)) {
                            key = current;
                            var data = new ByteArrayInputStream(rows.getBytes("attributes"));
                            var attributes = NbtIo.readCompressed(data, NbtAccounter.create(4L * 1024 * 1024));
                            item = new HypixelAuctions.Item(rows.getInt("item_count"), attributes).pricingKey();
                            String itemId = item.marketId();
                            if (!itemId.isBlank()) {
                                auctionItemIds.add(itemId);
                            }
                        }
                        var sale = new FlipMarket.Sale<>(rows.getString("id"), item,
                                rows.getLong("price"), rows.getLong("sold_at"));
                        exactSales.computeIfAbsent(item, ignored -> new ArrayList<>()).add(sale);
                        var unit = item.unitKey();
                        if (unit != null && item.count() > 1) {
                            stackSales.computeIfAbsent(unit, ignored -> new HashMap<>()).merge(item.count(), 1, Integer::sum);
                        }
                        if (unit != null && sale.price() >= item.count()) {
                            unitSales.computeIfAbsent(unit, ignored -> new ArrayList<>()).add(
                                    new FlipMarket.Sale<>(sale.id(), unit, sale.price() / item.count(), sale.soldAt()));
                        }
                    }
                }
            }
            stackSales.replaceAll((unit, counts) -> Map.copyOf(counts));
            var prices = priceGroups(exactSales, now);
            var unitPrices = priceGroups(unitSales, now);
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
                return new Sales(Map.copyOf(prices), Map.copyOf(unitPrices), Map.copyOf(stackSales),
                        Set.copyOf(sold), Set.copyOf(auctionItemIds), row.getInt(1));
            }
        } catch (SQLException failure) {
            throw new IOException("Could not read the local sales database.", failure);
        }
    }

    private static Map<HypixelAuctions.Item, FlipMarket.Price> priceGroups(
            Map<HypixelAuctions.Item, List<FlipMarket.Sale<HypixelAuctions.Item>>> groups, long now) {
        var prices = new HashMap<HypixelAuctions.Item, FlipMarket.Price>();
        groups.forEach((item, sales) -> {
            sales.sort(java.util.Comparator.comparingLong(
                    (FlipMarket.Sale<HypixelAuctions.Item> sale) -> sale.soldAt()).reversed()
                    .thenComparing(FlipMarket.Sale::id));
            addPrice(prices, item, sales.subList(0, Math.min(sales.size(), FlipMarket.MAX_COMPARABLE_SALES)), now);
        });
        return prices;
    }

    private static void addPrice(Map<HypixelAuctions.Item, FlipMarket.Price> prices, HypixelAuctions.Item item,
                                 List<FlipMarket.Sale<HypixelAuctions.Item>> group, long now) {
        var price = FlipMarket.price(group, now);
        if (price != null) {
            prices.put(item, price);
        }
    }

    void recordFlips(List<FlipMarket.Candidate> flips, long now, Map<String,String> variants, String player) throws IOException {
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
            try (var insert = connection.prepareStatement("INSERT OR IGNORE INTO flip_outcomes(id,variant,method,player) VALUES (?,?,?,?)")) {
                for (var flip : flips) {
                    insert.setString(1, flip.id());
                    insert.setString(2, variants.get(flip.id()));
                    insert.setString(3, flip.method().name());
                    insert.setString(4, player);
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
        var keys = new HashSet<>(variants.values());
        applyEvents(lastBatch.stream().filter(event -> variants.containsKey(event.auction())
                || keys.contains(event.variant())).toList());
    }

    void observe(List<SaleEvent> events) throws IOException {
        lastBatch = events.stream().sorted(java.util.Comparator.comparingLong(SaleEvent::time)).toList();
        applyEvents(lastBatch);
    }

    private void applyEvents(List<SaleEvent> events) throws IOException {
        if (events.isEmpty()) return;
        try (var connection = open();
             var sold = connection.prepareStatement("""
                     UPDATE flip_outcomes SET sold_at=?, buy_paid=CASE WHEN player=? THEN ? ELSE buy_paid END,
                     owned_uuid=CASE WHEN player=? THEN NULLIF(?,'') END WHERE id=?
                     """);
             var target = connection.prepareStatement("""
                     UPDATE flip_outcomes SET target_at=? WHERE variant=? AND target_at IS NULL AND method!='NPC'
                     AND id!=? AND EXISTS (SELECT 1 FROM flips f WHERE f.id=flip_outcomes.id AND f.resale<=?
                         AND f.seen_at < ? AND f.seen_at >= ?)
                     """);
             var resale = connection.prepareStatement("""
                     UPDATE flip_outcomes SET resale_paid=?, resale_at=?, chat_resale=NULL WHERE owned_uuid=? AND player=?
                     AND sold_at < ? AND (resale_at IS NULL OR chat_resale=1) AND id=(SELECT id FROM flip_outcomes
                         WHERE owned_uuid=? AND player=? AND sold_at < ? ORDER BY sold_at DESC LIMIT 1)
                     """)) {
            connection.setAutoCommit(false);
            for (var e : events) {
                sold.setLong(1,e.time()); sold.setString(2,e.buyer()); sold.setLong(3,e.price());
                sold.setString(4,e.buyer()); sold.setString(5,e.itemUuid()); sold.setString(6,e.auction()); sold.executeUpdate();
                target.setLong(1,e.time()); target.setString(2,e.variant()); target.setString(3,e.auction());
                target.setLong(4,e.price()); target.setLong(5,e.time()); target.setLong(6,e.time()-Duration.ofDays(1).toMillis());
                target.executeUpdate();
                if (!e.itemUuid().isBlank()) {
                    resale.setLong(1,e.price()); resale.setLong(2,e.time()); resale.setString(3,e.itemUuid());
                    resale.setString(4,e.seller()); resale.setLong(5,e.time()); resale.setString(6,e.itemUuid());
                    resale.setString(7,e.seller()); resale.setLong(8,e.time()); resale.executeUpdate();
                }
            }
            connection.commit();
        } catch (SQLException failure) {
            throw new IOException("Could not record auction outcomes", failure);
        }
    }

    void receipt(boolean purchase, String name, long price, String player, long now) throws IOException {
        String matching = purchase
                ? "SELECT f.id FROM flips f JOIN flip_outcomes o USING(id) WHERE o.player=? AND f.name=? AND f.buy=? AND f.seen_at>=? AND f.ends_at>=?"
                : "SELECT f.id FROM flips f JOIN flip_outcomes o USING(id) WHERE o.player=? AND f.name=? AND o.buy_paid IS NOT NULL AND o.resale_paid IS NULL";
        try (var connection = open()) {
            connection.setAutoCommit(false);
            String id;
            try (var q = connection.prepareStatement(matching)) {
                q.setString(1, player);
                q.setString(2, name);
                if (purchase) {
                    q.setLong(3, price);
                    q.setLong(4, now - Duration.ofMinutes(5).toMillis());
                    q.setLong(5, now);
                }
                try (var rows = q.executeQuery()) {
                    if (!rows.next()) return;
                    id = rows.getString(1);
                    if (rows.next()) return;
                }
            }
            String sql = purchase ? "UPDATE flip_outcomes SET buy_paid=?,sold_at=coalesce(sold_at,?) WHERE id=?"
                    : "UPDATE flip_outcomes SET resale_paid=?,resale_at=?,chat_resale=1 WHERE id=?";
            try (var q = connection.prepareStatement(sql)) {
                q.setLong(1,price);
                q.setLong(2,now);
                q.setString(3,id);
                q.executeUpdate();
            }
            connection.commit();
        } catch (SQLException failure) { throw new IOException("Could not store auction receipt", failure); }
    }

    String outcomeSummary(long now) throws IOException {
        try (var c = open(); var q = c.prepareStatement("""
                SELECT sum(CASE WHEN sold_at < seen_at+7000 THEN 1 ELSE 0 END) AS missed,
                       count(sold_at) AS observed,
                       sum(CASE WHEN method!='NPC' AND seen_at<=? AND target_at IS NOT NULL THEN 1 ELSE 0 END) AS hits,
                       sum(CASE WHEN method!='NPC' AND seen_at<=? THEN 1 ELSE 0 END) AS matured
                FROM flips JOIN flip_outcomes USING(id)
                """)) {
            q.setLong(1,now-Duration.ofDays(1).toMillis());
            q.setLong(2,now-Duration.ofDays(1).toMillis());
            try (var r = q.executeQuery()) {
                r.next();
                return "Gone before 7s: " + r.getInt("missed") + "/" + r.getInt("observed")
                        + " observed sales · 24h target hits: " + r.getInt("hits") + "/" + r.getInt("matured");
            }
        } catch (SQLException failure) { throw new IOException("Could not read outcome totals", failure); }
    }

    List<SeenFlip> recentFlips(long now) throws IOException {
        try (var connection = open();
             var query = connection.prepareStatement("""
                     SELECT f.*, o.sold_at, o.target_at, o.buy_paid, o.resale_paid, o.chat_resale, o.method FROM flips f LEFT JOIN flip_outcomes o ON o.id=f.id WHERE seen_at >= ?
                     ORDER BY seen_at DESC, profit DESC, id LIMIT 100
                     """)) {
            query.setLong(1, now - FLIP_RETENTION);

            var flips = new ArrayList<SeenFlip>();
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    long delay = Math.floorDiv(rows.getLong("sold_at") - rows.getLong("seen_at"), 1000);
                    String outcome = rows.getObject("sold_at") == null ? "Sale unobserved" :
                            delay < 0 ? "Sold before alert" : "Sold " + delay + "s after alert";
                    if (rows.getObject("target_at") != null) outcome += " · comparable reached target";
                    if (rows.getObject("buy_paid") != null) outcome += " · bought by you";
                    if (rows.getObject("resale_paid") != null) {
                        long received = rows.getLong("resale_paid");
                        outcome += (rows.getInt("chat_resale") == 1 ? " · possible resale (chat) ~" : " · resale profit ~") + (received - FlipMarket.estimatedFees(received) - rows.getLong("buy_paid"));
                    }
                    if ("NPC".equals(rows.getString("method"))) outcome = "NPC exit · " + outcome;
                    flips.add(new SeenFlip(new FlipMarket.Candidate(rows.getString("id"), rows.getString("name"),
                            rows.getLong("buy"), rows.getLong("resale"), rows.getLong("profit"),
                            rows.getInt("samples"), rows.getLong("ends_at")), rows.getLong("seen_at"), outcome));
                }
            }
            return List.copyOf(flips);
        } catch (SQLException failure) {
            throw new IOException("Could not read flip history.", failure);
        }
    }

    static String variantKey(HypixelAuctions.Item item) {
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
