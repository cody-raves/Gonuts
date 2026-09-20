package dev.doughbay.fabric.mixin;

import dev.doughbay.fabric.DoughBayClient;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Observes server action-bar title packets after vanilla has handled them.
 *
 * <p>The injection is deliberately non-cancellable and runs at RETURN. The
 * packet handler's normal same-thread guard therefore completes first, and
 * DoughBay neither suppresses nor rewrites the action-bar component.</p>
 */
@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerMixin {

    @Inject(method = "setActionBarText", at = @At("RETURN"))
    private void doughbay$observeActionBar(
            ClientboundSetActionBarTextPacket packet, CallbackInfo callbackInfo) {
        DoughBayClient.observeActionBar(packet.text());
    }
}
