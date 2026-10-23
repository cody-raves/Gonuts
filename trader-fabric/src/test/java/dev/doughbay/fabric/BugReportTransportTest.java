package dev.doughbay.fabric;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

class BugReportTransportTest {
    private static byte[] capabilitiesOfSize(int size) {
        String body = "{\"schemaVersions\":[1,2],\"maxBodyBytes\":65536,\"diagnosticLog\":{\"supported\":true,\"schemaVersion\":2,\"maxBytes\":24576,\"maxLines\":200,\"maxLineChars\":500}}";
        return (body + " ".repeat(size - body.length())).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
    @Test void manualCapabilityGateAcceptsExpandedReceiverMetadata() throws Exception {
        // The production receiver grew past 4 KiB when richer diagnostics were advertised.
        for (int size : new int[]{4096, 6298, 8192}) {
            var document = BugReportTransport.readCapabilities(200,
                    new java.io.ByteArrayInputStream(capabilitiesOfSize(size)));
            assertTrue(BugReportTransport.advertisesLogSupport(document), "capability bytes=" + size);
        }
    }
    @Test void capabilityDocumentRemainsBoundedAndRequiresSuccess() {
        assertThrows(java.io.IOException.class, () -> BugReportTransport.readCapabilities(200,
                new java.io.ByteArrayInputStream(capabilitiesOfSize(8193))));
        assertThrows(java.io.IOException.class, () -> BugReportTransport.readCapabilities(503,
                new java.io.ByteArrayInputStream(capabilitiesOfSize(6298))));
        assertThrows(java.io.IOException.class, () -> BugReportTransport.readCapabilities(200,
                new java.io.ByteArrayInputStream("not json".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }
    @Test void existingBugAttachmentsNeverFallBackToNewIssueOrAnotherOrigin() throws Exception {
        var c=new ObjectMapper().readTree("{\"relatedIssue\":{\"supported\":true,\"route\":\"/v1/bugs/{issue}/evidence\"}}");
        assertTrue(BugReportTransport.advertisesRelatedIssue(c));
        ((com.fasterxml.jackson.databind.node.ObjectNode)c.path("relatedIssue")).put("route","https://other.invalid/collect");
        assertFalse(BugReportTransport.advertisesRelatedIssue(c));
        var request=BugReportTransport.request(payload(),"retry_1234","private-credential","diagnostics","",92);
        assertEquals("https://gonuts.at/v1/bugs/92/evidence",request.uri().toString());
        assertFalse(request.headers().map().containsKey("Authorization"));
        assertTrue(BugReportTransport.interpretEvidence(200,"{\"issue\":92,\"url\":\"https://github.com/cody-raves/Gonuts/issues/92\",\"attached\":true}","",0,92).filed());
        assertFalse(BugReportTransport.interpretEvidence(200,"{\"issue\":89,\"url\":\"https://github.com/cody-raves/Gonuts/issues/89\",\"attached\":true}","",0,92).filed());
    }
    private static BugReportPayload payload() {
        return new BugReportPayload("0.1.1-staffwatch+26.2", "26.2", "A listing did not complete", "It should finish",
            BugReportDiagnostics.counters("DoughBay automation [SCANNING]: private message\nSQLiteException: private")).withoutSettings();
    }
    @Test void anonymousGroupingHeaderIsCapabilityGatedAndNeverUsesCredentials() throws Exception {
        var json=new ObjectMapper();
        var capabilities=json.readTree("{\"reporterKey\":{\"supported\":true,\"optional\":true,\"header\":\"X-GoNuts-Reporter\",\"pattern\":\"[0-9a-f]{64}\"}}");
        assertTrue(BugReportTransport.advertisesReporterKey(capabilities));
        assertFalse(BugReportTransport.advertisesReporterKey(json.readTree("{}")));
        ((com.fasterxml.jackson.databind.node.ObjectNode)capabilities.path("reporterKey")).put("header","Authorization");
        assertFalse(BugReportTransport.advertisesReporterKey(capabilities));
        String key="a".repeat(64);
        var request=BugReportTransport.request(payload(),"retry-key-123","private-market-token","",key);
        assertEquals(key,request.headers().firstValue("X-GoNuts-Reporter").orElseThrow());
        assertTrue(request.headers().firstValue("Authorization").isEmpty());
        assertFalse(request.headers().toString().contains("private-market-token"));
        assertTrue(BugReportTransport.request(payload(),"retry-key-123","private-market-token","").headers().firstValue("X-GoNuts-Reporter").isEmpty());
        assertThrows(IllegalArgumentException.class,()->BugReportTransport.request(payload(),"retry-key-123","","","not-a-key"));
    }
    @Test void exactReceiverSchemaContainsOnlyValidatedFields() throws Exception {
        var body = new ObjectMapper().readTree(payload().json());
        assertEquals(6, body.size()); assertEquals(1, body.path("schemaVersion").asInt());
        assertEquals("26.2", body.path("minecraftVersion").asText());
        assertEquals(6, body.path("diagnostics").size()); assertEquals(1, body.path("diagnostics").path("sqlite_errors").asLong());
        assertFalse(new String(payload().json(), java.nio.charset.StandardCharsets.UTF_8).contains("private"));
    }
    @Test void missingStateIsUppercaseUnknown() {
        assertEquals("UNKNOWN", BugReportDiagnostics.counters("").get("state"));
        var fromChat = BugReportDiagnostics.counters("[CHAT] DoughBay automation [PAUSED]: SQLiteException");
        assertEquals("UNKNOWN", fromChat.get("state"));
        assertEquals(0L, fromChat.get("sqlite_errors"));
    }
    @Test void httpsFixedDestinationAndRetryKeyRemainStable() {
        var a = BugReportTransport.request(payload(), "01234567-1234-1234-1234-012345678901", "test-install");
        var b = BugReportTransport.request(payload(), "01234567-1234-1234-1234-012345678901", "test-install");
        assertEquals("https://gonuts.at/v1/bugs", a.uri().toString());
        assertEquals(a.headers().firstValue("Idempotency-Key"), b.headers().firstValue("Idempotency-Key"));
        assertTrue(a.headers().firstValue("Authorization").isEmpty());
        assertEquals(a.uri(), BugReportTransport.request(payload(), "retry-key-123", "").uri());
        assertThrows(IllegalArgumentException.class, () -> BugReportTransport.request(payload(), "bad", "test"));
    }
    @Test void successAndDuplicateReturnOnlyTrustedIssueLinks() {
        String body = "{\"reportId\":\"br_test\",\"issueUrl\":\"https://github.com/cody-raves/Gonuts/issues/123\",\"duplicate\":false}";
        assertTrue(BugReportTransport.interpret(201, body, "", 0).filed());
        assertTrue(BugReportTransport.interpret(200, body.replace("false", "true"), "", 0).message().contains("Already"));
        assertFalse(BugReportTransport.interpret(201, body.replace("github.com", "attacker.net"), "", 0).filed());
        assertFalse(BugReportTransport.interpret(201, "not json", "", 0).filed());
    }
    @Test void rateLimitAndUnavailableStatusesDoNotEchoServerContent() {
        var rate = BugReportTransport.interpret(429, "secret echoed by server", "120", 1000);
        assertEquals(121000, rate.retryAtMillis()); assertFalse(rate.message().contains("secret"));
        assertEquals(3601000, BugReportTransport.interpret(429, "", "invalid", 1000).retryAtMillis());
        assertEquals(61000, BugReportTransport.interpret(503, "private", "", 1000).retryAtMillis());
        assertTrue(BugReportTransport.interpret(503, "", "", 0).message().contains("temporarily"));
        assertFalse(BugReportTransport.interpret(401, "", "", 0).message().contains("token"));
        assertTrue(BugReportTransport.interpret(409, "private", "", 0).message().contains("Prepare again"));
    }
    @Test void schemaRejectsRawLogsUnknownFieldsAndNonIntegerCounters() {
        assertThrows(IllegalArgumentException.class, () -> new BugReportPayload("0.1.0", "26.2", "short", "yes", payload().diagnostics()));
        assertThrows(IllegalArgumentException.class, () -> new BugReportPayload("0.1.0", "26.2", "[12:00:00] test\n[12:00:01] test\n[12:00:02] test", "yes", payload().diagnostics()));
        var counters = new java.util.HashMap<>(payload().diagnostics()); counters.put("sqlite_errors", 1.5);
        assertThrows(IllegalArgumentException.class, () -> new BugReportPayload("0.1.0", "26.2", "Description long enough", "yes", counters));
        counters.put("sqlite_errors", 1L); counters.put("unknown", 1L);
        assertThrows(IllegalArgumentException.class, () -> new BugReportPayload("0.1.0", "26.2", "Description long enough", "yes", counters));
    }
    @Test void logExtensionIsSeparateFromUnchangedVersionOneSchema() throws Exception {
        var json = new ObjectMapper();
        assertEquals(6, json.readTree(payload().json()).size());
        String excerpt = BugReportLog.sanitize("[12:00:00] DoughBay automation [PAUSED]: private");
        var extended = json.readTree(payload().json(excerpt));
        assertEquals(2, extended.path("schemaVersion").asInt()); assertEquals(7, extended.size());
        assertTrue(extended.path("diagnosticLog").asText().contains("state=PAUSED"));
        assertFalse(extended.path("diagnosticLog").asText().contains("private"));
        assertThrows(IllegalArgumentException.class, () -> payload().json("x".repeat(8193)));
        assertThrows(IllegalArgumentException.class, () -> payload().json("x".repeat(501)));
        assertThrows(IllegalArgumentException.class, () -> payload().json("x\n".repeat(201)));
    }
    @Test void exactAuthenticatedCapabilitiesShapeControlsAttachmentSupport() throws Exception {
        var json = new ObjectMapper();
        String capabilities = "{\"schemaVersions\":[1,2],\"maxBodyBytes\":65536,\"diagnosticLog\":{\"supported\":true,\"schemaVersion\":2,\"maxBytes\":24576,\"maxLines\":200,\"maxLineChars\":500}}";
        assertTrue(BugReportTransport.advertisesLogSupport(json.readTree(capabilities)));
        assertFalse(BugReportTransport.advertisesLogSupport(json.readTree(capabilities.replace("24576","8192"))));
        assertFalse(BugReportTransport.advertisesLogSupport(json.readTree(capabilities.replace("[1,2]","[1]"))));
        assertFalse(BugReportTransport.advertisesLogSupport(json.readTree("{}")));
    }
}
