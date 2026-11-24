package dev.skyblockflipper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.component.DataComponents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

final class HypixelAuctions {
    private static final Logger LOGGER = LoggerFactory.getLogger("skyblock-flipper");
    private static final long SAMPLE_INTERVAL_MS = Duration.ofMinutes(1).toMillis();
    private static final String API = "https://api.hypixel.net/v2/";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(12);
    private static final long ACTIVE_CHECK_INTERVAL_MS = FlipSettings.MIN_SECONDS * 1000L;
    private static final long MAX_SNAPSHOT_AGE_MS = Duration.ofMinutes(2).toMillis();
    private static final int MAX_PAGES = 200;
    private static final int MAX_ITEM_BYTES = 256 * 1024;

    private static final JsonObject PET_LEVELS = readPetLevels();

    private static JsonObject readPetLevels() {
        try (var stream = HypixelAuctions.class.getResourceAsStream("/market/pet-levels.json")) {
            return JsonParser.parseReader(new InputStreamReader(java.util.Objects.requireNonNull(stream), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException | RuntimeException failure) {
            LOGGER.warn("Pet level tables unavailable", failure);
            return new JsonObject();
        }
    }

    private static void bucketPetLevel(JsonObject info) {
        if (!info.has("type") || !info.has("tier") || !info.has("exp") || !PET_LEVELS.has("pet_levels")) return;
        String type = info.get("type").getAsString();
        String tier = info.get("tier").getAsString();
        var custom = PET_LEVELS.getAsJsonObject("custom_pet_leveling").getAsJsonObject(type);
        if (custom != null && custom.has("xp_multiplier")) return;
        var offsets = custom != null && custom.has("rarity_offset") ? custom.getAsJsonObject("rarity_offset") : PET_LEVELS.getAsJsonObject("pet_rarity_offset");
        if (!offsets.has(tier)) return;
        int offset = offsets.get(tier).getAsInt();
        int max = custom != null && custom.has("max_level") ? custom.get("max_level").getAsInt() : 100;
        double xp = info.get("exp").getAsDouble();
        if (!Double.isFinite(xp) || xp < 0) return;
        var levels = PET_LEVELS.getAsJsonArray("pet_levels");
        int level = 1;
        while (level < max) {
            int index = offset + level - 1;
            long cost = index < levels.size() ? levels.get(index).getAsLong()
                    : custom.getAsJsonArray("pet_levels").get(index - levels.size()).getAsLong();
            if (xp < cost) break;
            xp -= cost;
            level++;
        }
        info.remove("exp");
        info.addProperty("bgmLevel", level == 1 || level == max ? level : Math.max(2, level / 10 * 10));
    }

    record Item(int count, CompoundTag attributes) {
        String marketId() {
            String id = attributes.getStringOr("id", "");
            if (id.equals("PET")) {
                try {
                    String type = JsonParser.parseString(attributes.getStringOr("petInfo", ""))
                            .getAsJsonObject().get("type").getAsString();
                    return type.isBlank() ? "" : "PET:" + type.toUpperCase(Locale.ROOT);
                } catch (RuntimeException malformedPet) {
                    return "";
                }
            }
            if (id.equals("RUNE") || id.equals("UNIQUE_RUNE")) {
                var runes = attributes.getCompoundOrEmpty("runes");
                if (runes.size() != 1) {
                    return "";
                }
                String type = runes.keySet().iterator().next();
                return id + ":" + type + ":" + runes.getIntOr(type, 0);
            }
            return id;
        }

        Item pricingKey() {
            var comparable = attributes.copy();
            comparable.remove("originTag");
            comparable.remove("spawnedFor");
            comparable.remove("anvil_uses");
            comparable.remove("donated_museum");
            String id = comparable.getStringOr("id", "");
            if (id.equals("PULSE_RING") && comparable.contains("thunder_charge")) {
                long charge = comparable.getLongOr("thunder_charge", 0);
                comparable.putLong("thunder_charge", charge / 50_000 * 50_000);
            }
            if (id.startsWith("FINAL_DESTINATION_") && comparable.contains("eman_kills")) {
                long kills = comparable.getLongOr("eman_kills", 0);
                long tier = 0;
                for (int threshold : new int[]{50,100,150,250,500,1000,2500,5000,10000,25000,50000,100000,200000}) {
                    if (kills >= threshold) tier = threshold;
                }
                comparable.putLong("eman_kills", tier);
            }
            if (!id.equals("PET")) return comparable.equals(attributes) ? this : new Item(count, comparable);
            String json = attributes.getStringOr("petInfo", "");
            try {
                var info = JsonParser.parseString(json).getAsJsonObject();
                // Preserve progression, skins, held items and unknown fields.
                info.remove("uuid");
                info.remove("active");
                info.remove("hideInfo");
                info.remove("candyUsed");
                bucketPetLevel(info);
                var sorted = new JsonObject();
                new java.util.TreeMap<>(info.asMap()).forEach(sorted::add);
                String normalized = sorted.toString();
                if (normalized.equals(json) && comparable.equals(attributes)) {
                    return this;
                }
                comparable.putString("petInfo", normalized);
                return new Item(count, comparable);
            } catch (RuntimeException invalidPet) {
                return this;
            }
        }

        Item unitKey() {
            // Keep every upgrade and unknown field significant. Only plain stacks share a unit price.
            return attributes.size() == 1 ? new Item(1, attributes) : null;
        }
    }
    record Sample(int added, int saleCount) {}
    record Status(long snapshotTime, long salesCheckedAt, long backoffUntil,
                  String listingError, String salesError) {}
    record Scan(long snapshotTime, int saleCount, int pricedItems, long elapsedMillis,
                List<FlipMarket.Candidate> candidates) {}
    private record Snapshot(long time, Map<Item, List<FlipMarket.Listing<Item>>> byItem,
                            Map<String, FlipMarket.Listing<Item>> listingsById, Set<String> auctionItemIds) {
        boolean isCurrent(long now) {
            return now - time <= MAX_SNAPSHOT_AGE_MS;
        }
    }
    private record AuctionPage(Map<String, FlipMarket.Listing<Item>> listings, Set<String> itemIds) {}
    private record PricingView(Map<Item, FlipMarket.Price> prices, Map<Item, FlipMarket.Price> unitPrices,
                               Map<Item, Map<Integer, Integer>> stackSales, Set<String> sold, Set<String> auctionItemIds, Snapshot snapshot) {}

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final AuctionDatabase database;
    private boolean historyLoaded;
    private int saleCount;
    private long bonzoFragmentsCost;
    private long bonzoQuoteAt;
    private long nextBonzoQuoteAt;

    HypixelAuctions(AuctionDatabase database) {
        this.database = database;
    }

    private volatile long nextSaleRequestAt;
    private volatile PricingView pricing = new PricingView(Map.of(), Map.of(), Map.of(), Set.of(), Set.of(), null);
    private volatile long lastActiveRequest;
    private volatile long salesCheckedAt;
    private volatile String listingError = "";
    private volatile String salesError = "";
    private final AtomicLong requestBackoffUntil = new AtomicLong();

    boolean saleSampleDue(long now) {
        return now >= Math.max(nextSaleRequestAt, requestBackoffUntil.get());
    }

    boolean listingRefreshDue(long now) {
        return now >= listingRetryAt();
    }

    private long listingRetryAt() {
        return Math.max(lastActiveRequest + ACTIVE_CHECK_INTERVAL_MS, requestBackoffUntil.get());
    }

    static long secondsUntil(long deadline, long now) {
        long remaining = Math.max(0, deadline - now);
        return remaining / 1000L + (remaining % 1000L == 0 ? 0 : 1);
    }

    // History updates and snapshot publication run on the mod's single background worker.
    Sample sample() throws IOException, InterruptedException {
        long now = System.currentTimeMillis();
        if (!historyLoaded) {
            try {
                database.updateSales(List.of(), now);
                publishSales(now);
                historyLoaded = true;
            } catch (IOException failure) {
                salesError = failure.getMessage();
                nextSaleRequestAt = now + SAMPLE_INTERVAL_MS;
                throw failure;
            }
        }
        if (!saleSampleDue(now)) {
            return new Sample(0, saleCount);
        }
        nextSaleRequestAt = System.currentTimeMillis() + SAMPLE_INTERVAL_MS;

        List<FlipMarket.Sale<Item>> incoming = new ArrayList<>();
        var events = new ArrayList<AuctionDatabase.SaleEvent>();
        JsonArray rows;
        try {
            rows = auctions(fetch("auctions_ended"));
        } catch (IOException failure) {
            salesError = failure.getMessage();
            throw failure;
        }
        for (JsonElement row : rows) {
            JsonObject sale = row.getAsJsonObject();
            if (!isTrue(sale, "bin") || !sale.has("buyer") || sale.get("buyer").isJsonNull()
                    || sale.get("buyer").getAsString().isBlank()) {
                continue;
            }
            long price = sale.get("price").getAsLong();
            if (price <= 0) {
                continue;
            }
            itemOf(sale, true).ifPresent(raw -> {
                var attributes = raw.attributes();
                String uuid = attributes.getStringOr("uuid", "");
                attributes.remove("uuid");
                attributes.remove("timestamp");
                String auction = sale.get("auction_id").getAsString().replace("-", "").toLowerCase(Locale.ROOT);
                long time = sale.get("timestamp").getAsLong();
                incoming.add(new FlipMarket.Sale<>(auction, raw, price, time));
                events.add(new AuctionDatabase.SaleEvent(auction, AuctionDatabase.variantKey(raw.pricingKey()), uuid,
                        sale.get("buyer").getAsString().replace("-", ""), sale.has("seller") ? sale.get("seller").getAsString().replace("-", "") : "", price, time));
            });
        }
        long sampledAt = System.currentTimeMillis();
        try {
            int added = database.updateSales(incoming, sampledAt);
            database.observe(events);
            publishSales(sampledAt);
            salesCheckedAt = sampledAt;
            salesError = "";
            return new Sample(added, saleCount);
        } catch (IOException failure) {
            salesError = failure.getMessage();
            throw failure;
        }
    }

    private void publishSales(long now) throws IOException {
        var sales = database.readSales(now);
        saleCount = sales.count();
        if (guardUntil <= now) {
            var considered = new HashSet<String>();
            var dropping = new HashSet<String>();
            sales.prices().forEach((item, price) -> {
                if (price.sales() >= 12 && price.recentSalePrice() >= 10_000_000
                        && now - price.latestSaleAt() <= Duration.ofHours(2).toMillis()) {
                    considered.add(item.marketId());
                    if (price.recentMedian() < price.recentSalePrice() * 0.75) dropping.add(item.marketId());
                }
            });
            if (dropping.size() >= 5 && dropping.size() * 3 >= considered.size()) guardUntil = now + Duration.ofHours(48).toMillis();
        }
        pricing = new PricingView(sales.prices(), sales.unitPrices(), sales.stackSales(), sales.sold(), sales.auctionItemIds(), pricing.snapshot());
    }

    private volatile Snapshot preview;
    private Map<String, Long> npcPrices = Map.of();
    private long nextNpcRefresh;
    volatile long guardUntil;
    volatile long purse = Long.MAX_VALUE;

    private void refreshNpc(long now) throws InterruptedException {
        if (now < nextNpcRefresh) return;
        nextNpcRefresh = now + 60_000;
        try {
            Map<String, Long> prices = new java.util.HashMap<>();
            for (var row : fetchApi("resources/skyblock/items").getAsJsonArray("items")) {
                var item = row.getAsJsonObject();
                if (item.has("npc_sell_price")) prices.put(item.get("id").getAsString(), item.get("npc_sell_price").getAsLong());
            }
            npcPrices = Map.copyOf(prices);
            nextNpcRefresh = now + Duration.ofHours(6).toMillis();
        } catch (IOException | RuntimeException failure) {
            LOGGER.debug("NPC prices unavailable", failure);
        }
    }

    private long npcQuote(Item item) {
        long price = npcPrices.getOrDefault(item.attributes().getStringOr("id", ""), 0L);
        return price > 0 && price <= Long.MAX_VALUE / item.count() ? price * item.count() : 0;
    }

    Scan scan(FlipSettings settings) throws IOException, InterruptedException {
        return scan(settings, null);
    }

    Scan scan(FlipSettings settings, java.util.function.Consumer<Scan> early) throws IOException, InterruptedException {
        long started = System.nanoTime();
        if (early == null || !historyLoaded) refreshMarketInputs();
        Snapshot before = pricing.snapshot();
        Snapshot active = activeSnapshot(early == null ? null : snapshot -> {
            var ranked = rank(snapshot, settings, started);
            var firstPage = preview.listingsById();
            var fresh = ranked.candidates().stream()
                    .filter(flip -> firstPage.containsKey(flip.id()) && !before.listingsById().containsKey(flip.id()))
                    .toList();
            early.accept(new Scan(ranked.snapshotTime(), saleCount, ranked.pricedItems(), ranked.elapsedMillis(), fresh));
        });
        if (early != null && active == before) return new Scan(active.time(), saleCount, 0, 0, List.of());
        return rank(active, settings, started);
    }

    void refreshMarketInputs() throws InterruptedException {
        try {
            sample();
        } catch (IOException failure) {
            LOGGER.debug("Refreshing listings using the sales already on hand", failure);
        }
        refreshNpc(System.currentTimeMillis());
        refreshBonzoQuote(System.currentTimeMillis());
    }

    private Scan rank(Snapshot active, FlipSettings settings, long started) {
        long now = System.currentTimeMillis();
        var view = pricing;
        int pricedItems = (int) active.byItem().keySet().stream().filter(item -> comparablePrice(view, item, now) != null).count();
        long valuationTime = System.currentTimeMillis();
        var candidates = active.isCurrent(valuationTime)
                ? FlipMarket.rank(active.byItem(), item -> comparablePrice(view, item, valuationTime),
                        item -> replacementCost(active, view.sold(), item, valuationTime),
                        item -> hasStackSales(item, view.stackSales()), this::npcQuote, valuationTime < guardUntil, view.sold(), settings, valuationTime)
                : List.<FlipMarket.Candidate>of();
        candidates = candidates.stream().filter(f -> f.buy() <= purse).toList();
        return new Scan(active.time(), saleCount, pricedItems,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), candidates);
    }

    private void refreshBonzoQuote(long now) throws InterruptedException {
        if (now < nextBonzoQuoteAt) {
            return;
        }
        nextBonzoQuoteAt = now + SAMPLE_INTERVAL_MS;
        try {
            var bazaar = fetchApi("skyblock/bazaar");
            var fragments = bazaar.getAsJsonObject("products").getAsJsonObject("BONZO_FRAGMENT");
            long quoteAt = bazaar.get("lastUpdated").getAsLong();
            bonzoFragmentsCost = instantBuyCost(fragments.getAsJsonArray("buy_summary"), 8);
            bonzoQuoteAt = quoteAt;
        } catch (IOException | RuntimeException failure) {
            LOGGER.debug("Could not price Bonzo Fragments", failure);
        }
    }

    static long instantBuyCost(JsonArray offers, int quantity) {
        double cost = 0;
        for (var entry : offers) {
            var offer = entry.getAsJsonObject();
            int amount = offer.get("amount").getAsInt();
            double price = offer.get("pricePerUnit").getAsDouble();
            if (amount <= 0 || price <= 0 || !Double.isFinite(price)) {
                continue;
            }
            int filled = Math.min(quantity, amount);
            cost += filled * price;
            quantity -= filled;
            if (quantity == 0) {
                return (long) Math.ceil(cost);
            }
        }
        return 0;
    }

    private static boolean isFraggedBonzo(Item item) {
        return item.attributes().getStringOr("id", "").equals("STARRED_BONZO_STAFF");
    }

    private long replacementCost(Snapshot snapshot, Set<String> sold, Item item, long now) {
        if (!isFraggedBonzo(item)) {
            return Long.MAX_VALUE;
        }
        if (bonzoFragmentsCost <= 0 || now - bonzoQuoteAt > MAX_SNAPSHOT_AGE_MS) {
            return 0;
        }
        // A base staff must have the same count and all the same recorded upgrades.
        var baseAttributes = item.attributes().copy();
        baseAttributes.putString("id", "BONZO_STAFF");
        var base = new Item(item.count(), baseAttributes);
        var listing = FlipMarket.lowestBin(snapshot.byItem().getOrDefault(base, List.of()), sold, null, now);
        return listing == null || item.count() != 1 || listing.price() > Long.MAX_VALUE - bonzoFragmentsCost
                ? 0 : listing.price() + bonzoFragmentsCost;
    }

    private Snapshot activeSnapshot() throws IOException, InterruptedException {
        return activeSnapshot(null);
    }

    private Snapshot activeSnapshot(java.util.function.Consumer<Snapshot> early) throws IOException, InterruptedException {
        long now = System.currentTimeMillis();
        Snapshot cached = pricing.snapshot();
        long retryAt = listingRetryAt();
        if (now < retryAt) {
            if (cached != null && cached.isCurrent(now)) {
                return cached;
            }

            boolean rateLimited = now < requestBackoffUntil.get();
            String reason = rateLimited ? "Hypixel rate-limited the auction API." : listingError;
            if (reason.isEmpty()) {
                reason = "No complete auction snapshot is cached yet.";
            }
            throw new IOException(reason + " Next check allowed in " + secondsUntil(retryAt, now) + "s.");
        }

        lastActiveRequest = now;
        try {
            return loadActiveSnapshot(cached, early);
        } catch (IOException failure) {
            listingError = failure.getMessage();
            throw failure;
        } catch (IllegalStateException | NumberFormatException | NullPointerException malformed) {
            listingError = "Hypixel returned incomplete listing data.";
            throw new IOException(listingError, malformed);
        }
    }

    private Snapshot loadActiveSnapshot(Snapshot cached, java.util.function.Consumer<Snapshot> early) throws IOException, InterruptedException {
        JsonObject first = fetch("auctions?page=0");
        long updated = first.get("lastUpdated").getAsLong();
        long now = System.currentTimeMillis();
        if (updated <= 0 || now - updated > MAX_SNAPSHOT_AGE_MS) {
            throw new IOException("Hypixel's listing snapshot is older than two minutes.");
        }
        int pages = first.get("totalPages").getAsInt();
        if (pages < 1 || pages > MAX_PAGES || first.get("page").getAsInt() != 0) {
            throw new IOException("Hypixel returned unexpected auction pagination.");
        }
        if (cached != null && cached.time() >= updated) {
            if (updated == cached.time()) {
                listingError = "";
            }
            return cached;
        }

        Map<String, FlipMarket.Listing<Item>> listings = new LinkedHashMap<>();
        var previousItems = cached == null ? Map.<String, FlipMarket.Listing<Item>>of() : cached.listingsById();
        var marketIds = new HashSet<String>();
        readListings(first, listings, marketIds, previousItems, now);
        if (early != null && cached != null && cached.isCurrent(now)) {
            var combined = new LinkedHashMap<>(cached.listingsById());
            combined.putAll(listings);
            var merged = new Snapshot(updated, FlipMarket.indexListings(combined.values()), Map.copyOf(combined), Set.of());
            preview = new Snapshot(updated, Map.of(), Map.copyOf(listings), Set.of());
            early.accept(merged);
        }
        try (var workers = Executors.newFixedThreadPool(4)) {
            var completed = new ExecutorCompletionService<AuctionPage>(workers);
            var outstanding = new ArrayList<Future<AuctionPage>>();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(50);
            try {
                for (int page = 1; page < pages; page++) {
                    int index = page;
                    outstanding.add(completed.submit(() -> {
                        JsonObject data = fetch("auctions?page=" + index);
                        if (data.get("lastUpdated").getAsLong() != updated
                                || data.get("totalPages").getAsInt() != pages
                                || data.get("page").getAsInt() != index) {
                            throw new IOException("Hypixel refreshed its listings mid-scan. Try again on the next scan.");
                        }
                        Map<String, FlipMarket.Listing<Item>> pageListings = new LinkedHashMap<>();
                        var pageMarketIds = new HashSet<String>();
                        readListings(data, pageListings, pageMarketIds, previousItems, now);
                        return new AuctionPage(pageListings, pageMarketIds);
                    }));
                }

                for (int page = 1; page < pages; page++) {
                    var finished = completed.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
                    if (finished == null) {
                        throw new IOException("The full auction scan timed out.");
                    }
                    try {
                        var result = finished.get();
                        result.listings().forEach(listings::putIfAbsent);
                        marketIds.addAll(result.itemIds());
                    } catch (ExecutionException failure) {
                        if (failure.getCause() instanceof IOException io) {
                            throw io;
                        }
                        throw new IOException("Could not load an auction page.", failure.getCause());
                    }
                }
            } finally {
                outstanding.forEach(request -> request.cancel(true));
            }
        }
        var snapshot = new Snapshot(updated, FlipMarket.indexListings(listings.values()), Map.copyOf(listings), Set.copyOf(marketIds));
        pricing = new PricingView(pricing.prices(), pricing.unitPrices(), pricing.stackSales(), pricing.sold(), pricing.auctionItemIds(), snapshot);
        preview = null;
        listingError = "";
        return snapshot;
    }

    private static void readListings(JsonObject page, Map<String, FlipMarket.Listing<Item>> listings,
                                     Set<String> marketIds, Map<String, FlipMarket.Listing<Item>> previousItems,
                                     long now) throws IOException {
        for (JsonElement row : auctions(page)) {
            JsonObject auction = row.getAsJsonObject();
            long end = auction.get("end").getAsLong();
            if (isTrue(auction, "claimed") || end <= now) {
                continue;
            }
            long price = auction.get("starting_bid").getAsLong();
            String id = auction.get("uuid").getAsString().toLowerCase(Locale.ROOT);
            if (price <= 0 || !id.matches("[0-9a-fA-F]{32}")) {
                continue;
            }
            String name = auction.get("item_name").getAsString().replaceAll("§.", "");
            var previous = previousItems.get(id);
            Item item = previous == null ? itemOf(auction).orElse(null) : previous.item();
            if (item != null) {
                String marketId = item.marketId();
                if (!marketId.isBlank()) {
                    marketIds.add(marketId);
                }
                if (isTrue(auction, "bin") && auction.get("highest_bid_amount").getAsLong() == 0) {
                    listings.putIfAbsent(id, new FlipMarket.Listing<>(id, item.pricingKey(), name, price, auction.get("start").getAsLong(), end));
                }
            }
        }
    }

    private static Optional<Item> itemOf(JsonObject auction) { return itemOf(auction, false); }

    private static Optional<Item> itemOf(JsonObject auction, boolean retainIdentity) {
        try {
            JsonElement value = auction.get("item_bytes");
            String raw = value.isJsonObject() ? value.getAsJsonObject().get("data").getAsString()
                    : value.getAsString();
            if (raw.length() > MAX_ITEM_BYTES) {
                return Optional.empty();
            }
            byte[] compressed = Base64.getDecoder().decode(raw);
            CompoundTag root = NbtIo.readCompressed(new ByteArrayInputStream(compressed),
                    NbtAccounter.create(2 * 1024 * 1024));
            var items = root.getListOrEmpty("i");
            if (items.size() != 1 || !(items.get(0) instanceof CompoundTag stack)) {
                return Optional.empty();
            }
            var customData = stack.getCompoundOrEmpty("tag");
            int count = stack.getIntOr("Count", 0);
            if (count <= 0) {
                count = stack.getIntOr("count", 1);
                customData = stack.getCompoundOrEmpty("components").getCompoundOrEmpty("minecraft:custom_data");
            }
            return itemKey(count, customData, retainIdentity);
        } catch (IOException | RuntimeException malformedItem) {
            // A malformed item has no reliable comparable. Continue with the rest of the page.
            return Optional.empty();
        }
    }

    static Optional<Item> itemOf(ItemStack stack) {
        var customData = stack.get(DataComponents.CUSTOM_DATA);
        return customData == null ? Optional.empty() : itemKey(stack.getCount(), customData.copyTag(), false);
    }

    private static Optional<Item> itemKey(int count, CompoundTag data, boolean retainIdentity) {
        CompoundTag attributes = data.getCompoundOrEmpty("ExtraAttributes");
        if (attributes.getStringOr("id", "").isBlank()) {
            attributes = data;
        }
        if (count <= 0 || attributes.getStringOr("id", "").isBlank()) {
            return Optional.empty();
        }
        attributes = attributes.copy();
        if (!retainIdentity) {
            attributes.remove("uuid");
            attributes.remove("timestamp");
        }
        return Optional.of(new Item(count, attributes));
    }

    boolean hasAuctionEvidence(String itemId) {
        var view = pricing;
        var snapshot = view.snapshot();
        return view.auctionItemIds().contains(itemId)
                || snapshot != null && snapshot.auctionItemIds().contains(itemId);
    }

    boolean needsAuctionEvidence(long now) {
        var snapshot = pricing.snapshot();
        return (snapshot == null || !snapshot.isCurrent(now)) && listingRefreshDue(now);
    }

    String variantForAuction(String id) {
        var active = pricing.snapshot();
        var page = preview;
        var listing = page == null ? null : page.listingsById().get(id);
        if (listing == null && active != null) listing = active.listingsById().get(id);
        return listing == null ? null : AuctionDatabase.variantKey(listing.item());
    }

    boolean canOpenAuction(String id, long now) {
        var view = pricing;
        var snapshot = view.snapshot();
        var page = preview;
        if (page != null && page.isCurrent(now) && page.listingsById().containsKey(id)) snapshot = page;
        if (snapshot == null || !snapshot.isCurrent(now) || view.sold().contains(id)) {
            return false;
        }
        var listing = snapshot.listingsById().get(id);
        return listing != null && listing.endsAt() > now;
    }

    Status status() {
        var snapshot = pricing.snapshot();
        return new Status(snapshot == null ? 0 : snapshot.time(), salesCheckedAt,
                requestBackoffUntil.get(), listingError, salesError);
    }

    void refreshListings() throws IOException, InterruptedException {
        activeSnapshot();
    }

    FlipMarket.ListingPrice listingPrice(Item item, long now) {
        item = item.pricingKey();
        var view = pricing;
        var snapshot = view.snapshot();
        boolean fresh = snapshot != null && snapshot.isCurrent(now);
        long lowestBin = 0;
        if (fresh) {
            var listing = FlipMarket.lowestBin(snapshot.byItem().getOrDefault(item, List.of()), view.sold(), null, now);
            if (listing != null) {
                lowestBin = listing.price();
            }
        }
        return FlipMarket.listingPrice(comparablePrice(view, item, now), lowestBin, fresh, now);
    }

    static boolean hasStackSales(Item item, Map<Item, Map<Integer, Integer>> stackSales) {
        if (item.count() == 1) {
            return true;
        }
        var unit = item.unitKey();
        return unit != null && stackSales.getOrDefault(unit, Map.of()).entrySet().stream()
                .filter(sale -> sale.getKey() >= item.count())
                .mapToInt(Map.Entry::getValue)
                .sum() >= FlipMarket.MIN_STACK_SALES;
    }

    private static FlipMarket.Price comparablePrice(PricingView view, Item item, long now) {
        return FlipMarket.comparablePrice(item, item.unitKey(), item.count(), view.prices(), view.unitPrices(), now);
    }

    private JsonObject fetch(String resource) throws IOException, InterruptedException {
        return fetchApi("skyblock/" + resource);
    }

    JsonObject fetchApi(String resource) throws IOException, InterruptedException {
        if (System.currentTimeMillis() < requestBackoffUntil.get()) {
            throw new IOException("Waiting for Hypixel's auction API rate limit.");
        }
        var request = HttpRequest.newBuilder(URI.create(API + resource))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", "SkyBlockFlipper/0.1.9")
                .GET().build();
        var download = http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
        try {
            var response = download.get(REQUEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            if (response.statusCode() == 429) {
                long now = System.currentTimeMillis();
                long retryAt = response.headers().firstValue("Retry-After")
                        .map(value -> retryAfter(value, now)).orElse(now + SAMPLE_INTERVAL_MS);
                requestBackoffUntil.accumulateAndGet(retryAt, Math::max);
            }
            if (response.statusCode() != 200) {
                throw new IOException("Hypixel API returned HTTP " + response.statusCode() + ".");
            }
            try (var reader = new InputStreamReader(new ByteArrayInputStream(response.body()),
                    StandardCharsets.UTF_8)) {
                JsonObject data = JsonParser.parseReader(reader).getAsJsonObject();
                if (!isTrue(data, "success")) {
                    throw new IOException("Hypixel API returned an unsuccessful response.");
                }
                return data;
            }
        } catch (TimeoutException timeout) {
            throw new IOException("Hypixel's auction API timed out.", timeout);
        } catch (ExecutionException failure) {
            throw new IOException("Could not reach Hypixel's auction API.", failure.getCause());
        } catch (JsonParseException | IllegalStateException malformed) {
            throw new IOException("Hypixel returned invalid JSON.", malformed);
        } finally {
            download.cancel(true);
        }
    }

    private static long retryAfter(String value, long now) {
        try {
            long seconds = Long.parseLong(value);
            return now + Math.min(Long.MAX_VALUE - now, Math.max(SAMPLE_INTERVAL_MS, TimeUnit.SECONDS.toMillis(seconds)));
        } catch (NumberFormatException invalidSeconds) {
            try {
                return Math.max(now + SAMPLE_INTERVAL_MS,
                        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli());
            } catch (DateTimeParseException invalidDate) {
                return now + SAMPLE_INTERVAL_MS;
            }
        }
    }

    private static JsonArray auctions(JsonObject data) throws IOException {
        if (!data.has("auctions") || !data.get("auctions").isJsonArray()) {
            throw new IOException("Hypixel's response has no auction list.");
        }
        return data.getAsJsonArray("auctions");
    }

    private static boolean isTrue(JsonObject data, String key) {
        return data.has(key) && !data.get(key).isJsonNull() && data.get(key).getAsBoolean();
    }
}
