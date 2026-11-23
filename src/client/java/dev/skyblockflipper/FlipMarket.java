package dev.skyblockflipper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class FlipMarket {
    static final long PRICING_WINDOW_MS = Duration.ofHours(2).toMillis();
    private static final long FRESH_SALE_MS = Duration.ofMinutes(30).toMillis();
    static final int MIN_SALES = 3;
    static final int MAX_COMPARABLE_SALES = 12;

    record Sale<K>(String id, K item, long price, long soldAt) {}
    record Listing<K>(String id, K item, String name, long price, long endsAt) {}
    record Candidate(String id, String name, long buy, long resale, long profit, int sales, long endsAt) {}
    record Price(long recentSalePrice, int sales, long validUntil) {
        boolean isCurrent(long now) {
            return now < validUntil;
        }
    }

    record ListingPrice(long suggestedCoins, int sales, long lowestBin, boolean freshSnapshot) {}

    static Price price(List<? extends Sale<?>> recent, long now) {
        if (recent.size() < MIN_SALES) {
            return null;
        }
        long validUntil = Math.min(recent.getLast().soldAt() + PRICING_WINDOW_MS,
                recent.getFirst().soldAt() + FRESH_SALE_MS);
        if (validUntil <= now) {
            return null;
        }
        long[] prices = recent.stream().mapToLong(Sale::price).sorted().toArray();
        return new Price(prices[(prices.length - 1) / 4], prices.length, validUntil);
    }

    static <K> Map<K, List<Listing<K>>> indexListings(Iterable<Listing<K>> listings) {
        Map<K, List<Listing<K>>> byItem = new HashMap<>();
        for (Listing<K> listing : listings) {
            byItem.computeIfAbsent(listing.item(), key -> new ArrayList<>()).add(listing);
        }
        byItem.replaceAll((item, group) -> {
            group.sort(Comparator.comparingLong(Listing<K>::price).thenComparing(Listing::id));
            return List.copyOf(group);
        });
        return Map.copyOf(byItem);
    }

    static <K> Listing<K> lowestBin(List<Listing<K>> sorted, Set<String> sold, String excluded, long now) {
        for (Listing<K> listing : sorted) {
            if (listing.endsAt() > now && !listing.id().equals(excluded) && !sold.contains(listing.id())) {
                return listing;
            }
        }
        return null;
    }

    static long resale(Price price, long competingBin) {
        return competingBin > 1 ? Math.min(price.recentSalePrice(), competingBin - 1)
                : Math.min(price.recentSalePrice(), competingBin);
    }

    static ListingPrice listingPrice(Price price, long lowestBin, boolean freshSnapshot, long now) {
        long currentBin = freshSnapshot ? lowestBin : 0;
        if (price == null || !price.isCurrent(now)) {
            return new ListingPrice(0, 0, currentBin, freshSnapshot);
        }
        long suggested = currentBin > 0 ? resale(price, currentBin) : price.recentSalePrice();
        return new ListingPrice(suggested, price.sales(), currentBin, freshSnapshot);
    }

    static <K> List<Candidate> rank(Map<K, List<Listing<K>>> byItem, Map<K, Price> prices,
                                    Set<String> sold, FlipSettings settings, long now) {
        List<Candidate> candidates = new ArrayList<>();
        prices.forEach((item, price) -> {
            var listings = byItem.get(item);
            if (listings == null || !price.isCurrent(now)) {
                return;
            }
            var listing = lowestBin(listings, sold, null, now);
            if (listing == null || listing.price() < settings.minPrice() || listing.price() > settings.maxPrice()) {
                return;
            }
            var competitor = lowestBin(listings, sold, listing.id(), now);
            if (competitor == null) {
                return;
            }
            long resale = resale(price, competitor.price());
            if (resale <= listing.price()) {
                return;
            }
            long profit = resale - listing.price() - estimatedFees(resale);
            if (profit >= settings.minProfit() && profit > 0) {
                candidates.add(new Candidate(listing.id(), listing.name(), listing.price(),
                        resale, profit, price.sales(), listing.endsAt()));
            }
        });

        candidates.sort(Comparator.comparingLong(Candidate::profit).reversed()
                .thenComparing(Candidate::id));
        return candidates;
    }

    static long estimatedFees(long resale) {
        int basisPoints = resale < 10_000_000 ? 100 : resale < 100_000_000 ? 200 : 250;
        long listingFee = roundedUpFee(resale, basisPoints);
        long collectionTax = Math.min(roundedUpFee(resale, 100), Math.max(0, resale - 1_000_000));
        return listingFee + collectionTax;
    }

    private static long roundedUpFee(long price, int basisPoints) {
        return price / 10_000 * basisPoints + (price % 10_000 * basisPoints + 9_999) / 10_000;
    }
}
