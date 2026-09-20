package dev.doughbay.fabric.mixin;

import dev.doughbay.fabric.NameMask;
import net.minecraft.client.gui.Hud;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Scrambles the player's own name in the sidebar scoreboard as it is drawn; the scoreboard's data is untouched. */
@Mixin(Hud.class)
abstract class SidebarNameMixin {

    // Not required, for the same reason as the name tag: a moved call shows
    // the name again rather than stopping the client.
    @ModifyArg(
            method = "displayScoreboardSidebar",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text("
                            + "Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V"),
            index = 1,
            require = 0)
    private Component doughbay$hideOwnName(Component text) {
        return NameMask.mask(text);
    }
}
