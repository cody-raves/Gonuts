package dev.doughbay.fabric.mixin;

import dev.doughbay.fabric.OrbitCamera;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Draws the player while the camera is looking at them from somewhere else.
 *
 * <p>The game has one rule about your own body, and it is absolute:
 *
 * <pre>if (entity instanceof LocalPlayer &amp;&amp; camera.entity() != entity) continue;</pre>
 *
 * <p>Your character is drawn only when the camera is sitting on it. That is
 * the right rule for what it was written for - spectating something across the
 * map should not leave a body standing where you left it - and it is exactly
 * wrong for a camera whose entire purpose is to look back at you. It is also
 * invisible from the outside: the orbit worked, the world rendered, and the
 * one thing the feature exists to show was quietly skipped.
 *
 * <p>So the comparison is answered with the player rather than the camera's
 * own entity, and only while the orbit is actually detached. Nothing else in
 * the method changes, every other entity takes the same path it always did,
 * and the moment the orbit stands down the rule is back exactly as written.
 */
@Mixin(LevelExtractor.class)
abstract class LevelExtractorMixin {

    /**
     * The fourth and last time the loop asks the camera what it is attached
     * to. The three before it decide whether to skip the camera's own entity;
     * this one is the local-player test, and it is the only one worth lying
     * to.
     */
    // Not required. If a game update moves this call the hook simply does not
    // attach, and the worst that happens is the body goes back to being
    // invisible - which is a thing you can see. The alternative is a mixin
    // failure at world load, and crashing the client that is holding the
    // stock to fix the look of a camera is not a trade worth making.
    @Redirect(
            require = 0,
            method = "extractVisibleEntities",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/Camera;entity()Lnet/minecraft/world/entity/Entity;",
                    ordinal = 3))
    private Entity doughbay$drawTheBodyWeAreOrbiting(Camera camera) {
        if (OrbitCamera.detached()) {
            Minecraft client = Minecraft.getInstance();
            if (client != null && client.player != null) return client.player;
        }
        return camera.entity();
    }
}
