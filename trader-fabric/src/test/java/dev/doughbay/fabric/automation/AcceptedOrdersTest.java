package dev.doughbay.fabric.automation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class AcceptedOrdersTest {
    @TempDir Path directory;
    private AcceptedOrders.Bid bid(String id, long price, int count) {
        return new AcceptedOrders.Bid(id,"minecraft:end_crystal",price,count,123);
    }

    @Test void threeIdentical1024OrdersRemainThreeReceiptsAcrossRestart() throws Exception {
        var book = new AcceptedOrders();
        for (int n=1;n<=3;n++) book.remember(bid("order-"+n,100,1024));
        Path path=directory.resolve("orders-bids.txt");
        book.save(path);
        var restarted=new AcceptedOrders(); restarted.load(path);
        assertEquals(3,restarted.values().size());
        assertEquals(3072,restarted.values().stream().mapToInt(AcceptedOrders.Bid::count).sum());
        assertEquals(book.values(),restarted.values());
        assertFalse(restarted.cancelUnique("minecraft:end_crystal",100,1024,456));
        assertEquals(3,restarted.values().size());
    }

    @Test void legacyMigrationRetainsRepeatedRowsAndBecomesStable() throws Exception {
        Path path=directory.resolve("orders-bids.txt");
        Files.writeString(path,"minecraft:end_crystal|100|192|123\n".repeat(3));
        var book=new AcceptedOrders();book.load(path);
        assertEquals(3,book.values().size());
        var original=book.values();book.save(path);book.load(path);
        assertEquals(original,book.values());
    }

    @Test void replayIsIdempotentButConflictingReceiptCannotOverwriteAcceptedCount() {
        var book=new AcceptedOrders();book.remember(bid("one",100,1024));
        book.remember(bid("one",100,1024));
        assertThrows(IllegalArgumentException.class,()->book.remember(bid("one",100,192)));
        assertEquals(1024,book.values().iterator().next().count());
        assertEquals(1,book.values().size());
    }

    @Test void itemOnlyFillCannotUseAnArbitraryOrdersCost() {
        var book=new AcceptedOrders();book.remember(bid("one",100,1024));book.remember(bid("two",120,1024));
        assertNull(book.unambiguousItem("minecraft:end_crystal"));
        assertEquals(100,book.matching("minecraft:end_crystal",100,1024).unitPrice());
        assertTrue(book.cancelUnique("minecraft:end_crystal",120,1024,456));
        assertNull(book.unambiguousItem("minecraft:end_crystal"));
        assertEquals(2,book.values().size());
        assertEquals(456,book.values().stream().filter(b->b.id().equals("two")).findFirst().orElseThrow().cancelledAt());
    }

    @Test void corruptReadCannotReplaceExistingMemoryOrSilentlyDropAReceipt() throws Exception {
        var book=new AcceptedOrders();book.remember(bid("one",100,1024));
        Path path=directory.resolve("orders-bids.txt");
        Files.writeString(path,"minecraft:end_crystal|100|1024|123|same\nminecraft:end_crystal|100|1024|123|same\n");
        assertThrows(IOException.class,()->book.load(path));
        assertEquals(1,book.values().size());
        assertEquals("one",book.values().iterator().next().id());
    }

    @Test void roundedPriceCannotSelectBetweenDifferentExactCosts() {
        var book=new AcceptedOrders();book.remember(bid("one",3599,1024));
        assertEquals(3599,book.matching("minecraft:end_crystal",3500,1024).unitPrice());
        book.remember(bid("two",3598,1024));
        assertNull(book.matching("minecraft:end_crystal",3500,1024));
    }
}
