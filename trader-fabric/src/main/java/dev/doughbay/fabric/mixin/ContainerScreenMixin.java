package dev.doughbay.fabric.mixin;

import dev.doughbay.fabric.QuietScreen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.dialog.DialogScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips drawing a container the bot opened for itself.
 *
 * <p>The screen still exists and still works: slots are clicked by calling the
 * container handler with a slot number, which never asks what was drawn. So
 * cancelling the draw stops the auction house strobing over the game a few
 * hundred times an hour and costs the bot nothing at all.
 *
 * <p>The hook is on the wrapper the game actually calls, rather than on the
 * container screen's own method. Injecting into the subclass caught only part
 * of the work - the background and the tooltips are drawn either side of it -
 * so the page still appeared.
 */
@Mixin(Screen.class)
abstract class ContainerScreenMixin {

    @Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At("HEAD"), cancellable = true)
    private void doughbay$skipWhileWorking(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                           float partialTick, CallbackInfo callbackInfo) {
        Object screen = this;
        boolean botsWork;
        if (screen instanceof AbstractContainerScreen<?> container) {
            // Your own inventory is always container zero; anything the server
            // opens gets an id of its own. So the bot's workbench and your
            // pockets are trivially different, and only the first is hidden -
            // hiding both meant pressing E did nothing visible.
            botsWork = container.getMenu().containerId != 0;
        } else if (screen instanceof DialogScreen<?>) {
            // The confirmations and the boxes it types a price or a search
            // into. These are not containers, so the container test skipped
            // them and they carried on flashing on their own - which is most
            // of what is left once the pages are gone, because every buy and
            // every listing puts one up.
            botsWork = true;
        } else {
            return;
        }
        if (botsWork && QuietScreen.hideContainer()) callbackInfo.cancel();
    }
}
