package dev.doughbay.fabric;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class ActionTraceTest {
    @org.junit.jupiter.api.io.TempDir Path temp;
    @Test void realControllerContextAndFocusedEvidenceSurviveDiskOutboxReload() throws Exception {
        var controller=new dev.doughbay.fabric.automation.AutomationSessionController(new AutomatedExecutionDriver());
        String evidence=BugReportEvidence.combine(controller.bugReportContext(System.currentTimeMillis())
                +FaultTrace.INSTANCE.report(System.currentTimeMillis())+ActionTrace.INSTANCE.report(System.currentTimeMillis(),ReportTopic.RECOVERY),BugReportLog.UNAVAILABLE);
        var meta=AutomaticReportMetadata.of("0.1.2","26.2",new AutomaticReportDetector.Candidate(AutomaticReportDetector.Failure.PERSISTENCE_WRITER_FAILED,AutomaticReportDetector.Signature.PERSISTENCE_GENERIC_FAILURE));
        var payload=new BugReportPayload("0.1.2","26.2",meta.description(),meta.expected(),BugReportDiagnostics.counters(""),"");
        var pending=new AutomaticReportOutbox.Pending("fixture-disk-retry",meta.fingerprint(),new String(payload.json(evidence,meta),StandardCharsets.UTF_8),1000,0,1000);
        var outbox=new AutomaticReportOutbox(temp.resolve("outbox.json"));outbox.save(pending);
        assertArrayEquals(pending.bytes(),outbox.load(1500).orElseThrow().bytes());
    }
    @Test void lastRefusalSurvivesLaterSuccessfulActivityInTheSameSubsystem() {
        var trace=new ActionTrace();
        trace.record(ActionTrace.Scope.DRIVER,ActionTrace.Event.REFUSED,ActionTrace.Code.API,"ABOVE_CEILING",64,1200,1000,1000);
        for(int i=0;i<100;i++)trace.record(ActionTrace.Scope.DRIVER,ActionTrace.Event.SUCCEEDED,ActionTrace.Code.API,"NONE",i,900,1000,2000+i);
        String report=trace.report(3000);
        assertTrue(report.contains("reason=ABOVE_CEILING age_ms=2000"));
        assertTrue(report.contains("event=SUCCEEDED"));
    }
    @Test void repeatedRefusalKeepsFirstTimeCountAndLastAgeWithoutCrowdingOtherEvidence() {
        var trace=new ActionTrace();
        trace.record(ActionTrace.Scope.HIVE,ActionTrace.Event.REFUSED,ActionTrace.Code.CREATE,"OFFLINE_REQUIRED",-1,-1,-1,1000);
        for(int i=0;i<1000;i++)trace.record(ActionTrace.Scope.DESK,ActionTrace.Event.REFUSED,ActionTrace.Code.NONE,"INVENTORY_SAFETY",-1,-1,-1,2000+i);
        String text=trace.report(4000);
        assertTrue(text.contains("scope=HIVE event=REFUSED code=CREATE reason=OFFLINE_REQUIRED age_ms=3000"));
        assertTrue(text.contains("age_ms=1001 duration_ms=999 count=1000"));
        assertEquals(3,text.lines().count());
    }
    @Test void floodIsBoundedAndKeepsTheLatestEventFromEverySubsystem() {
        var trace=new ActionTrace();
        for(var scope:ActionTrace.Scope.values())trace.record(scope,ActionTrace.Event.FAULT,ActionTrace.Code.NONE,"IO_FAILURE",-1,-1,-1,1000);
        for(int i=0;i<5000;i++)trace.record(ActionTrace.Scope.DRIVER,ActionTrace.Event.OBSERVATION,ActionTrace.Code.API,"NONE",64,i,5000,2000+i);
        String text=trace.report(8000);
        for(var scope:ActionTrace.Scope.values())assertTrue(text.contains("scope="+scope+" "),scope.name());
        assertTrue(text.getBytes(StandardCharsets.UTF_8).length<=3072);
        assertTrue(text.lines().allMatch(l->l.length()<500));
    }
    @Test void unsafeReasonAndMarketCannotEnterTheTimelineOrFilteredLog() {
        var trace=new ActionTrace();
        trace.recordMarket(ActionTrace.Scope.DRIVER,ActionTrace.Event.REFUSED,ActionTrace.Code.API,"Bearer SECRET player name",64,1200,1000,"minecraft:diamond#SECRET");
        String text=trace.report(System.currentTimeMillis());
        assertFalse(text.contains("SECRET"));assertTrue(text.contains("reason=UNCLASSIFIED"));assertTrue(text.contains("market=NONE"));
        String valid="act scope=DRIVER event=REFUSED code=BUY reason=ABOVE_CEILING age_ms=0 duration_ms=0 count=1 quantity=64 price=1200 ceiling=1000 market=minecraft:diamond";
        String filtered=BugReportLog.sanitize("[12:00:00] GoNuts action "+valid+"\n[12:00:01] GoNuts action "+valid+" token=SECRET\n[CHAT] GoNuts action "+valid);
        assertTrue(filtered.contains("[12:00:00] "+valid));assertFalse(filtered.contains("SECRET"));assertFalse(filtered.contains("12:00:01"));
        assertNull(ActionTrace.sanitizedLogLine("GoNuts action "+valid.replace("count=1","count=0")));
        assertNull(ActionTrace.sanitizedLogLine("GoNuts action "+valid.replace("price=1200","price=9999999999999999999")));
    }
    @Test void sharedCaptureAndActualOutboundBodyKeepActionEvidenceWithoutHistoricalLogs() throws Exception {
        ActionTrace.INSTANCE.recordMarket(ActionTrace.Scope.DRIVER,ActionTrace.Event.REFUSED,ActionTrace.Code.API,"ABOVE_CEILING",64,1200,1000,"minecraft:diamond");
        String evidence=BugReportEvidence.readForReport(Path.of("missing-action-fixture","latest.log"));
        assertTrue(evidence.contains("price=1200 ceiling=1000 market=minecraft:diamond"));
        var payload=new BugReportPayload("0.1.2","26.2","A qualifying row was refused","Explain the live ceiling",BugReportDiagnostics.counters(""),"");
        var request=BugReportTransport.request(payload,"fixture-action-123","private-credential",evidence);
        var body=new java.io.ByteArrayOutputStream();var done=new java.util.concurrent.CompletableFuture<byte[]>();
        request.bodyPublisher().orElseThrow().subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>(){
            public void onSubscribe(java.util.concurrent.Flow.Subscription s){s.request(Long.MAX_VALUE);}
            public void onNext(java.nio.ByteBuffer b){byte[] part=new byte[b.remaining()];b.get(part);body.writeBytes(part);}
            public void onError(Throwable t){done.completeExceptionally(t);}
            public void onComplete(){done.complete(body.toByteArray());}
        });
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        assertEquals(evidence,json.readTree(done.get(2,java.util.concurrent.TimeUnit.SECONDS)).path("diagnosticLog").asText());
        var meta=AutomaticReportMetadata.of("0.1.2","26.2",new AutomaticReportDetector.Candidate(AutomaticReportDetector.Failure.PERSISTENCE_WRITER_FAILED,AutomaticReportDetector.Signature.PERSISTENCE_GENERIC_FAILURE));
        var automatic=new BugReportPayload("0.1.2","26.2",meta.description(),meta.expected(),BugReportDiagnostics.counters(""),"");
        assertEquals(evidence,json.readTree(automatic.json(evidence,meta)).path("diagnosticLog").asText());
        var pending=new AutomaticReportOutbox.Pending("fixture-action-retry",meta.fingerprint(),new String(automatic.json(evidence,meta),StandardCharsets.UTF_8),1000,0,1000);
        assertEquals(meta.kind(),pending.kind());
        assertFalse(body.toString(StandardCharsets.UTF_8).contains("private-credential"));
    }
    @Test void focusedFlipHistorySurvivesUnrelatedFloodBeforeSizeTrimming() {
        String flip="act scope=FLIPS event=REFUSED code=QUOTE reason=TOP_BID_CEILING age_ms=0 duration_ms=0 count=1 quantity=64 price=1200 ceiling=1000 market=minecraft:diamond";
        StringBuilder raw=new StringBuilder("[12:00:00] GoNuts action "+flip+"\n");
        for(int i=0;i<300;i++)raw.append("[12:01:00] GoNuts action act scope=DASHBOARD event=RETRY code=SOCKET reason=IO_FAILURE age_ms=0 duration_ms=0 count=").append(i+1).append(" quantity=-1 price=-1 ceiling=-1 market=NONE\n");
        String excerpt=BugReportLog.sanitize(raw.toString(),ReportTopic.FLIPS);
        assertTrue(excerpt.contains(flip));
        assertTrue(excerpt.indexOf("scope=FLIPS")<excerpt.indexOf("scope=DASHBOARD"));
        assertTrue(excerpt.getBytes(StandardCharsets.UTF_8).length<=BugReportLog.MAX_BYTES);
        assertEquals(ReportTopic.FLIPS,ReportTopic.infer("the flip bot won't collect orders"));
        assertEquals(ReportTopic.HIVE,ReportTopic.infer("Create hive is unresponsive"));
        assertEquals(ReportTopic.GENERAL,ReportTopic.infer("Bearer private-user-input"));
    }
    @Test void frozenContextRejectsUnknownKeysAndMessagesAndKeepsHeadersUnderTrimming() {
        String context="Controller context (fixed keys, no identities)\nctx state=PAUSED mode=CONTINUOUS\nctx driver_watch=true driver_phase=WAITING_FOR_LISTING\n";
        String history=BugReportLog.sanitize("[12:00:00] IOException\n".repeat(1000));
        String combined=BugReportEvidence.combine(context,history);
        assertTrue(AutomaticReportOutbox.validExcerpt(combined));
        assertFalse(AutomaticReportOutbox.validExcerpt(combined.replace("state=PAUSED","state=PrivateUser")));
        assertFalse(AutomaticReportOutbox.validExcerpt(combined.replace("mode=CONTINUOUS","token=SECRET")));
    }
    @Test @SuppressWarnings({"rawtypes","unchecked"}) void realDriverPhaseAndTerminalPublishDistinctOutcomesBeforeTargetsAreCleared() throws Exception {
        var driver=new AutomatedExecutionDriver();
        var op=AutomatedExecutionDriver.class.getDeclaredField("operation");op.setAccessible(true);op.set(driver,Enum.valueOf((Class)op.getType(),"BUY"));
        var phase=AutomatedExecutionDriver.class.getDeclaredField("phase");phase.setAccessible(true);
        var enter=AutomatedExecutionDriver.class.getDeclaredMethod("enterPhase",phase.getType(),String.class);enter.setAccessible(true);
        enter.invoke(driver,Enum.valueOf((Class)phase.getType(),"WAITING_FOR_LISTING"),"private name");
        assertTrue(driver.bugReportContext(System.currentTimeMillis()).contains("driver_phase=WAITING_FOR_LISTING"));
        var terminal=AutomatedExecutionDriver.class.getDeclaredMethod("publishTerminal",AutomatedExecutionDriver.TerminalOutcome.class,String.class);terminal.setAccessible(true);
        terminal.invoke(driver,AutomatedExecutionDriver.TerminalOutcome.ABORTED,"private failure");
        assertTrue(ActionTrace.INSTANCE.report(System.currentTimeMillis()).contains("event=ABORTED code=BUY"));
        terminal.invoke(driver,AutomatedExecutionDriver.TerminalOutcome.SUCCEEDED,"private receipt");
        assertTrue(ActionTrace.INSTANCE.report(System.currentTimeMillis()).contains("event=SUCCEEDED code=BUY"));
        assertFalse(ActionTrace.INSTANCE.report(System.currentTimeMillis()).contains("private"));
    }
}
