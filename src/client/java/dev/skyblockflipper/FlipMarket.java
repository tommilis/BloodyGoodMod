package dev.skyblockflipper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class FlipMarket {
    static final long PRICING_WINDOW_MS = Duration.ofHours(24).toMillis();
    private static final long SALE_HALF_LIFE_MS = Duration.ofHours(6).toMillis();
    private static final int MIN_FALLBACK_BINS = 2;
    static final int MIN_SALES = 3;
    static final int MAX_COMPARABLE_SALES = 64;

    record Sale<K>(String id, K item, long price, long soldAt) {}
    record Listing<K>(String id, K item, String name, long price, long endsAt) {}
    enum Method { SALES, UNIT_SALES, BIN_COMPARISON, HISTORY }

    record Candidate(String id, String name, long buy, long resale, long profit, int sales, long endsAt,
                     Method method, long latestSaleAt, int competingBins, long lowestCompetingBin) {
        Candidate(String id, String name, long buy, long resale, long profit, int sales, long endsAt) {
            this(id, name, buy, resale, profit, sales, endsAt, Method.HISTORY, 0, 0, 0);
        }

        double roi() {
            return 100.0 * profit / buy;
        }

        double rankingScore() {
            if (method == Method.BIN_COMPARISON) {
                return profit * 0.5;
            }
            return profit * (0.6 + 0.4 * Math.min(sales, 10) / 10.0);
        }

        String basis() {
            return switch (method) {
                case SALES -> "Comparable sales";
                case UNIT_SALES -> "Comparable sales per item";
                case BIN_COMPARISON -> "BIN comparison · no reliable sales estimate";
                case HISTORY -> "Recorded estimate";
            };
        }
    }

    record Price(long recentSalePrice, int sales, long validUntil, long latestSaleAt, boolean perUnit) {
        boolean isCurrent(long now) {
            return now < validUntil;
        }
    }

    record ListingPrice(long suggestedCoins, int sales, long lowestBin, boolean freshSnapshot, boolean perUnit) {}

    static Price price(List<? extends Sale<?>> recent, long now) {
        if (recent.size() < MIN_SALES) {
            return null;
        }
        var byPrice = new ArrayList<>(recent);
        byPrice.sort(Comparator.comparingLong(Sale::price));
        double target = byPrice.stream().mapToDouble(sale -> weight(sale, now)).sum() / 4;
        double cumulative = 0;
        long estimate = byPrice.getLast().price();
        for (var sale : byPrice) {
            cumulative += weight(sale, now);
            if (cumulative >= target) {
                estimate = sale.price();
                break;
            }
        }
        return new Price(estimate, recent.size(), recent.getLast().soldAt() + PRICING_WINDOW_MS,
                recent.getFirst().soldAt(), false);
    }

    private static double weight(Sale<?> sale, long now) {
        return Math.pow(0.5, (now - sale.soldAt()) / (double) SALE_HALF_LIFE_MS);
    }

    static <K> Price comparablePrice(K item, K unitKey, int count, Map<K, Price> prices, Map<K, Price> unitPrices,
                                      long now) {
        Price exact = prices.get(item);
        if (exact != null && exact.isCurrent(now)) {
            return exact;
        }
        Price unit = unitKey == null ? null : unitPrices.get(unitKey);
        if (unit == null || !unit.isCurrent(now) || unit.recentSalePrice() > Long.MAX_VALUE / count) {
            return null;
        }
        return new Price(unit.recentSalePrice() * count, unit.sales(), unit.validUntil(), unit.latestSaleAt(), true);
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
            return new ListingPrice(0, 0, currentBin, freshSnapshot, false);
        }
        long suggested = currentBin > 0 ? resale(price, currentBin) : price.recentSalePrice();
        return new ListingPrice(suggested, price.sales(), currentBin, freshSnapshot, price.perUnit());
    }

    static <K> List<Candidate> rank(Map<K, List<Listing<K>>> byItem,
                                    java.util.function.Function<K, Price> priceFor,
                                    Set<String> sold, FlipSettings settings, long now) {
        List<Candidate> candidates = new ArrayList<>();
        for (var group : byItem.entrySet()) {
            var listing = lowestBin(group.getValue(), sold, null, now);
            if (listing == null || listing.price() < settings.minPrice() || listing.price() > settings.maxPrice()) {
                continue;
            }
            int competingBins = 0;
            long competingBin = 0;
            for (var other : group.getValue()) {
                if (!other.id().equals(listing.id()) && other.endsAt() > now && !sold.contains(other.id())) {
                    if (competingBins == 0) {
                        competingBin = other.price();
                    }
                    competingBins++;
                }
            }
            if (competingBins == 0) {
                continue;
            }
            Price price = priceFor.apply(group.getKey());
            int samples = price == null ? 0 : price.sales();
            if (samples < settings.minComparableSales()
                    || (price == null && (!settings.binComparisons() || competingBins < MIN_FALLBACK_BINS))) {
                continue;
            }

            long resale = price == null ? competingBin - 1 : resale(price, competingBin);
            if (resale <= listing.price()) {
                continue;
            }
            long profit = resale - listing.price() - estimatedFees(resale);
            if (profit > 0 && profit >= settings.minProfit()
                    && 100.0 * profit / listing.price() >= settings.minRoi()) {
                Method method = price == null ? Method.BIN_COMPARISON
                        : price.perUnit() ? Method.UNIT_SALES : Method.SALES;
                candidates.add(new Candidate(listing.id(), listing.name(), listing.price(), resale, profit,
                        samples, listing.endsAt(), method, price == null ? 0 : price.latestSaleAt(),
                        competingBins, competingBin));
            }
        }

        candidates.sort(Comparator.comparingDouble(Candidate::rankingScore).reversed()
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
