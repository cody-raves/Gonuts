package dev.doughbay.fabric.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Reaches the method a right-click actually calls.
 *
 * <p>Calling {@code gameMode.useItemOn} directly looked equivalent and was
 * not: it is one step out of several, and skipping the rest produced an
 * interaction the server answered with nothing. The real click goes through
 * {@code Minecraft.startUseItem}, which chooses the hand, consults the same
 * hit result the crosshair is on, swings the arm, and sets the right-click
 * delay that stops a held button firing every tick.
 *
 * <p>Borrowing that method rather than reproducing it is the whole point. Any
 * reimplementation is a guess at what the client does, and every difference
 * between the guess and the real thing is a difference somebody could measure.
 * This way there is nothing to get wrong: the packets are the ones a hand on
 * the mouse produces, because it is the same code producing them.
 */
@Mixin(Minecraft.class)
public interface MinecraftInvoker {

    @Invoker("startUseItem")
    void doughbay$startUseItem();
}
