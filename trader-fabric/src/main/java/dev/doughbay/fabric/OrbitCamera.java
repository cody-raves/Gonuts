package dev.doughbay.fabric;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A slow camera orbit for a bot nobody is playing.
 *
 * <p>The account trades for hours and the view never changes: a character
 * standing in a hole, facing a wall. Nothing about the bot needs a camera - it
 * clicks slots by number and never looks at anything - so the view is spare,
 * and a slow drift around the player turns a window somebody is ignoring into
 * one worth leaving on a second monitor.
 *
 * <p>The player is not touched. Two earlier versions of this moved something
 * that belonged to somebody else - first the camera, after the renderer had
 * already been built around where it used to be, and then the player's own
 * head, which spun the character like a sprinkler. This version adds a second,
 * invisible thing to the world and asks the game to look through that instead,
 * which is the hook spectator mode uses. The player carries on standing still
 * and facing the wall, and the game does every part of the camera itself,
 * because from its point of view the camera simply is where it says it is.
 *
 * <p>It gives way immediately. Any turn of the mouse hands control back and
 * keeps it for a while afterwards, because a camera that fights you is worse
 * than no camera at all - and since nothing here writes to the player's
 * rotation any more, a rotation that moved is the operator's hand and nothing
 * else.
 */
public final class OrbitCamera {
    /** After the player looks around, the orbit waits this long before resuming. */
    private static final long YIELD_MILLIS = 6_000L;
    /** A full turn takes this long. Slow enough to be scenery, not motion. */
    private static final long PERIOD_MILLIS = 90_000L;
    /** Never closer than this to the eye, so a tight spot does not sit inside the head. */
    private static final double MIN_DISTANCE = 1.4;
    /** How far to stop short of whatever the clearance test hit. */
    private static final double WALL_MARGIN = 0.25;

    private static long yieldUntil;
    private static CameraEntity camera;
    /** The rotation we last saw, so a rotation that moved is the operator's. */
    private static float lastYaw = Float.NaN;
    private static float lastPitch = Float.NaN;

    private OrbitCamera() {
    }

    /**
     * Whether the view is currently sitting on the orbit camera rather than on
     * the player. The renderer asks, because the game skips drawing your own
     * body whenever the camera is not attached to it.
     */
    public static boolean detached() {
        return camera != null;
    }

    /** Whether the orbit should be driving the camera this frame. */
    public static boolean active() {
        if (Tuning.get("camera.orbit") < 0.5) return false;
        if (System.currentTimeMillis() < yieldUntil) return false;
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || client.level == null) return false;
        // A screen the operator is reading stops the orbit. A screen the bot
        // opened for itself does not - it opens one every few seconds, and
        // standing down for each of those made the view snap in and out
        // constantly. Those screens are not even drawn any more, so there is
        // nothing to stand down for.
        if (client.gui.screen() != null && !QuietScreen.hideContainer()) return false;
        // And never while the session is stopped or paused: a bot that is not
        // working is one you are probably about to play on. Any running session
        // (armed, or reading orders before it re-arms) is the bot's, so the
        // camera drifts then too, matching what quiet-mode hides.
        return DoughBayClient.automationSessionController().drivingOwnContainers();
    }

    /**
     * Moves the camera, or puts it away. Called once a client tick; the game
     * interpolates between two of these on its own, so a position set here is
     * a smooth drift on screen rather than twenty steps a second.
     */
    public static void tick(Minecraft client) {
        if (client == null || client.player == null || client.level == null) {
            standDown(client);
            return;
        }
        // The yield test watches the player's own rotation, which nothing here
        // writes to. So any movement in it came from the mouse.
        float yaw = client.player.getYRot();
        float pitch = client.player.getXRot();
        if (!Float.isNaN(lastYaw)
                && (Math.abs(yaw - lastYaw) > 0.05f || Math.abs(pitch - lastPitch) > 0.05f)) {
            yieldUntil = System.currentTimeMillis() + YIELD_MILLIS;
        }
        lastYaw = yaw;
        lastPitch = pitch;

        if (!active()) {
            standDown(client);
            return;
        }
        if (camera == null || camera.level() != client.level || camera.isRemoved()) {
            standDown(client);
            CameraEntity fresh = new CameraEntity(client.level);
            Vec3 seed = place(client);
            fresh.setPos(seed);
            fresh.setOldPosAndRot();
            client.level.addEntity(fresh);
            client.setCameraEntity(fresh);
            camera = fresh;
        }
        // Old position first: the game draws the camera partway between this
        // tick and the last one, and without a previous position to move from
        // the first frame is a streak across the map.
        camera.setOldPosAndRot();
        camera.setPos(place(client));
        lookAt(camera, client.player.getEyePosition());
    }

    /** Hands the view back to the player and takes the camera out of the world. */
    private static void standDown(Minecraft client) {
        if (camera == null) return;
        CameraEntity going = camera;
        camera = null;
        if (client != null) {
            client.setCameraEntity(client.player);
            if (client.level != null) {
                client.level.removeEntity(CameraEntity.ID, Entity.RemovalReason.DISCARDED);
            }
        }
        going.setRemoved(Entity.RemovalReason.DISCARDED);
    }

    /**
     * Where the camera wants to be, pulled in to wherever it can actually get.
     *
     * <p>This is the inversion the whole thing turns on. A cheat client puts
     * the camera at the wanted position and clips through the wall; a camera
     * meant to be watched asks for a position and then tests the line to it,
     * because the shot is ruined by a wall in front of the lens rather than by
     * being outside it.
     */
    private static Vec3 place(Minecraft client) {
        Vec3 eye = client.player.getEyePosition();
        double want = distance();
        double yaw = Math.toRadians(yaw());
        double pitch = Math.toRadians(pitch());
        Vec3 out = new Vec3(
                -Math.sin(yaw) * Math.cos(pitch),
                Math.sin(pitch),
                Math.cos(yaw) * Math.cos(pitch));
        Vec3 target = eye.add(out.scale(want));
        BlockHitResult hit = client.level.clip(new ClipContext(
                eye, target, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, client.player));
        if (hit != null && hit.getType() != HitResult.Type.MISS) {
            double reach = Math.max(MIN_DISTANCE, hit.getLocation().distanceTo(eye) - WALL_MARGIN);
            target = eye.add(out.scale(reach));
        }
        return target;
    }

    /** Points the camera back at the player, the way vanilla aims an entity. */
    private static void lookAt(Entity entity, Vec3 at) {
        Vec3 d = at.subtract(entity.position());
        double flat = Math.sqrt(d.x * d.x + d.z * d.z);
        entity.setXRot(Mth.wrapDegrees((float) -Math.toDegrees(Math.atan2(d.y, flat))));
        entity.setYRot(Mth.wrapDegrees((float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0)));
    }

    /** Where the orbit is pointing now, in degrees. */
    public static float yaw() {
        long now = System.currentTimeMillis();
        return (now % PERIOD_MILLIS) / (float) PERIOD_MILLIS * 360f;
    }

    /**
     * A gentle rise and fall, so it drifts over and under rather than tracking
     * one flat circle. Half the orbit period, so the height and the angle do
     * not come round together and repeat.
     */
    public static float pitch() {
        long now = System.currentTimeMillis();
        double phase = (now % (PERIOD_MILLIS / 2)) / (double) (PERIOD_MILLIS / 2) * Math.PI * 2;
        return (float) (12 + Math.sin(phase) * 14);
    }

    /** How far back to sit, before anything solid gets in the way. */
    public static float distance() {
        return (float) Math.max(2.0, Math.min(12.0, Tuning.get("camera.distance")));
    }
}
