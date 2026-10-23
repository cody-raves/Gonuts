package dev.doughbay.fabric;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static dev.doughbay.fabric.AutomaticReportDetector.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

class AutomaticReportServiceTest {
    @TempDir Path root;
    static final ObjectMapper JSON=new ObjectMapper();
    final AtomicLong clock=new AtomicLong(1_000_000);
    static Observation healthy(){return new Observation(true,true,true,false,true,false,false,null);}
    static Observation failure(){return new Observation(true,true,true,false,true,true,false,Signature.IMMUTABLE_POSITION_IDENTITY_CONFLICT);}
    static JsonNode caps() throws Exception {
        return JSON.readTree("""
            {"schemaVersions":[1,2,3],"configured":true,"requiresAuthentication":false,"maxBodyBytes":65536,
             "diagnosticLog":{"supported":true,"schemaVersion":2,"maxBytes":24576,"maxLines":200,"maxLineChars":500},
             "automaticReports":{"supported":true,"grouped":true,"schemaVersion":3,"kinds":["DURABLE_WRITE_REJECTED","PERSISTENCE_WRITER_FAILED"]}}
            """);
    }
    class FakeGateway implements AutomaticReportService.Gateway {
        final List<String> keys=new ArrayList<>(),bodies=new ArrayList<>();
        boolean supported=true,fail,permanent,recoverySupported;
        Runnable onCapabilities=()->{};
        public JsonNode capabilities() throws Exception {onCapabilities.run();var value=caps();var automatic=(com.fasterxml.jackson.databind.node.ObjectNode)value.path("automaticReports");automatic.put("supported",supported);if(recoverySupported){automatic.withArray("kinds").add("AUTOMATIC_RECOVERY_STALLED");automatic.putObject("signatures").putArray("AUTOMATIC_RECOVERY_STALLED").add("EVASION_RESUME_STALLED");}return value;}
        public BugReportTransport.AutomaticResult send(byte[] body,String key) throws Exception {
            keys.add(key);bodies.add(new String(body,java.nio.charset.StandardCharsets.UTF_8));
            if(fail) throw new java.io.IOException("private text must not be logged");
            return new BugReportTransport.AutomaticResult(new BugReportTransport.Result(!permanent,"",permanent?"":"https://github.com/cody-raves/Gonuts/issues/123",0),permanent);
        }
    }
    AutomaticReportService service(FakeGateway gateway) {
        return new AutomaticReportService(root,"0.1.0","26.2",gateway,a ->
            new AutomaticReportService.Prepared(new BugReportPayload("0.1.0","26.2",a.description(),a.expected(),BugReportDiagnostics.counters("")),
                BugReportLog.sanitize("[12:00:00] [Render thread/ERROR] IllegalStateException: SecretPlayer token=secret\n[12:00:01] [CHAT] OtherPlayer: private chat")),clock::get,false);
    }
    void qualify(AutomaticReportService service) {service.observe(healthy(),0);service.observe(failure(),1);service.observe(failure(),120001);}
    @Test void recoveryReportWaitsForReceiverAdvertisementAndUsesExistingDelivery() {
        var gateway=new FakeGateway();var service=service(gateway);
        var stalled=new Observation(true,true,true,true,true,false,false,null,true);
        service.observe(healthy(),0);service.observe(stalled,1);service.observe(stalled,300001);service.process();
        assertTrue(gateway.bodies.isEmpty(),"old receiver must receive no unsupported recovery incident");
        gateway.recoverySupported=true;clock.addAndGet(300001);service.observe(stalled,600002);service.process();
        assertEquals(1,gateway.bodies.size());assertTrue(gateway.bodies.getFirst().contains("AUTOMATIC_RECOVERY_STALLED"));
        service.process();assertEquals(1,gateway.bodies.size(),"unchanged incident is suppressed by the existing journal");
        service.close();
    }
    @Test void healthyOrRecoveringClientsNeverSubmit() {
        var gateway=new FakeGateway();var service=service(gateway);
        service.observe(healthy(),0);service.observe(failure(),1);service.process();
        service.observe(healthy(),120001);service.process();
        assertTrue(gateway.keys.isEmpty());assertFalse(Files.exists(root.resolve("gonuts-reports/outbox.json")));
    }
    @Test void oneSanitizedReportIsBookedAndRepeatedFailureSuppressed() throws Exception {
        var gateway=new FakeGateway();var service=service(gateway);qualify(service);service.process();service.process();
        assertEquals(1,gateway.keys.size());String body=gateway.bodies.getFirst();
        assertEquals(3,JSON.readTree(body).path("schemaVersion").asInt());
        assertFalse(body.contains("SecretPlayer"));assertFalse(body.contains("OtherPlayer"));assertFalse(body.contains("token="));
        assertFalse(Files.exists(root.resolve("gonuts-reports/outbox.json")));
        assertTrue(service.status().contains("suppressed"));
    }
    @Test void ambiguousSubmissionSurvivesRestartWithExactBodyAndKey() {
        var gateway=new FakeGateway();gateway.fail=true;
        var service=service(gateway);qualify(service);service.process();service.close();
        clock.addAndGet(60_001);gateway.fail=false;
        var restarted=service(gateway);restarted.observe(healthy(),0);restarted.process();
        assertEquals(2,gateway.keys.size());assertEquals(gateway.keys.get(0),gateway.keys.get(1));
        assertEquals(gateway.bodies.get(0),gateway.bodies.get(1));assertTrue(restarted.status().contains("received"));
    }
    @Test void optOutPreventsRetryOfAttemptedRequest() {
        var gateway=new FakeGateway();gateway.fail=true;var service=service(gateway);qualify(service);service.process();
        clock.addAndGet(60_001);service.observe(new Observation(false,true,true,false,true,true,false,null),200000);service.process();
        assertEquals(1,gateway.keys.size());
    }
    @Test void capabilityDowngradeBlocksSendingWithoutCreatingAnOutbox() {
        var gateway=new FakeGateway();gateway.supported=false;var service=service(gateway);qualify(service);service.process();
        assertTrue(gateway.keys.isEmpty());assertFalse(Files.exists(root.resolve("gonuts-reports/outbox.json")));
    }
    @Test void recoveryDuringCapabilitiesPreventsFirstUpload() {
        var gateway=new FakeGateway();var service=service(gateway);qualify(service);
        gateway.onCapabilities=()->service.observe(healthy(),120002);service.process();assertTrue(gateway.keys.isEmpty());
    }
    @Test void staleTickFactsAreNotEvidenceOfCurrentConnection() {
        var gateway=new FakeGateway();var service=service(gateway);qualify(service);clock.addAndGet(15_001);service.process();
        assertTrue(gateway.keys.isEmpty());
    }
    @Test void permanentRejectionDoesNotMintAnotherKey() {
        var gateway=new FakeGateway();gateway.permanent=true;var service=service(gateway);qualify(service);service.process();service.process();
        assertEquals(1,gateway.keys.size());
    }
    @Test void retriesStopAtFiveAttempts() {
        var gateway=new FakeGateway();gateway.fail=true;var service=service(gateway);qualify(service);
        for(int i=0;i<7;i++){service.process();clock.addAndGet(3_600_000);service.observe(failure(),120001L+i*3_600_000);}
        assertEquals(5,gateway.keys.size());assertEquals(1,new HashSet<>(gateway.keys).size());
    }
    @Test void corruptOutboxFailsClosedWithoutUploading() throws Exception {
        var gateway=new FakeGateway();var service=service(gateway);qualify(service);
        Files.createDirectories(root.resolve("gonuts-reports"));Files.writeString(root.resolve("gonuts-reports/outbox.json"),"not-json");
        service.process();assertTrue(gateway.keys.isEmpty());assertTrue(service.status().contains("local state unavailable"));
    }
    @Test void frozenBodyValidationRefusesNamesUnknownFieldsAndForgedFingerprint() throws Exception {
        var a=AutomaticReportPayloadTest.metadata("0.1.0");
        String valid=new String(AutomaticReportPayloadTest.payload().json(BugReportLog.sanitize(""),a),java.nio.charset.StandardCharsets.UTF_8);
        assertDoesNotThrow(()->AutomaticReportOutbox.validateBody(valid,a.fingerprint()));
        var body=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree(valid);body.put("privateName","SecretPlayer");
        assertThrows(IllegalArgumentException.class,()->AutomaticReportOutbox.validateBody(body.toString(),a.fingerprint()));body.remove("privateName");
        body.put("diagnosticLog",BugReportLog.sanitize("")+"SecretPlayer");
        assertThrows(IllegalArgumentException.class,()->AutomaticReportOutbox.validateBody(body.toString(),a.fingerprint()));
        assertThrows(IllegalArgumentException.class,()->AutomaticReportOutbox.validateBody(valid,"0".repeat(64)));
    }
    @Test void missingFrozenBodyCannotBeRebuiltWithDifferentEvidence() throws Exception {
        var gateway=new FakeGateway();var service=service(gateway);qualify(service);
        new AutomaticReportJournal(root.resolve("gonuts-reports/journal.json")).reserve(AutomaticReportPayloadTest.metadata("0.1.0").fingerprint(),clock.get());
        service.process();assertTrue(gateway.keys.isEmpty());assertTrue(service.status().contains("local state unavailable"));
    }
}
