package dev.doughbay.fabric.staffwatch;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
class LocalCompletionInboxTest {
    @Test void latePreTransferReplyIsConsumedButCannotTrigger(){var inbox=new LocalCompletionInbox();inbox.register(30000);inbox.reset();assertTrue(inbox.complete(30000,List.of("Staff")));assertNull(inbox.take());inbox.register(30001);assertTrue(inbox.complete(30001,List.of("Staff")));assertEquals(List.of("Staff"),inbox.take());assertNull(inbox.take());}
    @Test void alreadyReceivedOldReplyIsClearedOnArrival(){var inbox=new LocalCompletionInbox();inbox.register(1);inbox.complete(1,List.of("Staff"));inbox.reset();assertNull(inbox.take());}
    @Test void otherModulesResponsesAreNeverStolen(){var inbox=new LocalCompletionInbox();assertFalse(inbox.complete(999,List.of("Staff")));}
    @Test void pendingRequestsStayBoundedAcrossTransfers(){var inbox=new LocalCompletionInbox();for(int i=0;i<100;i++){inbox.register(i);inbox.reset();}assertFalse(inbox.complete(0,List.of("Staff")));assertTrue(inbox.complete(99,List.of("Staff")));assertNull(inbox.take());}
}
