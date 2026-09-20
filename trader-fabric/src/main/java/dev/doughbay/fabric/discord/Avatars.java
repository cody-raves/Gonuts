package dev.doughbay.fabric.discord;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Player heads for the panel header, so a page says whose it is at a glance.
 *
 * <p>The account name was already in the subtitle, but a name in small grey
 * text next to five other small grey figures is not what anybody reads first.
 * The face is, and it is the one thing on the page nobody has to decode.
 *
 * <p>Skins come from a public head service by name. A head is fetched once and
 * then kept: skins change rarely, the panel redraws every half minute, and a
 * page that cannot reach the internet must still render - so a failure is
 * remembered as "no head" for an hour rather than retried on every draw.
 */
final class Avatars {
    /** Heads are small; this is generous for one and trivial for a few. */
    private static final Duration TIMEOUT = Duration.ofSeconds(8);
    /** A face that could not be fetched is retried after this - short, so one
     * failed fetch does not blank the faces for an hour. A face that a restart
     * caught mid-warm-up used to stay blank until the next hour. */
    private static final long RETRY_MILLIS = 3 * 60_000L;
    /** Drawn at 30 pixels; asking for 64 keeps it sharp at the image's 2x scale. */
    private static final int SIZE = 64;

    private record Entry(BufferedImage head, long at) {
    }

    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();
    private static final java.util.Set<String> INFLIGHT = ConcurrentHashMap.newKeySet();
    private static final java.util.concurrent.ExecutorService POOL =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "doughbay-avatars");
                t.setDaemon(true);
                return t;
            });
    private static volatile HttpClient http;

    private Avatars() {
    }

    /**
     * One player's head, or null when there is not one to draw yet.
     *
     * <p>Never throws and never blocks: the header is decoration, and the panel
     * redraws every half minute, so a head is fetched on a background thread
     * and rendered on the next redraw once it arrives rather than holding the
     * render thread for the network. A fetch that fails is retried in minutes,
     * not held blank for an hour - which is what left the faces missing after a
     * restart caught the fetch before the connection was warm.
     */
    static BufferedImage head(String name) {
        if (name == null || name.isBlank()) return null;
        String key = name.strip().toLowerCase(Locale.ROOT);
        Entry cached = CACHE.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && cached.head() != null) return cached.head();
        boolean stale = cached == null || now - cached.at() >= RETRY_MILLIS;
        if (stale && INFLIGHT.add(key)) {
            POOL.submit(() -> {
                BufferedImage head = fetch(key);
                CACHE.put(key, new Entry(head, System.currentTimeMillis()));
                INFLIGHT.remove(key);
                dev.doughbay.fabric.DoughBayClient.LOGGER.debug(
                        "DoughBay avatar for {}: {}", key, head != null ? "fetched" : "none");
            });
        }
        return cached != null ? cached.head() : null;
    }

    private static BufferedImage fetch(String name) {
        try {
            HttpClient client = http;
            if (client == null) {
                client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
                http = client;
            }
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://minotar.net/helm/" + name + "/" + SIZE + ".png"))
                    .timeout(TIMEOUT)
                    .header("User-Agent", "DoughBay")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) return null;
            return ImageIO.read(new java.io.ByteArrayInputStream(response.body()));
        } catch (Exception | Error e) {
            // No head is a perfectly good outcome: the page draws the name.
            return null;
        }
    }
}
