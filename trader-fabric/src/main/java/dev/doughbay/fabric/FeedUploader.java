package dev.doughbay.fabric;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPOutputStream;

/**
 * Sends what the client sees in the game back to the DoughBay API.
 *
 * <p>Donut's API says nothing about the order house - it is a page in the
 * game and nowhere else - so a client that reads it is the only source there
 * is. Every sweep goes home as one call: the whole book as this client saw it,
 * with the time it was seen, so the API can hold an order book that the
 * public feed cannot give anyone.
 *
 * <p>Never on the game thread, never blocking a trade: a sweep is queued and
 * sent from one background thread. A failed send is retried with a growing
 * wait and dropped after an hour, because a stale book is worse than a gap -
 * the API merges by observation time, and an hour-old sweep can only lose to
 * fresher ones. An API that does not take uploads yet (404) is left alone for
 * half an hour at a time rather than hit on every sweep.
 */
public final class FeedUploader {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private static final long DROP_AFTER_MILLIS = 3_600_000L;
    private static final String MOD_VERSION = "0.1.0";

    private final String baseUrl;
    private final String token;
    private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "doughbay-feed-upload");
        t.setDaemon(true);
        return t;
    });

    /** One thing waiting to go: where, what, when it was first tried. */
    private record Batch(String path, byte[] gzipJson, long queuedAt, int attempts) {}

    private final ArrayDeque<Batch> pending = new ArrayDeque<>();
    private volatile long holdOffUntil;
    private volatile String lastOutcome = "";
    private volatile long sent;
    private volatile long dropped;

    public FeedUploader(String baseUrl, String token) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.token = token == null ? "" : token;
    }

    private static boolean enabled() {
        return Tuning.get("upload.enabled") >= 0.5;
    }

    /** Short line for the status pages: what went, what did not. */
    public String status() {
        if (!enabled()) return "off";
        return sent + " sent" + (dropped > 0 ? ", " + dropped + " dropped" : "")
                + (lastOutcome.isEmpty() ? "" : " · " + lastOutcome);
    }

    /**
     * A sweep of the order house, as one call home.
     *
     * @param full whether every page was walked, so the API may treat orders
     *             it holds that are missing here as gone
     */
    public void ordersSwept(List<AutomatedExecutionDriver.OrderRow> orders, int pages, boolean full, long observedAt) {
        if (!enabled() || baseUrl.isEmpty() || token.isEmpty() || orders == null) return;
        // Nothing read is not "the book is empty": the client was not at the
        // order house when the read fired. Not worth a round trip.
        if (orders.isEmpty() && pages == 0) return;
        ObjectNode body = MAPPER.createObjectNode();
        body.put("client", DoughBayClient.account());
        body.put("mod", MOD_VERSION);
        body.put("observed_at", observedAt);
        body.put("pages", pages);
        body.put("full_sweep", full);
        ArrayNode rows = body.putArray("orders");
        for (AutomatedExecutionDriver.OrderRow o : orders) {
            ObjectNode row = rows.addObject();
            row.put("item_id", o.itemId());
            row.put("item_key", o.itemKey());
            if (o.descriptorJson() != null && !o.descriptorJson().isBlank()) row.put("descriptor", o.descriptorJson());
            ArrayNode parts = row.putArray("parts");
            for (String p : o.parts()) parts.add(p);
            // Everything the tooltip says below the price, verbatim. Stored
            // and echoed by the API, never part of an order's identity.
            ArrayNode tail = row.putArray("tail");
            for (String t : o.tail()) tail.add(t);
            row.put("unit_price", o.unitPrice());
            row.put("delivered", o.delivered());
            row.put("total", o.total());
            row.put("remaining", Math.max(0, o.total() - o.delivered()));
            row.put("page", o.page());
            row.put("observed_at", observedAt);
        }
        enqueue("/v1/ingest/orders", body);
    }

    private void enqueue(String path, ObjectNode body) {
        byte[] gz;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (GZIPOutputStream z = new GZIPOutputStream(out)) {
                z.write(MAPPER.writeValueAsBytes(body));
            }
            gz = out.toByteArray();
        } catch (Exception e) {
            DoughBayClient.LOGGER.warn("DoughBay upload could not encode a batch: {}", e.toString());
            return;
        }
        synchronized (pending) {
            pending.addLast(new Batch(path, gz, System.currentTimeMillis(), 0));
            while (pending.size() > 50) {
                pending.removeFirst();
                dropped++;
            }
        }
        sender.submit(this::drain);
    }

    private void drain() {
        while (true) {
            long now = System.currentTimeMillis();
            if (now < holdOffUntil) return;
            Batch b;
            synchronized (pending) {
                b = pending.peekFirst();
                if (b == null) return;
                if (now - b.queuedAt() > DROP_AFTER_MILLIS) {
                    pending.removeFirst();
                    dropped++;
                    continue;
                }
            }
            int code = send(b);
            if (code / 100 == 2) {
                synchronized (pending) {
                    pending.removeFirst();
                }
                sent++;
                if (!"ok".equals(lastOutcome)) {
                    DoughBayClient.LOGGER.info("DoughBay upload: the API is taking order sweeps ({} sent)", sent);
                }
                lastOutcome = "ok";
                continue;
            }
            if (code == 404 || code == 501) {
                // No ingest route yet. Not an error worth a line every sweep.
                if (!lastOutcome.startsWith("no ingest")) {
                    DoughBayClient.LOGGER.info("DoughBay upload: the API does not take uploads yet ({}); trying again in 30 min", code);
                }
                lastOutcome = "no ingest route (" + code + ")";
                holdOffUntil = now + 30 * 60_000L;
                return;
            }
            if (code == 401 || code == 403) {
                lastOutcome = "token refused (" + code + ")";
                DoughBayClient.LOGGER.warn("DoughBay upload: the API refused this client's token ({}); trying again in 30 min", code);
                holdOffUntil = now + 30 * 60_000L;
                return;
            }
            // Anything else: keep it, back off, try again.
            Batch retry = new Batch(b.path(), b.gzipJson(), b.queuedAt(), b.attempts() + 1);
            synchronized (pending) {
                pending.removeFirst();
                pending.addFirst(retry);
            }
            long wait = Math.min(10 * 60_000L, 15_000L * (1L << Math.min(6, retry.attempts())));
            lastOutcome = "retrying (" + code + ")";
            holdOffUntil = now + wait;
            DoughBayClient.LOGGER.warn("DoughBay upload: {} answered {}; trying again in {} s", b.path(), code, wait / 1000);
            return;
        }
    }

    /** HTTP status, or 0 for a transport failure. */
    private int send(Batch b) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + b.path()))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .header("Content-Encoding", "gzip")
                    .header("X-DoughBay-Mod", MOD_VERSION)
                    .header("X-DoughBay-Client", DoughBayClient.account())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(b.gzipJson()))
                    .build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return res.statusCode();
        } catch (Exception e) {
            lastOutcome = "unreachable";
            return 0;
        }
    }

    /**
     * Waits for the queue to go out - used on shutdown so the last sweep is
     * not lost with the process; bounded, never a hang.
     */
    public void flush() {
        sender.submit(this::drain);
        sender.shutdown();
        try {
            sender.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
