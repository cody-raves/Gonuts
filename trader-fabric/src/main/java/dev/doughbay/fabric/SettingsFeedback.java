package dev.doughbay.fabric;

import java.util.*;

/** Explanations only. Never changes settings, trade eligibility or safety gates. */
public final class SettingsFeedback {
    public record Notice(String id, String level, String title, String effect, List<String> keys) { }
    private SettingsFeedback() { }
    public static List<Notice> current() { return evaluate(numbers(), texts()); }
    static Map<String,Double> numbers() {
        var values=new LinkedHashMap<String,Double>();
        for(var s:Tuning.SETTINGS)values.put(s.key(),Tuning.get(s.key()));
        return values;
    }
    static Map<String,String> texts() {
        // Rules need only the focus list, never credentials, addresses or arbitrary text.
        return Map.of("buy.focus_items",Tuning.text("buy.focus_items"));
    }
    public static List<Notice> preview(String key,String value) {
        var n=numbers();var t=new HashMap<>(texts());
        var setting=Tuning.SETTINGS.stream().filter(s->s.key().equals(key)).findFirst().orElse(null);
        if(setting!=null){
            Double parsed=WebPages.typed(value,setting.kind()==Tuning.Kind.TOGGLE);
            if(parsed==null)throw new IllegalArgumentException("Enter a valid number.");
            n.put(key,setting.clamp(parsed));
        }else if(key.equals("buy.focus_items"))t.put(key,value==null?"":value);
        else if(Tuning.LISTS.stream().noneMatch(s->s.key().equals(key)))throw new IllegalArgumentException("Unknown setting.");
        return evaluate(n,t);
    }
    public static List<Notice> evaluate(Map<String,Double> n,Map<String,String> t) {
        var out=new ArrayList<Notice>();
        if(on(n,"multi.enabled")&&on(n,"multi.scan_only"))add(out,"scan-only","warning","This client only scans",
            "Scan-only mode prevents this client from executing trades. Turn it off if this account should buy and sell.","multi.enabled","multi.scan_only");
        if(on(n,"buy.focus")){
            boolean empty=t.getOrDefault("buy.focus_items","").split("[,;\\s]+").length==0||t.getOrDefault("buy.focus_items","").isBlank();
            if(empty)add(out,"empty-focus","warning","Focus has no items",
                "No new auction purchases or buy orders can pass the focus filter. Add focus items or turn Focus off. Existing stock is still managed.","buy.focus","buy.focus_items");
            else add(out,"focus-filter","info","Buying is limited to your focus list",
                "Tracking an item alone does not allow buying it. New purchases and orders must also pass Focus and the Items filters.","buy.focus","buy.focus_items","watch.tracked","items.allow","items.deny");
        }
        if(!on(n,"orders.enabled")&&on(n,"orders.bid"))add(out,"orders-off","warning","Order-book scanning is switched off",
            "Bidding is on, but routine order-book scanning is off. The bot loses those market-discovery reads; the separate bid desk can still manage and place orders. This is not a complete bidding stop.","orders.enabled","orders.bid");
        if(on(n,"orders.enabled")&&!on(n,"orders.bid"))add(out,"bidding-off","info","No new buy orders",
            "The order system may manage existing orders, but bidding is off, so it will not place new ones.","orders.enabled","orders.bid");
        if(on(n,"orders.bid")){
            if(n.getOrDefault("orders.bid_min_samples",6d)>=15&&!on(n,"orders.bid_on_real_trades"))add(out,"history-wait","warning","New markets may wait for price history",
                "Ordinary history-based bids need "+Math.round(n.getOrDefault("orders.bid_min_samples",6d))+" price observations. The early real-trade fallback is off; supported retail pricing paths can still qualify. Markets without enough evidence may wait, especially on a new client.","orders.bid_min_samples","orders.bid_on_real_trades");
            if(n.getOrDefault("orders.depth_share_pct",100d)<=5)add(out,"depth-sizing","info","Market depth can reduce or block a batch",
                "Each bid is limited to 5% or less of observed daily volume. Thin whole-stack markets can be skipped. Stacks per bid is a maximum, not a promised batch size.","orders.depth_share_pct","orders.bid_stacks");
            if(on(n,"orders.fixed_batch"))add(out,"fixed-batch","info","The whole batch must fit",
                "New automatic whole-stack bids wait instead of shrinking when the requested batch cannot fit quantity recognition, market depth, listing room or spending limits. Price and ownership guards still apply.","orders.fixed_batch","orders.bid_stacks");
            if(on(n,"orders.fixed_batch")&&n.getOrDefault("orders.bid_stacks",3d)>15)add(out,"exact-count","warning","A 16-stack ordinary batch still waits for quantity support",
                "16 stacks of 64 items require 1,024. This draft retains the 999-item recognition guard and does not substitute a smaller batch. Do not enable it for 16 ordinary stacks until the full native placement and collection path is verified.","orders.fixed_batch","orders.bid_stacks");
            if(!on(n,"orders.fixed_batch")&&n.getOrDefault("orders.bid_stacks",3d)>15)add(out,"exact-count","info","64-item stacks are capped below 1,000 units",
                "The order house abbreviates larger quantities. Ordinary 64-item stack bids are capped at 15 stacks so their exact quantities remain recognizable; other limits may reduce them further.","orders.bid_stacks");
        }
        if(!on(n,"orders.collect")){
            if(on(n,"orders.bid"))add(out,"collection-off","info","Bidding can still collect its fills",
                "Collect filled orders is off, but an active bid desk can still collect matching deliveries. Turn off bidding as well if the intent is to stop normal desk collection.","orders.collect","orders.bid");
            else add(out,"desk-off","warning","The order desk will not run",
                "Both bidding and filled-order collection are off. The normal order desk will neither place new bids nor collect deliveries.","orders.collect","orders.bid");
        }
        if(on(n,"orders.reserve_stock")&&n.getOrDefault("orders.reserve_stacks",12d)>n.getOrDefault("orders.bid_stacks",3d))add(out,"reserve-ceiling","info","Reserve batches use the lower stack limit",
            "Reserve batch stacks exceeds Stacks per bid. The smaller limit is used, and the complete batch must still fit spending and depth limits.","orders.reserve_stock","orders.reserve_stacks","orders.bid_stacks");
        if(n.getOrDefault("orders.max_per_market",0d)>1)add(out,"multiple-orders","info","Separate orders share capacity and capital",
            "Buy orders per item permits more than one separate automatic order in normal mode too. Filled orders awaiting collection still count. Stacks per bid remains a per-order ceiling; all orders and owned stock share depth and spending limits. Stock stays on the order house until the selling shelf has room.","orders.max_per_market","orders.bid_stacks","orders.depth_share_pct");
        if(on(n,"orders.reserve_stock"))add(out,"reserve-batch-count","info","Reserve Stock uses a funded stock cycle",
            "Buy orders per item also sets purchases per Reserve Stock cycle (0 keeps its previous two-batch default). The next stock cycle waits until existing stock and orders drain. Account order slots, spending and depth limits still apply.","orders.reserve_stock","orders.max_per_market","orders.reserve_stacks");
        if(on(n,"underdog.enabled")&&on(n,"orders.bid"))add(out,"rival-lots","info","Rival trades may request small quantities",
            "Underdog orders follow the observed rival lot, up to one stack. Stacks per bid does not turn those lots into bulk orders.","underdog.enabled","orders.bid_stacks");
        if(on(n,"orders.bid_on_real_trades")&&!on(n,"feed.keyless"))add(out,"real-trades-feed","info","Early trade evidence needs the keyless feed",
            "The early real-trade fallback only runs while the keyless feed is active. Selecting it does not bypass sample and confidence requirements on other feeds.","orders.bid_on_real_trades","feed.keyless");
        if(!on(n,"evasion.enabled")&&on(n,"evasion.escape"))add(out,"evasion-off","warning","Evasion responses are inactive",
            "Automatic escape is selected, but the Evasion guard is off. Those guard responses cannot protect this client until the guard is enabled. Separate Staff Watch controls are unchanged.","evasion.enabled","evasion.escape");
        if(on(n,"hive.split_markets")&&!on(n,"multi.enabled"))add(out,"split-inactive","info","Market splitting needs multi-client mode",
            "Market splitting is selected while multi-client mode is off. Do not expect this switch alone to coordinate separate accounts.","hive.split_markets","multi.enabled");
        if(on(n,"prices.only"))add(out,"own-prices","warning","Automatic markets are restricted",
            "Only your own price list is traded. Other watched markets will not receive ordinary automatic bids.","prices.only","prices.own");
        if(on(n,"buy.scale_with_bank")&&n.getOrDefault("buy.purchase_cap_pct",0d)>n.getOrDefault("buy.outstanding_cap_pct",100d))add(out,"spending-ceiling","info","Total exposure can limit a single purchase",
            "The per-purchase percentage is higher than the total exposure percentage. Available total headroom can prevent a purchase even when it fits the purchase cap.","buy.scale_with_bank","buy.purchase_cap_pct","buy.outstanding_cap_pct");
        for(String stem:List.of("pace.step","pace.command","pace.gap","pace.long_gap","pace.react","pace.click_gap")){
            String min=stem+"_min_sec",max=stem+"_max_sec";
            if(n.containsKey(min)&&n.containsKey(max)&&n.get(min)>n.get(max))add(out,"range-"+stem,"warning","Minimum delay exceeds maximum",
                "These delay endpoints are reversed. Set the minimum at or below the maximum for a clear pacing range.",min,max);
        }
        out.sort(Comparator.comparingInt(notice->notice.level().equals("warning")?0:1));
        return List.copyOf(out);
    }
    private static boolean on(Map<String,Double> n,String k){return n.getOrDefault(k,0d)>=.5;}
    private static void add(List<Notice> out,String id,String level,String title,String effect,String...keys){out.add(new Notice(id,level,title,effect,List.of(keys)));}
    public static List<Notice> forKey(List<Notice> notices,String key){return notices.stream().filter(n->n.keys().contains(key)).toList();}
}
