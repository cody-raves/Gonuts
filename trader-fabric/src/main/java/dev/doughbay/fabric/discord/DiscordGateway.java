package dev.doughbay.fabric.discord;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * One gateway connection: identify, heartbeat, deliver interactions, and
 * come back on its own after any drop. No intents are needed for button
 * presses and form submits, which is all this bot listens for.
 */
final class DiscordGateway implements WebSocket.Listener {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Logger log;
    private final String token;
    private final DiscordRest rest;
    private final Consumer<JsonNode> onInteraction;
    private final Consumer<String> onReady;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final StringBuilder frame = new StringBuilder();
    private volatile WebSocket socket;
    private volatile long heartbeatMillis = 41_250;
    private volatile long lastSeq = -1;
    private volatile String sessionId = "";
    private volatile String resumeUrl = "";
    private volatile boolean acked = true;
    private volatile String status = "not connected";
    private Thread heart;
    private int backoff = 2;

    DiscordGateway(Logger log, String token, DiscordRest rest, Consumer<JsonNode> onInteraction, Consumer<String> onReady) {
        this.log = log;
        this.token = token;
        this.rest = rest;
        this.onInteraction = onInteraction;
        this.onReady = onReady;
    }

    String status() {
        return status;
    }

    void start() {
        Thread t = new Thread(this::connectLoop, "doughbay-discord-gateway");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Sets what Discord shows under the bot's name: "Watching the auction
     * house", "Selling map x64 for $420K", and so on. Discord allows a few
     * of these every twenty seconds, so the bridge sends only changes.
     */
    void presence(String text, int activityType) {
        WebSocket ws = socket;
        if (ws == null || text == null || text.isBlank()) return;
        ObjectNode d = MAPPER.createObjectNode();
        d.putNull("since");
        d.put("status", "online");
        d.put("afk", false);
        ObjectNode activity = d.putArray("activities").addObject();
        activity.put("name", text.length() > 128 ? text.substring(0, 128) : text);
        activity.put("type", activityType);
        try {
            send(ws, 3, d);
        } catch (RuntimeException e) {
            log.debug("DoughBay Discord presence: {}", e.toString());
        }
    }

    void stop() {
        running.set(false);
        WebSocket s = socket;
        if (s != null) s.sendClose(1000, "bye");
    }

    private void connectLoop() {
        while (running.get()) {
            try {
                String url = !resumeUrl.isBlank() ? resumeUrl : rest.gatewayUrl();
                status = "connecting";
                socket = HttpClient.newHttpClient().newWebSocketBuilder()
                        .buildAsync(URI.create(url + "/?v=10&encoding=json"), this).join();
                backoff = 2;
                // The listener drives everything from here; wait for the close.
                synchronized (this) {
                    while (socket != null && running.get()) wait(5_000);
                }
            } catch (Exception e) {
                status = "reconnecting: " + e.getMessage();
                log.warn("DoughBay Discord gateway: {}", e.toString());
            }
            if (!running.get()) return;
            try {
                Thread.sleep(backoff * 1000L);
            } catch (InterruptedException e) {
                return;
            }
            backoff = Math.min(60, backoff * 2);
        }
    }

    @Override
    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
        frame.append(data);
        ws.request(1);
        if (!last) return null;
        String text = frame.toString();
        frame.setLength(0);
        try {
            handle(ws, MAPPER.readTree(text));
        } catch (Exception e) {
            log.warn("DoughBay Discord gateway frame: {}", e.toString());
        }
        return null;
    }

    private void handle(WebSocket ws, JsonNode msg) throws Exception {
        int op = msg.path("op").asInt(-1);
        if (msg.hasNonNull("s")) lastSeq = msg.get("s").asLong();
        switch (op) {
            case 10 -> {   // HELLO
                heartbeatMillis = msg.path("d").path("heartbeat_interval").asLong(41_250);
                startHeart(ws);
                if (!sessionId.isBlank()) resume(ws); else identify(ws);
            }
            case 11 -> acked = true;   // HEARTBEAT_ACK
            case 1 -> heartbeat(ws);
            case 7 -> ws.sendClose(4000, "reconnect requested");   // RECONNECT
            case 9 -> {   // INVALID_SESSION
                boolean resumable = msg.path("d").asBoolean(false);
                if (!resumable) {
                    sessionId = "";
                    resumeUrl = "";
                }
                Thread.sleep(1500);
                if (resumable) resume(ws); else identify(ws);
            }
            case 0 -> {   // DISPATCH
                String type = msg.path("t").asText("");
                JsonNode d = msg.path("d");
                switch (type) {
                    case "READY" -> {
                        sessionId = d.path("session_id").asText("");
                        resumeUrl = d.path("resume_gateway_url").asText("");
                        status = "connected";
                        onReady.accept(d.path("application").path("id").asText(""));
                    }
                    case "RESUMED" -> status = "connected";
                    case "INTERACTION_CREATE" -> onInteraction.accept(d);
                    default -> { }
                }
            }
            default -> { }
        }
    }

    private void identify(WebSocket ws) {
        ObjectNode d = MAPPER.createObjectNode();
        d.put("token", token);
        d.put("intents", 0);
        ObjectNode props = d.putObject("properties");
        props.put("os", "windows");
        props.put("browser", "doughbay");
        props.put("device", "doughbay");
        send(ws, 2, d);
    }

    private void resume(WebSocket ws) {
        ObjectNode d = MAPPER.createObjectNode();
        d.put("token", token);
        d.put("session_id", sessionId);
        d.put("seq", lastSeq);
        send(ws, 6, d);
    }

    private void heartbeat(WebSocket ws) {
        ObjectNode env = MAPPER.createObjectNode();
        env.put("op", 1);
        if (lastSeq >= 0) env.put("d", lastSeq); else env.putNull("d");
        acked = false;
        ws.sendText(env.toString(), true);
    }

    private void startHeart(WebSocket ws) {
        if (heart != null) heart.interrupt();
        heart = new Thread(() -> {
            try {
                Thread.sleep((long) (heartbeatMillis * Math.random()));
                while (running.get() && socket == ws) {
                    if (!acked) {
                        log.warn("DoughBay Discord gateway: heartbeat unanswered; reconnecting");
                        ws.sendClose(4001, "zombie");
                        return;
                    }
                    heartbeat(ws);
                    Thread.sleep(heartbeatMillis);
                }
            } catch (InterruptedException ignored) {
                // replaced by a newer connection
            }
        }, "doughbay-discord-heart");
        heart.setDaemon(true);
        heart.start();
    }

    private void send(WebSocket ws, int op, JsonNode d) {
        ObjectNode env = MAPPER.createObjectNode();
        env.put("op", op);
        env.set("d", d);
        ws.sendText(env.toString(), true);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
        status = "closed (" + code + " " + reason + ")";
        if (code == 4004 || code == 4014) {
            log.error("DoughBay Discord gateway refused the bot: {} {}", code, reason);
            running.set(false);
        }
        release(ws);
        return null;
    }

    @Override
    public void onError(WebSocket ws, Throwable error) {
        status = "error: " + error.getMessage();
        release(ws);
    }

    private void release(WebSocket ws) {
        if (socket == ws) socket = null;
        synchronized (this) {
            notifyAll();
        }
    }
}
