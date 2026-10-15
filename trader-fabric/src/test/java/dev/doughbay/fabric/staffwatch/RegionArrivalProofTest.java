package dev.doughbay.fabric.staffwatch;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RegionArrivalProofTest {
 @Test void teleportAndFreshReplyAreBothRequired(){var p=new RegionArrivalProof();p.begin(100);assertFalse(p.needsProbe(true));p.teleport(110,105);assertFalse(p.needsProbe(false));assertTrue(p.needsProbe(true));p.probe(1);assertFalse(p.confirmed(true,true));assertFalse(p.complete(2));assertTrue(p.complete(1));assertTrue(p.confirmed(true,true));assertFalse(p.confirmed(false,true));assertFalse(p.confirmed(true,false));}
 @Test void teleportBeforeDispatchCannotCount(){var p=new RegionArrivalProof();p.begin(100);p.teleport(99,101);p.teleport(102,90);p.teleport(103,0);assertFalse(p.needsProbe(true));assertFalse(p.complete(1));}
 @Test void previousHopReplyCannotConfirmTheNextHop(){var p=new RegionArrivalProof();p.begin(100);p.teleport(110,105);p.probe(1);p.begin(200);p.teleport(210,205);p.probe(2);assertFalse(p.complete(1));assertFalse(p.confirmed(true,true));assertTrue(p.complete(2));assertTrue(p.confirmed(true,true));}
}
