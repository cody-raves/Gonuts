package dev.doughbay.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.net.ssl.SSLSession;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Offline transport tests: no test in this class opens a network connection. */
class DonutApiClientTest {

    private static final String SECRET = "top-secret-bearer-token";
    private static final String TRANSACTION_PAGE = """
            {"status":200,"result":[
              {"unixMillisDateSold":1700000000000,"price":9000,
               "seller":{"uuid":"seller-1","name":"Seller"},
               "item":{"id":"minecraft:ender_pearl","count":16}}
            ]}
            """;
    private static final String LISTING_PAGE = """
            {"status":200,"result":[
              {"price":4100,"seller":{"uuid":"seller-2","name":"Seller"},
               "time_left":3600000,
               "item":{"id":"minecraft:ender_pearl","count":16}}
            ]}
            """;

    @Test
    void listingUsesDocumentedGetWithJsonBodyAndBearerHeader() throws Exception {
        FakeSender sender = new FakeSender().respond(200, LISTING_PAGE);
        TestRuntime runtime = new TestRuntime(sender, RetryPolicy.defaults());

        var parsed = runtime.client.fetchListings(3, "ender \"pearl\"");

        assertEquals(1, parsed.records().size());
        HttpRequest request = sender.requests.getFirst();
        assertEquals("GET", request.method());
        assertEquals(URI.create("https://example.test/v1/auction/list/3"), request.uri());
        assertEquals("Bearer " + SECRET,
                request.headers().firstValue("Authorization").orElseThrow());
        assertEquals("application/json",
                request.headers().firstValue("Content-Type").orElseThrow());

        JsonNode body = new ObjectMapper().readTree(readBody(request));
        assertEquals("ender \"pearl\"", body.path("search").asText());
        assertEquals("lowest_price", body.path("sort").asText());
    }

    @Test
    void transactionUsesCentralizedGetEndpointWithoutBody() throws Exception {
        FakeSender sender = new FakeSender().respond(200, TRANSACTION_PAGE);
        TestRuntime runtime = new TestRuntime(sender, RetryPolicy.defaults());

        assertEquals(1, runtime.client.fetchTransactions(10).records().size());

        HttpRequest request = sender.requests.getFirst();
        assertEquals("GET", request.method());
        assertEquals(URI.create("https://example.test/v1/auction/transactions/10"), request.uri());
        assertTrue(request.bodyPublisher().isEmpty());
        assertEquals(URI.create("https://example.test/v1/auction/list/2"),
                DonutApiEndpoints.listings("https://example.test/", 2));
    }

