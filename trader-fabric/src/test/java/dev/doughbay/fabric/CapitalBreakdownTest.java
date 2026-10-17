package dev.doughbay.fabric;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class CapitalBreakdownTest {
 @TempDir Path dir;
 private AutomatedExecutionDriver.OwnOrderRow row(int total,int delivered,boolean cancelled){return new AutomatedExecutionDriver.OwnOrderRow("minecraft:diamond","minecraft:diamond",total,100,delivered,1000,cancelled);}
 @Test void collectionMovesCostBetweenBucketsWithoutDuplication(){
  var before=CapitalBreakdown.orders(1000,List.of(row(100,40,false)),Map.of());assertEquals(6000L,before.reserved());assertEquals(4000L,before.awaitingCollection());
  var after=CapitalBreakdown.orders(1000,List.of(row(100,40,false)),Map.of("minecraft:diamond|100|100",40));assertEquals(6000L,after.reserved());assertEquals(0L,after.awaitingCollection());
  var snapshot=CapitalBreakdown.combine(1000,5000,1000,after,new CapitalBreakdown.Stock(1000,4000,0,0,0,0));assertEquals(15000d,snapshot.knownTotal());
 }
 @Test void identicalOrdersShareCollectedCounterOnlyOnce(){var o=CapitalBreakdown.orders(1000,List.of(row(100,40,false),row(100,20,false)),Map.of("minecraft:diamond|100|100",50));assertEquals(14000L,o.reserved());assertEquals(1000L,o.awaitingCollection());}
 @Test void cancelledOrderHasGoodsButNoReservedUnfilledFunds(){var o=CapitalBreakdown.orders(1000,List.of(row(100,40,true)),Map.of());assertEquals(0L,o.reserved());assertEquals(4000L,o.awaitingCollection());}
 @Test void staleAndImpossibleValuesStayUnknown(){var o=CapitalBreakdown.orders(1000,List.of(row(100,110,false)),Map.of());assertNull(o.reserved());assertNull(o.awaitingCollection());assertNull(CapitalBreakdown.orders(1000,List.of(row(100,40,false)),Map.of("minecraft:diamond|100|100",-1)).awaitingCollection());
  var s=CapitalBreakdown.combine(400000,0,1,new CapitalBreakdown.Orders(1,0,0L,0L,"old"),new CapitalBreakdown.Stock(1,100,200,999,0,0));assertNull(s.bank());assertNull(s.orders());assertNull(s.listed());assertNull(s.asking());assertEquals(0d,s.knownTotal());}
 @Test void askingPriceDoesNotBecomeBookValue(){var s=CapitalBreakdown.combine(1000,0,1000,new CapitalBreakdown.Orders(1000,0,0L,0L,"observed"),new CapitalBreakdown.Stock(1000,200,300,5000,100,0));assertEquals(600d,s.knownTotal());assertEquals(5000L,s.asking());}
 @Test void historyPersistsUnknownsAndNeverBackfillsOrOverwritesCorruption()throws Exception{
  Path file=dir.resolve("history.json");var h=new CapitalHistory(file);
  var s=CapitalBreakdown.combine(1000,-1,0,null,new CapitalBreakdown.Stock(1000,200,300,500,0,0));h.record(s);h.record(CapitalBreakdown.combine(1001,-1,0,null,null));assertEquals(1,h.copy().size());
  var read=new CapitalHistory(file);assertNull(read.copy().getFirst().bank());assertEquals(1000,read.copy().getFirst().at());
  Files.writeString(file,"broken");new CapitalHistory(file).record(s);assertEquals("broken",Files.readString(file));
 }
 @Test void retailParentsAndOtherAccountsAreNotCountedInThisAccountsStock()throws Exception{
  try(var db=new dev.doughbay.storage.Database(dir.resolve("ledger.db"))){var repo=new dev.doughbay.storage.PositionRepository(db);
   repo.setClient("main");repo.insert(position(dev.doughbay.core.model.PositionStatus.PURCHASED,200,0));repo.insert(position(dev.doughbay.core.model.PositionStatus.LISTED,300,700));repo.insert(position(dev.doughbay.core.model.PositionStatus.CANCELLED,500,0));
   repo.setClient("alt");repo.insert(position(dev.doughbay.core.model.PositionStatus.LISTED,900,1800));
   repo.setClient("");repo.insert(position(dev.doughbay.core.model.PositionStatus.PURCHASED,50,0));
   var capital=LedgerStatsProvider.readCapital(db,1000).account("MAIN");assertEquals(200,capital.unlisted());assertEquals(300,capital.listed());assertEquals(700,capital.asking());assertEquals(50,capital.unattributed());
  }
 }
 private dev.doughbay.core.model.Position position(dev.doughbay.core.model.PositionStatus state,long cost,long target){return new dev.doughbay.core.model.Position(0,"REAL","minecraft:diamond",dev.doughbay.core.model.StackBucket.X64,64,cost,target,1,0,0,0,Double.NaN,state);}
}
