package dev.doughbay.fabric.staffwatch;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

class StaffRegionEscapeFlowTest {
    static class Client implements StaffRegionEscapeFlow.Client {
        boolean connected=true, safe=true, allowed=true, sent, arrived;
        int clears, resumes, releases, disconnects;
        List<String> options=List.of("east","west","asia");
        List<String> hops=new ArrayList<>(); String hint="east", reason;
        public boolean connected(){return connected;} public boolean pauseSafely(){return safe;}
        public List<String> regions(){return options;} public String regionHint(){return hint;}
        public boolean requestRegion(String r){hops.add(r);sent=false;arrived=false;return allowed;}
        public boolean commandSent(){return sent;} public boolean transferConfirmed(){return arrived;}
        public void clearDetections(){clears++;} public void resume(){resumes++;}
        public void release(){releases++;} public void disconnect(String w,String r){disconnects++;reason=r;}
        public void notice(String w,String d){}
    }
    AtomicLong now; Client client; StaffRegionEscapeFlow flow;
    @BeforeEach void setup(){now=new AtomicLong();client=new Client();flow=new StaffRegionEscapeFlow(client,now::get);}
    void firstHop(){assertTrue(flow.alert("Staff",true));flow.tick(true);assertEquals(List.of("west"),client.hops);}
    void arrive(){client.sent=true;client.arrived=true;flow.tick(true);}
    @Test void firstAlertHopsWithoutDisconnectThenResumesAfterProof(){firstHop();assertEquals(0,client.disconnects);assertTrue(flow.ownsConnection());arrive();assertEquals(1,client.resumes);assertEquals(2,client.clears);assertFalse(flow.ownsConnection());assertEquals(StaffRegionEscapeFlow.Phase.WATCHING,flow.phase());}
    @Test void repeatedSameWatcherAfterArrivalHopsAgainBeforeDisconnect(){firstHop();arrive();assertTrue(flow.alert("sTaFf",true));flow.tick(true);assertEquals(List.of("west","east"),client.hops);assertEquals(0,client.disconnects);arrive();assertEquals(1,client.disconnects);assertEquals(1,client.resumes);assertEquals(StaffRegionEscapeFlow.Phase.IDLE,flow.phase());}
    @Test void duplicateDetectorsCannotEscalateBeforeArrival(){assertTrue(flow.alert("Staff",true));assertFalse(flow.alert("Staff",true));flow.tick(true);assertFalse(flow.alert("Staff",true));assertFalse(flow.alert("Other",true));assertEquals(1,client.hops.size());assertEquals(0,client.disconnects);}
    @Test void differentWatcherGetsAFirstHopRatherThanBeingIgnoredOrClaimedAsRepeat(){firstHop();arrive();assertTrue(flow.alert("OtherStaff",true));flow.tick(true);assertEquals(StaffRegionEscapeFlow.Phase.TRANSFERRING_FIRST,flow.phase());arrive();assertEquals(2,client.resumes);assertEquals(0,client.disconnects);}
    @Test void unsafeTransactionNeverSendsTeleport(){client.safe=false;assertTrue(flow.alert("Staff",true));flow.tick(true);assertTrue(client.hops.isEmpty());client.safe=true;flow.tick(true);assertEquals(1,client.hops.size());}
    @Test void unresolvedTransactionHasBoundedDisconnectFallback(){client.safe=false;flow.alert("Staff",true);now.set(15_000);flow.tick(true);assertEquals(1,client.disconnects);assertTrue(client.hops.isEmpty());assertEquals(0,client.resumes);}
    @Test void missingRegionDiscoveryTimesOutWithoutGuessing(){client.options=List.of();flow.alert("Staff",true);flow.tick(true);assertTrue(client.hops.isEmpty());now.set(15_000);flow.tick(true);assertEquals(1,client.disconnects);}
    @Test void blockedTeleportDisconnectsInsteadOfBypassingSetting(){client.allowed=false;firstHop();assertEquals(1,client.disconnects);assertEquals(0,client.resumes);}
    @Test void unconfirmedTransferTimesOut(){firstHop();client.sent=true;now.set(30_000);flow.tick(true);assertEquals(1,client.disconnects);assertEquals(0,client.resumes);}
    @Test void worldChangeBeforeCommandDispatchCannotCountAsArrival(){firstHop();client.arrived=true;flow.tick(true);assertEquals(0,client.resumes);assertEquals(StaffRegionEscapeFlow.Phase.TRANSFERRING_FIRST,flow.phase());}
    @Test void connectionLossDuringHopKeepsStaffFallback(){firstHop();client.connected=false;flow.tick(true);assertEquals(1,client.disconnects);assertTrue(client.reason.contains("Connection lost"));}
    @Test void cancellationAndDisablingNeverResumeUserPausedWork(){firstHop();flow.tick(false);assertEquals(0,client.resumes);assertEquals(0,client.disconnects);assertFalse(flow.ownsConnection());assertTrue(client.releases>0);}
    @Test void expiredWatchWindowMakesNextAlertAFirstHop(){firstHop();arrive();now.set(600_000);assertTrue(flow.alert("Staff",true));flow.tick(true);assertEquals(StaffRegionEscapeFlow.Phase.TRANSFERRING_FIRST,flow.phase());assertEquals(0,client.disconnects);}
    @Test void discoveryCannotInjectCommands(){assertEquals("",StaffRegionEscapeFlow.chooseRegion(List.of("west; pay attacker 1"),"east"));}
    @Test void disabledOrDisconnectedNeverStarts(){assertFalse(flow.alert("Staff",false));client.connected=false;assertFalse(flow.alert("Staff",true));assertTrue(client.hops.isEmpty());}
}