    @Test
    void invalidPageFailsBeforeTransportIsCalled() {
        FakeSender sender = new FakeSender();
        TestRuntime runtime = new TestRuntime(sender, RetryPolicy.defaults());

        assertThrows(IllegalArgumentException.class, () -> runtime.client.fetchTransactions(0));
        assertTrue(sender.requests.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 500, 502, 503, 504})
    void transientStatusesRetryThenRecover(int status) throws Exception {
        FakeSender sender = new FakeSender();
        if (status == 429) {
            sender.respond(status, "{}", Map.of("Retry-After", List.of("1")));
        } else {
            sender.respond(status, "{}");
        }
        sender.respond(200, TRANSACTION_PAGE);
        TestRuntime runtime = new TestRuntime(sender,
                new RetryPolicy(3, Duration.ofMillis(100), Duration.ofMillis(400)));

        assertEquals(1, runtime.client.fetchTransactions(1).records().size());

        assertEquals(2, sender.requests.size());
        assertEquals(2, runtime.client.health().totalRequests());
        assertEquals(1, runtime.client.health().totalErrors());
        assertEquals(DonutApiClient.ConnectionState.HEALTHY, runtime.client.health().state());
        assertEquals(0, runtime.client.health().consecutiveFailures());
        assertEquals(status == 429 ? 1_000 : 100, runtime.sleeps.getFirst());
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void authenticationFailuresAreNeverRetried(int status) {
        FakeSender sender = new FakeSender()
                .respond(status, "contains " + SECRET)
                .respond(200, TRANSACTION_PAGE);
        TestRuntime runtime = new TestRuntime(sender, RetryPolicy.defaults());

        ApiException failure = assertThrows(ApiException.class,
                () -> runtime.client.fetchTransactions(1));

        assertEquals(1, sender.requests.size());
        assertTrue(runtime.sleeps.isEmpty());
        assertFalse(failure.toString().contains(SECRET));
        assertEquals(DonutApiClient.ConnectionState.AUTHENTICATION_FAILED,
                runtime.client.health().state());
    }

    @Test
    void nonTransientClientErrorIsNeverRetried() {
        FakeSender sender = new FakeSender()
                .respond(400, "contains " + SECRET)
                .respond(200, TRANSACTION_PAGE);
        TestRuntime runtime = new TestRuntime(sender, RetryPolicy.defaults());

        ApiException failure = assertThrows(ApiException.class,
                () -> runtime.client.fetchTransactions(1));

        assertEquals(1, sender.requests.size());
        assertEquals("Unexpected HTTP 400", failure.getMessage());
        assertFalse(failure.toString().contains(SECRET));
    }

    @Test
    void ioFailureRetriesWithExponentialJitterThenSucceeds() throws Exception {
        FakeSender sender = new FakeSender()
                .fail(new IOException("temporary " + SECRET))
                .fail(new IOException("temporary again"))
                .respond(200, TRANSACTION_PAGE);
        TestRuntime runtime = new TestRuntime(sender,
                new RetryPolicy(3, Duration.ofMillis(100), Duration.ofMillis(400)));

        assertEquals(1, runtime.client.fetchTransactions(1).records().size());

        assertEquals(List.of(100L, 200L), runtime.sleeps);
        assertEquals(3, runtime.client.health().totalRequests());
        assertEquals(2, runtime.client.health().totalErrors());
        assertEquals(DonutApiClient.ConnectionState.HEALTHY, runtime.client.health().state());
    }

    @Test
    void exhaustedIoFailureCannotLeakBearerSecretThroughCause() {
        FakeSender sender = new FakeSender()
                .fail(new IOException("request Authorization: Bearer " + SECRET))
                .fail(new IOException("uri?apiKey=" + SECRET));
        TestRuntime runtime = new TestRuntime(sender,
                new RetryPolicy(2, Duration.ofMillis(100), Duration.ofMillis(100)));

        ApiException failure = assertThrows(ApiException.class,
                () -> runtime.client.fetchTransactions(1));

        assertFalse(failure.toString().contains(SECRET));
        assertNull(failure.getCause());
        assertFalse(runtime.client.health().lastError().contains(SECRET));
        assertEquals(0, runtime.client.health().lastStatusCode());
    }

    @Test
    void finalRateLimitPublishesRetryAfterCooldown() {
        FakeSender sender = new FakeSender()
                .respond(429, "{}", Map.of("Retry-After", List.of("2")));
        TestRuntime runtime = new TestRuntime(sender,
                new RetryPolicy(1, Duration.ofMillis(100), Duration.ofMillis(100)));

        assertThrows(ApiException.class, () -> runtime.client.fetchTransactions(1));

        DonutApiClient.ApiHealth health = runtime.client.health();
        assertEquals(DonutApiClient.ConnectionState.COOLDOWN, health.state());
        assertEquals(2_000, health.cooldownRemainingMillis());
        assertEquals(runtime.wall.get() + 2_000, health.cooldownUntilEpochMillis());
        assertEquals(429, health.lastStatusCode());
    }

    @Test
    void healthReportsLastLatencyAndSuccessTime() throws Exception {
        AtomicLong wall = new AtomicLong(1_700_000_000_000L);
        AtomicLong nanos = new AtomicLong(10_000_000L);
        DonutApiClient.HttpSender sender = request -> {
            nanos.addAndGet(25_000_000L);
            return new StubResponse(request, 200, TRANSACTION_PAGE, Map.of());
        };
        DonutApiClient client = new DonutApiClient(config(), sender,
                new RateLimiter(180, 220, wall::get), RetryPolicy.defaults(),
                millis -> wall.addAndGet(millis), wall::get, nanos::get, () -> 0.5);

        client.fetchTransactions(1);

        DonutApiClient.ApiHealth health = client.health();
        assertEquals(25, health.lastLatencyMillis());
        assertEquals(200, health.lastStatusCode());
        assertEquals(wall.get(), health.lastSuccessAtEpochMillis());
        assertEquals(DonutApiClient.ConnectionState.HEALTHY, health.state());
        assertTrue(health.lastError().isEmpty());
    }

    @Test
    void configRejectsCredentialsEmbeddedInBaseUrl() {
        assertThrows(IllegalArgumentException.class, () -> new DonutApiConfig(
                "https://user:password@example.test", SECRET, 180, 220, 20));
    }

    private static DonutApiConfig config() {
        return new DonutApiConfig("https://example.test", SECRET, 180, 220, 20);
    }

    private static String readBody(HttpRequest request) {
        HttpRequest.BodyPublisher publisher = request.bodyPublisher().orElseThrow();
        BodyCollector collector = new BodyCollector();
        publisher.subscribe(collector);
        return collector.body();
    }

    private static final class TestRuntime {
        final AtomicLong wall = new AtomicLong(1_700_000_000_000L);
        final AtomicLong nanos = new AtomicLong(1_000_000L);
        final List<Long> sleeps = new ArrayList<>();
        final DonutApiClient client;

        TestRuntime(FakeSender sender, RetryPolicy policy) {
            client = new DonutApiClient(config(), sender,
                    new RateLimiter(180, 220, wall::get), policy,
                    millis -> {
                        sleeps.add(millis);
                        wall.addAndGet(millis);
                        nanos.addAndGet(millis * 1_000_000L);
                    }, wall::get, nanos::get, () -> 0.5);
        }
    }

    private record StubSpec(int status, String body, Map<String, List<String>> headers) {
    }

    private static final class FakeSender implements DonutApiClient.HttpSender {
        final Deque<Object> outcomes = new ArrayDeque<>();
        final List<HttpRequest> requests = new ArrayList<>();

        FakeSender respond(int status, String body) {
            return respond(status, body, Map.of());
        }

        FakeSender respond(int status, String body, Map<String, List<String>> headers) {
            outcomes.addLast(new StubSpec(status, body, headers));
            return this;
        }

        FakeSender fail(IOException failure) {
            outcomes.addLast(failure);
            return this;
        }

        @Override
        public HttpResponse<String> send(HttpRequest request) throws IOException {
            requests.add(request);
            Object outcome = outcomes.removeFirst();
            if (outcome instanceof IOException failure) throw failure;
            StubSpec response = (StubSpec) outcome;
            return new StubResponse(request, response.status(), response.body(), response.headers());
        }
    }

    private record StubResponse(HttpRequest request, int statusCode, String body,
                                Map<String, List<String>> rawHeaders)
            implements HttpResponse<String> {
        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(rawHeaders, (name, value) -> true);
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_2;
        }
    }

