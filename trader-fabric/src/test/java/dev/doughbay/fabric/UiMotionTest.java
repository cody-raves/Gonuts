package dev.doughbay.fabric;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UiMotionTest {
    @Test void notificationStaysVisibleUntilItsActualExpiry() {
        long lifetime = 9_000; // Seven seconds plus the two-second reading extension.
        assertEquals(0, UiMotion.visibility(-1, lifetime, 220, 260));
        assertEquals(0, UiMotion.visibility(0, lifetime, 220, 260));
        assertEquals(1, UiMotion.visibility(7_500, lifetime, 220, 260));
        assertEquals(0.5, UiMotion.visibility(8_870, lifetime, 220, 260));
        assertEquals(0, UiMotion.visibility(9_000, lifetime, 220, 260));
    }

    @Test void narrowScreensTakePrecedenceOverMinimumCardWidth() {
        assertEquals(150, UiMotion.boundedWidth(300, 190, 340, 160, 5));
        assertEquals(190, UiMotion.boundedWidth(120, 190, 340, 800, 8));
        assertEquals(340, UiMotion.boundedWidth(600, 190, 340, 800, 8));
    }
}
