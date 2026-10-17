package dev.doughbay.fabric;
import java.util.*;

/** Cost-basis buckets are distinct; asking prices are never booked assets or profit. */
public final class CapitalBreakdown {
 public static final List<String> LABELS=List.of("Available bank","Unfilled order funds","Order stock awaiting collection","Collected / unlisted stock","Auction stock at cost","Unreconciled stock at cost");
 public static final int[] COLORS={0xFF3987E5,0xFFD95926,0xFF199E70,0xFFFFD966,0xFFC08CFF,0xFF9EB6C9};
 public record Orders(long at,int rows,Long reserved,Long awaitingCollection,String note){}
 public record Stock(long at,long unlisted,long listed,long asking,long unresolved,long unattributed){}
 public record Snapshot(long at,Long bank,Long orders,Long awaitingCollection,Long unlisted,Long listed,Long unresolved,
                        Long asking,long bankAt,long ordersAt,long stockAt,List<String> notes){
  public List<Long> values(){return Arrays.asList(bank,orders,awaitingCollection,unlisted,listed,unresolved);}
  public Long value(int index){return values().get(index);}
  public double knownTotal(){double total=0;for(Long v:values())if(v!=null&&v>0)total+=v;return total;}
 }
 public static Orders orders(long at,List<AutomatedExecutionDriver.OwnOrderRow> rows,Map<String,Integer> collected){
  if(at<=0)return new Orders(0,0,null,null,"Own orders have not been read");
  long reserved=0,waiting=0;
  Map<String,Long> delivered=new HashMap<>(),prices=new HashMap<>();
  try{
   for(var row:rows){if(row.requested()<=0||row.unitPrice()<=0||row.delivered()<0||row.delivered()>row.requested())throw new ArithmeticException();
    String key=row.itemId()+"|"+row.unitPrice()+"|"+row.requested();
    if(!row.alreadyCancelled())reserved=Math.addExact(reserved,Math.multiplyExact((long)row.remaining(),row.unitPrice()));
    delivered.merge(key,(long)row.delivered(),Math::addExact);prices.put(key,row.unitPrice());
   }
   for(var e:delivered.entrySet()){
    long had=collected.getOrDefault(e.getKey(),0);
    if(had<0)return new Orders(at,rows.size(),reserved,null,"Collection counter is invalid; awaiting stock needs reconciliation");
    if(had>e.getValue())return new Orders(at,rows.size(),reserved,null,"Collection counter exceeds observed deliveries; awaiting stock needs reconciliation");
    waiting=Math.addExact(waiting,Math.multiplyExact(e.getValue()-had,prices.get(e.getKey())));
   }
   return new Orders(at,rows.size(),reserved,waiting,"Observed own-order rows only; delivered minus recorded collections. Manual changes and unseen pages may be missing.");
  }catch(ArithmeticException invalid){return new Orders(at,rows.size(),null,null,"Order quantities or costs could not be reconciled");}
 }
 public static Snapshot combine(long now,long bank,long bankAt,Orders orders,Stock stock){
  boolean bankFresh=bank>=0&&bankAt>0&&now-bankAt<=300_000;
  boolean ordersFresh=orders!=null&&orders.at()>0&&now-orders.at()<=300_000;
  boolean stockFresh=stock!=null&&stock.at()>0&&now-stock.at()<=30_000;
  List<String> notes=new ArrayList<>();
  notes.add("This account only. Pie shows observed capital at purchase cost, not net worth or realized profit.");
  notes.add("Sidebar bank amounts have the displayed resolution; source-read timestamps are retained.");
  if(!bankFresh)notes.add("Bank is unknown or older than five minutes.");
  if(!ordersFresh)notes.add("Own-order figures are unknown or older than five minutes.");
  if(orders!=null)notes.add(orders.rows()+" own-order rows observed. "+orders.note());
  if(!stockFresh)notes.add("Account stock ledger is unavailable or older than 30 seconds.");
  if(stock!=null&&stock.unattributed()>0)notes.add("Unattributed legacy stock exists in the hive ledger; it is not assigned to this account.");
  notes.add("Auction asking prices are potential gross revenue only. Unreconciled stock location is uncertain.");
  return new Snapshot(now,bankFresh?bank:null,ordersFresh?orders.reserved():null,ordersFresh?orders.awaitingCollection():null,
   stockFresh?stock.unlisted():null,stockFresh?stock.listed():null,stockFresh?stock.unresolved():null,stockFresh?stock.asking():null,
   bankAt,orders==null?0:orders.at(),stock==null?0:stock.at(),List.copyOf(notes));
 }
}
