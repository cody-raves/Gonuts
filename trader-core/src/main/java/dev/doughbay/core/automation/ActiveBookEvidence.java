package dev.doughbay.core.automation;

import dev.doughbay.core.model.Listing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Validates and fingerprints one exhaustive active-book scan.
 *
 * <p>Observed timestamps and countdowns are intentionally excluded from the
 * fingerprint. Any identity, seller, count, or price change is material. Any
 * duplicate key makes the scan unusable: a deterministic fallback key can
 * collide for two distinct but materially identical auctions, so equality is
 * not proof that the second row is pagination overlap.
 */
public final class ActiveBookEvidence {

    public Analysis analyze(String requestedItemId, List<Listing> listings) {
        if (requestedItemId == null || requestedItemId.isBlank()) {
            return Analysis.invalid("Requested item id is missing");
        }
        List<Listing> source = listings == null ? List.of() : List.copyOf(listings);
        Map<String, Listing> byKey = new LinkedHashMap<>();
        for (Listing listing : source) {
            if (listing == null || !listing.isValid()
                    || !requestedItemId.equals(listing.itemId())) {
                return Analysis.invalid("Coverage contains a null, malformed, or cross-item listing");
            }
            String key = safe(listing.listingKey()).strip();
            if (key.isEmpty()) {
                return Analysis.invalid("Coverage contains a listing without a stable key");
            }
            Listing prior = byKey.putIfAbsent(key, listing);
            if (prior != null) {
                return Analysis.invalid("Coverage repeats listing key " + key
                        + "; exact multiplicity is unknowable");
            }
        }

        List<Listing> unique = new ArrayList<>(byKey.values());
        unique.sort(Comparator.comparing(ActiveBookEvidence::canonicalRow));
        return new Analysis(true, List.copyOf(unique), fingerprint(unique),
                "Complete book has " + unique.size() + " unique listings");
    }

    public boolean consecutiveStable(Analysis first, long firstCompletedAt,
                                     Analysis second, long secondScanStartedAt) {
        return first != null && second != null
                && first.valid() && second.valid()
                && firstCompletedAt > 0
                && secondScanStartedAt > firstCompletedAt
                && first.fingerprint().equals(second.fingerprint());
    }

    /**
     * Stability of only the rows a phase depends on.
     *
     * <p>A whole book on a bot-traded item changes several times a second, so
     * two identical full scans may never occur. What must hold still is the
     * target listing before a buy, or the local player's own rows around a
     * listing; every other row may churn freely.
     */
    public boolean consecutiveStable(Analysis first, long firstCompletedAt,
                                     Analysis second, long secondScanStartedAt,
                                     Predicate<Listing> relevant) {
        return first != null && second != null
                && first.valid() && second.valid()
                && firstCompletedAt > 0
                && secondScanStartedAt > firstCompletedAt
                && fingerprintOf(first, relevant).equals(fingerprintOf(second, relevant));
    }

    /** Fingerprint of the rows of a valid book that satisfy {@code relevant}. */
    public String fingerprintOf(Analysis book, Predicate<Listing> relevant) {
        if (book == null || !book.valid()) return "";
        List<Listing> subset = new ArrayList<>();
        for (Listing listing : book.listings()) {
            if (relevant.test(listing)) subset.add(listing);
        }
        subset.sort(Comparator.comparing(ActiveBookEvidence::canonicalRow));
        return subset.size() + ":" + fingerprint(subset);
    }

    private static String fingerprint(List<Listing> listings) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Listing listing : listings) {
                byte[] row = canonicalRow(listing).getBytes(StandardCharsets.UTF_8);
                digest.update((byte) (row.length >>> 24));
                digest.update((byte) (row.length >>> 16));
                digest.update((byte) (row.length >>> 8));
                digest.update((byte) row.length);
                digest.update(row);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM does not provide SHA-256", impossible);
        }
    }

    private static String canonicalRow(Listing listing) {
        return field(listing.listingKey())
                + field(listing.itemKey())
                + field(listing.itemId())
                + field(Integer.toString(listing.itemCount()))
                + field(Long.toString(listing.totalPrice()))
                + field(safe(listing.sellerUuid()).strip().toLowerCase(Locale.ROOT))
                + field(safe(listing.sellerName()).strip().toLowerCase(Locale.ROOT));
    }

    private static String field(String value) {
        String safe = safe(value);
        return safe.length() + ":" + safe;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public record Analysis(boolean valid, List<Listing> listings,
                           String fingerprint, String detail) {
        public Analysis {
            listings = listings == null ? List.of() : List.copyOf(listings);
            fingerprint = fingerprint == null ? "" : fingerprint;
            detail = detail == null ? "" : detail;
            if (valid && fingerprint.isBlank()) {
                throw new IllegalArgumentException("Valid book evidence requires a fingerprint");
            }
        }

        public static Analysis invalid(String detail) {
            return new Analysis(false, List.of(), "", detail);
        }
    }
}
