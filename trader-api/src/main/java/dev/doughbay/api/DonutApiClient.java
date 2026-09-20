package dev.doughbay.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.doughbay.api.ResponseParser.ParseResult;
import dev.doughbay.api.ResponseParser.ParsedListing;
import dev.doughbay.api.ResponseParser.ParsedSale;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;

/**
 * Read-only client for the Donut auction API: completed transactions and
 * active listings. Market data in, nothing out — this client performs no
 * in-game actions of any kind.
 */
public class DonutApiClient {

    private static final long MAX_RETRY_AFTER_MILLIS = 60_000;

    private final DonutApiConfig config;
    private final HttpSender sender;
    private final RateLimiter rateLimiter;
    private final RetryPolicy retryPolicy;
    private final Sleeper sleeper;

    /** Narrows this client's share of the request budget; see {@link RateLimiter#setBudget}. */
    public void setRequestBudget(int targetPerMinute, int hardMaxPerMinute) {
        rateLimiter.setBudget(targetPerMinute, hardMaxPerMinute);
    }
    private final LongSupplier wallClockMillis;
    private final LongSupplier monotonicNanos;
    private final DoubleSupplier jitterSource;
    private final ResponseParser parser = new ResponseParser();
    private final ObjectMapper mapper = new ObjectMapper();

    private final AtomicLong requestCount = new AtomicLong();
    private final AtomicLong errorCount = new AtomicLong();
    private final AtomicLong cooldownUntilEpochMillis = new AtomicLong();
    private final AtomicLong lastLatencyMillis = new AtomicLong(-1);
    private final AtomicLong lastSuccessAtEpochMillis = new AtomicLong();
    private final AtomicInteger lastStatusCode = new AtomicInteger();
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile ConnectionState connectionState = ConnectionState.IDLE;
    private volatile String lastError = "";

    public DonutApiClient(DonutApiConfig config) {
        this(config, RetryPolicy.defaults());
    }

