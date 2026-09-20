package dev.doughbay.fabric;

import dev.doughbay.core.execution.ExecutionResult;
import dev.doughbay.fabric.automation.AutomationSessionController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Stops the session when another player comes close, and resumes it once
 * the area has been clear for a while. The client already receives every
 * player within render distance, so the check is a distance per tick.
 */
public final class EvasionGuard {
    public static int radiusBlocks() {
        return (int) Tuning.get("evasion.radius_blocks");
    }

    private static long clearBeforeResumeMillis() {
        return Tuning.millis("evasion.clear_before_resume_sec");
    }

    private final Path store;
    private volatile boolean enabled;
    private boolean triggered;
    private long clearSinceMillis;
    private String lastIntruder = "";

    EvasionGuard(Path store) {
        this.store = store;
        try {
            enabled = Files.exists(store)
                    && Files.readString(store, StandardCharsets.UTF_8).strip().equals("on");
        } catch (IOException ignored) {
            enabled = false;
        }
    }

    /**
     * On only when both say so: the Automation tab's button and the Evasion
     * setting. The setting is the one people look for, and it wins.
     */
    public boolean enabled() {
        return enabled && Tuning.get("evasion.enabled") >= 0.5;
    }

    public boolean triggered() {
        return triggered;
    }

    /** One thing the guard saw or did: when, who, how far, and what. */
    public record Event(long at, String who, double distance, String action) {}
    private final java.util.ArrayDeque<Event> events = new java.util.ArrayDeque<>();

    /** The shared ledger the events are written to, so the panel sees every client's; null keeps them in memory. */
    private static volatile java.nio.file.Path ledger;

    public static void setLedger(java.nio.file.Path path) {
        ledger = path;
    }

