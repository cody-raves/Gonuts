package dev.doughbay.fabric.staffwatch;

import dev.doughbay.fabric.DoughBayClient;
import dev.doughbay.fabric.staffwatch.event.EventPriority;
import dev.doughbay.fabric.staffwatch.event.impl.PacketReceiveNettyEvent;
import dev.doughbay.fabric.staffwatch.module.ModuleRegistry;
import dev.doughbay.fabric.staffwatch.module.impl.misc.AntiVanishModule;
import dev.doughbay.fabric.staffwatch.module.impl.misc.StaffListModule;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Minecraft adapter. Region commands come from the server's own completion list. */
final class StaffRegionEscapeController {
    private static final Set<String> ALLOWED = Set.of("east", "west", "asia", "oceania", "eu west", "eu central");
    private static final AtomicInteger IDS = new AtomicInteger(90_000);
    private final Consumer<String> disconnect;
    private volatile List<String> regions = List.of();
    private volatile int discoveryId = -1;
    private final RegionArrivalProof arrival = new RegionArrivalProof();
    private long nextDiscovery;
    private volatile long requestedAt;
    private Object world, player;
    private double originX, originY, originZ;
    private volatile String command = "";
    private boolean paused, resumeTrading;
    final StaffRegionEscapeFlow flow;

    StaffRegionEscapeController(Consumer<String> disconnect) {
        this.disconnect = disconnect;
        flow = new StaffRegionEscapeFlow(new StaffRegionEscapeFlow.Client() {
            public boolean connected() { return mc().level != null && mc().player != null && mc().getConnection() != null; }
            public List<String> regions() { return regions; }
            public String regionHint() {
                String sent = DoughBayClient.executionDriver().lastEscapeCommand();
                if (sent.startsWith("rtp ") && ALLOWED.contains(sent.substring(4))) return sent.substring(4);
                var server = mc().getCurrentServer();
                if (server != null && server.ip.startsWith("east.")) return "east";
                if (server != null && server.ip.startsWith("west.")) return "west";
                return "";
            }
            public boolean pauseSafely() {
                var session = DoughBayClient.automationSessionController();
                if (!session.safeToLeave() || !mc().player.containerMenu.getCarried().isEmpty()) return false;
                if (paused) return true;
                String state = session.snapshot().state().name();
                if (!List.of("SCANNING", "MONITORING", "PAUSED", "STOPPED", "IDLE").contains(state)) return false;
                resumeTrading = !List.of("PAUSED", "STOPPED", "IDLE").contains(state);
                // Do not turn a stopped session into an armed trading session on arrival.
                if (resumeTrading) session.pause("Staff Watch: preparing region escape");
                session.holdForStaffTransfer(true);
                DoughBayClient.executionDriver().setExternalAllowed(true);
                if (mc().player.containerMenu != mc().player.inventoryMenu) mc().player.closeContainer();
                paused = true;
                return session.safeToLeave(); // The pause checkpoint must also be durable.
            }
            public boolean requestRegion(String region) {
                if (!ALLOWED.contains(region) || DoughBayClient.executionDriver().refusesTeleports()) return false;
                command = "rtp " + region;
                requestedAt = System.currentTimeMillis(); world = mc().level; player = mc().player;
                originX=mc().player.getX();originY=mc().player.getY();originZ=mc().player.getZ();
                arrival.begin(requestedAt);
                DoughBayClient.executionDriver().sendWhenClear(mc(), command);
                return true;
            }
            public boolean commandSent() {
                var driver = DoughBayClient.executionDriver();
                return driver.lastEscapeCommand().equals(command) && driver.lastEscapeSentAt() >= requestedAt;
            }
            public boolean transferConfirmed() {
                if (!connected()) return false;
                double dx=mc().player.getX()-originX,dy=mc().player.getY()-originY,dz=mc().player.getZ()-originZ;
                boolean moved=mc().level!=world||mc().player!=player||dx*dx+dy*dy+dz*dz>=128*128;
                // Donut may preserve the world/player on RTP. Do not require respawn,
                // but do require a server teleport and a fresh post-teleport reply.
                if (arrival.needsProbe(moved)) {
                    int id=IDS.incrementAndGet();arrival.probe(id);
                    mc().getConnection().send(new ServerboundCommandSuggestionPacket(id,"minecraft:msg "));
                }
                return arrival.confirmed(commandSent(),moved);
            }
            public void clearDetections() {
                var anti = ModuleRegistry.get(AntiVanishModule.class);
                var staff = ModuleRegistry.get(StaffListModule.class);
                if (anti != null) anti.resetForRegionTransfer();
                if (staff != null) staff.resetForRegionTransfer();
            }
            public void resume() {
                boolean wasRunning = resumeTrading;
                release();
                if (wasRunning) {
                    var result = DoughBayClient.automationSessionController().resumeAfterStaffTransfer();
                    DoughBayClient.LOGGER.info("Staff Watch region escape: resume result {}", result);
                }
            }
            public void release() {
                if (!command.isBlank()) DoughBayClient.executionDriver().withdrawExternal(command);
                command = ""; paused = false; resumeTrading = false;
                DoughBayClient.automationSessionController().holdForStaffTransfer(false);
            }
            public void disconnect(String watcher, String reason) { disconnect.accept(watcher); }
            public void notice(String watcher, String detail) {
                DoughBayClient.LOGGER.warn("Staff Watch region escape [{}]: {}", watcher, detail);
            }
        }, System::currentTimeMillis);
        PacketReceiveNettyEvent.TYPE.listen(this, EventPriority.HIGHEST, event -> {
            if (event.packet instanceof ClientboundRespawnPacket || event.packet instanceof ClientboundPlayerPositionPacket) {
                var driver=DoughBayClient.executionDriver();
                if (!command.isBlank() && driver.lastEscapeCommand().equals(command))
                    arrival.teleport(System.currentTimeMillis(),driver.lastEscapeSentAt());
            }
            if (event.packet instanceof ClientboundCommandSuggestionsPacket reply && arrival.complete(reply.id())) {
                event.setCancelled(true);return;
            }
            if (event.packet instanceof ClientboundCommandSuggestionsPacket reply && reply.id() == discoveryId) {
                discoveryId = -1;
                regions = reply.toSuggestions().getList().stream().map(s -> s.getText().toLowerCase(java.util.Locale.ROOT).strip())
                    .filter(ALLOWED::contains).distinct().toList();
                event.setCancelled(true);
            }
        });
    }
    private static Minecraft mc() { return Minecraft.getInstance(); }
    void tick(boolean enabled) {
        // A cancelled READ may deliver its menu one tick after the safe pause.
        // Close that late page before the command gate drains the queued RTP.
        if (paused && mc().player != null && DoughBayClient.automationSessionController().safeToLeave()
                && mc().player.containerMenu != mc().player.inventoryMenu
                && mc().player.containerMenu.getCarried().isEmpty()) mc().player.closeContainer();
        if (enabled && mc().getConnection() != null && mc().level != null && System.currentTimeMillis() >= nextDiscovery) {
            nextDiscovery = System.currentTimeMillis() + 30_000;
            discoveryId = IDS.incrementAndGet();
            mc().getConnection().send(new ServerboundCommandSuggestionPacket(discoveryId, "rtp "));
        }
        flow.tick(enabled);
    }
    void cancel() { flow.cancel(); regions = List.of(); discoveryId = -1; nextDiscovery = 0; }
}
