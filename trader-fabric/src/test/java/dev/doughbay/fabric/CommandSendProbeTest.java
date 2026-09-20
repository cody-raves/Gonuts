package dev.doughbay.fabric;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommandSendProbeTest {
    @Test void voidSendReturningNormallyIsNotProofOfAPacket() {
        var error = assertThrows(IllegalStateException.class,
                () -> CommandSendProbe.verify("ah sell 15500", () -> { }));
        assertTrue(error.getMessage().contains("canceled locally"));
    }

    @Test void matchingPacketHandoffIsAcceptedButAnotherCommandIsNot() {
        assertDoesNotThrow(() -> CommandSendProbe.verify("ah sell 15500",
                () -> CommandSendProbe.observe("ah sell 15500")));
        assertThrows(IllegalStateException.class, () -> CommandSendProbe.verify("ah sell 15500",
                () -> CommandSendProbe.observe("ah")));
    }

    @Test void previousOrManualPacketsCannotProveTheNextDispatch() {
        CommandSendProbe.observe("ah sell 15500");
        assertThrows(IllegalStateException.class,
                () -> CommandSendProbe.verify("ah sell 15500", () -> { }));
        assertThrows(IllegalArgumentException.class, () -> CommandSendProbe.verify("ah", () -> {
            CommandSendProbe.observe("ah");
            throw new IllegalArgumentException("disconnected");
        }));
        assertThrows(IllegalStateException.class, () -> CommandSendProbe.verify("ah", () -> { }));
    }
}
