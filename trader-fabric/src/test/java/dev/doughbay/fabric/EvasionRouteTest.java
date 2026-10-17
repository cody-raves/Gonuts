package dev.doughbay.fabric;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class EvasionRouteTest {
    @Test void disconnectNeverSelectsTeleportWithAnyHomeConfiguration() {
        for (boolean use : new boolean[]{false,true}) for (boolean homes : new boolean[]{false,true}) {
            assertEquals(EvasionRoute.Action.DISCONNECT,EvasionRoute.action(2,use,homes));
            assertFalse(EvasionRoute.mayWander(2,use,homes));
        }
    }
    @Test void pauseDoesNotAuthorizeAutomaticWandering() {
        assertEquals(EvasionRoute.Action.PAUSE,EvasionRoute.action(0,false,false));
        assertFalse(EvasionRoute.mayWander(0,true,true));
    }
    @Test void homeModeWithNoHomeCannotBecomeRandomTeleport() {
        assertEquals(EvasionRoute.Action.DISCONNECT,EvasionRoute.action(1,true,false));
        assertFalse(EvasionRoute.mayWander(1,true,false));
    }
    @Test void explicitRtpAndConfiguredHomesStillWork() {
        assertEquals(EvasionRoute.Action.TELEPORT,EvasionRoute.action(1,false,false));
        assertEquals(EvasionRoute.Action.TELEPORT,EvasionRoute.action(1,true,true));
        assertTrue(EvasionRoute.mayWander(1,true,true));
    }
}
