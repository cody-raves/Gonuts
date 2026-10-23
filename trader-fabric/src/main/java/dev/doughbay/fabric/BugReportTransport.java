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
    private static final int MAX_CAPABILITY_BYTES = 8192;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    public record Result(boolean filed, String message, String issueUrl, long retryAtMillis) { }
    public record AutomaticResult(Result result, boolean permanent) { }
    /** Public capability lookup; never forwards the market API credential. */
    public com.fasterxml.jackson.databind.JsonNode automaticCapabilities() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("https://gonuts.at/v1/bugs/capabilities"))
            .timeout(Duration.ofSeconds(10)).GET().build();
        var response = http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()).get(15, TimeUnit.SECONDS);
        try (var stream = response.body()) {
            CompletableFuture.delayedExecutor(10, TimeUnit.SECONDS).execute(() -> { try { stream.close(); } catch(Exception ignored) {} });
            return readCapabilities(response.statusCode(), stream);
        }
    }
    /** Runs on the report daemon only. The caller validates its frozen outbox before calling. */
    public AutomaticResult sendAutomatic(byte[] frozenBody, String key) throws Exception {
        if (frozenBody.length>65536 || key==null || !key.matches("[A-Za-z0-9_-]{8,64}"))
            throw new IllegalArgumentException("Invalid automatic request");
        var request=HttpRequest.newBuilder(ENDPOINT).timeout(Duration.ofSeconds(25))
            .header("Content-Type","application/json").header("Idempotency-Key",key)
            .POST(HttpRequest.BodyPublishers.ofByteArray(frozenBody)).build();
        HttpRequest outgoing=request;
        try {var body=JSON.readTree(frozenBody);outgoing=SwarmReportLinks.attach(request,body.path("diagnosticLog").asText(),automaticCapabilities(),System.currentTimeMillis());}
        catch(Exception ignored){} // Public reporting remains available if optional linkage is unavailable.
        var response=http.sendAsync(outgoing,HttpResponse.BodyHandlers.ofInputStream()).get(30,TimeUnit.SECONDS);
        try(var stream=response.body()) {
            CompletableFuture.delayedExecutor(10,TimeUnit.SECONDS).execute(() -> {try {stream.close();} catch(Exception ignored) {}});
            byte[] bytes=stream.readNBytes(16385);
            if(bytes.length>16384) throw new java.io.IOException("Unexpected receiver reply");
            int code=response.statusCode();
            return new AutomaticResult(interpret(code,new String(bytes,java.nio.charset.StandardCharsets.UTF_8),
                response.headers().firstValue("Retry-After").orElse(""),System.currentTimeMillis()),
                code>=400 && code<500 && code!=429);
        }
    }
    static HttpRequest request(BugReportPayload payload, String key, String token) {
        return request(payload, key, token, "");
    }
    static HttpRequest request(BugReportPayload payload, String key, String token, String diagnosticLog) {
        return request(payload,key,token,diagnosticLog,"");
    }
    static HttpRequest request(BugReportPayload payload, String key, String token, String diagnosticLog, String reporter) {
        return request(payload,key,token,diagnosticLog,reporter,0);
    }
    static HttpRequest request(BugReportPayload payload,String key,String token,String diagnosticLog,String reporter,int relatedIssue) {
        if(relatedIssue<0)throw new IllegalArgumentException("Invalid related issue");
        if (key == null || !key.matches("[A-Za-z0-9_-]{8,64}")) throw new IllegalArgumentException("Invalid report retry identifier");
        URI endpoint=relatedIssue==0?ENDPOINT:URI.create(ENDPOINT+"/"+relatedIssue+"/evidence");
        var builder=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(25))
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", key);
        if(reporter!=null&&!reporter.isEmpty()) {
            if(!reporter.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid anonymous report key");
            builder.header("X-GoNuts-Reporter",reporter);
        }
        return builder.POST(HttpRequest.BodyPublishers.ofByteArray(payload.json(diagnosticLog))).build();
    }
    static boolean advertisesReporterKey(com.fasterxml.jackson.databind.JsonNode capabilities) {
        var key=capabilities.path("reporterKey");
        return key.path("supported").asBoolean(false)&&key.path("optional").asBoolean(false)
                &&key.path("header").asText().equals("X-GoNuts-Reporter")&&key.path("pattern").asText().equals("[0-9a-f]{64}");
    }
    static HttpRequest automaticRequest(BugReportPayload payload, String key, String diagnosticLog,
            AutomaticReportMetadata automatic, com.fasterxml.jackson.databind.JsonNode capabilities) {
        if (!advertisesAutomaticReports(capabilities,automatic.kind())
                || diagnosticLog!=null&&!diagnosticLog.isEmpty()&&!advertisesLogSupport(capabilities))
            throw new IllegalStateException("Receiver does not support grouped automatic reports");
        if(key==null||!key.matches("[A-Za-z0-9_-]{8,64}"))throw new IllegalArgumentException("Invalid report retry identifier");
        return HttpRequest.newBuilder(ENDPOINT).timeout(Duration.ofSeconds(25))
            .header("Content-Type","application/json").header("Idempotency-Key",key)
            .POST(HttpRequest.BodyPublishers.ofByteArray((advertisesSettingsSupport(capabilities) ? payload : payload.withoutSettings()).json(diagnosticLog,automatic))).build();
    }
    public CompletableFuture<Boolean> supportsLogAttachments(String apiBaseUrl, String token) {
        var request = HttpRequest.newBuilder(URI.create("https://gonuts.at/v1/bugs/capabilities"))
            .timeout(Duration.ofSeconds(10)).GET().build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()).thenApplyAsync(response -> {
            try (var stream = response.body()) {
                CompletableFuture.delayedExecutor(10, TimeUnit.SECONDS).execute(() -> {try {stream.close();} catch(Exception ignored) {}});
                return advertisesLogSupport(readCapabilities(response.statusCode(), stream));
            } catch (Exception e) {return false;}
        }).orTimeout(15, TimeUnit.SECONDS).exceptionally(e -> false);
    }
    /** Both manual and automatic reporting must accept the same bounded capability document. */
    static com.fasterxml.jackson.databind.JsonNode readCapabilities(int status, java.io.InputStream stream)
            throws java.io.IOException {
        byte[] bytes = stream.readNBytes(MAX_CAPABILITY_BYTES + 1);
        if (status != 200 || bytes.length > MAX_CAPABILITY_BYTES) throw new java.io.IOException("Capabilities unavailable");
        return JSON.readTree(bytes);
    }
    static boolean advertisesSettingsSupport(com.fasterxml.jackson.databind.JsonNode body) {
        var settings = body.path("settingsCode");
        return settings.path("supported").asBoolean(false)
            && settings.path("format").asText().equals("DB1-numeric-v1")
            && settings.path("maxChars").asInt() >= 12000;
    }
    static boolean advertisesLogSupport(com.fasterxml.jackson.databind.JsonNode body) {
        var log = body.path("diagnosticLog");
        if (!body.path("schemaVersions").isArray() || body.path("maxBodyBytes").asInt() < 65536
            || !log.path("supported").asBoolean(false) || log.path("schemaVersion").asInt() != 2
            || log.path("maxBytes").asInt() < BugReportLog.MAX_BYTES || log.path("maxLines").asInt() < 102 || log.path("maxLineChars").asInt() < 500) return false;
        for (var version : body.path("schemaVersions")) if (version.isIntegralNumber() && version.asInt() == 2) return true;
        return false;
    }
    /** Never send an automatic incident to a receiver that only supports manual schemas. */
    static boolean advertisesAutomaticReports(com.fasterxml.jackson.databind.JsonNode body, AutomaticReportDetector.Failure kind) {
        var automatic=body.path("automaticReports");
        if(!body.path("configured").asBoolean(false) || body.path("requiresAuthentication").asBoolean(true)
                || !automatic.path("supported").asBoolean(false) || !automatic.path("grouped").asBoolean(false)
                || automatic.path("schemaVersion").asInt()!=3 || body.path("maxBodyBytes").asInt()<65536
                || !body.path("schemaVersions").isArray() || !automatic.path("kinds").isArray())return false;
        boolean schema=false, supportedKind=false;
        for(var version:body.path("schemaVersions")) if(version.isIntegralNumber()&&version.asInt()==3)schema=true;
        for(var value:automatic.path("kinds")) if(value.isTextual()&&value.asText().equals(kind.name()))supportedKind=true;
        if (kind == AutomaticReportDetector.Failure.AUTOMATIC_RECOVERY_STALLED) {
            boolean signature = false;
            for (var value : automatic.path("signatures").path(kind.name()))
                if (value.isTextual() && value.asText().equals("EVASION_RESUME_STALLED")) signature = true;
            if (!signature) return false;
        }
        return schema&&supportedKind;
    }
    public CompletableFuture<Result> send(BugReportPayload payload, String key, String apiBaseUrl, String token) {
        return send(payload, key, apiBaseUrl, token, "");
    }
    public CompletableFuture<Result> send(BugReportPayload payload, String key, String apiBaseUrl, String token, String diagnosticLog) {
        return sendRelated(payload,key,diagnosticLog,0);
    }
    public CompletableFuture<Result> sendRelated(BugReportPayload payload,String key,String diagnosticLog,int relatedIssue) {
        if(relatedIssue<0)return CompletableFuture.completedFuture(new Result(false,"Invalid related issue.","",0));
        return CompletableFuture.supplyAsync(() -> {
            try { return automaticCapabilities(); }
            catch (Exception e) { return JSON.createObjectNode(); }
        }).thenComposeAsync(capabilities -> {
            if(relatedIssue>0&&!advertisesRelatedIssue(capabilities))
                return CompletableFuture.completedFuture(new Result(false,"Attaching evidence is unavailable. Keep this preview and retry later; no new issue was created.","",0));
            if (diagnosticLog != null && !diagnosticLog.isEmpty() && !advertisesLogSupport(capabilities))
                return CompletableFuture.completedFuture(new Result(false,"Report service capabilities unavailable. Retry this prepared report later.","",0));
            if (payload.settingsCode() != null && !payload.settingsCode().isEmpty() && !advertisesSettingsSupport(capabilities))
                return CompletableFuture.completedFuture(new Result(false,"Settings attachment support unavailable. Keep this prepared report and retry later.","",0));
            return sendSupported(payload,key,"",diagnosticLog,advertisesReporterKey(capabilities)?BugReporterKey.current():"",relatedIssue,capabilities);
        });
    }

    static boolean advertisesRelatedIssue(com.fasterxml.jackson.databind.JsonNode capabilities) {
        return capabilities.path("relatedIssue").path("supported").asBoolean(false)
                &&capabilities.path("relatedIssue").path("route").asText().equals("/v1/bugs/{issue}/evidence");
    }
    private CompletableFuture<Result> sendSupported(BugReportPayload payload, String key, String token, String diagnosticLog, String reporter,int relatedIssue,com.fasterxml.jackson.databind.JsonNode capabilities) {
        try {
            var outgoing=SwarmReportLinks.attach(request(payload,key,token,diagnosticLog,reporter,relatedIssue),diagnosticLog,capabilities,System.currentTimeMillis());
            return http.sendAsync(outgoing, HttpResponse.BodyHandlers.ofInputStream())
                .thenApplyAsync(response -> {
                    try (var body = response.body()) {
                        CompletableFuture.delayedExecutor(10, TimeUnit.SECONDS).execute(() -> {
                            try { body.close(); } catch (java.io.IOException ignored) { }
                        });
                        byte[] bytes = body.readNBytes(16385);
                        if (bytes.length > 16384) return new Result(false, "Unexpected receiver reply. Retry later.", "", 0);
                        String text=new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
                        String retry=response.headers().firstValue("Retry-After").orElse("");
                        return relatedIssue==0?interpret(response.statusCode(),text,retry,System.currentTimeMillis())
                                :interpretEvidence(response.statusCode(),text,retry,System.currentTimeMillis(),relatedIssue);
                    } catch (Exception e) { return new Result(false, "Receiver reply unavailable. Retry later with this report.", "", 0); }
                }).orTimeout(30, TimeUnit.SECONDS)
                .exceptionally(e -> new Result(false, "Connection failed. Retry this report; its retry identifier is retained.", "", 0));
        } catch (Exception e) { return CompletableFuture.completedFuture(new Result(false, "Report could not be sent. Nothing was uploaded.", "", 0)); }
    }
    static Result interpretEvidence(int code,String body,String retry,long now,int issue) {
        if(code==200||code==201) {
            try {
                var reply=JSON.readTree(body);
                String url="https://github.com/cody-raves/Gonuts/issues/"+issue;
                if(issue<=0||reply.path("issue").asInt()!=issue||!reply.path("attached").asBoolean()||!reply.path("url").asText().equals(url))throw new IllegalArgumentException();
                return new Result(true,"Diagnostics attached to the existing bug; no new issue created.",url,0);
            }catch(Exception e){return new Result(false,"Unexpected attachment reply. Keep this preview and retry later.","",0);}
        }
        if(code==404)return new Result(false,"That bug is closed or no longer canonical. Refresh the tracker before attaching.","",0);
        return interpret(code,body,retry,now);
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
            return new Result(false, "Please consolidate your bugs into one report. Repeated spam reports will be ignored. Try again after " + seconds + " seconds.", "", now + seconds * 1000);
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
