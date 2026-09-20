package dev.doughbay.fabric.discord;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/**
 * The few Discord REST calls the bot needs: post and edit its one message
 * (with the rendered image attached), answer interactions, and find the
 * gateway. Rate limits are honoured by sleeping out {@code retry_after}.
 */
final class DiscordRest {
    private static final String API = "https://discord.com/api/v10";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final String token;

    DiscordRest(String token) {
        this.token = token;
    }

    String gatewayUrl() throws IOException, InterruptedException {
        JsonNode n = json(send(request("/gateway/bot").GET().build()));
        return n.path("url").asText("wss://gateway.discord.gg");
    }

    /** Posts the message; returns its id. */
    String createMessage(String channelId, String payloadJson, byte[] png) throws IOException, InterruptedException {
        HttpRequest req = multipart(request("/channels/" + channelId + "/messages"), "POST", payloadJson, png);
        return json(send(req)).path("id").asText("");
    }

    /** Edits the message in place; false when it no longer exists. */
    boolean editMessage(String channelId, String messageId, String payloadJson, byte[] png) throws IOException, InterruptedException {
        HttpRequest req = multipart(request("/channels/" + channelId + "/messages/" + messageId), "PATCH", payloadJson, png);
        HttpResponse<String> r = send(req);
        return r.statusCode() != 404;
    }

    /** Answers an interaction within its three-second window. */
    void respond(String interactionId, String interactionToken, String bodyJson) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(API + "/interactions/" + interactionId + "/" + interactionToken + "/callback"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8)).build();
        send(req);
    }

    /** A follow-up message on an interaction (used for ephemeral replies after a deferred ack). */
    void followUp(String applicationId, String interactionToken, String bodyJson) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(API + "/webhooks/" + applicationId + "/" + interactionToken))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8)).build();
        send(req);
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(API + path))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bot " + token)
                .header("User-Agent", "DiscordBot (https://github.com/cody-raves/GoNuts, 0.1)");
    }

    private static HttpRequest multipart(HttpRequest.Builder b, String method, String payloadJson, byte[] png) throws IOException {
        String boundary = "----DoughBay" + UUID.randomUUID();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"payload_json\"\r\nContent-Type: application/json\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(payloadJson.getBytes(StandardCharsets.UTF_8));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        if (png != null) {
            out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"files[0]\"; filename=\"stats.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(png);
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return b.header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .method(method, HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build();
    }

    private HttpResponse<String> send(HttpRequest req) throws IOException, InterruptedException {
        for (int attempt = 0; attempt < 4; attempt++) {
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 429) {
                double wait = 1.0;
                try {
                    wait = MAPPER.readTree(r.body()).path("retry_after").asDouble(1.0);
                } catch (IOException ignored) {
                    // no body: a second is plenty
                }
                Thread.sleep((long) (wait * 1000) + 100);
                continue;
            }
            if (r.statusCode() >= 500 && attempt < 3) {
                Thread.sleep(1500L * (attempt + 1));
                continue;
            }
            if (r.statusCode() >= 400 && r.statusCode() != 404) {
                throw new IOException("Discord " + r.statusCode() + " on " + req.method() + " " + req.uri().getPath()
                        + ": " + r.body().substring(0, Math.min(300, r.body().length())));
            }
            return r;
        }
        throw new IOException("Discord kept rate-limiting " + req.uri().getPath());
    }

    private static JsonNode json(HttpResponse<String> r) throws IOException {
        return MAPPER.readTree(r.body() == null || r.body().isBlank() ? "{}" : r.body());
    }
}
