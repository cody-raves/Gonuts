package dev.doughbay.core.automation;

import dev.doughbay.core.model.Listing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Pure UUID-first ownership and exact active-listing reconciliation. */
public final class ActiveListingReconciler {
    private static final Pattern UUID_32 = Pattern.compile("[0-9a-fA-F]{32}");
    private static final Pattern UUID_36 = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-"
                    + "[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}");
    private static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    /**
     * True only when the entire market scan began strictly after the evidence
     * it is meant to reconcile. A scan that began before the event remains
     * pre-event evidence even if it completed or was observed afterward.
     */
    public static boolean scanStartedAfterEvidence(long scanStartedAtMillis,
                                                   long evidenceObservedAtMillis) {
        return scanStartedAtMillis > 0
                && evidenceObservedAtMillis > 0
                && scanStartedAtMillis > evidenceObservedAtMillis;
    }

    public Reconciliation reconcile(List<Listing> activeListings, Target target,
                                    UUID localPlayerUuid, String localPlayerName) {
        if (target == null || localPlayerUuid == null
                || !PLAYER_NAME.matcher(nullToEmpty(localPlayerName)).matches()) {
            return new Reconciliation(List.of(), 1,
                    "Local player identity or exact listing target is unavailable");
        }
        List<Listing> source = activeListings == null ? List.of() : List.copyOf(activeListings);
        Map<String, Listing> own = new LinkedHashMap<>();
        int unknown = 0;
        for (Listing listing : source) {
            if (!target.matches(listing)) continue;
            Ownership ownership = ownershipOf(listing, localPlayerUuid, localPlayerName);
            if (ownership == Ownership.OWN) {
                if (listing.listingKey() == null || listing.listingKey().isBlank()) unknown++;
                else own.putIfAbsent(listing.listingKey(), listing);
            } else if (ownership == Ownership.UNKNOWN) {
                unknown++;
            }
        }
        String detail = unknown > 0
                ? "Exact active listings include unknown ownership"
                : own.isEmpty()
                ? "No exact own active listing"
                : own.size() == 1
                ? "One exact own active listing"
                : "Multiple exact own active listings";
        return new Reconciliation(new ArrayList<>(own.values()), unknown, detail);
    }

    public Ownership ownershipOf(Listing listing, UUID localPlayerUuid,
                                 String localPlayerName) {
        if (listing == null || localPlayerUuid == null) return Ownership.UNKNOWN;
        String rawUuid = nullToEmpty(listing.sellerUuid()).strip();
        if (!rawUuid.isEmpty()) {
            Optional<String> normalized = normalizeUuid(rawUuid);
            if (normalized.isEmpty()) return Ownership.UNKNOWN;
            boolean uuidOwn = normalized.get().equals(uuid32(localPlayerUuid));
            // A well-formed UUID that is not ours settles it. Bedrock players
            // reach the server through a proxy with names such as ".name",
            // which fail the Java name pattern; that must not turn a clearly
            // foreign listing into "unknown", which blocks every gate.
            String seller = nullToEmpty(listing.sellerName()).strip();
            String local = nullToEmpty(localPlayerName).strip();
            if (!uuidOwn) {
                // Our own name under a foreign UUID is a contradiction worth
                // refusing to interpret; any other name, well-formed or not,
                // is simply someone else.
                return !seller.isEmpty() && seller.equalsIgnoreCase(local)
                        ? Ownership.UNKNOWN : Ownership.OTHER;
            }
            if (!seller.isEmpty()) {
                if (!PLAYER_NAME.matcher(seller).matches()
                        || !PLAYER_NAME.matcher(local).matches()) return Ownership.UNKNOWN;
                if (!seller.equalsIgnoreCase(local)) return Ownership.UNKNOWN;
            }
            return Ownership.OWN;
        }

        // Seller name is a fallback only when the API supplied no UUID at all;
        // it never overrides a malformed or mismatched UUID.
        String seller = nullToEmpty(listing.sellerName()).strip();
        String local = nullToEmpty(localPlayerName).strip();
        if (!PLAYER_NAME.matcher(seller).matches()
                || !PLAYER_NAME.matcher(local).matches()) return Ownership.UNKNOWN;
        return seller.equalsIgnoreCase(local) ? Ownership.OWN : Ownership.OTHER;
    }

    private static Optional<String> normalizeUuid(String raw) {
        if (UUID_32.matcher(raw).matches()) return Optional.of(raw.toLowerCase(Locale.ROOT));
        if (!UUID_36.matcher(raw).matches()) return Optional.empty();
        try {
            return Optional.of(uuid32(UUID.fromString(raw)));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private static String uuid32(UUID uuid) {
        return uuid.toString().replace("-", "").toLowerCase(Locale.ROOT);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public enum Ownership { OWN, OTHER, UNKNOWN }

    public record Target(String itemId, int itemCount, long totalPrice) {
        public Target {
            if (itemId == null || itemId.isBlank() || itemCount <= 0 || totalPrice <= 0) {
                throw new IllegalArgumentException("An exact positive listing target is required");
            }
        }

        public boolean matches(Listing listing) {
            return listing != null
                    && itemId.equals(listing.itemId())
                    && itemCount == listing.itemCount()
                    && totalPrice == listing.totalPrice();
        }
    }

    public record Reconciliation(
            List<Listing> ownMatches,
            int unknownOwnershipMatches,
            String detail
    ) {
        public Reconciliation {
            ownMatches = ownMatches == null ? List.of() : List.copyOf(ownMatches);
            if (unknownOwnershipMatches < 0) {
                throw new IllegalArgumentException("unknownOwnershipMatches must be non-negative");
            }
            detail = detail == null ? "" : detail;
        }

        public boolean unambiguousZero() {
            return unknownOwnershipMatches == 0 && ownMatches.isEmpty();
        }

        public Optional<Listing> uniqueOwn() {
            return unknownOwnershipMatches == 0 && ownMatches.size() == 1
                    ? Optional.of(ownMatches.getFirst()) : Optional.empty();
        }

        public boolean ambiguous() {
            return unknownOwnershipMatches > 0 || ownMatches.size() > 1;
        }
    }
}
