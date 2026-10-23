package dev.doughbay.fabric.hosted;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.doughbay.fabric.*;
import dev.doughbay.fabric.automation.AutomationSessionController;
import net.minecraft.client.Minecraft;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ThreadLocalRandom;
import java.util.List;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Outbound-only dashboard. Never makes relay availability a condition for trading. */
public final class HostedDashboardClient {
    public static final HostedDashboardClient INSTANCE=new HostedDashboardClient();
    public record View(boolean linked,boolean online,boolean busy,String name,String status,long retryAt){}
    private volatile View view=new View(false,false,false,"","Not linked",0);
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"gonuts-hosted-dashboard");t.setDaemon(true);return t;});
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final AtomicLong epochs=new AtomicLong();
    private final DashboardCommands commands=new DashboardCommands();
    private final DashboardPanel panel=new DashboardPanel(DoughBayClient::panelBackend,
            task->Minecraft.getInstance().execute(task),worker);
    private DashboardHive hivePanel;private volatile String panelAccount="";private long panelPublishedAt;
    private record SavingsCapture(long at,ObjectNode value){}
    private volatile SavingsCapture hiveSavings,hiveUse;
    public ObjectNode hiveDataUse(){
        var capture=hiveUse;long now=System.currentTimeMillis();
        return capture!=null&&now>=capture.at()&&now-capture.at()<DashboardHive.FRESH_MS?capture.value().deepCopy():SharedDataUse.hive(DashboardProtocol.JSON.createArrayNode());
    }
    public ObjectNode hiveReadSavings(){
        var capture=hiveSavings;long now=System.currentTimeMillis();
        return capture!=null&&now>=capture.at()&&now-capture.at()<DashboardHive.FRESH_MS
                ?capture.value().deepCopy():SwarmSavingsTotals.hive(DashboardProtocol.JSON.createArrayNode());
    }
    private DashboardStore store;private DashboardStore.Link link;private Path database;private String hive="",build="";
    private WebSocket socket;private volatile boolean started;
    private record Capture(String account,ObjectNode telemetry){}
    private volatile Capture latest;private String metricsAccount="";
    private record StaffUpload(long epoch,Capture capture,ArrayNode rows){}
    private final AtomicReference<StaffUpload> staffPending=new AtomicReference<>();
    private final AtomicBoolean staffQueued=new AtomicBoolean();
    private DashboardActivityCounters activity;private String activityAccount="";private long observedTerminal;
    private long capturedAt,nextConnect,connectedAt,lastPing,lastTelemetry,metricsAt;private volatile long epoch;private long lastReceived;
    private int attempts;private boolean connecting,welcome,readSavingsSupported,dataUseSupported;
    private ObjectNode metrics;private String lastShape="";
    private final SettingsAnalysis settingsAnalysis = new SettingsAnalysis();
    private boolean settingsAnalysisSupported;
    private final DiagnosticHistory diagnosticHistory=new DiagnosticHistory();
    private long historyLastAttempt;
    public String diagnosticReportToken(){return diagnosticHistory.reportToken(panelAccount,CommunityClient.INSTANCE.diagnosticSharing(),System.currentTimeMillis());}
    private Path rankDirectory;private SwarmRankCoverage rankCoverage;private String rankAccount="",rankingSalt="",publishedAccountId="";
    private CompletableFuture<Void> writes=CompletableFuture.completedFuture(null);private int pendingWrites;private boolean forceTelemetry;
    private HostedDashboardClient(){}
    public View view(){return view;}
    /** Nonblocking handoff on the existing transport worker; game protection never waits for delivery. */
    public void queueStaffSightings(ArrayNode rows){
        var capture=latest;if(!started||capture==null||rows==null||rows.isEmpty())return;
        staffPending.set(new StaffUpload(epoch,capture,rows.deepCopy()));scheduleStaffUpload();
    }
    private void scheduleStaffUpload(){
        if(staffQueued.compareAndSet(false,true))worker.execute(this::publishStaffUpload);
    }
    private void publishStaffUpload(){
        var upload=staffPending.getAndSet(null);
        try {
            long now=System.currentTimeMillis();
            if(upload==null||upload.epoch()!=epoch||!welcome||!CommunityClient.INSTANCE.staffSharingSession(now)
                    ||!upload.capture().account().equalsIgnoreCase(panelAccount))return;
            var j=upload.capture().telemetry().deepCopy().put("at",now);
            if(metrics!=null&&metricsAccount.equals(upload.capture().account())){
                j.setAll(metrics);
                String identity=SwarmRank.accountId(rankingSalt,upload.capture().account());
                if(!identity.isEmpty())j.put("accountId",identity);
            }
            j.set("staffSightings",upload.rows());send(j);
        } finally {
            staffQueued.set(false);if(staffPending.get()!=null)scheduleStaffUpload();
        }
    }
    public synchronized void start(Path game,Path shared,Path db,String currentBuild) {
        if(started)return;started=true;store=new DashboardStore(game);hivePanel=new DashboardHive(shared);database=db;build=DashboardProtocol.build(currentBuild);rankDirectory=game.resolve("gonuts-dashboard/rank-coverage");
        worker.execute(()->{
            try{hive=DashboardStore.hive(shared);link=store.load();if(link!=null){view=new View(true,false,false,link.name,"Checking link",0);nextConnect=0;}}
            catch(Exception e){view=new View(false,false,false,"","Could not load private dashboard settings",0);}
        });
        worker.scheduleWithFixedDelay(()->{try{pump();}catch(Exception e){failed("Dashboard temporarily unavailable");}},250,250,TimeUnit.MILLISECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(()->{epochs.incrementAndGet();panel.close();worker.shutdownNow();if(socket!=null)socket.abort();},"gonuts-dashboard-close"));
    }
    public synchronized void pair(String code,String name) {
        if(!started||view.busy()||view.linked()||System.currentTimeMillis()<view.retryAt())return;
        String normalized;
        try{normalized=DashboardProtocol.code(code);}catch(IllegalArgumentException e){view=new View(false,false,false,"","Enter the eight-character code from the website",0);return;}
        view=new View(false,false,true,"","Linking securely...",0);long operation=epochs.incrementAndGet();
        worker.execute(()->{
            try {
                var request=HttpRequest.newBuilder(DashboardProtocol.REDEEM).timeout(Duration.ofSeconds(15)).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(DashboardProtocol.pairing(normalized,name,build,hive).toString())).build();
                var response=http.send(request,HttpResponse.BodyHandlers.ofByteArray());
                if(operation!=epochs.get())return;
                if(response.statusCode()!=201){long retry=response.statusCode()==429?retry(response):0;view=new View(false,false,false,"",DashboardProtocol.pairError(response.statusCode()),retry);return;}
                if(response.body().length>DashboardProtocol.MAX_BYTES)throw new IllegalStateException();
                var j=DashboardProtocol.JSON.readTree(response.body());
                if(j.path("protocol").asInt()!=1||!j.path("wsUrl").asText().equals(DashboardProtocol.SOCKET.toString())||!j.path("checkUrl").asText().equals(DashboardProtocol.CHECK.toString()))throw new IllegalStateException();
                var next=new DashboardStore.Link(j.path("installationId").asLong(),j.path("credential").asText(),j.path("name").asText());
                store.save(next);link=next;attempts=0;nextConnect=0;view=new View(true,false,false,next.name,"Linked; connecting",0);
                CommunityClient.INSTANCE.dashboardPaired();
            }catch(Exception e){if(operation==epochs.get())view=new View(false,false,false,"","Could not link or save private settings; use a new code if it was consumed",0);}
        });
    }
    /** Only community enrollment may use this dashboard credential; it never leaves this transport. */
    public CompletableFuture<HttpResponse<byte[]>> enrollCommunity(ObjectNode body) {
        var result=new CompletableFuture<HttpResponse<byte[]>>();
        worker.execute(()->{
            var current=link;if(current==null){result.completeExceptionally(new IllegalStateException("Dashboard not linked"));return;}
            var request=HttpRequest.newBuilder(CommunityWire.BASE.resolve("enroll")).timeout(Duration.ofSeconds(15))
                    .header("Authorization","Bearer "+current.credential).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
            http.sendAsync(request,HttpResponse.BodyHandlers.ofByteArray()).whenComplete((response,error)->{if(error!=null)result.completeExceptionally(new IllegalStateException("Enrollment unavailable"));else result.complete(response);});
        });return result;
    }
    private static long retry(HttpResponse<?> response){
        try{return System.currentTimeMillis()+Math.min(3600,Math.max(1,Long.parseLong(response.headers().firstValue("Retry-After").orElse("60"))))*1000;}
        catch(Exception e){return System.currentTimeMillis()+60_000;}
    }
    public synchronized void unlink(){
        settingsAnalysis.clear();
        diagnosticHistory.clear();
        staffPending.set(null);
        SwarmStaffSightings.INSTANCE.clear();
        SwarmRank.clear();
        if(!started)return;long old=epoch;epochs.incrementAndGet();commands.disconnected(old);panel.disconnected(old);view=new View(false,false,true,"","Removing local link...",0);
        CommunityClient.INSTANCE.dashboardUnlinked();
        worker.execute(()->{
            if(socket!=null)socket.abort();socket=null;link=null;welcome=false;connecting=false;nextConnect=Long.MAX_VALUE;
            try{store.forget();view=new View(false,false,false,"","Not linked; unlink the offline entry on the website too",0);}
            catch(Exception e){view=new View(false,false,false,"","Disconnected, but could not remove the local credential file",0);}
        });
    }
    public void reconnect(){if(!started||!view.linked()||view.busy())return;SwarmRank.clear();SwarmStaffSightings.INSTANCE.clear();worker.execute(()->{rankingSalt="";publishedAccountId="";epochs.incrementAndGet();commands.disconnected(epoch);panel.disconnected(epoch);attempts=0;nextConnect=0;if(socket!=null)socket.abort();socket=null;welcome=false;connecting=false;});}
    /** Called on the client thread: only reads already-held figures, never SQL or networking. */
    public void tick(Minecraft client){
        DecisionTrace.INSTANCE.observeSettings(System.currentTimeMillis());
        CommunityClient.INSTANCE.dashboardLinked(view.linked());
        long now=System.currentTimeMillis();String account=DoughBayClient.account();panelAccount=account;CommunityClient.INSTANCE.account(account);CommunityRegion.tick(client);CommunityClient.INSTANCE.connected(CommunityRegion.donut(client));CommunityPerformance.INSTANCE.tick(CommunityRegion.donut(client),System.nanoTime());
        if(activity==null||!activityAccount.equals(account)){activity=new DashboardActivityCounters(now);activityAccount=account;SwarmRank.clear();SwarmStaffSightings.INSTANCE.clear();staffPending.set(null);}
        if(!CommunityClient.INSTANCE.sharingStaffSightings(now))SwarmStaffSightings.INSTANCE.clear();
        var controller=DoughBayClient.automationSessionController();
        var session=controller.snapshot();
        var terminal=DoughBayClient.executionDriver().lastTerminalEvent();
        boolean reprice=terminal.intent()==AutomatedExecutionDriver.OperationIntent.LIST
                &&terminal.sequence()>observedTerminal&&terminal.outcome()==AutomatedExecutionDriver.TerminalOutcome.SUCCEEDED&&session.position()!=null
                &&controller.consumeDashboardReprice(session.position().positionId());
        observedTerminal=Math.max(observedTerminal,terminal.sequence());
        activity.observe(terminal.sequence(),terminal.intent().name(),terminal.outcome().name(),reprice,terminal.occurredAtMillis(),now);
        if(!view.linked()||now-capturedAt<1000)return;capturedAt=now;
        try {
            settingsAnalysis.capture(account, CommunityClient.INSTANCE.diagnosticSharing(), now, SettingsCode::exportForReport);
            if(diagnosticHistory.accountChanged(account)){diagnosticHistory.clear();reconnect();}
            diagnosticHistory.capture(account,CommunityClient.INSTANCE.diagnosticSharing(),now,
                    ()->FaultTrace.INSTANCE.report(now)+ActionTrace.INSTANCE.report(now).lines().filter(line->line.startsWith("act ")).limit(3).collect(java.util.stream.Collectors.joining("\n","","\n"))+controller.bugReportContext(now),SettingsCode::exportForReport);
        } catch (RuntimeException ignored) {
            settingsAnalysis.clear(); // Analysis must never interrupt trading or expose configuration in an exception log.
        }
        var update=UpdateCheck.status();boolean newer=update.state()==UpdateCheck.State.NEWER||update.state()==UpdateCheck.State.READY;
        var j=DashboardProtocol.telemetry(now,session.state().name(),NameMask.on()?"Account":account,
            Math.max(session.openListings(),Math.max(0,DoughBayClient.automationSessionController().serverListedSlots())),Math.round(Tuning.get("slots.max")),Math.max(0,SidebarBalance.current()),session.tradesStarted(),build,newer,session.updatedAtMillis());
        // No raw session detail/action text crosses the relay: it can contain identities, home names or paths.
        j.setAll(activity.snapshot(now));
        var swarm=CommunityClient.INSTANCE.view();
        j.put("mascotFace", Mascot.faceFor(session,DoughBayClient.evasionGuard()).name());
        j.putObject("swarm").put("enabled",swarm.path("enabled").asBoolean())
                .put("guidanceEnabled",swarm.path("guidanceEnabled").asBoolean()).put("eligible",swarm.path("eligible").asBoolean())
                .put("sentAt",SwarmActivity.sentAt()).put("receivedAt",SwarmActivity.receivedAt());
        if(SidebarBalance.current()<=0)j.remove("bank");
        latest=new Capture(account,j);
    }
    public ObjectNode hiveStatus(String account){return hivePanel==null?null:hivePanel.status(account,System.currentTimeMillis());}
    public boolean hiveAction(String account,String action,String code,String name) throws Exception {
        return hivePanel!=null&&hivePanel.post(account,action,code,name,System.currentTimeMillis());
    }
    private void pump() throws Exception {
        long now=System.currentTimeMillis();
        if(hivePanel!=null&&!panelAccount.isBlank()&&now-panelPublishedAt>=2000){
            panelPublishedAt=now;
            try{
                hivePanel.sweep(now);
                var request=hivePanel.take(panelAccount,now);
                if(request!=null)switch(request.path("action").asText()){
                    case "link" -> pair(request.path("code").asText(),request.path("name").asText());
                    case "unlink" -> unlink();
                    case "reconnect" -> reconnect();
                    case "community_enable" -> CommunityClient.INSTANCE.enable();
                    case "community_disable" -> CommunityClient.INSTANCE.disable();
                }
                hivePanel.publish(panelAccount,view,now);
                var accounts=new java.util.TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
                accounts.addAll(DoughBayClient.hiveAccounts());accounts.add(panelAccount);
                var rows=DashboardProtocol.JSON.createArrayNode();
                for(String account:accounts){
                    var status=hivePanel.status(account,now);var row=rows.addObject()
                            .put("id",DashboardHive.id(account)).put("available",status!=null);
                    if(status!=null)row.set("community",status.path("community"));
                }
                hiveSavings=new SavingsCapture(now,SwarmSavingsTotals.hive(rows));
                hiveUse=new SavingsCapture(now,SharedDataUse.hive(rows));
            }catch(Exception ignored){/* IPC failure never stops trading or exposes pairing data. */}
        }
        if(link==null||view.busy())return;
        if(socket==null&&!connecting&&now>=nextConnect){connect();return;}
        if(socket!=null&&!welcome&&now-connectedAt>10_000){failed("Dashboard handshake timed out");return;}
        if(!welcome)return;
        if(now-lastReceived>=80_000){failed("Dashboard heartbeat timed out; reconnecting");return;}
        if(now-lastPing>=20_000){send(DashboardProtocol.JSON.createObjectNode().put("type","ping").put("at",now));lastPing=now;}
        publish(false);
        publishDiagnostics(now);
    }
    private void publishDiagnostics(long now){
        if(!welcome||latest==null||now-historyLastAttempt<5000)return;
        var batch=diagnosticHistory.batch(latest.account(),CommunityClient.INSTANCE.diagnosticSharing(),now);
        if(batch==null)return;historyLastAttempt=now;
        if(send(batch))diagnosticHistory.sent(batch);
    }
    private void connect() {
        if(link==null)return;connecting=true;welcome=false;readSavingsSupported=false;dataUseSupported=false;settingsAnalysisSupported=false;diagnosticHistory.disconnected();epoch=epochs.incrementAndGet();long own=epoch;
        view=new View(true,false,false,link.name,"Checking link",0);
        var request=HttpRequest.newBuilder(DashboardProtocol.CHECK).timeout(Duration.ofSeconds(10)).header("Authorization","Bearer "+link.credential)
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        http.sendAsync(request,HttpResponse.BodyHandlers.ofByteArray()).whenComplete((r,e)->worker.execute(()->{
            if(epochs.get()!=own)return;
            if(e!=null){failed("Dashboard connection failed; retrying");return;}
            if(r.statusCode()==401){revoked();return;}
            try{
                if(r.statusCode()!=200||r.body().length>DashboardProtocol.MAX_BYTES)throw new IllegalStateException();
                var check=DashboardProtocol.JSON.readTree(r.body());
                if(!check.path("ok").asBoolean()||check.path("installationId").asLong()!=link.installationId||check.path("protocol").asInt()!=1)throw new IllegalStateException();
                String currentName=DashboardProtocol.label(check.path("name").asText(link.name));
                if(!currentName.equals(link.name)){
                    link=new DashboardStore.Link(link.installationId,link.credential,currentName);store.save(link);
                }
                http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).header("Authorization","Bearer "+link.credential)
                    .buildAsync(DashboardProtocol.SOCKET,new Listener(own)).whenComplete((ws,error)->worker.execute(()->{
                        if(epochs.get()!=own){if(ws!=null)ws.abort();return;}
                        if(error!=null){failed("Dashboard socket unavailable; retrying");return;}
                        socket=ws;connecting=false;connectedAt=System.currentTimeMillis();lastPing=connectedAt;lastTelemetry=0;lastShape="";writes=CompletableFuture.completedFuture(null);pendingWrites=0;
                        view=new View(true,false,false,link.name,"Negotiating dashboard protocol",0);
                        send(DashboardProtocol.hello(build,link.name,hive));
                    }));
            }catch(Exception ex){failed("Dashboard check failed; retrying");}
        }));
    }
    private void received(long own,String text){
        if(epochs.get()!=own||socket==null)return;
        try{
            var j=DashboardProtocol.parse(text);lastReceived=System.currentTimeMillis();String type=j.path("type").asText();
            if(type.equals("welcome")){
                if(welcome||j.path("protocol").asInt()!=1||j.path("installationId").asLong()!=link.installationId||!j.path("commands").isArray())throw new IllegalArgumentException();
                for(var action:j.path("commands"))if(!DashboardProtocol.CAPABILITIES.contains(action.asText()))throw new IllegalArgumentException();
                rankingSalt=j.path("rankingSalt").asText("");SwarmRank.clear();publishedAccountId="";
                readSavingsSupported=SwarmSavingsWire.negotiated(j);
                dataUseSupported=SharedDataUse.negotiated(j);
                settingsAnalysisSupported=SettingsAnalysis.negotiated(j);
                diagnosticHistory.welcome(j,System.currentTimeMillis());
                welcome=true;commands.connected(own);view=new View(true,true,false,link.name,"Online",0);publish(true);return;
            }
            if(!welcome)throw new IllegalArgumentException();
            if(type.equals("panel"))panel.receive(own,j,()->epochs.get()==own&&welcome,
                    reply->worker.execute(()->{if(epochs.get()==own&&welcome)send(reply);}));
            else if(type.equals("command"))command(own,j);
            else if(type.equals("diagnostic_history_ack"))diagnosticHistory.acknowledge(j);
            else if(type.equals("diagnostic_report_token"))diagnosticHistory.token(j,System.currentTimeMillis());
            else if(type.equals("swarm_rank")){
                long now=System.currentTimeMillis();
                SwarmRank.receive(j,publishedAccountId,now);
                SwarmTotals.receiveFrame(j,now);
            }
            else if(!type.equals("pong")&&!type.equals("error"))throw new IllegalArgumentException();
        }catch(Exception e){failed("Dashboard protocol error; retrying");}
    }
    private void command(long own,JsonNode j){
        String id=j.path("id").asText();if(!id.matches("cmd_[A-Za-z0-9_-]{1,64}")){failed("Invalid dashboard command");return;}
        var request=new DashboardCommands.Command(id,j.path("command").asText(),j.path("createdAt").asLong(),j.path("expiresAt").asLong());
        var receipt=commands.receive(own,request,System.currentTimeMillis());
        send(DashboardProtocol.JSON.createObjectNode().put("type","ack").put("id",id));
        if(receipt.result()!=null){result(own,id,receipt.result());return;}
        Minecraft.getInstance().execute(()->{
            if(epochs.get()!=own)commands.disconnected(own);
            var done=commands.dispatch(own,id,System.currentTimeMillis(),HostedDashboardClient::control);
            capturedAt=0;tick(Minecraft.getInstance());
            worker.execute(()->result(own,id,done));
        });
    }
    private static DashboardCommands.Result control(String action){
        if(action.equals("snapshot"))return new DashboardCommands.Result("completed","snapshot refreshed");
        if(action.equals("swarm_enable")) {
            if(!INSTANCE.view().linked())return new DashboardCommands.Result("rejected","dashboard link required");
            CommunityClient.INSTANCE.enable();
            return new DashboardCommands.Result("completed","Swarm opt-in requested; guidance waits for eligibility and safe idle");
        }
        if(action.equals("swarm_disable")) {
            if(INSTANCE.view().linked())return new DashboardCommands.Result("rejected","Unlink this client to leave Swarm; participation is included with the dashboard");
            CommunityClient.INSTANCE.disable();
            return new DashboardCommands.Result("completed","Swarm disabled; normal discovery resumes at safe idle");
        }
        if(!action.equals("pause")&&!action.equals("resume"))return new DashboardCommands.Result("rejected","unsupported");
        var session=DoughBayClient.automationSessionController();
        String answer=HiveControl.apply(session,action.equals("pause")?HiveControl.STOP:HiveControl.START);
        // Relay results are categories, never a controller exception or text containing player identities.
        boolean ok=answer.startsWith("OK:")||answer.startsWith("Stopped")||answer.startsWith("Already");
        return new DashboardCommands.Result(ok?"completed":"rejected",ok?action.equals("pause")?"trading paused":"trading resumed":"controller refused; check the local panel");
    }
    private void result(long own,String id,DashboardCommands.Result result){
        if(epochs.get()!=own||!welcome)return;
        send(DashboardProtocol.JSON.createObjectNode().put("type","result").put("id",id).put("status",result.status()).put("reason",result.reason()));publish(true);
    }
    private void publish(boolean force) {
        long now=System.currentTimeMillis();forceTelemetry|=force;var captured=latest;if(captured==null||now-lastTelemetry<1000)return;
        if(now-metricsAt>=15_000||!metricsAccount.equals(captured.account())){
            metricsAt=now;metricsAccount=captured.account();
            try{
                metrics=DashboardMetrics.read(database,captured.account(),now);
                // The private local filename uses an independent hash; only owner-salted ids go over the wire.
                if(!rankAccount.equals(captured.account())){
                    rankAccount=captured.account();SwarmRank.clear();
                    String local=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(rankAccount.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8)));
                    rankCoverage=new SwarmRankCoverage(rankDirectory.resolve(local+".json"));
                }
                metrics.put("metricsAt",now);
                double coverage=rankCoverage.observe(now);
                if(Math.abs(metrics.path("profit").path("h24").asDouble())<1_000_000_000_000L&&metrics.path("sales").path("h24").asLong()<1_000_000)
                    ((ObjectNode)metrics.get("profit")).put("h24Coverage",coverage);
            }catch(Exception e){metrics=null;if(rankCoverage!=null)rankCoverage.gap();SwarmRank.clear();}
        }
        var j=captured.telemetry().deepCopy();if(metrics!=null)j.setAll(metrics);
        String identity=metrics==null?"":SwarmRank.accountId(rankingSalt,captured.account());
        if(!identity.equals(publishedAccountId)){SwarmRank.clear();publishedAccountId=identity;}
        if(metrics!=null&&!identity.isEmpty())j.put("accountId",identity);
        var performance=CommunityPerformance.INSTANCE.view();
        SharedDataUse.attach(j,performance,SharedDataUse.INSTANCE.view(),welcome&&dataUseSupported&&readSavingsSupported&&metrics!=null&&!identity.isEmpty(),CommunityClient.INSTANCE.readSavingsSharing(),now);
        SwarmSavingsWire.attach(j,performance,welcome&&readSavingsSupported&&metrics!=null&&!identity.isEmpty(),
                CommunityClient.INSTANCE.readSavingsSharing(),now);
        j.set("staffSightings",SwarmStaffSightings.INSTANCE.snapshot(CommunityClient.INSTANCE.sharingStaffSightings(now),now));
        var shape=SwarmSavingsWire.comparison(j);String encoded=shape.toString();
        var settingsSnapshot = settingsAnalysis.pending(captured.account(), epoch,
                welcome && settingsAnalysisSupported && metrics != null && metricsAccount.equals(captured.account()) && !identity.isEmpty(),
                CommunityClient.INSTANCE.diagnosticSharing(), now);
        if(!forceTelemetry&&settingsSnapshot==null&&encoded.equals(lastShape)&&now-lastTelemetry<15_000)return;
        if(settingsSnapshot!=null)j.set("settingsAnalysis",settingsSnapshot);
        if(send(j)) {
            if(settingsSnapshot!=null)settingsAnalysis.sent(captured.account(),epoch,settingsSnapshot.path("sampledAt").asLong());
            lastShape=encoded;lastTelemetry=now;forceTelemetry=false;
        }
    }
    private boolean send(ObjectNode j){
        if(socket==null)return false;String body=j.toString();int limit=j.path("type").asText().equals("panel_result")?DashboardProtocol.MAX_PANEL_FRAME_BYTES:DashboardProtocol.MAX_BYTES;if(body.getBytes(StandardCharsets.UTF_8).length>limit)return false;
        WebSocket current=socket;long own=epoch;
        if(pendingWrites>=64){failed("Dashboard send queue full; reconnecting");return false;}
        pendingWrites++;
        writes=writes.handle((v,e)->null).thenCompose(v->{
            if(epochs.get()!=own)return CompletableFuture.completedFuture(null);
            return current.sendText(body,true).thenApply(ws->(Void)null);
        });
        writes.whenComplete((v,error)->worker.execute(()->{
            if(epochs.get()!=own)return;pendingWrites=Math.max(0,pendingWrites-1);
            if(error!=null)failed("Dashboard send failed; retrying");
        }));
        return true;
    }
    private void failed(String message){
        dev.doughbay.fabric.ActionTrace.INSTANCE.record(dev.doughbay.fabric.ActionTrace.Scope.DASHBOARD,dev.doughbay.fabric.ActionTrace.Event.RETRY,dev.doughbay.fabric.ActionTrace.Code.SOCKET,"IO_FAILURE",-1,-1,-1);
        staffPending.set(null);
        SwarmStaffSightings.INSTANCE.clear();
        SwarmRank.clear();rankingSalt="";publishedAccountId="";
        long old=epoch;epochs.incrementAndGet();commands.disconnected(old);panel.disconnected(old);
        if(socket!=null)socket.abort();socket=null;connecting=false;welcome=false;
        long now=System.currentTimeMillis();if(connectedAt>0&&now-connectedAt>=60_000)attempts=0;connectedAt=0;
        nextConnect=now+DashboardProtocol.backoff(attempts++,ThreadLocalRandom.current().nextDouble(.7,1.3));
        if(link!=null)view=new View(true,false,false,link.name,message,nextConnect);
    }
    private void revoked(){
        dev.doughbay.fabric.ActionTrace.INSTANCE.record(dev.doughbay.fabric.ActionTrace.Scope.DASHBOARD,dev.doughbay.fabric.ActionTrace.Event.REFUSED,dev.doughbay.fabric.ActionTrace.Code.SOCKET,"AUTHORIZATION_HOLD",-1,-1,-1);
        staffPending.set(null);
        SwarmStaffSightings.INSTANCE.clear();
        SwarmRank.clear();rankingSalt="";publishedAccountId="";
        long old=epoch;epochs.incrementAndGet();commands.disconnected(old);panel.disconnected(old);if(socket!=null)socket.abort();socket=null;link=null;welcome=false;connecting=false;
        try{store.forget();view=new View(false,false,false,"","Link revoked; pair with a new code",0);}
        catch(Exception e){view=new View(false,false,false,"","Link revoked; could not remove local credential file",0);}
    }
    private final class Listener implements WebSocket.Listener {
        private final long own;private final StringBuilder fragments=new StringBuilder();private int bytes;
        Listener(long own){this.own=own;}
        @Override public void onOpen(WebSocket ws){dev.doughbay.fabric.ActionTrace.INSTANCE.record(dev.doughbay.fabric.ActionTrace.Scope.DASHBOARD,dev.doughbay.fabric.ActionTrace.Event.CONNECTED,dev.doughbay.fabric.ActionTrace.Code.SOCKET,"NONE",-1,-1,-1);ws.request(1);}
        @Override public CompletionStage<?> onText(WebSocket ws,CharSequence data,boolean last){
            bytes+=data.toString().getBytes(StandardCharsets.UTF_8).length;
            if(bytes>DashboardProtocol.MAX_PANEL_FRAME_BYTES){ws.abort();worker.execute(()->{if(epochs.get()==own)failed("Oversized dashboard message");});return null;}
            fragments.append(data);if(last){String text=fragments.toString();fragments.setLength(0);bytes=0;worker.execute(()->received(own,text));}
            ws.request(1);return null;
        }
        @Override public CompletionStage<?> onBinary(WebSocket ws,java.nio.ByteBuffer data,boolean last){ws.abort();worker.execute(()->{if(epochs.get()==own)failed("Unsupported dashboard message");});return null;}
        @Override public CompletionStage<?> onClose(WebSocket ws,int code,String reason){worker.execute(()->{
            if(epochs.get()!=own)return;
            var closeCode=switch(code){case 4401->dev.doughbay.fabric.ActionTrace.Code.SOCKET_4401;case 4403->dev.doughbay.fabric.ActionTrace.Code.SOCKET_4403;case 4409->dev.doughbay.fabric.ActionTrace.Code.SOCKET_4409;case 1000->dev.doughbay.fabric.ActionTrace.Code.SOCKET_1000;case 1006->dev.doughbay.fabric.ActionTrace.Code.SOCKET_1006;default->dev.doughbay.fabric.ActionTrace.Code.SOCKET;};
            dev.doughbay.fabric.ActionTrace.INSTANCE.record(dev.doughbay.fabric.ActionTrace.Scope.DASHBOARD,dev.doughbay.fabric.ActionTrace.Event.DISCONNECTED,closeCode,"IO_FAILURE",-1,-1,-1);
            if(code==4401||code==4403){revoked();return;}
            if(code==4409){SwarmRank.clear();rankingSalt="";publishedAccountId="";epochs.incrementAndGet();commands.disconnected(own);panel.disconnected(own);socket=null;welcome=false;connecting=false;nextConnect=Long.MAX_VALUE;view=new View(true,false,false,link.name,"Another process owns this link; reconnect only if intentional",0);return;}
            failed("Dashboard offline; reconnecting");
        });return null;}
        @Override public void onError(WebSocket ws,Throwable error){worker.execute(()->{if(epochs.get()==own){dev.doughbay.fabric.FaultTrace.INSTANCE.record(dev.doughbay.fabric.FaultTrace.Stage.DASHBOARD_LINK,error,System.currentTimeMillis());failed("Dashboard connection failed; retrying");}});}
    }
}
