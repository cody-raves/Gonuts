package dev.doughbay.api;

import java.net.URI;

/**
 * API connection settings. The key comes from the environment or a local
 * untracked config file — never from source control.
 */
public record DonutApiConfig(
        String baseUrl,
        String apiKey,
        int targetRequestsPerMinute,   // what we aim to stay under
        int hardMaxRequestsPerMinute,  // internal ceiling, below the official 250
        int requestTimeoutSeconds
) {
    public static final String DEFAULT_BASE_URL = "https://api.donutsmp.net";
    /** Conservative operating target below DonutSMP's published ceiling. */
    /**
     * What we aim to stay under. The published allowance is 250 a minute per
     * key; 180 left more than a quarter of it unused, which with one client on
     * the key is a slower sweep and staler prices for no gain.
     */
    public static final int MAX_TARGET_REQUESTS_PER_MINUTE = 210;
    /**
     * Non-overridable ceiling, kept below the official 250 on purpose. Going
     * over does not slow the feed, it stops it: the answer is 429 and a
     * retry-after, and a burst that lands either side of the server's minute
     * can breach a limit our own count says we are inside. Fifteen requests of
     * margin is what pays for that difference in clocks.
     */
    public static final int MAX_HARD_REQUESTS_PER_MINUTE = 235;

    public static DonutApiConfig withDefaults(String apiKey) {
        return withDefaults(DEFAULT_BASE_URL, apiKey);
    }

    /**
     * The same defaults against a different address.
     *
     * <p>A service answering in DonutSMP's shape is indistinguishable from
     * DonutSMP as far as everything downstream is concerned - same parser,
     * same medians, same decisions - so pointing somewhere else is a matter
     * of one string rather than a second client. The budget stays as it is
     * either way: a proxy that fans one fetch out to many readers is helped
     * by callers that ask politely, not hindered.
     */
    public static DonutApiConfig withDefaults(String baseUrl, String apiKey) {
        return new DonutApiConfig(
                baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl.strip(), apiKey,
                MAX_TARGET_REQUESTS_PER_MINUTE, MAX_HARD_REQUESTS_PER_MINUTE, 20);
    }

    public DonutApiConfig {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("API base URL is missing");
        }
        URI base;
        try {
            base = URI.create(baseUrl);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("API base URL is invalid", e);
        }
        if (!("https".equalsIgnoreCase(base.getScheme())
                || "http".equalsIgnoreCase(base.getScheme()))
                || base.getHost() == null || base.getUserInfo() != null
                || base.getQuery() != null || base.getFragment() != null) {
            throw new IllegalArgumentException(
                    "API base URL must be an HTTP(S) origin without credentials, query, or fragment");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException(
                    "API key missing. Set DONUT_API_KEY or add it to the local config file.");
        }
        if (targetRequestsPerMinute < 1 || hardMaxRequestsPerMinute < 1) {
            throw new IllegalArgumentException("request budgets must be positive");
        }
        if (targetRequestsPerMinute > MAX_TARGET_REQUESTS_PER_MINUTE) {
            throw new IllegalArgumentException("target request budget must be <= "
                    + MAX_TARGET_REQUESTS_PER_MINUTE + " per minute");
        }
        if (hardMaxRequestsPerMinute > MAX_HARD_REQUESTS_PER_MINUTE) {
            throw new IllegalArgumentException("hard request ceiling must be <= "
                    + MAX_HARD_REQUESTS_PER_MINUTE + " per minute");
        }
        if (hardMaxRequestsPerMinute < targetRequestsPerMinute) {
            throw new IllegalArgumentException("hard max must be >= target budget");
        }
        if (requestTimeoutSeconds < 1) {
            throw new IllegalArgumentException("request timeout must be positive");
        }
    }

    /** For logs and errors: never leak the real key. */
    public String redactedKey() {
        if (apiKey.length() <= 6) return "***";
        return apiKey.substring(0, 3) + "***" + apiKey.substring(apiKey.length() - 2);
    }
}
