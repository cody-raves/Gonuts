package dev.doughbay.fabric;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Sends only an explicitly reviewed report, off the render thread. Never logs credentials or response bodies. */
public final class BugReportTransport {
    private static final URI ENDPOINT = URI.create("https://gonuts.at/v1/bugs");
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    public record Result(boolean filed, String message, String issueUrl, long retryAtMillis) { }
    static HttpRequest request(BugReportPayload payload, String key, String token) {
        return request(payload, key, token, "");
    }
    static HttpRequest request(BugReportPayload payload, String key, String token, String diagnosticLog) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{8,64}")) throw new IllegalArgumentException("Invalid report retry identifier");
        return HttpRequest.newBuilder(ENDPOINT).timeout(Duration.ofSeconds(25))
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", key).POST(HttpRequest.BodyPublishers.ofByteArray(payload.json(diagnosticLog))).build();
    }
    public CompletableFuture<Boolean> supportsLogAttachments(String apiBaseUrl, String token) {
        var request = HttpRequest.newBuilder(URI.create("https://gonuts.at/v1/bugs/capabilities"))
            .timeout(Duration.ofSeconds(10)).GET().build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()).thenApplyAsync(response -> {
            try (var stream = response.body()) {
                CompletableFuture.delayedExecutor(10, TimeUnit.SECONDS).execute(() -> {try {stream.close();} catch(Exception ignored) {}});
                byte[] bytes = stream.readNBytes(4097);
                if (response.statusCode() != 200 || bytes.length > 4096) return false;
                return advertisesLogSupport(JSON.readTree(bytes));
            } catch (Exception e) {return false;}
        }).orTimeout(15, TimeUnit.SECONDS).exceptionally(e -> false);
    }
    static boolean advertisesLogSupport(com.fasterxml.jackson.databind.JsonNode body) {
        var log = body.path("diagnosticLog");
        if (!body.path("schemaVersions").isArray() || body.path("maxBodyBytes").asInt() < 32768
            || !log.path("supported").asBoolean(false) || log.path("schemaVersion").asInt() != 2
            || log.path("maxBytes").asInt() < 8192 || log.path("maxLines").asInt() < 102 || log.path("maxLineChars").asInt() < 500) return false;
        for (var version : body.path("schemaVersions")) if (version.isIntegralNumber() && version.asInt() == 2) return true;
        return false;
    }
    public CompletableFuture<Result> send(BugReportPayload payload, String key, String apiBaseUrl, String token) {
        return send(payload, key, apiBaseUrl, token, "");
    }
    public CompletableFuture<Result> send(BugReportPayload payload, String key, String apiBaseUrl, String token, String diagnosticLog) {
        if (diagnosticLog != null && !diagnosticLog.isEmpty()) return supportsLogAttachments(apiBaseUrl,token).thenCompose(supported -> supported
            ? sendSupported(payload,key,token,diagnosticLog)
            : CompletableFuture.completedFuture(new Result(false,"Log attachment support is unavailable. Prepare without the log to send diagnostics only.","",0)));
        return sendSupported(payload,key,token,"");
    }
    private CompletableFuture<Result> sendSupported(BugReportPayload payload, String key, String token, String diagnosticLog) {
        try {
            return http.sendAsync(request(payload, key, token, diagnosticLog), HttpResponse.BodyHandlers.ofInputStream())
                .thenApplyAsync(response -> {
                    try (var body = response.body()) {
                        CompletableFuture.delayedExecutor(10, TimeUnit.SECONDS).execute(() -> {
                            try { body.close(); } catch (java.io.IOException ignored) { }
                        });
                        byte[] bytes = body.readNBytes(16385);
                        if (bytes.length > 16384) return new Result(false, "Unexpected receiver reply. Retry later.", "", 0);
                        return interpret(response.statusCode(), new String(bytes, java.nio.charset.StandardCharsets.UTF_8),
                            response.headers().firstValue("Retry-After").orElse(""), System.currentTimeMillis());
                    } catch (Exception e) { return new Result(false, "Receiver reply unavailable. Retry later with this report.", "", 0); }
                }).orTimeout(30, TimeUnit.SECONDS)
                .exceptionally(e -> new Result(false, "Connection failed. Retry this report; its retry identifier is retained.", "", 0));
        } catch (Exception e) { return CompletableFuture.completedFuture(new Result(false, "Report could not be sent. Nothing was uploaded.", "", 0)); }
    }
    static Result interpret(int code, String body, String retryAfter, long now) {
        if (code == 200 || code == 201) {
            try {
                var reply = JSON.readTree(body);
                String url = reply.path("issueUrl").asText("");
                if (!url.matches("https://github\\.com/cody-raves/Gonuts/issues/[1-9][0-9]*") || !reply.path("reportId").asText("").matches("br_[A-Za-z0-9_-]{1,100}")) throw new IllegalArgumentException();
                return new Result(true, reply.path("duplicate").asBoolean(false) ? "Already received. Open the existing issue below." : "Sent. Open your issue below.", url, 0);
            } catch (Exception e) { return new Result(false, "Unexpected receiver reply. Retry this report later.", "", 0); }
        }
        if (code == 429) {
            long seconds = 3600;
            try { seconds = Math.max(1, Math.min(86400, Long.parseLong(retryAfter.strip()))); } catch (Exception ignored) { }
            return new Result(false, "Report limit reached. Try again after " + seconds + " seconds.", "", now + seconds * 1000);
        }
        return new Result(false, switch (code) {
            case 400 -> "The receiver rejected the form. Check its text and prepare it again.";
            case 401 -> "The reporting service rejected public access. Try later or use GitHub fallback.";
            case 409 -> "This retry identifier conflicts with another report. Prepare again before sending.";
            case 413 -> "Report is too large. Shorten the description.";
            case 503 -> "Bug reporting is temporarily unavailable. Keep this report and try later.";
            case 502 -> "GitHub is unavailable. Retry this report later.";
            default -> "Receiver unavailable (HTTP " + code + "). Try later.";
        }, "", (code == 502 || code == 503 || code >= 500) ? now + 60_000 : 0);
    }
}
