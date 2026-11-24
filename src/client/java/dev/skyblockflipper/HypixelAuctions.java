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
                            Map<String, FlipMarket.Listing<Item>> listingsById) {
        boolean isCurrent(long now) {
            return now - time <= MAX_SNAPSHOT_AGE_MS;
        }
    }
    private record PricingView(Map<Item, FlipMarket.Price> prices, Map<Item, FlipMarket.Price> unitPrices,
                               Set<String> sold, Set<String> auctionItemIds, Snapshot snapshot) {}

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final AuctionDatabase database;
    private boolean historyLoaded;
    private int saleCount;

    HypixelAuctions(AuctionDatabase database) {
        this.database = database;
    }

    private volatile long nextSaleRequestAt;
    private volatile PricingView pricing = new PricingView(Map.of(), Map.of(), Set.of(), Set.of(), null);
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
            itemOf(sale).ifPresent(item -> incoming.add(new FlipMarket.Sale<>(
                    sale.get("auction_id").getAsString().toLowerCase(Locale.ROOT), item, price,
                    sale.get("timestamp").getAsLong())));
        }
        long sampledAt = System.currentTimeMillis();
        try {
            int added = database.updateSales(incoming, sampledAt);
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
        pricing = new PricingView(sales.prices(), sales.unitPrices(), sales.sold(), sales.auctionItemIds(), pricing.snapshot());
    }

    Scan scan(FlipSettings settings) throws IOException, InterruptedException {
        long started = System.nanoTime();
        try {
            sample();
        } catch (IOException failure) {
            LOGGER.debug("Refreshing listings using the sales already on hand", failure);
        }
        Snapshot active = activeSnapshot();
        long now = System.currentTimeMillis();
        var view = pricing;
        int pricedItems = (int) active.byItem().keySet().stream().filter(item -> comparablePrice(view, item, now) != null).count();
        var candidates = active.isCurrent(now)
                ? FlipMarket.rank(active.byItem(), item -> comparablePrice(view, item, now), view.sold(), settings, now)
                : List.<FlipMarket.Candidate>of();
        return new Scan(active.time(), saleCount, pricedItems,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), candidates);
    }

    private Snapshot activeSnapshot() throws IOException, InterruptedException {
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
            return loadActiveSnapshot(cached);
        } catch (IOException failure) {
            listingError = failure.getMessage();
            throw failure;
        } catch (IllegalStateException | NumberFormatException | NullPointerException malformed) {
            listingError = "Hypixel returned incomplete listing data.";
            throw new IOException(listingError, malformed);
        }
    }

    private Snapshot loadActiveSnapshot(Snapshot cached) throws IOException, InterruptedException {
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
        readListings(first, listings, previousItems, now);
        try (var workers = Executors.newFixedThreadPool(4)) {
            var completed = new ExecutorCompletionService<Map<String, FlipMarket.Listing<Item>>>(workers);
            var outstanding = new ArrayList<Future<Map<String, FlipMarket.Listing<Item>>>>();
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
                        readListings(data, pageListings, previousItems, now);
                        return pageListings;
                    }));
                }

                for (int page = 1; page < pages; page++) {
                    var finished = completed.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
                    if (finished == null) {
                        throw new IOException("The full auction scan timed out.");
                    }
                    try {
                        finished.get().forEach(listings::putIfAbsent);
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
        var snapshot = new Snapshot(updated, FlipMarket.indexListings(listings.values()), Map.copyOf(listings));
        pricing = new PricingView(pricing.prices(), pricing.unitPrices(), pricing.sold(), pricing.auctionItemIds(), snapshot);
        listingError = "";
        return snapshot;
    }

    private static void readListings(JsonObject page, Map<String, FlipMarket.Listing<Item>> listings,
                                     Map<String, FlipMarket.Listing<Item>> previousItems, long now) throws IOException {
        for (JsonElement row : auctions(page)) {
            JsonObject auction = row.getAsJsonObject();
            long end = auction.get("end").getAsLong();
            if (!isTrue(auction, "bin") || isTrue(auction, "claimed") || end <= now
                    || auction.get("highest_bid_amount").getAsLong() != 0) {
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
                listings.putIfAbsent(id, new FlipMarket.Listing<>(id, item, name, price, end));
            }
        }
    }

    private static Optional<Item> itemOf(JsonObject auction) {
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
            return itemKey(count, customData);
        } catch (IOException | RuntimeException malformedItem) {
            // A malformed item has no reliable comparable. Continue with the rest of the page.
            return Optional.empty();
        }
    }

    static Optional<Item> itemOf(ItemStack stack) {
        var customData = stack.get(DataComponents.CUSTOM_DATA);
        return customData == null ? Optional.empty() : itemKey(stack.getCount(), customData.copyTag());
    }

    private static Optional<Item> itemKey(int count, CompoundTag data) {
        CompoundTag attributes = data.getCompoundOrEmpty("ExtraAttributes");
        if (attributes.getStringOr("id", "").isBlank()) {
            attributes = data;
        }
        if (count <= 0 || attributes.getStringOr("id", "").isBlank()) {
            return Optional.empty();
        }
        attributes = attributes.copy();
        attributes.remove("uuid");
        attributes.remove("timestamp");
        return Optional.of(new Item(count, attributes));
    }

    boolean hasAuctionEvidence(String itemId, long now) {
        var view = pricing;
        if (view.auctionItemIds().contains(itemId)) {
            return true;
        }
        var snapshot = view.snapshot();
        return snapshot != null && snapshot.isCurrent(now) && snapshot.byItem().keySet().stream()
                .anyMatch(item -> item.marketId().equals(itemId));
    }

    boolean canOpenAuction(String id, long now) {
        var view = pricing;
        var snapshot = view.snapshot();
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
