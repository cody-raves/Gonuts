package dev.doughbay.fabric.mixin;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import dev.doughbay.fabric.Tuning;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keep the client ticking at full rate when its window is minimized, unfocused,
 * or idle.
 *
 * <p>Vanilla's {@link FramerateLimitTracker#getFramerateLimit()} caps an
 * iconified or long-idle window to 10 fps. The whole automation loop runs on the
 * render thread, so that cap starves it - every tick-based timeout stretches by
 * the same factor, and a page that opens takes minutes to clear instead of
 * seconds. Two things make this unavoidable for a trading bot: it never touches
 * the real keyboard or mouse, so it trips the AFK throttle even while visible;
 * and running several clients at once forces all but one to be minimized. No
 * window arrangement fixes either.</p>
 *
 * <p>With {@code perf.no_afk_throttle} on (the default), report {@code NONE} so
 * the limiter falls through to the configured framerate instead of a throttle
 * cap. Turning the setting off restores vanilla power-saving behaviour.</p>
 */
@Mixin(FramerateLimitTracker.class)
public class FramerateLimitTrackerMixin {
    @Inject(method = "getThrottleReason", at = @At("HEAD"), cancellable = true)
    private void doughbay$keepFullFramerate(
            CallbackInfoReturnable<FramerateLimitTracker.FramerateThrottleReason> cir) {
        if (Tuning.get("perf.no_afk_throttle") >= 0.5) {
            cir.setReturnValue(FramerateLimitTracker.FramerateThrottleReason.NONE);
        }
    }
}
