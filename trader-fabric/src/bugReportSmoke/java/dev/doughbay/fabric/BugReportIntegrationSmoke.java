package dev.doughbay.fabric;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import static org.lwjgl.opengl.GL33.*;
import org.lwjgl.system.MemoryUtil;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;

/** Disposable, offline GUI verification. Never joins a Minecraft server. */
public final class BugReportIntegrationSmoke implements ClientModInitializer {
    private int phase, ticks;
    private long start = System.nanoTime();
    private final List<String> checks = new ArrayList<>();
    private BugReportScreen screen;
    private CompletableFuture<BugReportTransport.Result> duplicate;
    private String issue;
    public void onInitializeClient() {
        Tuning.set("join.auto", 0); Tuning.set("update.auto", 0); Tuning.set("session.auto_resume", 0);
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }
    private Object field(String name) throws Exception {
        var f = BugReportScreen.class.getDeclaredField(name); f.setAccessible(true); return f.get(screen);
    }
    private Button button(String label) {
        return (Button) Screens.getWidgets(screen).stream().filter(w -> w instanceof Button && w.getMessage().getString().equals(label)).findFirst().orElseThrow();
    }
    private void press(String label) { button(label).onPress(null); }
    private void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); checks.add(label); }
    private void next() { phase++; ticks = 0; }
    private void tick(Minecraft mc) {
        if (phase == 99) return;
        try {
            if (System.nanoTime() - start > 180_000_000_000L) throw new AssertionError("GUI test timed out");
            ticks++; if (mc.gui.overlay() != null) return;
            if (Boolean.getBoolean("gonuts.bugReport.screenshotsOnly")) { screenshots(mc); return; }
            switch (phase) {
                case 0 -> {
                    if (ticks < 70) return;
                    check(mc.level == null, "Test is offline, not joined to a server");
                    check(DoughBayClient.activeConfig().apiKey().isBlank(), "Install has no API token configured");
                    mc.options.pauseOnLostFocus = false; mc.options.guiScale().set(2); mc.resizeGui();
                    screen = new BugReportScreen(mc.gui.screen()); mc.gui.setScreen(screen); next();
                }
                case 1 -> {
                    if (ticks < 60) return;
                    ((EditBox) field("happened")).setValue("[test] Please close: public GoNuts GUI smoke test: local privacy filtering and explicit consent. Test data: test@example.invalid https://example.invalid/private 192.0.2.1 token=test-only");
                    ((EditBox) field("expected")).setValue("One public test issue, with private examples removed and a duplicate retry returning the same issue.");
                    press("Prepare"); next();
                }
                case 2 -> {
                    if (ticks < 30 || (boolean) field("collecting")) return;
                    var payload = (BugReportPayload) field("payload");
                    check(payload != null, "Prepared valid receiver payload from GUI");
                    check((boolean)field("attachmentReady") && !((String)field("diagnosticLog")).isEmpty(), "Public capabilities enable a locally filtered log attachment");
                    for (String secret : List.of("test@example", "example.invalid", "192.0.2.1", "test-only"))
                        check(!payload.description().contains(secret), "Private sample removed: " + secret);
                    check(!button("Send reviewed").active, "Submission disabled before explicit public-report consent");
                    capture(mc, "bug-report-preview.png");
                    press("I reviewed this public report");
                    check(button("Send reviewed").active, "Reviewed report can be submitted without authentication");
                    press("Send reviewed"); next();
                }
                case 3 -> {
                    if ((boolean) field("sending")) return;
                    issue = (String) field("issueUrl");
                    check(!issue.isEmpty(), "GUI submission accepted: " + field("status"));
                    check(button("Open issue").active, "GUI offers the accepted issue link");
                    check(!button("Send reviewed").active, "Accepted report cannot be accidentally resubmitted");
                    var config = DoughBayClient.activeConfig();
                    duplicate = new BugReportTransport().send((BugReportPayload) field("payload"), (String) field("retryKey"), "", "", (String)field("diagnosticLog"));
                    next();
                }
                case 4 -> {
                    if (!duplicate.isDone()) return;
                    var result = duplicate.join();
                    check(result.filed() && result.issueUrl().equals(issue) && result.message().contains("Already"), "Same identifier reuses the issue on a duplicate retry");
                    capture(mc, "bug-report-success.png");
                    mc.options.guiScale().set(3); mc.resizeGui(); next();
                }
                case 5 -> {
                    if (ticks < 20) return;
                    screen.mouseScrolled(200, 200, 0, -30);
                    capture(mc, "bug-report-compact.png");
                    check((int) field("scroll") > 0, "Long preview can be reviewed by scrolling in a compact window");
                    Files.writeString(Path.of("bug-report-gui-result.json"), new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("passed",true,"issueUrl",issue,"checks",checks)));
                    phase = 99; mc.stop();
                }
            }
        } catch (Throwable e) {
            try { Files.writeString(Path.of("bug-report-gui-result.json"), new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("passed",false,"checks",checks,"failure",e.getClass().getSimpleName()+": "+e.getMessage()))); } catch (Exception ignored) { }
            phase = 99; mc.stop();
        }
    }
    private void screenshots(Minecraft mc) throws Exception {
        switch (phase) {
            case 0 -> {
                if (ticks < 70) return;
                mc.options.pauseOnLostFocus=false; mc.options.guiScale().set(2); mc.resizeGui();
                mc.gui.setScreen(new DoughBayScreen(DoughBayClient.watcher())); next();
            }
            case 1 -> {
                if(ticks < 15) return;
                ((Button)Screens.getWidgets(mc.gui.screen()).stream().filter(w -> w instanceof Button && w.getMessage().getString().equals("About")).findFirst().orElseThrow()).onPress(null);
                next();
            }
            case 2 -> {
                if(ticks < 15) return;
                capture(mc,"bug-report-entrypoint.png");
                ((Button)Screens.getWidgets(mc.gui.screen()).stream().filter(w -> w instanceof Button && w.getMessage().getString().equals("Report bug")).findFirst().orElseThrow()).onPress(null);
                screen=(BugReportScreen)mc.gui.screen(); next();
            }
            case 3 -> {
                if(ticks < 60) return;
                ((EditBox)field("happened")).setValue("Example: the bot pauses while preparing a listing and does not continue.");
                ((EditBox)field("expected")).setValue("Finish the listing or explain what needs attention.");
                press("Prepare"); next();
            }
            case 4 -> {
                if(ticks < 30 || (boolean)field("collecting")) return;
                capture(mc,"bug-report-shareable.png");
                phase=99; mc.stop();
            }
        }
    }
    private void capture(Minecraft mc, String name) throws Exception {
        int w = mc.getWindow().getWidth(), h = mc.getWindow().getHeight();
        int fb = glGetInteger(GL_READ_FRAMEBUFFER_BINDING), pack = glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING), align = glGetInteger(GL_PACK_ALIGNMENT);
        int row = glGetInteger(GL_PACK_ROW_LENGTH), sx = glGetInteger(GL_PACK_SKIP_PIXELS), sy = glGetInteger(GL_PACK_SKIP_ROWS);
        var bytes = MemoryUtil.memAlloc(w*h*4);
        try {
            glBindFramebuffer(GL_READ_FRAMEBUFFER,0); glReadBuffer(GL_BACK); glBindBuffer(GL_PIXEL_PACK_BUFFER,0);
            glPixelStorei(GL_PACK_ALIGNMENT,1); glPixelStorei(GL_PACK_ROW_LENGTH,0); glPixelStorei(GL_PACK_SKIP_PIXELS,0); glPixelStorei(GL_PACK_SKIP_ROWS,0);
            glReadPixels(0,0,w,h,GL_RGBA,GL_UNSIGNED_BYTE,bytes);
            var image = new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
            for (int y=0;y<h;y++) for (int x=0;x<w;x++) {int p=(y*w+x)*4; image.setRGB(x,h-1-y,((bytes.get(p)&255)<<16)|((bytes.get(p+1)&255)<<8)|(bytes.get(p+2)&255));}
            ImageIO.write(image,"png",Path.of(name).toFile());
        } finally {MemoryUtil.memFree(bytes); glBindFramebuffer(GL_READ_FRAMEBUFFER,fb); glBindBuffer(GL_PIXEL_PACK_BUFFER,pack); glPixelStorei(GL_PACK_ALIGNMENT,align); glPixelStorei(GL_PACK_ROW_LENGTH,row); glPixelStorei(GL_PACK_SKIP_PIXELS,sx); glPixelStorei(GL_PACK_SKIP_ROWS,sy);}
    }
}