    private void note(String who, double distance, String action) {
        long at = System.currentTimeMillis();
        String name = who == null ? "" : who;
        double blocks = distance == Double.MAX_VALUE ? 0 : distance;
        synchronized (events) {
            events.addFirst(new Event(at, name, blocks, action));
            while (events.size() > 40) events.removeLast();
        }
        java.nio.file.Path path = ledger;
        if (path == null) return;
        String client = DoughBayClient.account();
        // Off the render thread: the ledger is large and shared, and a write
        // that waits on the other client must not stall a frame.
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try (dev.doughbay.storage.Database db = new dev.doughbay.storage.Database(path);
                 java.sql.PreparedStatement ps = db.connection().prepareStatement(
                         "INSERT INTO evasion_events (client, at, who, distance, action) VALUES (?,?,?,?,?)")) {
                ps.setString(1, client);
                ps.setLong(2, at);
                ps.setString(3, name);
                ps.setDouble(4, blocks);
                ps.setString(5, action);
                ps.executeUpdate();
            } catch (Exception e) {
                DoughBayClient.LOGGER.debug("DoughBay evasion could not record an event: {}", e.toString());
            }
        });
    }

    /** Newest first. */
    public java.util.List<Event> events() {
        synchronized (events) {
            return java.util.List.copyOf(events);
        }
    }

    /** True while a move-on teleport, with nobody about, is in the air. */
    private boolean wandering;
    /** Consecutive /rtp attempts that landed under water; capped so a flooded map cannot loop. */
    private int underwaterRtpCount;
    /** How many times in a row the all-clear resume has been refused. */
    private int resumeRefusals;

    public String lastIntruder() {
        return lastIntruder;
    }

    public void setEnabled(boolean value) {
        enabled = value;
        if (!value) {
            triggered = false;
            clearSinceMillis = 0;
        }
        try {
            Files.createDirectories(store.getParent());
            Files.writeString(store, value ? "on" : "off", StandardCharsets.UTF_8);
        } catch (IOException e) {
            DoughBayClient.LOGGER.warn("DoughBay could not save the evasion toggle: {}", e.toString());
        }
    }

    /** Short status for the HUD line and the Automation tab. */
    /** The outer ring: someone this close is named in the log and in chat, but nothing stops yet. */
    public static int warnRadius() {
        return (int) Tuning.get("evasion.warn_blocks");
    }

    /** How close someone has to come before the client leaves the server; 0 never leaves. */
    public static int disconnectRadius() {
        return (int) Tuning.get("evasion.disconnect_blocks");
    }

    /** How long to stay off before coming back; 0 stays off until the player returns. */
    private static long reconnectAfterMillis() {
        return (long) (Tuning.get("evasion.reconnect_sec") * 1000);
    }

    /** What the guard does when someone crosses the inner ring: 0 nothing, 1 teleport away, 2 leave the server. */
    private static int escapeMode() {
        return (int) Tuning.get("evasion.escape");
    }

    /** How long the server takes to actually move you after {@code /rtp}. */
    private static long teleportWaitMillis() {
        return (long) (Tuning.get("evasion.rtp_wait_sec") * 1000);
    }

    /** Someone this close during the teleport warm-up means it will not finish in time: leave instead. */
    private static int abortRadius() {
        return (int) Tuning.get("evasion.rtp_abort_blocks");
    }

    /** Someone closing this fast is treated by the time they need to reach us, not by the ring they are in. */
    private static double contactSeconds() {
        return Tuning.get("evasion.contact_sec");
    }

    /** How far out a player gliding on an elytra is already treated as a threat. */
    private static int glideWatchBlocks() {
        return (int) Tuning.get("evasion.glide_blocks");
    }

    private record Track(double distance, long at) { }

    private final java.util.Map<java.util.UUID, Track> tracks = new java.util.HashMap<>();

    /**
     * The fastest anyone is closing on us, expressed as the seconds they need
     * to arrive. A player walking is twenty seconds away at ten blocks; a
     * player on an elytra is two. Returns null when nobody is closing.
     */
    private ClosingThreat closingThreat(Minecraft client, long now) {
        ClosingThreat worst = null;
        java.util.Set<java.util.UUID> seen = new java.util.HashSet<>();
        for (Player other : client.level.players()) {
            if (other == client.player) continue;
            if (SafeHomes.isAlly(other.getName().getString())) continue;
            java.util.UUID id = other.getUUID();
            seen.add(id);
            double distance = other.distanceTo(client.player);
            Track previous = tracks.put(id, new Track(distance, now));
            boolean gliding = other.isFallFlying();
            if (gliding && distance <= glideWatchBlocks()) {
                double seconds = distance / 40.0;   // a rocket-boosted glide is about forty blocks a second
                if (worst == null || seconds < worst.seconds()) {
                    worst = new ClosingThreat(other.getName().getString(), distance, seconds, true);
                }
                continue;
            }
            if (previous == null) continue;
            double dt = (now - previous.at()) / 1000.0;
            if (dt < 0.2 || dt > 5) continue;
            double closing = (previous.distance() - distance) / dt;
            if (closing <= 1.0) continue;   // not meaningfully coming at us
            double seconds = distance / closing;
            if (worst == null || seconds < worst.seconds()) {
                worst = new ClosingThreat(other.getName().getString(), distance, seconds, false);
            }
        }
        tracks.keySet().retainAll(seen);
        return worst;
    }

    private record ClosingThreat(String name, double distance, double seconds, boolean gliding) { }

    /**
     * Whether anything could actually reach us from there, or whether there is
     * a wall in the way. A skeleton fourteen blocks off through the roof of a
     * sealed room is not a threat, and treating it as one had a client hopping
     * between its two homes all afternoon.
     */
    private static boolean canSeeUs(net.minecraft.world.entity.Entity entity, Minecraft client) {
        if (Tuning.get("evasion.line_of_sight") < 0.5) return true;
        try {
            net.minecraft.world.phys.Vec3 from = entity.getEyePosition();
            net.minecraft.world.phys.Vec3 to = client.player.getEyePosition();
            net.minecraft.world.phys.HitResult hit = client.level.clip(new net.minecraft.world.level.ClipContext(
                    from, to,
                    net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.NONE,
                    entity));
            return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS;
        } catch (RuntimeException ex) {
            return true;   // cannot tell: treat it as a threat
        }
    }

    /** Mobs that shoot: distance is no protection from these. */
    private static boolean ranged(net.minecraft.world.entity.Entity entity) {
        if (entity instanceof net.minecraft.world.entity.monster.RangedAttackMob) return true;
        // Ghasts and blazes shoot without implementing that interface in every
        // version, so their names are checked as well.
        String type = entity.getType().toString().toLowerCase(java.util.Locale.ROOT);
        return type.contains("ghast") || type.contains("blaze") || type.contains("shulker")
                || type.contains("skeleton") || type.contains("witch") || type.contains("pillager")
                || type.contains("stray") || type.contains("bogged") || type.contains("illusioner");
    }

    /**
     * Whether a mob is looking more or less straight at us. The server never
     * tells the client what a mob is targeting, but it does send where the mob
     * is looking, and a skeleton lines up before it shoots.
     */
    private static boolean facingUs(net.minecraft.world.entity.Entity entity, Minecraft client) {
        net.minecraft.world.phys.Vec3 toUs = client.player.position().subtract(entity.position()).normalize();
        net.minecraft.world.phys.Vec3 look = entity.getLookAngle().normalize();
        double dot = toUs.dot(look);
        return dot > 0.87;   // within about thirty degrees
    }

    /** A projectile that is closing on us rather than flying past. */
    private static boolean isIncoming(net.minecraft.world.entity.Entity entity, Minecraft client) {
        // The projectile classes have moved between versions, so the entity
        // type's own name is what decides: arrow, potion, fireball, skull.
        String type = entity.getType().toString().toLowerCase(java.util.Locale.ROOT);
        boolean projectile = type.contains("arrow") || type.contains("potion") || type.contains("fireball")
                || type.contains("skull") || type.contains("bullet") || type.contains("trident");
        if (!projectile) return false;
        net.minecraft.world.phys.Vec3 velocity = entity.getDeltaMovement();
        if (velocity.lengthSqr() < 0.01) return false;
        net.minecraft.world.phys.Vec3 toUs = client.player.position().subtract(entity.position()).normalize();
        return velocity.normalize().dot(toUs) > 0.7;
    }

    private volatile long teleportedAt;
    /** When the next unprompted move is due; zero until the first one is scheduled. */
    private long wanderAt;
    private volatile net.minecraft.world.phys.Vec3 teleportFrom;
    private volatile String warnedOf = "";
    private volatile long warnedAt;
    private volatile float lastHealth = -1;
    private volatile double lastFallDistance;
    private volatile long lastPlayerInputAt;
    private volatile double lastLookYaw = Double.NaN;

    /**
     * Whether the player is at the keyboard. A human who is walking, looking
     * around or attacking does not need the bot to run away on their behalf,
     * and jumping off a ledge should not empty the auction house.
     */
    private boolean playerIsDriving(Minecraft client, long now) {
        boolean input = client.options.keyUp.isDown() || client.options.keyDown.isDown()
                || client.options.keyLeft.isDown() || client.options.keyRight.isDown()
                || client.options.keyJump.isDown() || client.options.keyAttack.isDown()
                || client.options.keyUse.isDown() || client.options.keySprint.isDown();
        double yaw = client.player.getYRot();
        if (!Double.isNaN(lastLookYaw) && Math.abs(yaw - lastLookYaw) > 3.0) input = true;
        lastLookYaw = yaw;
        if (input) lastPlayerInputAt = now;
        return now - lastPlayerInputAt < (long) (Tuning.get("evasion.player_idle_sec") * 1000);
    }
    private volatile boolean leaveWanted;
    private volatile long leaveWantedAt;
    private volatile boolean away;
    private volatile long leftAt;
    private volatile boolean waitingLogged;
    private volatile net.minecraft.client.multiplayer.ServerData lastServer;

    /** True while the guard is holding the client off the server. */
    public boolean away() {
        return away;
    }

    public String status() {
        if (!enabled) return "off";
        if (away) return "left the server, " + lastIntruder + " came too close";
        if (leaveWanted) return "leaving once the trade in flight finishes";
        if (triggered) return "paused, " + lastIntruder + " nearby";
        return "on, " + radiusBlocks() + " blocks";
    }

    void tick(Minecraft client, AutomationSessionController session) {
        if (!enabled || client == null) return;
        long now = System.currentTimeMillis();

        // Off the server: come back when the wait is up.
        if (away) {
            if (client.player != null) {
                away = false;
                leaveWanted = false;
                triggered = true;   // resume runs through the ordinary all-clear path
                clearSinceMillis = 0;
                return;
            }
            long wait = reconnectAfterMillis();
            if (wait > 0 && now - leftAt >= wait && client.gui.screen() != null && lastServer != null) {
                DoughBayClient.LOGGER.info("DoughBay evasion: {} s elapsed; reconnecting to {}",
                        (now - leftAt) / 1000, lastServer.ip);
                leftAt = now;   // one attempt per wait, however it goes
                reconnect(client, lastServer);
            }
            return;
        }

        if (client.player == null || client.level == null) return;
        if (client.getCurrentServer() != null) lastServer = client.getCurrentServer();
        wander(client, session, now);

        // Asked to leave and waiting for the trade in flight to finish.
        if (leaveWanted) {
            if (leaveWantedAt == 0) leaveWantedAt = now;
            // Waiting is right for a second or two, but never for ever: after
            // a minute the threat matters more than the tidy exit.
            if (!session.safeToLeave() && now - leaveWantedAt < 60_000) {
                if (!waitingLogged) {
                    waitingLogged = true;
                    DoughBayClient.LOGGER.info("DoughBay evasion: waiting for the trade in flight before escaping");
                }
                return;
            }
            if (!session.safeToLeave()) {
                DoughBayClient.LOGGER.warn("DoughBay evasion: waited a minute for the trade in flight; escaping anyway");
            }
            leaveWantedAt = 0;
            if (escapeMode() == 0) {
                // Escapes are switched off, so this is a pause and nothing
                // more. Falling through to leave() here is what turned a
                // player walking past into a disconnect, a rejoin, and the
                // whole thing again - the client looking like it restarts
                // itself. It is also how an inventory is lost: leaving while
                // someone is near you is a combat log.
                DoughBayClient.LOGGER.info(
                        "DoughBay evasion: {} came too close; paused and staying put", lastIntruder);
                triggered = true;
                clearSinceMillis = 0;
                leaveWanted = false;
                return;
            }
            if (escapeMode() == 1) {
                teleportAway(client);
            } else {
                leave(client, String.format(java.util.Locale.ROOT,
                        "Evasion: %s came too close; leaving the server", lastIntruder));
            }
            leaveWanted = false;
            return;
        }

        // The teleport has a warm-up, and that is the dangerous part: if
        // anyone closes in while we stand there waiting, the teleport will not
        // save us and the connection has to go instead.
        if (teleportedAt > 0) {
            double moved = teleportFrom == null ? 0 : client.player.position().distanceTo(teleportFrom);
            double enough = SafeHomes.lastUsedAt() > teleportedAt - 2000 ? 8 : 100;
            if (moved > enough) {
                // Landed in water. On a flooded server the bot ends up submerged,
                // where it cannot see or reach the auction and slowly drowns, so
                // /rtp again for dry ground - up to a few tries, in case the whole
                // area is under water and there is nowhere dry to land.
                if (client.player.isUnderWater()
                        && dev.doughbay.fabric.Tuning.get("evasion.rtp_if_underwater") >= 0.5
                        && underwaterRtpCount < 5) {
                    underwaterRtpCount++;
                    DoughBayClient.LOGGER.info("DoughBay evasion: landed underwater (try {}); /rtp again",
                            underwaterRtpCount);
                    teleportFrom = client.player.position();
                    teleportedAt = now;
                    DoughBayClient.executionDriver().sendWhenClear(client, "rtp");
                    return;
                }
                underwaterRtpCount = 0;
                DoughBayClient.LOGGER.info("DoughBay evasion: teleported {} blocks away; carrying on", Math.round(moved));
                note(wandering ? "" : lastIntruder, moved, wandering ? "moved on (nobody about)" : "teleported away");
                if (wandering) {
                    // Nothing was paused for a move-on, so there is nothing
                    // to resume: just carry on where we landed.
                    wandering = false;
                    teleportedAt = 0;
                    teleportFrom = null;
                    return;
                }
                teleportedAt = 0;
                teleportFrom = null;
                triggered = true;         // the ordinary all-clear path resumes trading
                clearSinceMillis = 0;
                return;
            }
            String closing = null;
            double closingDistance = Double.MAX_VALUE;
            for (Player other : client.level.players()) {
                if (other == client.player) continue;
                if (SafeHomes.isAlly(other.getName().getString())) continue;
                double d = other.distanceTo(client.player);
                if (d <= abortRadius() && d < closingDistance) {
                    closingDistance = d;
                    closing = other.getName().getString();
                }
            }
            if (closing != null) {
                teleportedAt = 0;
                teleportFrom = null;
                leave(client, String.format(java.util.Locale.ROOT,
                        "Evasion: %s closed to %.0f blocks during the teleport warm-up; leaving instead",
                        closing, closingDistance));
                return;
            }
            // The command is paced behind whatever went out last, so the
            // warm-up is counted from the moment it was actually sent - the
            // decision came a second or more earlier, and counting from there
            // is what called a teleport dead before the server had seen it.
            long sentAt = DoughBayClient.executionDriver().lastEscapeSentAt();
            long since = sentAt >= teleportedAt ? sentAt : teleportedAt;
            boolean unsent = sentAt < teleportedAt;
            if ((!unsent && now - since > teleportWaitMillis()) || (unsent && now - teleportedAt > teleportWaitMillis() + 10_000)) {
                teleportedAt = 0;
                teleportFrom = null;
                if (wandering) {
                    // Nobody is about; a move-on that did not take is not
                    // worth the connection. Try again next time round.
                    wandering = false;
                    DoughBayClient.LOGGER.info("DoughBay evasion: the move-on teleport did not take; staying put");
                    note("", 0, "move-on did not take; stayed put");
                    return;
                }
                DoughBayClient.LOGGER.warn("DoughBay evasion: the teleport did not move us in {} s; leaving instead",
                        teleportWaitMillis() / 1000);
                leave(client, "Evasion: the teleport did not take; leaving the server");
                return;
            }
            return;   // still waiting on the teleport; do nothing else
        }
        String nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        String nearestWarn = null;
        double warnDistance = Double.MAX_VALUE;
        for (Player other : client.level.players()) {
            if (other == client.player || other.getUUID().equals(client.player.getUUID())) continue;
            if (SafeHomes.isAlly(other.getName().getString())) continue;
            double distance = other.distanceTo(client.player);
            if (distance <= radiusBlocks() && distance < nearestDistance) {
                nearestDistance = distance;
                nearest = other.getName().getString();
            }
            if (warnRadius() > radiusBlocks() && distance <= warnRadius() && distance < warnDistance) {
                warnDistance = distance;
                nearestWarn = other.getName().getString();
            }
        }
        AutomationSessionController.SessionSnapshot snapshot = session.snapshot();
        boolean running = snapshot.state() != AutomationSessionController.State.STOPPED
                && snapshot.state() != AutomationSessionController.State.PAUSED;

        // Taking damage is the hardest signal there is: hunger does not fall
        // while standing still, so health that drops means somebody is hitting
        // you. React to it before anyone reaches a distance threshold.
        boolean driving = playerIsDriving(client, now);
        float health = client.player.getHealth();
        // Landing hard is not an attack. The fall distance from just before
        // the landing is what says so, because it is zero again by the time
        // the damage lands.
        boolean fell = lastFallDistance > 3.0 && client.player.onGround();
        lastFallDistance = client.player.fallDistance;
        if (lastHealth >= 0 && health < lastHealth - 0.01f && !fell && !driving) {
            java.util.List<String> near = new java.util.ArrayList<>();
            boolean strangerAbout = false;
            boolean anyoneAbout = false;
            for (Player other : client.level.players()) {
                if (other == client.player) continue;
                anyoneAbout = true;
                near.add(other.getName().getString() + " at " + Math.round(other.distanceTo(client.player)) + "b");
                if (!SafeHomes.isAlly(other.getName().getString())) strangerAbout = true;
            }
            // A friend caught us with a stray swing. Running from that costs a
            // session and, when the server refuses the teleport for being in
            // combat, the whole connection: an accidental punch took the bot
            // off the server entirely. Damage still stops nothing when the only
            // people who can see us are on the ally list.
            boolean onlyFriendsAbout = anyoneAbout && !strangerAbout;
            String who = near.isEmpty() ? "nobody the client can see" : String.join(", ", near);
            String message = String.format(java.util.Locale.ROOT,
                    "Evasion: took %.1f damage (%.1f health left); nearby: %s", lastHealth - health, health, who);
            DoughBayClient.LOGGER.warn("DoughBay {}", message);
            client.player.sendSystemMessage(Component.literal("[GoNuts]" + message));
            if (onlyFriendsAbout) {
                DoughBayClient.LOGGER.info(
                        "DoughBay evasion: the damage came from a friend and nobody else is about; carrying on");
            } else if (Tuning.get("evasion.on_damage") >= 0.5) {
                if (running) session.pause("Evasion: took damage");
                closeAnyScreen(client);
                triggered = true;
                lastIntruder = near.isEmpty() ? "an unseen attacker" : near.get(0);
                note(lastIntruder, 0, String.format(java.util.Locale.ROOT, "took %.1f damage", lastHealth - health));
                clearSinceMillis = 0;
                // Damage is the surest sign someone is on us, so escape straight
                // away rather than only pausing - /rtp (escape=1) or a disconnect
                // (escape=2), whichever is set. rtp_on_damage forces this even
                // with no disconnect ring; the old disconnect-ring trigger still
                // applies when it is off. The escape itself waits for a safe
                // moment (no trade in flight) in the leaveWanted handler above.
                boolean escapeOnDamage = escapeMode() > 0
                        && (Tuning.get("evasion.rtp_on_damage") >= 0.5 || disconnectRadius() > 0);
                if (escapeOnDamage) {
                    leaveWanted = true;
                    waitingLogged = false;
                }
            }
        }
        lastHealth = health;

        // Nothing runs away while the player is at the controls.
        if (driving) {
            return;
        }

        // Hostile mobs. A wandering spawn is ignored; what matters is one
        // close enough to swing, one that is aiming at us from range, or a
        // projectile already in the air. Six points of damage from nothing
        // visible is a skeleton arrow, and the arrow is the thing to see.
        int mobBlocks = (int) Tuning.get("evasion.mob_blocks");
        if (mobBlocks > 0 && escapeMode() > 0 && teleportedAt == 0 && !leaveWanted) {
            net.minecraft.world.entity.Entity hostile = null;
            double hostileDistance = Double.MAX_VALUE;
            String why = "within";
            int rangedBlocks = (int) Tuning.get("evasion.ranged_blocks");
            int projectileBlocks = (int) Tuning.get("evasion.projectile_blocks");
            for (net.minecraft.world.entity.Entity entity : client.level.entitiesForRendering()) {
                double d = entity.distanceTo(client.player);
                // Anything already flying at us: an arrow, a potion, a fireball.
                // A projectile already in the air is a threat whatever is
                // between us: it is past the wall or it is not, and its own
                // path says which.
                if (projectileBlocks > 0 && d <= projectileBlocks && isIncoming(entity, client)) {
                    hostileDistance = d;
                    hostile = entity;
                    why = "incoming, now";
                    break;
                }
                if (!(entity instanceof net.minecraft.world.entity.monster.Enemy)) continue;
                if (d <= mobBlocks && d < hostileDistance && canSeeUs(entity, client)) {
                    hostileDistance = d;
                    hostile = entity;
                    why = "within";
                    continue;
                }
                // Ranged attackers do not need to be next to us, and one that
                // is facing us is about to shoot.
                if (rangedBlocks > 0 && d <= rangedBlocks && d < hostileDistance
                        && ranged(entity) && facingUs(entity, client) && canSeeUs(entity, client)) {
                    hostileDistance = d;
                    hostile = entity;
                    why = "aiming at us from";
                }
            }
            if (hostile != null) {
                String message = String.format(java.util.Locale.ROOT, "Evasion: %s %s %.0f blocks; escaping",
                        hostile.getName().getString(), why, hostileDistance);
                DoughBayClient.LOGGER.warn("DoughBay {}", message);
                client.player.sendSystemMessage(Component.literal("[GoNuts]" + message));
                if (running) session.pause("Evasion: " + hostile.getName().getString() + " " + why + " " + Math.round(hostileDistance) + " blocks");
                closeAnyScreen(client);
                triggered = true;
                lastIntruder = hostile.getName().getString();
                note(lastIntruder, hostileDistance, "hostile mob; escaping");
                clearSinceMillis = 0;
                leaveWanted = true;
                waitingLogged = false;
                return;
            }
        }

        // Rings are distance, and distance is the wrong unit against an
        // elytra: someone gliding covers the outer ring in a second. Anyone
        // who will reach us inside the contact window is handled now, at
        // whatever range they happen to be.
        ClosingThreat threat = closingThreat(client, now);
        if (threat != null && threat.seconds() <= contactSeconds() && escapeMode() > 0 && teleportedAt == 0) {
            String message = String.format(java.util.Locale.ROOT,
                    "Evasion: %s is %.0f blocks out and %s; about %.1f s away",
                    threat.name(), threat.distance(), threat.gliding() ? "gliding in" : "closing fast", threat.seconds());
            DoughBayClient.LOGGER.warn("DoughBay {}", message);
            client.player.sendSystemMessage(Component.literal("[GoNuts]" + message));
            if (running) session.pause("Evasion: " + threat.name() + " closing fast");
            closeAnyScreen(client);
            triggered = true;
            lastIntruder = threat.name();
            clearSinceMillis = 0;
            note(threat.name(), threat.distance(), threat.gliding() ? "gliding in" : "closing fast");
            // Too close for the teleport's warm-up: go straight off the server.
            if (threat.seconds() <= teleportWaitMillis() / 1000.0 || escapeMode() == 2) {
                if (session.safeToLeave()) {
                    leave(client, "Evasion: " + threat.name() + " is on top of us; leaving the server");
                } else {
                    leaveWanted = true;
                    waitingLogged = false;
                }
            } else {
                leaveWanted = true;
                waitingLogged = false;
            }
            return;
        }

        // The outer ring only speaks; it is the early warning, not an action.
        if (nearestWarn != null && !nearestWarn.equals(warnedOf) || (nearestWarn != null && now - warnedAt > 60_000)) {
            warnedOf = nearestWarn;
            warnedAt = now;
            note(nearestWarn, warnDistance, "sighted");
            String message = String.format(java.util.Locale.ROOT, "Evasion: %s is %d blocks away", nearestWarn, Math.round(warnDistance));
            DoughBayClient.LOGGER.info("DoughBay {}", message);
            client.player.sendSystemMessage(Component.literal("[GoNuts]" + message));
        }
        if (nearestWarn == null) warnedOf = "";

        if (nearest != null) {
            clearSinceMillis = 0;
            int leaveAt = disconnectRadius();
            if (leaveAt > 0 && nearestDistance <= leaveAt && escapeMode() > 0) {
                // Pause first, then escape, but never mid-trade: the ledger's
                // last checkpoint has to be the whole truth before we move.
                // A pause, not an emergency stop: the stop marks the session
                // player-paused and disarms it, and nothing then resumes it
                // when the coast is clear - every escape left the bot parked
                // for good until somebody pressed Resume.
                if (running) session.pause("Evasion: " + nearest + " within " + leaveAt + " blocks; escaping");
                closeAnyScreen(client);
                triggered = true;
                lastIntruder = nearest;
                note(nearest, nearestDistance, escapeMode() == 2 ? "leaving the server" : "teleporting away");
                leaveWanted = true;
                waitingLogged = false;
                return;
            }
            if (running) {
                session.pause("Evasion: " + nearest + " within " + radiusBlocks() + " blocks");
                closeAnyScreen(client);
                triggered = true;
                lastIntruder = nearest;
                note(nearest, nearestDistance, "paused");
                String message = String.format(java.util.Locale.ROOT,
                        "Evasion: %s within %d blocks (%.0f); session paused",
                        nearest, radiusBlocks(), nearestDistance);
                DoughBayClient.LOGGER.info("DoughBay {}", message);
                client.player.sendSystemMessage(Component.literal("[GoNuts]" + message));
            }
            return;
        }

        if (!triggered) return;
        if (clearSinceMillis == 0) {
            clearSinceMillis = now;
            return;
        }
        if (now - clearSinceMillis < clearBeforeResumeMillis()) return;
        if (snapshot.state() != AutomationSessionController.State.PAUSED) {
            // Ended or restarted by hand meanwhile; nothing to resume.
            triggered = false;
            return;
        }
        ExecutionResult result = session.resumeRecoveredSession(DoughBayClient.continuousPolicy());
        // A session paused in place is not a recovered one and that call
        // refuses it; the ordinary resume is what an in-place pause wants.
        if (!result.ok()) {
            ExecutionResult plain = session.resume();
            if (plain.ok()) result = plain;
        }
        if (result.ok()) {
            triggered = false;
            clearSinceMillis = 0;
            resumeRefusals = 0;
            note(lastIntruder, 0, "area clear; resumed");
        } else if (++resumeRefusals >= 4) {
            // A minute of refusals means this pause is not one the guard can
            // undo - it happened mid-trade, or the rejoin left it in a state
            // only the session itself knows how to pick up. Let go of it:
            // the session's own auto-resume takes over the moment the guard
            // stops holding it back, and it knows to list a bought stack
            // first. Holding on here is what left a bot parked for an hour
            // and a half with nobody within a hundred blocks.
            triggered = false;
            clearSinceMillis = 0;
            resumeRefusals = 0;
            note(lastIntruder, 0, "area clear; handed the resume to the session");
            DoughBayClient.LOGGER.info(
                    "DoughBay evasion: resume refused four times ({}); leaving it to the session's own auto-resume",
                    result.detail());
            return;
        } else {
            // A refused resume is usually momentary: a death screen up, a page
            // still open. Keep the trigger and try again shortly.
            clearSinceMillis = now - clearBeforeResumeMillis() + 15_000;
        }
        String message = result.ok()
                ? "Evasion: area clear for a minute; session resumed"
                : "Evasion: area clear, but resume was refused (" + result.detail() + "); trying again in 15 s";
        DoughBayClient.LOGGER.info("DoughBay {}", message);
        client.player.sendSystemMessage(Component.literal("[GoNuts]" + message));
    }

    /**
     * Teleports away with the server's own random-teleport command. Better
     * than leaving when it works: the auction house is reachable from
     * anywhere, so the session keeps trading from wherever it lands.
     */
    /**
     * Tells the webhook what just happened and, when a player caused it, who
     * they are and what they were carrying. Read here rather than later
     * because in a few seconds they will be thousands of blocks away.
     */
    private void reportEscape(Minecraft client, String what, String reason, String who) {
        try {
            StatusWebhook hook = DoughBayClient.webhook();
            if (hook == null || Tuning.get("webhook.alert_evasion") < 0.5) return;
            java.util.List<String[]> lines = new java.util.ArrayList<>();
            lines.add(new String[] {"reason", reason});
            String face = "";
            for (PlayerIntel.Nearby p : PlayerIntel.nearby(client, 200, 8)) {
                if (who == null || !p.name().equalsIgnoreCase(who)) continue;
                face = p.name();
                lines.add(new String[] {"distance", p.distance() + " blocks"});
                if (p.profile() != null && !p.profile().killLine().isEmpty()) {
                    lines.add(new String[] {"kills / deaths", p.profile().killLine()});
                }
                StringBuilder gear = new StringBuilder();
                for (PlayerIntel.Gear g : p.gear()) {
                    gear.append(g.slot()).append(": ").append(g.name());
                    if (g.durabilityMax() > 0) {
                        gear.append(" (").append(g.durabilityLeft() * 100 / g.durabilityMax()).append("%)");
                    }
                    for (String ench : g.enchantments()) gear.append("\n    ").append(ench);
                    gear.append('\n');
                }
                lines.add(new String[] {"carrying", gear.length() == 0 ? "nothing" : gear.toString()});
                break;
            }
            hook.alert(what, 0xE86A6A, lines, face);
        } catch (Throwable ignored) {
            // an alert that cannot be built must never stop the escape itself
        }
    }

    /** The server has said not to teleport; an escape that obeys is worth more than the escape. */
    private boolean serverRestarting() {
        try {
            var session = DoughBayClient.automationSessionController();
            return session != null && session.serverRestarting();
        } catch (Throwable t) {
            return false;
        }
    }

    private void teleportAway(Minecraft client) {
        if (serverRestarting()) {
            DoughBayClient.LOGGER.info("DoughBay evasion: the server is restarting and says not to teleport; staying put");
            return;
        }
        try {
            if (client.getConnection() == null) return;
            // Teleports are refused at the send gate, so arming the warm-up
            // here would guarantee it expires - and the expiry path leaves the
            // server, which then rejoins, which looks exactly like the client
            // restarting itself in a loop. Nothing is sent, so nothing is
            // waited for: hold still and let the pause do the work.
            if (DoughBayClient.executionDriver().refusesTeleports()) {
                DoughBayClient.LOGGER.info(
                        "DoughBay evasion: {} came too close; staying put (teleports are not used on this server)",
                        lastIntruder);
                triggered = true;
                clearSinceMillis = 0;
                return;
            }
            teleportFrom = client.player.position();
            teleportedAt = System.currentTimeMillis();
            // A named home is lit, enclosed and known; the random teleport is
            // only the fallback when none are configured.
            String home = Tuning.get("evasion.use_homes") >= 0.5 ? SafeHomes.next() : "";
            String command = home.isEmpty() ? "rtp" : "home " + home;
            if (!home.isEmpty()) SafeHomes.noteUsed(home);
            String message = "Evasion: " + lastIntruder + " came too close; "
                    + (home.isEmpty() ? "teleporting away" : "going to home '" + home + "'");
            DoughBayClient.LOGGER.info("DoughBay {}", message);
            client.player.sendSystemMessage(Component.literal("[GoNuts]" + message));
            reportEscape(client, "Escaped: " + lastIntruder + " came too close",
                    home.isEmpty() ? "teleported away" : "went to home '" + home + "'", lastIntruder);
            DoughBayClient.executionDriver().sendWhenClear(client, command);
        } catch (RuntimeException e) {
            teleportedAt = 0;
            DoughBayClient.LOGGER.warn("DoughBay evasion could not teleport: {}", e.toString());
        }
    }

    /**
     * Moves on every so often with nobody chasing us.
     *
     * <p>The escape teleport only fires when somebody is already close, which
     * means it fires when it has been noticed. Standing in one hole for eleven
     * hours is what gets you noticed: an account that never moves, never
     * mines, never speaks, and lists four hundred stacks a day is a shape
     * anyone watching the market can pick out, and it is a fixed set of
     * coordinates for anyone who decides to come and look.
     *
     * <p>So it moves anyway - on its own clock, at no particular interval, for
     * no reason anyone can point at. There is nothing to interrupt: the bot
     * clicks the auction house through commands and never touches the world,
     * so where it stands has never mattered to what it does.
     */
    private void wander(Minecraft client, AutomationSessionController session, long now) {
        long every = (long) (Tuning.get("evasion.wander_every_min") * 60_000);
        if (every <= 0) {
            wanderAt = 0;
            return;
        }
        if (DoughBayClient.executionDriver().refusesTeleports()) return;
        if (wanderAt == 0) {
            wanderAt = now + jitteredWander(every);
            return;
        }
        if (now < wanderAt) return;
        // Never mid-trade. A stack in hand and a listing half sent is the one
        // moment where being somewhere else is expensive, and there is no
        // hurry here at all - the whole point is that nothing is chasing us.
        if (triggered || leaveWanted || teleportedAt > 0) return;
        if (session == null || !session.safeToLeave()) return;
        // Idle between trades only. safeToLeave() speaks for the stack in
        // hand, not for the bid desk or a reprice pass half way through its
        // pages; a teleport under one of those is a chest opened somewhere
        // else, or a page that never arrives.
        AutomationSessionController.State st = session.snapshot().state();
        if (st != AutomationSessionController.State.SCANNING
                && st != AutomationSessionController.State.MONITORING
                && st != AutomationSessionController.State.STOPPED) return;
        if (client.gui.screen() != null) return;
        if (StashDesk.busy()) return;
        // A chest in the pocket before leaving, because there is no getting one
        // after landing: opening the shelf is what hands them out, and opening
        // the shelf needs one to place. Run the shelf now, take one, and go
        // once it has finished putting itself away.
        if (StashDesk.enabled() && !StashDesk.hasChest(client) && StashDesk.run(client)) {
            DoughBayClient.LOGGER.info("DoughBay evasion: topping up from the shelf before moving on");
            return;
        }
        wanderAt = now + jitteredWander(every);
        teleportFrom = client.player.position();
        teleportedAt = now;
        wandering = true;
        DoughBayClient.LOGGER.info("DoughBay evasion: moving on with nobody about; /rtp");
        DoughBayClient.executionDriver().sendWhenClear(client, "rtp");
    }

    /**
     * Somewhere between half and one and a half times the interval. A move
     * that lands on the hour, every hour, is its own signature.
     */
    private static long jitteredWander(long every) {
        return (long) (every * (0.5 + Math.random()));
    }

    /**
     * Leaves the server the way the pause menu does: the connection closes and
     * the client falls back to the title screen. Nothing is force-quit, so the
     * ledger's last checkpoint stands and the session recovers on the next
     * login.
     */
    private void leave(Minecraft client, String reason) {
        wandering = false;
        note(lastIntruder, 0, "left the server");
        try {
            if (client.player != null) client.player.sendSystemMessage(Component.literal("[GoNuts]" + reason));
            DoughBayClient.LOGGER.info("DoughBay {}", reason);
            away = true;
            leaveWanted = false;
            leftAt = System.currentTimeMillis();
            if (client.getConnection() != null) {
                client.getConnection().getConnection().disconnect(Component.literal("GoNuts evasion"));
            }
        } catch (RuntimeException e) {
            away = false;
            leaveWanted = false;
            DoughBayClient.LOGGER.warn("DoughBay evasion could not leave cleanly: {}", e.toString());
        }
    }

    /**
     * Rejoins the last server. The connect screen's entry point has moved
     * between versions, so it is found by name and called with whatever
     * arguments it takes.
     */
    private static void reconnect(Minecraft client, net.minecraft.client.multiplayer.ServerData server) {
        try {
            Class<?> connect = Class.forName("net.minecraft.client.gui.screens.ConnectScreen");
            java.lang.reflect.Method start = null;
            for (java.lang.reflect.Method m : connect.getMethods()) {
                if (m.getName().equals("startConnecting") && java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                    if (start == null || m.getParameterCount() > start.getParameterCount()) start = m;
                }
            }
            if (start == null) {
                DoughBayClient.LOGGER.warn("DoughBay evasion: no way to reconnect on this version");
                return;
            }
            Class<?>[] types = start.getParameterTypes();
            Object[] args = new Object[types.length];
            for (int i = 0; i < types.length; i++) {
                if (types[i].isAssignableFrom(net.minecraft.client.gui.screens.TitleScreen.class)) {
                    args[i] = new net.minecraft.client.gui.screens.TitleScreen();
                } else if (types[i].isInstance(client)) {
                    args[i] = client;
                } else if (types[i].isInstance(server)) {
                    args[i] = server;
                } else if (types[i] == boolean.class) {
                    args[i] = Boolean.FALSE;
                } else if (types[i].getSimpleName().equals("ServerAddress")) {
                    args[i] = types[i].getMethod("parseString", String.class).invoke(null, server.ip);
                } else {
                    args[i] = null;
                }
            }
            start.invoke(null, args);
        } catch (ReflectiveOperationException | RuntimeException e) {
            DoughBayClient.LOGGER.warn("DoughBay evasion could not reconnect: {}", e.toString());
        }
    }

    private static void closeAnyScreen(Minecraft client) {
        Screen open = client.gui.screen();
        if (open instanceof AbstractContainerScreen<?>) {
            client.player.closeContainer();
        } else if (open != null && !(open instanceof DoughBayScreen)) {
            client.gui.setScreen(null);
        }
    }
}
