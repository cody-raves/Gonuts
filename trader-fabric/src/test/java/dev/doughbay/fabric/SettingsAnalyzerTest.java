package dev.doughbay.fabric;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class SettingsAnalyzerTest {
 private SettingsAnalyzer.Input input(long bank,long cap,boolean fresh){return new SettingsAnalyzer.Input(1000000,bank,cap,90,2,3600000,ItemBoard.Board.empty(ItemBoard.Range.DAY),Set.of("minecraft:diamond"),List.of(),List.of(),fresh,Map.of("buy.scale_with_bank",0d));}
 @Test void zeroBankNeverProposesRaisingCaps(){var r=SettingsAnalyzer.analyze(input(0,1000000,true));assertTrue(r.findings().stream().anyMatch(f->f.title().equals("No available bankroll")));assertTrue(r.numbers().isEmpty());assertTrue(r.lists().isEmpty());}
 @Test void unknownBankIsDistinctFromZero(){var r=SettingsAnalyzer.analyze(input(-1,10000,false));assertTrue(r.findings().stream().anyMatch(f->f.title().equals("Bankroll is not known")));assertFalse(r.findings().stream().anyMatch(f->f.title().equals("No available bankroll")));assertTrue(r.numbers().isEmpty());}
 @Test void unaffordableFixedCapSuggestsScalingWithoutApplying(){double original=Tuning.get("buy.scale_with_bank");var r=SettingsAnalyzer.analyze(input(100,10000,true));assertEquals(1d,r.numbers().get("buy.scale_with_bank"));assertEquals(original,Tuning.get("buy.scale_with_bank"));assertTrue(r.findings().stream().anyMatch(f->f.estimate().contains("profit is not established")));}
 @Test void hiveProfitNeverDividedByOneAccountsUptime(){var r=SettingsAnalyzer.analyze(input(10000,100,true));assertTrue(r.baseline().stream().anyMatch(s->s.contains("not used to divide hive-wide profit")));assertTrue(r.baseline().stream().anyMatch(s->s.contains("wall-clock")));assertTrue(r.lists().isEmpty());}
 @Test void replacementRequiresRealEvidenceAndDoesNotClaimHourlyUplift(){
  long now=1000000;
  var weak=new ItemBoard.Row("minecraft:diamond","Diamond",10,640,1000,900,-100,0,10,-10,-10,3600000,360000,0,0,10,-100,2,200,3600000,999000,0,false,1);
  var board=new ItemBoard.Board(ItemBoard.Range.DAY,now,1,List.of(weak),10,-100,1000,2,200,new long[24]);
  var listing=new dev.doughbay.core.model.Listing("example",now,"","","minecraft:emerald","minecraft:emerald",64,100,null);
  var stats=new dev.doughbay.core.model.MarketStats("minecraft:emerald",dev.doughbay.core.model.StackBucket.X64,86400000,20,0,3,now,100,150,155,160,170,20,.01,0,.8,now);
  var opportunity=new dev.doughbay.core.model.Opportunity(listing,stats,100,150,50,50,.2,.8,.8,50,List.of());
  var input=new SettingsAnalyzer.Input(now,10000,1000,90,2,3600000,board,Set.of("minecraft:diamond"),List.of(opportunity),List.of(),true,Map.of());
  var result=SettingsAnalyzer.analyze(input);assertEquals("minecraft:emerald",result.lists().get("watch.tracked"));assertEquals(1d,result.numbers().get("watch.tracked_managed"));
  assertTrue(result.findings().stream().anyMatch(f->f.estimate().contains("profit/hour in this market are unmeasured")));
  var stale=new SettingsAnalyzer.Input(now,10000,1000,90,2,3600000,board,input.tracked(),input.opportunities(),List.of(),false,Map.of());
  assertTrue(SettingsAnalyzer.analyze(stale).lists().isEmpty());
 }
 @Test void outsideObservedDemandIsNotPresentedAsOurProfit(){
  var basic=input(10000,100,true);long now=basic.now();
  var market=new dev.doughbay.core.model.MarketStats("minecraft:emerald",dev.doughbay.core.model.StackBucket.X64,86400000,20,0,3,now,100,150,155,160,170,20,.01,0,.8,now);
  var expanded=new SettingsAnalyzer.Input(now,basic.bank(),basic.purchaseCap(),basic.accountSlots(),basic.accounts(),basic.activeMillis(),basic.board(),basic.tracked(),List.of(),List.of(),true,basic.settings(),0,List.of(market));
  var result=SettingsAnalyzer.analyze(expanded);
  assertTrue(result.findings().stream().anyMatch(f->f.title().startsWith("Observed market to investigate")&&f.estimate().contains("not your throughput")));
  assertTrue(result.lists().isEmpty());
 }
}
