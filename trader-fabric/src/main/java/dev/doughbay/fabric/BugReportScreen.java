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
    private boolean collecting, sending, reviewed;
    private long retryAt;
    private int scroll;
    private boolean capabilityChecked, capabilityResolved, attachmentReady, includeLog = true;
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
        var prepare = CockpitButton.builder(Component.literal("Prepare"), b -> prepare()).bounds(x, 111, each, 20).build();
        prepare.active = !collecting && !sending && capabilityResolved; addRenderableWidget(prepare);
        sendButton = CockpitButton.builder(Component.literal("Send reviewed"), b -> send()).bounds(x + each + gap, 111, each, 20).build();
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
        var consent = CockpitButton.builder(Component.literal(reviewed ? "Reviewed; OK to publish" : "I reviewed this public report"), b -> {
            reviewed = !reviewed; rebuildWidgets();
        }).bounds(x, 138, Math.min(210, w), 20).build();
        consent.active = payload != null && !sending; addRenderableWidget(consent);
        if (issueUrl.isEmpty()) {
            var log = CockpitButton.builder(Component.literal(attachmentReady ? "Include log: " + (includeLog ? "On" : "Off") : "Log upload pending"), b -> {
                if (collecting || sending) return;
                includeLog = !includeLog; invalidate(); status = "Prepare again to review the changed attachment choice."; rebuildWidgets();
            }).bounds(x + w - 145, 138, 145, 20).build();
            log.active = attachmentReady && !collecting && !sending; addRenderableWidget(log);
        }
        if (!issueUrl.isEmpty()) addRenderableWidget(CockpitButton.builder(Component.literal("Open issue"), b ->
            Util.getPlatform().openUri(URI.create(issueUrl))).bounds(x + w - 85, 138, 85, 20).build());
        refreshSend();
    }
    private void refreshSend() {
        var config = DoughBayClient.activeConfig();
        sendButton.active = payload != null && reviewed && !collecting && !sending && issueUrl.isEmpty()
            && System.currentTimeMillis() >= retryAt;
    }
    private void invalidate() {
        payload = null; report = ""; reviewed = false; retryKey = ""; issueUrl = ""; scroll = 0; diagnosticLog = "";
        if (sendButton != null) sendButton.active = false;
    }
    private static String filtered(String input) {
        var config = DoughBayClient.activeConfig();
        if (config != null && !config.apiKey().isBlank()) input = input.replace(config.apiKey(), "[credential removed]");
        return BugReportPrivacy.clean(input);
    }
    private void prepare() {
        if (collecting || sending || !capabilityResolved) return;
        invalidate(); collecting = true; status = "Preparing locally; nothing is uploaded."; rebuildWidgets();
        String description = filtered(happenedDraft), expectation = filtered(expectedDraft);
        boolean withLog = attachmentReady && includeLog;
        CompletableFuture.supplyAsync(() -> {
            try {
                String version = FabricLoader.getInstance().getModContainer("doughbay").orElseThrow().getMetadata().getVersion().getFriendlyString();
                try (var input = BugReportScreen.class.getResourceAsStream("/doughbay-build.properties")) {
                    if (input != null) {
                        var properties = new java.util.Properties(); properties.load(input);
                        String stamp = properties.getProperty("built_at", "");
                        if (stamp.matches("[0-9]{13}") && version.length() + 14 <= 40) version += "-" + stamp;
                    }
                }
                var next = new BugReportPayload(version, "26.2", description, expectation,
                    BugReportDiagnostics.readCounters(FabricLoader.getInstance().getGameDir().resolve("logs/latest.log")));
                String excerpt = withLog ? BugReportLog.read(FabricLoader.getInstance().getGameDir().resolve("logs/latest.log")) : "";
                next.json(excerpt); return new Prepared(next, excerpt);
            } catch (Exception e) { return null; }
        }).thenAccept(result -> minecraft.execute(() -> {
            collecting = false; payload = result == null ? null : result.payload(); diagnosticLog = result == null ? "" : result.log();
            report = result == null ? "" : payload.preview() + (diagnosticLog.isEmpty() ? "" : "\n\nAttached filtered log:\n" + diagnosticLog);
            var config = DoughBayClient.activeConfig();
            status = result == null ? "Preparation failed. Describe the problem (10+ characters); no raw-log fallback."
                : "Review the full report below (scroll), then confirm it may be published.";
            if (minecraft.gui.screen() == this) rebuildWidgets();
        }));
    }
    private void send() {
        var config = DoughBayClient.activeConfig();
        if (payload == null || !reviewed || collecting || sending || !issueUrl.isEmpty() || System.currentTimeMillis() < retryAt) return;
        if (retryKey.isEmpty()) retryKey = java.util.UUID.randomUUID().toString();
        sending = true; status = "Sending reviewed report over HTTPS…"; rebuildWidgets();
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
    @Override public void onClose() { if (!sending) minecraft.gui.setScreen(parent); }
    @Override public void removed() { DoughBayClient.automationSessionController().setPanelOpen(false); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        refreshSend();
        g.fill(0, 0, width, height, 0xE0050910);
        int x = left() + 12, w = panelWidth() - 24;
        g.text(font, "REPORT A BUG", x, 12, UiTheme.GOLD);
        g.text(font, "GitHub issues are public. Raw logs are never included.", x, 23, UiTheme.MUTED);
        g.text(font, "What happened? (Do not include names or private chat)", x, 33, UiTheme.TEXT);
        g.text(font, "What did you expect?", x, 70, UiTheme.TEXT);
        int statusY = 163;
        for (var line : font.split(Component.literal(status), w)) { if (statusY > 173) break; g.text(font, line, x, statusY, UiTheme.SECONDARY); statusY += 10; }
        var lines = font.split(Component.literal(report), w);
        int y = 187;
        for (int i = scroll; i < lines.size() && y <= height - 22; i++, y += 10) g.text(font, lines.get(i), x, y, UiTheme.TEXT);
        if (!lines.isEmpty()) g.text(font, "Scroll to review all details before sending.", x, height - 12, UiTheme.MUTED);
        super.extractRenderState(g, mx, my, delta);
    }
}
