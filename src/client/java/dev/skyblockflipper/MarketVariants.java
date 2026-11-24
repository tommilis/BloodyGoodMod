package dev.skyblockflipper;

import net.minecraft.nbt.CompoundTag;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

final class MarketVariants {
    record Shard(String productId, String searchName) {}

    private static final String SHARD_ID = "ATTRIBUTE_SHARD";
    private static final String SHARD_PREFIX = SHARD_ID + "_";
    private static final Map<String, Shard> SHARDS = loadShards();

    private MarketVariants() {}

    static boolean isShard(String id) {
        return id.equals(SHARD_ID) || id.startsWith(SHARD_PREFIX) || id.startsWith("SHARD_");
    }

    static Shard shard(String id, CompoundTag attributes, String name) {
        Shard result = SHARDS.get(id);
        if (result != null) {
            return result;
        }
        var levels = attributes.getCompoundOrEmpty("attributes");
        if (levels.size() == 1) {
            String key = levels.keySet().iterator().next();
            result = SHARDS.get(SHARD_PREFIX + key.toUpperCase(Locale.ROOT) + ";" + levels.getIntOr(key, 0));
            if (result != null) {
                return result;
            }
        }
        String clean = name.replaceFirst("(?i)\\s+(?:x|×)\\s*\\d+$", "");
        if (id.startsWith("SHARD_")) {
            return new Shard(id, clean);
        }
        result = SHARDS.get(clean.toUpperCase(Locale.ROOT));
        if (result != null) {
            return result;
        }
        if (!clean.matches("(?i)[a-z0-9 ]+ shard")) {
            return null;
        }
        // For new shards, try the literal name only. The caller still requires live Bazaar membership.
        String type = clean.substring(0, clean.length() - " Shard".length()).strip();
        return new Shard("SHARD_" + type.toUpperCase(Locale.ROOT).replace(' ', '_'), clean);
    }

    static String historyTag(String auctionId) {
        if (auctionId.startsWith("PET:")) {
            return "PET_" + auctionId.substring(4);
        }
        if (auctionId.startsWith("RUNE:") || auctionId.startsWith("UNIQUE_RUNE:")) {
            String[] parts = auctionId.split(":");
            return parts.length == 3 ? "RUNE_" + parts[1].toUpperCase(Locale.ROOT) : "";
        }
        return auctionId;
    }

    private static Map<String, Shard> loadShards() {
        var stream = MarketVariants.class.getResourceAsStream("/market/attribute-shards.tsv");
        if (stream == null) {
            return Map.of();
        }
        Map<String, Shard> shards = new HashMap<>();
        try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#")) {
                    continue;
                }
                String[] columns = line.split("\t");
                if (columns.length == 3) {
                    String name = columns[2] + " Shard";
                    var shard = new Shard(columns[0], name);
                    shards.put(columns[0], shard);
                    shards.put(columns[1], shard);
                    shards.putIfAbsent(name.toUpperCase(Locale.ROOT), shard);
                    shards.putIfAbsent(columns[2].toUpperCase(Locale.ROOT), shard);
                }
            }
            return Map.copyOf(shards);
        } catch (IOException failure) {
            LoggerFactory.getLogger(MarketVariants.class).warn("Could not load the Attribute Shard mappings", failure);
            return Map.of();
        }
    }
}
