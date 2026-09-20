package dev.doughbay.fabric.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read access to the key a {@link KeyMapping} is currently bound to.
 *
 * <p>The mod polls its keys' raw state so an open auction screen cannot swallow
 * a press. Raw polling needs the actual keycode, and {@code getDefaultKey()}
 * gives only the original binding - so a key the player rebound was polled at
 * its old code and never fired. This exposes the live binding; nothing is
 * modified.</p>
 */
@Mixin(KeyMapping.class)
public interface KeyMappingAccessor {
    @Accessor("key")
    InputConstants.Key doughbay$boundKey();
}