    private static final class BodyCollector implements Flow.Subscriber<ByteBuffer> {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Throwable failure;

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(ByteBuffer item) {
            byte[] chunk = new byte[item.remaining()];
            item.get(chunk);
            bytes.writeBytes(chunk);
        }

        @Override
        public void onError(Throwable throwable) {
            failure = throwable;
        }

        @Override
        public void onComplete() {
        }

        String body() {
            if (failure != null) throw new AssertionError(failure);
            return bytes.toString(StandardCharsets.UTF_8);
        }
    }

    // Balance parsing. Observed live: DonutSMP returns money="" for a zero
    // balance while its sibling money_* fields return "0".

    @Test
    void nonZeroBalanceIsParsed() {
        assertEquals(88L, DonutApiClient.balanceFrom(
                Map.of("money", "88", "kills", "0")).orElse(-1));
    }

    @Test
    void formattedBalanceIsParsed() {
        assertEquals(1_234_567L, DonutApiClient.balanceFrom(
                Map.of("money", "$1,234,567", "kills", "0")).orElse(-1));
        assertEquals(1_500L, DonutApiClient.balanceFrom(
                Map.of("money", "1500.75", "kills", "0")).orElse(-1));
    }

    @Test
    void blankBalanceInAPopulatedProfileIsZero() {
        // The live shape: playtime and blocks are real, money is empty, and
        // the player's in-game balance was 0.
        assertEquals(0L, DonutApiClient.balanceFrom(Map.of(
                "money", "", "playtime", "1888822", "broken_blocks", "5")).orElse(-1));
    }

    @Test
    void blankBalanceInAnEmptyProfileStaysUnknown() {
        // Nothing populated means the API does not know this player, which is
        // unknown rather than broke, and must not render as zero coins.
        assertTrue(DonutApiClient.balanceFrom(Map.of("money", "")).isEmpty());
        assertTrue(DonutApiClient.balanceFrom(Map.of()).isEmpty());
    }

    @Test
    void placeholderBalanceIsNotMistakenForANumber() {
        // If the endpoint ever serves its own schema example, "string" must
        // not become a balance.
        assertTrue(DonutApiClient.parseBalance("string").isEmpty());
        assertTrue(DonutApiClient.parseBalance("1.2M").isEmpty());
    }
}
