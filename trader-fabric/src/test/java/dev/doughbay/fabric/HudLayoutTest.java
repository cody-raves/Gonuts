package dev.doughbay.fabric;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class HudLayoutTest {
    @Test void alertsAvoidStatusPanelInSameCorner() {
        var panel = new HudLayout.Rect(8, 8, 300, 90);
        var toast = HudLayout.place(8, 8, 260, 69, 480, 8, List.of(panel));
        assertNotNull(toast);
        assertEquals(104, toast.y());
        assertFalse(toast.overlaps(panel, 6));
    }

    @Test void oppositeLaneDoesNotMoveUnnecessarily() {
        var panel = new HudLayout.Rect(8, 8, 270, 90);
        assertEquals(8, HudLayout.place(700, 8, 250, 69, 480, 8, List.of(panel)).y());
    }

    @Test void wrapsAboveBottomAnchoredPanelAndDeclinesWhenFull() {
        var panel = new HudLayout.Rect(8, 180, 270, 112);
        assertEquals(8, HudLayout.place(8, 230, 250, 69, 300, 8, List.of(panel)).y());
        assertNull(HudLayout.place(8, 8, 250, 69, 140, 8,
                List.of(new HudLayout.Rect(8, 8, 270, 112))));
    }

    @Test void advancesPastEveryOverlappingSurface() {
        var first = new HudLayout.Rect(8, 8, 270, 50);
        var second = new HudLayout.Rect(8, 64, 270, 50);
        assertEquals(120, HudLayout.place(8, 8, 250, 60, 300, 8, List.of(second, first)).y());
    }
}
