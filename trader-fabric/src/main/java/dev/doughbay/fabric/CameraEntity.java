package dev.doughbay.fabric;

import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * An empty thing for the camera to sit on.
 *
 * <p>The camera cannot be moved by hand. Every earlier attempt did exactly
 * that - let the game place the camera, then shove it somewhere else - and
 * broke rendering three separate ways, because the camera's position is not a
 * number the renderer reads once. It is where the chunk visibility graph
 * starts walking, what the cull frustum is built around, and what entity
 * culling is measured from. Moving it after those were decided left the
 * picture describing a place the camera no longer was: sections popping in and
 * out at chunk boundaries, and the player culled along with them.
 *
 * <p>So the camera is not moved at all. The game is given a different entity
 * to look through, which is the same hook spectator mode uses, and every one
 * of those steps then happens around the new position because the game did
 * them itself. This entity exists only to be that thing: it holds a position
 * and a facing, it ticks to nothing, and it never leaves this client - the
 * server is never told it exists, because as far as the server is concerned
 * nothing has happened.
 */
public final class CameraEntity extends Entity {
    /**
     * Entity ids come from the server and count up from one, so a negative is
     * ours alone and can never be handed out from under us.
     */
    static final int ID = -8_675_309;

    public CameraEntity(Level level) {
        super(EntityTypes.MARKER, level);
        // A marker has no renderer, so nothing is ever drawn here. What is
        // seen is the world from this point, and the player standing in it.
        this.noPhysics = true;
        this.setNoGravity(true);
        this.setId(ID);
    }

    /** Driven from OrbitCamera; nothing here should move on its own. */
    @Override
    public void tick() {
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
    }

    @Override
    public boolean hurtServer(net.minecraft.server.level.ServerLevel level, DamageSource source, float amount) {
        return false;
    }
}
