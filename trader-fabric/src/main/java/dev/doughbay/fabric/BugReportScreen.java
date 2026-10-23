package dev.doughbay.fabric;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import java.net.URI;
import java.util.concurrent.CompletableFuture;

/** Local preview and explicit consent precede every direct submission. */
public final class BugReportScreen extends Screen {
    private final Screen parent;
    private final BugReportTransport transport = new BugReportTransport();
    private EditBox happened, expected;
    private String happenedDraft = "", expectedDraft = "", report = "", status = "", retryKey = "", issueUrl = "";
    private BugReportPayload payload;
    private boolean collecting, sending, sendWhenReady;
    private long retryAt;
    private int scroll;
    private boolean capabilityChecked, capabilityResolved, attachmentReady;
    private String diagnosticLog = "";
    private net.minecraft.client.gui.components.Button sendButton;
    public BugReportScreen(Screen parent) { super(Component.literal("Report a GoNuts bug")); this.parent = parent; }
    private int left() { return (width - panelWidth()) / 2; }
    private int panelWidth() { return Math.min(580, width - 24); }
    @Override protected void init() {
        DoughBayClient.automationSessionController().setPanelOpen(true);
        if (!capabilityChecked) {
            capabilityChecked = true;
            var config = DoughBayClient.activeConfig();
            transport.supportsLogAttachments("", "").thenAccept(ready -> minecraft.execute(() -> {
                attachmentReady = ready;
                capabilityResolved = true;
                if (minecraft.gui.screen() == this) rebuildWidgets();
            }));
        }
        int x = left() + 12, w = panelWidth() - 24, gap = 5, each = (w - 3 * gap) / 4;
        addRenderableWidget(CockpitButton.builder(Component.literal("Feature request"), b ->
            Util.getPlatform().openUri(URI.create("https://github.com/cody-raves/Gonuts/issues/new?template=feature-request.yml")))
            .bounds(x + w - 130, 8, 130, 16).build());
        happened = new EditBox(font, x, 46, w, 20, Component.literal("What happened?"));
        happened.setMaxLength(1500); happened.setValue(happenedDraft); happened.setResponder(v -> { happenedDraft = v; invalidate(); });
        expected = new EditBox(font, x, 83, w, 20, Component.literal("What should happen?"));
        expected.setMaxLength(1500); expected.setValue(expectedDraft); expected.setResponder(v -> { expectedDraft = v; invalidate(); });
        happened.active = !collecting && !sending; expected.active = !collecting && !sending;
        addRenderableWidget(happened); addRenderableWidget(expected);
        var prepare = CockpitButton.builder(Component.literal("Preview"), b -> prepare()).bounds(x, 111, each, 20).build();
        prepare.active = !collecting && !sending && capabilityResolved; addRenderableWidget(prepare);
        sendButton = CockpitButton.builder(Component.literal("Send report"), b -> send()).bounds(x + each + gap, 111, each, 20).build();
        addRenderableWidget(sendButton);
        var github = CockpitButton.builder(Component.literal("GitHub fallback"), b -> {
            if (report.isEmpty() || sending) return;
            minecraft.keyboardHandler.setClipboard(report);
            Util.getPlatform().openUri(URI.create("https://github.com/cody-raves/Gonuts/issues/new?template=bug-report.yml"));
            status = "Paste the filtered report into GitHub and review it before submitting.";
        }).bounds(x + 2 * (each + gap), 111, each, 20).build();
        github.active = !report.isEmpty() && !sending; addRenderableWidget(github);
        var back = CockpitButton.builder(Component.literal("Back"), b -> onClose()).bounds(x + 3 * (each + gap), 111, each, 20).build();
        back.active = !sending; addRenderableWidget(back);
        if (!issueUrl.isEmpty()) addRenderableWidget(CockpitButton.builder(Component.literal("Open issue"), b ->
            Util.getPlatform().openUri(URI.create(issueUrl))).bounds(x + w - 85, 138, 85, 20).build());
        refreshSend();
    }
    private void refreshSend() {
        var config = DoughBayClient.activeConfig();
        sendButton.active = capabilityResolved && attachmentReady && !collecting && !sending && issueUrl.isEmpty()
            && System.currentTimeMillis() >= retryAt;
    }
    private void invalidate() {
        sendWhenReady = false; payload = null; report = ""; retryKey = ""; issueUrl = ""; scroll = 0; diagnosticLog = "";
        if (sendButton != null) sendButton.active = false;
    }
    private static String filtered(String input) {
        var config = DoughBayClient.activeConfig();
        if (config != null && !config.apiKey().isBlank()) input = input.replace(config.apiKey(), "[credential removed]");
        return BugReportPrivacy.clean(dev.doughbay.fabric.proxy.ProxyAccess.redact(input));
    }
    private void prepare() { prepare(false); }
    private void prepare(boolean autoSend) {
        if (collecting || sending || !capabilityResolved) return;
        invalidate(); sendWhenReady = autoSend; collecting = true; status = "Preparing locally; nothing is uploaded."; rebuildWidgets();
        String description = filtered(happenedDraft), expectation = filtered(expectedDraft);
        CompletableFuture.supplyAsync(() -> {
            try {
                String version = BugReportBuild.current();
                var next = new BugReportPayload(version, "26.2", description, expectation,
                    BugReportDiagnostics.readCountersForReport(FabricLoader.getInstance().getGameDir().resolve("logs/latest.log")));
                String excerpt = BugReportEvidence.readForReport(FabricLoader.getInstance().getGameDir().resolve("logs/latest.log"),description,expectation);
                next.json(excerpt); return new Prepared(next, excerpt);
            } catch (Exception e) { return null; }
        }).thenAccept(result -> minecraft.execute(() -> {
            collecting = false; payload = result == null ? null : result.payload(); diagnosticLog = result == null ? "" : result.log();
            report = result == null ? "" : payload.preview() + "\n\n" + BugReporterKey.NOTICE + "\n" + SwarmReportLinks.NOTICE + (diagnosticLog.isEmpty() ? "" : "\n\nAttached filtered log:\n" + diagnosticLog);
            var config = DoughBayClient.activeConfig();
            status = result == null ? "Preparation failed. Describe the problem (10+ characters); no raw-log fallback."
                : diagnosticLog.contains(BugReportLog.UNAVAILABLE)?"Historical log unavailable; current controller evidence is included. Review before sending.":"Preview ready. Sanitized diagnostics are attached automatically.";
            if (minecraft.gui.screen() == this) {
                rebuildWidgets();
                if (result != null && sendWhenReady) { sendWhenReady = false; send(); }
            }
        }));
    }
    private void send() {
        if (collecting || sending || !capabilityResolved || !attachmentReady || !issueUrl.isEmpty() || System.currentTimeMillis() < retryAt) return;
        if (payload == null) { prepare(true); return; }
        if (retryKey.isEmpty()) retryKey = java.util.UUID.randomUUID().toString();
        sending = true; status = "Sending sanitized report over HTTPS…"; rebuildWidgets();
        transport.send(payload, retryKey, "", "", diagnosticLog).thenAccept(result -> minecraft.execute(() -> {
            sending = false; status = result.message(); issueUrl = result.issueUrl(); retryAt = result.retryAtMillis();
            if (minecraft.gui.screen() == this) rebuildWidgets();
        }));
    }
    private record Prepared(BugReportPayload payload, String log) { }
    @Override public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (y >= 182 && !report.isEmpty()) {
            int count = font.split(Component.literal(report), panelWidth() - 24).size();
            scroll = Math.max(0, Math.min(Math.max(0, count - Math.max(1, (height - 200) / 10)), scroll - (int)Math.signum(dy) * 3));
            return true;
        }
        return super.mouseScrolled(x, y, dx, dy);
    }
    @Override public void onClose() { if (!sending) { sendWhenReady = false; minecraft.gui.setScreen(parent); } }
    @Override public void removed() { DoughBayClient.automationSessionController().setPanelOpen(false); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        refreshSend();
        g.fill(0, 0, width, height, 0xE0050910);
        int x = left() + 12, w = panelWidth() - 24;
        g.text(font, "REPORT A BUG", x, 12, UiTheme.GOLD);
        g.text(font, "Send publishes diagnostics and an anonymous report-grouping key.", x, 23, UiTheme.MUTED);
        g.text(font, "What happened? (Do not include names or private chat)", x, 33, UiTheme.TEXT);
        g.text(font, "What did you expect?", x, 70, UiTheme.TEXT);
        if(issueUrl.isEmpty())g.text(font,UiTheme.fit(font,!capabilityResolved?"Checking sanitized log support...":attachmentReady
                ?"Sanitized diagnostic logs are included automatically.":"Receiver log support unavailable; reopen to retry.",w),x,145,UiTheme.SECONDARY);
        int statusY = 163;
        for (var line : font.split(Component.literal(status), w)) { if (statusY > 173) break; g.text(font, line, x, statusY, UiTheme.SECONDARY); statusY += 10; }
        var lines = font.split(Component.literal(report), w);
        int y = 187;
        for (int i = scroll; i < lines.size() && y <= height - 22; i++, y += 10) g.text(font, lines.get(i), x, y, UiTheme.TEXT);
        if (!lines.isEmpty()) g.text(font, "Scroll to review all details before sending.", x, height - 12, UiTheme.MUTED);
        super.extractRenderState(g, mx, my, delta);
    }
}
