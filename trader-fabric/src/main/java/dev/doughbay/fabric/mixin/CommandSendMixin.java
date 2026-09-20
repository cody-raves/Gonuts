package dev.doughbay.fabric.mixin;

import dev.doughbay.fabric.CommandSendProbe;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observes the normal connection handoff without bypassing command handlers. */
@Mixin(ClientCommonPacketListenerImpl.class)
abstract class CommandSendMixin {
    @Inject(method = "send", at = @At("RETURN"))
    private void doughbay$commandHandedToConnection(Packet<?> packet, CallbackInfo ci) {
        if (packet instanceof ServerboundChatCommandPacket command) CommandSendProbe.observe(command.command());
        else if (packet instanceof ServerboundChatCommandSignedPacket command) CommandSendProbe.observe(command.command());
    }
}
