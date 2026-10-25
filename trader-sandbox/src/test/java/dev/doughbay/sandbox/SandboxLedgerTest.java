package dev.doughbay.sandbox;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SandboxLedgerTest {
    @TempDir Path directory;
    @Test void threeExactSixteenStackOrdersRemainSeparateThroughPartialCollections(){
        var m=new SandboxLedger();long before=m.balance();
        var a=m.place("minecraft:stone",1024,100);var b=m.place("minecraft:stone",1024,100);var c=m.place("minecraft:stone",1024,100);
        assertNotEquals(a.id,b.id);assertNotEquals(b.id,c.id);assertEquals(before-307200,m.balance());
        m.deliver(b.id,1024);assertEquals(64,m.collect(b.id,64));assertEquals(960,b.owed());assertEquals(0,a.delivered);assertEquals(0,c.delivered);
        assertEquals(960,m.collect(b.id,2000));assertEquals(0,m.collect(b.id,64));assertEquals(1024,m.stock("minecraft:stone"));
        assertEquals(204800,m.locked());
    }
    @Test void cancelRefundsOnlyUndeliveredEscrowAndKeepsPhysicalGoods(){
        var m=new SandboxLedger();long before=m.balance();var o=m.place("minecraft:diamond",192,1000);
        m.deliver(o.id,70);assertEquals(122000,m.cancel(o.id));assertEquals(0,m.cancel(o.id));assertEquals(70,o.owed());
        assertEquals(0,m.deliver(o.id,100));assertEquals(70,m.collect(o.id,100));assertEquals(before-70000,m.balance());
    }
    @Test void listingEscrowsStockAndSaleCreditsOnlyOnceAfterFee(){
        var m=new SandboxLedger();long before=m.balance();var o=m.place("minecraft:stone",64,100);m.deliver(o.id,64);m.collect(o.id,64);
        var listing=m.list("minecraft:stone",64,8000);assertEquals(0,m.stock("minecraft:stone"));assertEquals(1,m.occupied());
        assertEquals(7840,m.sell(listing.id));assertEquals(0,m.sell(listing.id));assertEquals(0,m.withdraw(listing.id));
        assertEquals(before-6400+7840,m.balance());assertEquals(0,m.occupied());
    }
    @Test void duplicatePurchaseCannotChargeOrCreateStockTwice(){
        var m=new SandboxLedger();m.seedMarket();var listing=m.listings().getFirst();long before=m.balance();
        assertTrue(m.buy(listing.id));assertFalse(m.buy(listing.id));assertEquals(before-listing.price,m.balance());assertEquals(listing.count,m.stock(listing.item));
    }
    @Test void rejectedActionsDoNotChangeBalanceOrOutstandingOrders(){
        var m=new SandboxLedger();long before=m.balance();assertThrows(ArithmeticException.class,()->m.place("minecraft:stone",2,Long.MAX_VALUE));
        assertThrows(IllegalStateException.class,()->m.place("minecraft:stone",4096,1_000_000_000));assertTrue(m.orders().isEmpty());assertEquals(before,m.balance());
        assertThrows(IllegalStateException.class,()->m.list("minecraft:diamond",64,100));assertTrue(m.listings().isEmpty());
    }
    @Test void fullPageFixtureLeavesSpecificCollectibleOrdersOnTheSecondPage(){
        var m=new SandboxLedger();m.seedFullOrders();assertEquals(48,m.orders().size());var later=m.orders().get(47);
        assertEquals(192,later.owed());assertEquals(64,m.collect(later.id,64));assertEquals(192,m.orders().getFirst().owed());
        assertThrows(IllegalStateException.class,m::seedFullOrders);
    }
    @Test void partialAndStalledScenariosAreDeterministic(){
        var m=new SandboxLedger();var o=m.place("minecraft:stone",192,100);m.scenario("partials");assertEquals(7,m.advance().getFirst().count());
        m.scenario("stalled");for(int i=0;i<4;i++)m.advance();assertEquals(7,o.delivered);m.scenario("steady");m.advance();assertEquals(71,o.delivered);
    }
    @Test void saveReloadPreservesExactOrdersEscrowOwedStockAndFollowing()throws Exception{
        var m=new SandboxLedger();var a=m.place("minecraft:stone",1024,100);m.deliver(a.id,100);m.collect(a.id,64);m.toggleFollow(SandboxLedger.ACTORS.getFirst());
        Path file=directory.resolve("world/sandbox.json");m.save(file);var restored=SandboxLedger.load(file);
        assertEquals(m.balance(),restored.balance());assertEquals(m.locked(),restored.locked());assertEquals(36,restored.order(a.id).owed());assertEquals(64,restored.stock(a.item));assertEquals(m.following(),restored.following());
        assertTrue(restored.place(a.item,1024,100).id>a.id);
    }
    @Test void corruptSavedStateDoesNotSilentlyResetMoneyOrPositions()throws Exception{
        Path file=directory.resolve("sandbox.json");Files.writeString(file,"{broken");assertThrows(java.io.IOException.class,()->SandboxLedger.load(file));
        var m=new SandboxLedger();m.save(file);Files.writeString(file,Files.readString(file).replace("\"balance\": 1000000000","\"balance\": -1"));assertThrows(java.io.IOException.class,()->SandboxLedger.load(file));
    }
    @Test void withdrawingAnUnsoldListingRestoresStockWithoutCreatingMoney(){
        var m=new SandboxLedger();var o=m.place("minecraft:stone",64,100);m.deliver(o.id,64);m.collect(o.id,64);long balance=m.balance();var l=m.list(o.item,64,8000);
        assertEquals(64,m.withdraw(l.id));assertEquals(0,m.withdraw(l.id));assertEquals(64,m.stock(o.item));assertEquals(balance,m.balance());
    }
}