    public DonutApiClient(DonutApiConfig config, RetryPolicy retryPolicy) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(retryPolicy, "retryPolicy");
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.requestTimeoutSeconds()))
                .build();
        this.config = config;
        this.sender = request -> http.send(request, HttpResponse.BodyHandlers.ofString());
        this.rateLimiter = new RateLimiter(config.targetRequestsPerMinute(),
                config.hardMaxRequestsPerMinute());
        this.retryPolicy = retryPolicy;
        this.sleeper = Thread::sleep;
        this.wallClockMillis = System::currentTimeMillis;
        this.monotonicNanos = System::nanoTime;
        this.jitterSource = () -> ThreadLocalRandom.current().nextDouble();
    }

    DonutApiClient(
            DonutApiConfig config,
            HttpSender sender,
            RateLimiter rateLimiter,
            RetryPolicy retryPolicy,
            Sleeper sleeper,
            LongSupplier wallClockMillis,
            LongSupplier monotonicNanos,
            DoubleSupplier jitterSource
    ) {
        this.config = Objects.requireNonNull(config, "config");
        this.sender = Objects.requireNonNull(sender, "sender");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.wallClockMillis = Objects.requireNonNull(wallClockMillis, "wallClockMillis");
        this.monotonicNanos = Objects.requireNonNull(monotonicNanos, "monotonicNanos");
        this.jitterSource = Objects.requireNonNull(jitterSource, "jitterSource");
    }

    /** GET /v1/auction/transactions/{page} — completed sales, newest first. */
    public ParseResult<ParsedSale> fetchTransactions(int page) throws ApiException, InterruptedException {
        JsonNode result = getResultArray(DonutApiEndpoints.transactions(config.baseUrl(), page));
        return parser.parseTransactions(result);
    }

    /**
     * GET /v1/auction/list/{page} with a JSON request body — active listings,
     * optionally filtered by a search term and sorted cheapest first so
     * arbitrage candidates surface early.
     */
    public ParseResult<ParsedListing> fetchListings(int page, String search)
            throws ApiException, InterruptedException {
        return fetchListings(page, search, "lowest_price");
    }

    /**
     * The same page under another sort. {@code recently_listed} is the live
     * feed of new listings, newest first, the stream the in-game watch page
     * shows; the API serves it without a client on the page.
     */
    public ParseResult<ParsedListing> fetchListings(int page, String search, String sort)
            throws ApiException, InterruptedException {
        String body = mapper.createObjectNode()
                .put("search", search == null ? "" : search)
                .put("sort", sort == null || sort.isBlank() ? "lowest_price" : sort)
                .toString();
        JsonNode result = getResultArray(
                DonutApiEndpoints.listings(config.baseUrl(), page), body);
        return parser.parseListings(result, wallClockMillis.getAsLong());
    }

    /**
     * GET /v1/stats/{user} — the player's balance, in whole coins.
     *
     * <p>The API types every stat as a string, and the values arrive formatted
     * for a chat window rather than for parsing, so the money field is
     * normalized here rather than by the caller.
     *
     * @return the balance, or empty when the field is absent or unreadable
     */
    public java.util.Map<String, String> fetchPlayerStats(String username)
            throws ApiException, InterruptedException {
        HttpRequest request = baseRequest(
                DonutApiEndpoints.playerStats(config.baseUrl(), username)).GET().build();
        JsonNode result = executeForObject(request);

        // Every stat is documented as a string, but returning the whole map
        // rather than one field means a renamed or reshaped profile can be
        // diagnosed from what came back instead of guessed at.
        java.util.Map<String, String> stats = new java.util.LinkedHashMap<>();
        java.util.Iterator<java.util.Map.Entry<String, JsonNode>> fields = result.fields();
        while (fields.hasNext()) {
            java.util.Map.Entry<String, JsonNode> field = fields.next();
            JsonNode value = field.getValue();
            if (value == null || value.isNull()) continue;
            stats.put(field.getKey(),
                    value.isTextual() ? value.textValue() : value.toString());
        }
        return java.util.Map.copyOf(stats);
    }

    /** The rank {@code /findplayer} shows for the player, or "" when the profile has none. */
    public String fetchRank(String username) throws ApiException, InterruptedException {
        HttpRequest request = baseRequest(
                DonutApiEndpoints.lookup(config.baseUrl(), username)).GET().build();
        // One attempt and no cooldown: a lookup the server cannot answer must
        // never slow the sales and listing feeds behind it.
        awaitCooldown();
        rateLimiter.acquire();
        requestCount.incrementAndGet();
        HttpResponse<String> response;
        try {
            response = sender.send(request);
        } catch (IOException e) {
            throw new ApiException("lookup failed: " + e.getClass().getSimpleName());
        }
        int status = response.statusCode();
        String body = response.body() == null ? "" : response.body().strip();
        if (status / 100 != 2) {
            throw new ApiException("lookup HTTP " + status + ": "
                    + redact(body.length() > 200 ? body.substring(0, 200) : body));
        }
        JsonNode root = parser.readTree(body);
        JsonNode result = root.has("result") && root.get("result").isObject() ? root.get("result") : root;
        JsonNode rank = result.get("rank");
        return rank == null || rank.isNull() ? "" : rank.asText("").strip();
    }

    /** Field names the balance may arrive under, most specific first. */
    private static final String[] MONEY_FIELDS = {
            "money", "balance", "coins", "cash"
    };

    /**
     * The balance from a profile map, or empty when the profile has none.
     *
     * <p>A zero balance arrives as an empty string. Observed live: a profile
     * with correct playtime, kills, and blocks returned {@code money=""} for a
     * player whose in-game balance was 0, while the sibling {@code
     * money_made_from_sell} and {@code money_spent_on_shop} fields both
     * returned {@code "0"}. Only the balance is spelled this way.
     *
     * <p>The blank is therefore read as zero, but only when the profile is
     * otherwise populated — a wholly empty profile is a player the API does
     * not know, which is genuinely unknown rather than broke.
     */
    public static java.util.OptionalLong balanceFrom(java.util.Map<String, String> stats) {
        boolean sawMoneyField = false;
        for (String field : MONEY_FIELDS) {
            String raw = stats.get(field);
            if (raw == null) continue;
            sawMoneyField = true;
            java.util.OptionalLong parsed = parseBalance(raw);
            if (parsed.isPresent()) return parsed;
        }
        if (sawMoneyField && hasPopulatedStats(stats)) {
            return java.util.OptionalLong.of(0);
        }
        return java.util.OptionalLong.empty();
    }

    /** Whether anything in the profile carries a real value. */
    private static boolean hasPopulatedStats(java.util.Map<String, String> stats) {
        for (java.util.Map.Entry<String, String> entry : stats.entrySet()) {
            String value = entry.getValue();
            if (value != null && !value.isBlank()) return true;
        }
        return false;
    }

    /**
     * Reads a balance that may arrive as "1,234,567", "1234567.89", or with a
     * currency symbol. Anything that is not a number after stripping the
     * decoration is treated as unknown rather than guessed at.
     */
    static java.util.OptionalLong parseBalance(String raw) {
        if (raw == null) return java.util.OptionalLong.empty();
        String cleaned = raw.strip().replace(",", "").replace("$", "").replace("_", "");
        if (cleaned.isEmpty()) return java.util.OptionalLong.empty();
        try {
            return java.util.OptionalLong.of(
                    new java.math.BigDecimal(cleaned)
                            .setScale(0, java.math.RoundingMode.DOWN)
                            .longValueExact());
        } catch (NumberFormatException | ArithmeticException e) {
            return java.util.OptionalLong.empty();
        }
    }

    /** Like {@link #execute} but for endpoints whose result is an object. */
    private JsonNode executeForObject(HttpRequest request)
            throws ApiException, InterruptedException {
        JsonNode root = executeRaw(request);
        JsonNode result = root.get("result");
        if (result == null || !result.isObject()) {
            throw new ApiException("Response has no result object");
        }
        return result;
    }


    private JsonNode getResultArray(URI uri) throws ApiException, InterruptedException {
        HttpRequest request = baseRequest(uri).GET().build();
        return execute(request);
    }

    private JsonNode getResultArray(URI uri, String jsonBody) throws ApiException, InterruptedException {
        HttpRequest request = baseRequest(uri)
                .header("Content-Type", "application/json")
                .method("GET", HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        return execute(request);
    }

    private HttpRequest.Builder baseRequest(URI uri) {
        return HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(config.requestTimeoutSeconds()))
                .header("Authorization", "Bearer " + config.apiKey())
                .header("Accept", "application/json");
    }

    /** Result-array endpoints: auction transactions and listings. */
    private JsonNode execute(HttpRequest request) throws ApiException, InterruptedException {
        JsonNode root = executeRaw(request);
        try {
            return parser.extractResultArray(root);
        } catch (ApiException e) {
            recordFailure(lastStatusCode.get(),
                    "Invalid API response: " + redact(e.getMessage()),
                    ConnectionState.DEGRADED);
            throw new ApiException(lastError);
        }
    }

    /**
     * The shared transport: rate limiting, retries, cooldown, and health
     * accounting. Returns the whole response body, because not every endpoint
     * wraps its payload in an array.
     */
    private JsonNode executeRaw(HttpRequest request) throws ApiException, InterruptedException {
        for (int attempt = 1; attempt <= retryPolicy.maxAttempts(); attempt++) {
            awaitCooldown();
            rateLimiter.acquire();
            requestCount.incrementAndGet();
            connectionState = ConnectionState.CONNECTING;

            HttpResponse<String> response;
            long started = monotonicNanos.getAsLong();
            try {
                response = sender.send(request);
            } catch (IOException e) {
                recordLatency(started);
                recordFailure(0, "I/O failure (" + e.getClass().getSimpleName() + ")",
                        ConnectionState.DEGRADED);
                if (attempt == retryPolicy.maxAttempts()) {
                    throw new ApiException("Request failed after " + attempt
                            + " attempts: " + lastError);
                }
                waitForRetry(backoffMillis(attempt));
                continue;
            } catch (InterruptedException e) {
                recordLatency(started);
                connectionState = ConnectionState.DEGRADED;
                lastError = "Request interrupted";
                Thread.currentThread().interrupt();
                throw e;
            }

            recordLatency(started);
            int status = response.statusCode();
            lastStatusCode.set(status);
            if (status / 100 == 2) {
                try {
                    JsonNode root = parser.readTree(response.body());
                    recordSuccess();
                    return root;
                } catch (ApiException e) {
                    recordFailure(status, "Invalid API response: " + redact(e.getMessage()),
                            ConnectionState.DEGRADED);
                    throw new ApiException(lastError);
                }
            }

            if (status == 401 || status == 403) {
                recordFailure(status, "Authentication rejected (HTTP " + status + ")",
                        ConnectionState.AUTHENTICATION_FAILED);
                throw new ApiException(lastError);
            }

            if (isRetryableStatus(status)) {
                long delay = retryDelayMillis(response, attempt);
                recordFailure(status, status == 429
                                ? "Rate limited by server (HTTP 429)"
                                : "Temporary server failure (HTTP " + status + ")",
                        ConnectionState.COOLDOWN);
                setCooldown(delay);
                if (attempt < retryPolicy.maxAttempts()) {
                    awaitCooldown();
                    continue;
                }
                throw new ApiException(lastError + " after " + attempt + " attempts");
            }

            recordFailure(status, "Unexpected HTTP " + status, ConnectionState.DEGRADED);
            throw new ApiException(lastError);
        }
        throw new ApiException("Request failed");
    }

    private void awaitCooldown() throws InterruptedException {
        while (true) {
            long remaining = cooldownUntilEpochMillis.get() - wallClockMillis.getAsLong();
            if (remaining <= 0) {
                cooldownUntilEpochMillis.set(0);
                return;
            }
            connectionState = ConnectionState.COOLDOWN;
            try {
                sleeper.sleep(remaining);
            } catch (InterruptedException e) {
                connectionState = ConnectionState.DEGRADED;
                lastError = "Retry wait interrupted";
                Thread.currentThread().interrupt();
                throw e;
            }
        }
    }

    private void waitForRetry(long delayMillis) throws InterruptedException {
        setCooldown(delayMillis);
        awaitCooldown();
    }

    private void setCooldown(long delayMillis) {
        long now = wallClockMillis.getAsLong();
        long safeDelay = Math.max(1, Math.min(delayMillis, MAX_RETRY_AFTER_MILLIS));
        cooldownUntilEpochMillis.set(saturatedAdd(now, safeDelay));
        connectionState = ConnectionState.COOLDOWN;
    }

    private long retryDelayMillis(HttpResponse<String> response, int failedAttempt) {
        if (response.statusCode() == 429) {
            Optional<String> retryAfter = response.headers().firstValue("Retry-After");
            if (retryAfter.isPresent()) {
                Long parsed = parseRetryAfterMillis(retryAfter.get());
                if (parsed != null) {
                    return parsed;
                }
            }
        }
        return backoffMillis(failedAttempt);
    }

    private Long parseRetryAfterMillis(String value) {
        String trimmed = value == null ? "" : value.trim();
        try {
            long seconds = Long.parseLong(trimmed);
            if (seconds < 0) return null;
            return Math.min(MAX_RETRY_AFTER_MILLIS, saturatedMultiply(seconds, 1_000));
        } catch (NumberFormatException ignored) {
            try {
                Instant retryAt = ZonedDateTime.parse(
                        trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                return Math.min(MAX_RETRY_AFTER_MILLIS,
                        Math.max(0, retryAt.toEpochMilli() - wallClockMillis.getAsLong()));
            } catch (DateTimeParseException invalidDate) {
                return null;
            }
        }
    }

    private long backoffMillis(int failedAttempt) {
        long initial = retryPolicy.initialBackoff().toMillis();
        int shift = Math.min(30, Math.max(0, failedAttempt - 1));
        long exponential = saturatedMultiply(initial, 1L << shift);
        long capped = Math.min(retryPolicy.maxBackoff().toMillis(), exponential);
        double random = Math.max(0.0, Math.min(1.0, jitterSource.getAsDouble()));
        double factor = 0.5 + random;
        return Math.max(1, Math.round(capped * factor));
    }

    private void recordLatency(long startedNanos) {
        long elapsed = Math.max(0, monotonicNanos.getAsLong() - startedNanos);
        lastLatencyMillis.set(Duration.ofNanos(elapsed).toMillis());
    }

    private void recordSuccess() {
        consecutiveFailures.set(0);
        lastError = "";
        lastSuccessAtEpochMillis.set(wallClockMillis.getAsLong());
        cooldownUntilEpochMillis.set(0);
        connectionState = ConnectionState.HEALTHY;
    }

    private void recordFailure(int status, String message, ConnectionState state) {
        errorCount.incrementAndGet();
        consecutiveFailures.incrementAndGet();
        lastStatusCode.set(status);
        lastError = redact(message);
        connectionState = state;
    }

    private static boolean isRetryableStatus(int status) {
        return status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
    }

    private static long saturatedMultiply(long left, long right) {
        if (left == 0 || right == 0) return 0;
        if (left > Long.MAX_VALUE / right) return Long.MAX_VALUE;
        return left * right;
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }

    private String redact(String message) {
        return message == null ? "" : message.replace(config.apiKey(), "[redacted]");
    }

    public ApiHealth health() {
        long now = wallClockMillis.getAsLong();
        long cooldownUntil = cooldownUntilEpochMillis.get();
        return new ApiHealth(requestCount.get(), errorCount.get(),
                rateLimiter.requestsInLastMinute(), config.targetRequestsPerMinute(),
                connectionState, cooldownUntil,
                Math.max(0, cooldownUntil - now), lastLatencyMillis.get(),
                lastStatusCode.get(), consecutiveFailures.get(),
                lastSuccessAtEpochMillis.get(), lastError);
    }

    public record ApiHealth(long totalRequests, long totalErrors,
                            int requestsInLastMinute, int budgetPerMinute,
                            ConnectionState state, long cooldownUntilEpochMillis,
                            long cooldownRemainingMillis, long lastLatencyMillis,
                            int lastStatusCode, int consecutiveFailures,
                            long lastSuccessAtEpochMillis, String lastError) {
    }

    public enum ConnectionState {
        IDLE,
        CONNECTING,
        HEALTHY,
        COOLDOWN,
        AUTHENTICATION_FAILED,
        DEGRADED
    }

    @FunctionalInterface
    interface HttpSender {
        HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

}
