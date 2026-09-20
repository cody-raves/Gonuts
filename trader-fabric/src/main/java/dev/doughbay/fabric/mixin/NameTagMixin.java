package dev.doughbay.fabric.mixin;

import dev.doughbay.fabric.NameMask;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Scrambles the name tag over the player's own head. It is only ever drawn
 * while the orbit camera looks back at the player, which is exactly when a
 * screenshot would show it.
 */
@Mixin(EntityRenderer.class)
abstract class NameTagMixin {

    // The overload every renderer ends in: it is the one that reads the name.
    // Not required: if an update moves this the name simply shows again,
    // which is a thing you can see, rather than the client failing to start.
    @Inject(method = "extractNameTags(Lnet/minecraft/world/entity/Entity;"
            + "Lnet/minecraft/client/renderer/entity/state/EntityRenderState;FDD)V",
            at = @At("TAIL"), require = 0)
    private void doughbay$hideOwnName(Entity entity, EntityRenderState state, float partialTick,
                                      double nameRange, double scoreRange, CallbackInfo callbackInfo) {
        if (state.nameTag == null || !NameMask.on()) return;
        Minecraft client = Minecraft.getInstance();
        if (client != null && entity == client.player) state.nameTag = NameMask.mask(state.nameTag);
    }
}
