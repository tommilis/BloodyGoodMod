package dev.skyblockflipper;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;

final class MarketHistory {
    enum Result { SOLD, NO_SALES, UNAVAILABLE }
    private record Cached(Result result, long expiresAt) {}

    private static final Duration LOOKBACK = Duration.ofDays(30);
    private static final long CACHE_MS = Duration.ofMinutes(15).toMillis();
    private static final long RETRY_MS = Duration.ofMinutes(1).toMillis();
    private static final int MAX_ENTRIES = 512;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    private final LinkedHashMap<String, Cached> cache = new LinkedHashMap<>(16, 0.75f, true);
    private long nextRequestAt;

    // Called from the auction worker after checking the local listing and sale evidence.
    Result lookup(String auctionId) throws InterruptedException {
        String tag = MarketVariants.historyTag(auctionId).replace(':', '-');
        if (!tag.matches("[A-Z0-9_-]{1,128}")) {
            return Result.NO_SALES;
        }
        long now = System.currentTimeMillis();
        Cached cached = cache.get(tag);
        if (cached != null && now < cached.expiresAt()) {
            return cached.result();
        }
        if (now < nextRequestAt) {
            return Result.UNAVAILABLE;
        }
        nextRequestAt = now + 2_000;
        Result result;
        try {
            var request = HttpRequest.newBuilder(URI.create("https://sky.coflnet.com/api/auctions/tag/" + tag + "/sold"))
                    .timeout(Duration.ofSeconds(6)).header("User-Agent", "BloodyGoodMod").build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            result = switch (response.statusCode()) {
                case 200 -> hasRecentSale(JsonParser.parseString(response.body()), tag, Instant.ofEpochMilli(now))
                        ? Result.SOLD : Result.NO_SALES;
                case 404 -> Result.NO_SALES;
                default -> Result.UNAVAILABLE;
            };
            if (response.statusCode() == 429) {
                nextRequestAt = System.currentTimeMillis() + RETRY_MS;
            }
        } catch (IOException | RuntimeException failure) {
            LoggerFactory.getLogger(MarketHistory.class).debug("Could not check sold auctions for {}", tag, failure);
            result = Result.UNAVAILABLE;
        }
        cache.put(tag, new Cached(result, System.currentTimeMillis() + (result == Result.UNAVAILABLE ? RETRY_MS : CACHE_MS)));
        if (cache.size() > MAX_ENTRIES) {
            cache.pollFirstEntry();
        }
        return result;
    }

    static boolean hasRecentSale(JsonElement response, String tag, Instant now) {
        Instant oldest = now.minus(LOOKBACK);
        for (JsonElement entry : response.getAsJsonArray()) {
            if (!entry.isJsonObject()) {
                continue;
            }
            var sale = entry.getAsJsonObject();
            var saleTag = sale.get("tag");
            var bid = sale.get("highestBidAmount");
            var end = sale.get("end");
            if (saleTag == null || !saleTag.isJsonPrimitive() || !tag.equals(saleTag.getAsString())
                    || bid == null || !bid.isJsonPrimitive() || !bid.getAsJsonPrimitive().isNumber()
                    || bid.getAsLong() <= 0 || end == null || !end.isJsonPrimitive()) {
                continue;
            }
            try {
                var date = DateTimeFormatter.ISO_DATE_TIME.parseBest(end.getAsString(), OffsetDateTime::from, LocalDateTime::from);
                Instant soldAt = date instanceof OffsetDateTime offset ? offset.toInstant()
                        : ((LocalDateTime) date).toInstant(ZoneOffset.UTC);
                if (!soldAt.isBefore(oldest) && !soldAt.isAfter(now)) {
                    return true;
                }
            } catch (DateTimeParseException invalidDate) {
                // An undated sale cannot establish recent market activity.
            }
        }
        return false;
    }
}
