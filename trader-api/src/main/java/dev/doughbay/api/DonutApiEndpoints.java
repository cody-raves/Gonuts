package dev.doughbay.api;

import java.net.URI;

/**
 * Documented DonutSMP auction endpoints used by DoughBay.
 *
 * <p>Keeping paths here prevents request-method or route drift between
 * collectors. This module intentionally defines only the two read-only
 * auction routes it currently consumes.</p>
 */
public final class DonutApiEndpoints {

    private static final String TRANSACTIONS = "/v1/auction/transactions/";
    private static final String LISTINGS = "/v1/auction/list/";
    private static final String STATS = "/v1/stats/";
    private static final String LOOKUP = "/v1/lookup/";

    private DonutApiEndpoints() {
    }

    public static URI transactions(String baseUrl, int page) {
        return resolve(baseUrl, TRANSACTIONS + requirePage(page));
    }

    public static URI listings(String baseUrl, int page) {
        return resolve(baseUrl, LISTINGS + requirePage(page));
    }

    /** GET /v1/lookup/{user} — rank and location, as {@code /findplayer} shows them. */
    public static URI lookup(String baseUrl, String username) {
        return resolve(baseUrl, LOOKUP + requireUsername(username));
    }

    /** GET /v1/stats/{user} — the player profile behind {@code /stats}. */
    public static URI playerStats(String baseUrl, String username) {
        return resolve(baseUrl, STATS + requireUsername(username));
    }

    /**
     * Minecraft usernames are 3-16 of [A-Za-z0-9_]. Validating here keeps
     * anything else out of the path rather than URL-encoding a surprise into
     * a request.
     */
    private static String requireUsername(String username) {
        String trimmed = username == null ? "" : username.strip();
        if (!trimmed.matches("[A-Za-z0-9_]{1,16}")) {
            throw new IllegalArgumentException("Invalid Minecraft username");
        }
        return trimmed;
    }

    private static int requirePage(int page) {
        if (page < 1) {
            throw new IllegalArgumentException("Auction page must be at least 1");
        }
        return page;
    }

    private static URI resolve(String baseUrl, String path) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("API base URL is missing");
        }
        String normalizedBase = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
        return URI.create(normalizedBase + path);
    }
}
