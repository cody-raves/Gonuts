package dev.doughbay.fabric.mixin;

import net.minecraft.client.gui.screens.dialog.DialogScreen;
import net.minecraft.server.dialog.Dialog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read access to the dialog a {@link DialogScreen} is showing.
 *
 * <p>DonutSMP's purchase confirmation ("Are you sure you want to buy this?"
 * with No/Yes) is a server dialog, not a container screen. Its title, item,
 * exact price, and button labels live in the dialog data, which the screen
 * keeps private. This exposes it read-only; nothing is modified.</p>
 */
@Mixin(DialogScreen.class)
public interface DialogScreenAccessor {
    @Accessor("dialog")
    Dialog doughbay$dialog();
}
