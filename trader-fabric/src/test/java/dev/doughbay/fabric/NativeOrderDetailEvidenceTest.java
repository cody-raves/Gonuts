package dev.doughbay.fabric;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeOrderDetailEvidenceTest {
    @Test void extractsQuantityHintsFromHoverWithoutRetainingNamesOrOtherText() {
        var text=Component.literal("Order for private-player-identity")
                .withStyle(s->s.withHoverEvent(new HoverEvent.ShowText(
                        Component.literal("Requested: 1,024\nDelivered: 64\napi_token private-secret\nPrice: 987654321"))));
        var hints=NativeOrderEvidence.quantityHints(text);
        assertTrue(hints.contains("requested=1,024"));
        assertTrue(hints.contains("delivered=64"));
        assertFalse(hints.toString().contains("private"));
        assertFalse(hints.toString().contains("987654321"));
    }
    @Test void roundedHintsRemainRoundedAndAreNeverConvertedToExactCounts() {
        var hints=NativeOrderEvidence.quantityHints(Component.literal("1.0K requested | 64 items | unrelated123"));
        assertTrue(hints.contains("requested=1.0k"));
        assertTrue(hints.contains("items=64"));
        assertFalse(hints.toString().contains("1024"));
        assertFalse(hints.toString().contains("unrelated"));
        assertEquals(java.util.List.of("collect","cancel"),NativeOrderEvidence.controlWords("Collect / Cancel private-player"));
    }
}
