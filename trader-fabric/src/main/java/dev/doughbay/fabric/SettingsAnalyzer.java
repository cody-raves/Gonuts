package dev.doughbay.fabric;

import dev.doughbay.core.model.Opportunity;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Local, read-only analysis. Findings are evidence, never controller actions. */
public final class SettingsAnalyzer {
    public record Finding(String title, String evidence, String suggestion, String estimate) { }
    public record Input(long now, long bank, long purchaseCap, int accountSlots, int accounts,
                        long activeMillis, ItemBoard.Board board, Set<String> tracked,
                        List<Opportunity> opportunities, List<Follows.Seen> follows,
                        boolean freshMarkets, Map<String, Double> settings, int accountListed, List<dev.doughbay.core.model.MarketStats> markets) {
        Input(long now,long bank,long purchaseCap,int accountSlots,int accounts,long activeMillis,ItemBoard.Board board,
              Set<String> tracked,List<Opportunity> opportunities,List<Follows.Seen> follows,boolean freshMarkets,Map<String,Double> settings){
            this(now,bank,purchaseCap,accountSlots,accounts,activeMillis,board,tracked,opportunities,follows,freshMarkets,settings,-1,List.of());
        }
    }
    public record Report(long builtAt, List<String> baseline, List<Finding> findings,
                         Map<String, Double> numbers, Map<String, String> lists,
                         String currentCode, String proposedCode) { }
    private static volatile Report report;
    private static volatile CompletableFuture<Void> pending;
    private static volatile String status = "Press Analyze to review your local trading history";
    public static Report report() { return report; }
    public static String status() { return status; }
    public static synchronized void refresh() {
        if (pending != null && !pending.isDone()) return;
        status = "Analyzing local ledger and observed markets...";
        // Capture account context on the client thread; cached ledger reads and follow aggregation run off it.
        var watcher = DoughBayClient.watcher(); var market = watcher == null ? null : watcher.snapshot();
        var session = DoughBayClient.automationSessionController().snapshot();
        long cap; try { cap = DoughBayClient.automationSessionController().purchaseCapNow(); } catch (RuntimeException unavailable) { cap = 0; }
        final long capturedCap = cap;
        int accounts = DoughBayClient.multiClient() && DoughBayClient.apiBudget() != null ? Math.max(1,DoughBayClient.apiBudget().clients()) : 1;
        Map<String,Double> values = new LinkedHashMap<>();
        for (var setting : Tuning.SETTINGS) values.put(setting.key(), Tuning.get(setting.key()));
        Set<String> tracked = new TreeSet<>(); for (String key : ItemLists.tracked()) tracked.add(base(key));
        Map<String,String> texts = new LinkedHashMap<>();
        for (var list : Tuning.LISTS) if (!list.key().equals("webhook.url")) texts.put(list.key(),Tuning.text(list.key()));
        String current = SettingsCode.exportPreset(values, texts);
        long now = System.currentTimeMillis();
        pending = CompletableFuture.runAsync(() -> {
            try {
                var ledger = DoughBayClient.ledgerStats();
                var board = ledger == null ? ItemBoard.Board.empty(ItemBoard.Range.DAY) : ledger.board(ItemBoard.Range.DAY);
                var follows = DoughBayClient.follows();
                var input = new Input(now, market == null || market.demo() ? -1 : market.accountBalance(), capturedCap,
                    (int)values.getOrDefault("slots.max",0d).doubleValue(), accounts, session.lifetimeActiveMillis(), board,
                    tracked, market == null || market.demo() ? List.of() : market.opportunities(),
                    follows == null ? List.of() : follows.recent(now-86_400_000L),
                    market != null && !market.demo() && market.updatedAt() > 0 && now-market.updatedAt() <= 300_000, values, session.openListings(),market == null || market.demo()?List.of():market.markets());
                Report analyzed = analyze(input);
                var proposedNumbers = new LinkedHashMap<>(values); proposedNumbers.putAll(analyzed.numbers());
                var proposedLists = new LinkedHashMap<>(texts); proposedLists.putAll(analyzed.lists());
                report = new Report(analyzed.builtAt(), analyzed.baseline(), analyzed.findings(), analyzed.numbers(), analyzed.lists(),
                    current, SettingsCode.exportPreset(proposedNumbers, proposedLists));
                status = "Analysis ready; no settings were changed";
            } catch (Exception failure) { status = "Analysis unavailable; existing settings and report kept"; }
        });
    }
    static String base(String key) {
        String result = key == null ? "" : key.split("[|#]",2)[0];
        return result.contains(":") ? result : "minecraft:" + result;
    }
    static String money(double value) { return String.format(Locale.ROOT,"$%,.0f",value); }
    static Report analyze(Input in) {
        var findings = new ArrayList<Finding>(); var baseline = new ArrayList<String>();
        var changes = new LinkedHashMap<String,Double>(); var lists = new LinkedHashMap<String,String>();
        ItemBoard.Board board = in.board();
        baseline.add("24h local ledger: " + board.sales() + " sales; " + money(board.profit()) + " realized profit");
        baseline.add("24h wall-clock average: " + money(board.profit()/24d) + "/h; " + String.format(Locale.ROOT,"%.1f",board.sales()/24d) + " sales/h");
        baseline.add("This account bank: " + (in.bank()<0 ? "unknown" : money(in.bank())) + "; effective purchase cap " + money(in.purchaseCap()));
        baseline.add(in.accounts()+" active client(s); this account slot limit " + in.accountSlots() + "; hive ledger open positions " + board.open());
        baseline.add("This account occupied slots: "+(in.accountListed()<0?"unknown":in.accountListed())+" / "+in.accountSlots()+"; capacities of other accounts are not guessed");
        baseline.add("This account recorded active time: " + String.format(Locale.ROOT,"%.1f",in.activeMillis()/3_600_000d) + "h; not used to divide hive-wide profit");
        baseline.add("Explicit tracked pool: " + (in.tracked().isEmpty()?"all/default markets":in.tracked().size()+" items") + "; local observation timestamp " + in.now());
        if (!in.freshMarkets()) findings.add(new Finding("Market observations are stale or unavailable",
            "No recent non-demo market snapshot is available.","Refresh normal market observations before choosing a replacement.","No market profit forecast provided."));
        if (in.bank()==0) findings.add(new Finding("No available bankroll", "The latest account balance is zero.",
            "Add trading capital or wait for existing sales; increasing trade caps cannot create funds.","No earnings improvement can be estimated without usable capital."));
        else if (in.bank()<0) findings.add(new Finding("Bankroll is not known", "The client has not read a real balance.",
            "Wait for the normal balance read before sizing caps.","Capital recommendations withheld."));
        else if (in.purchaseCap()>in.bank()) {
            findings.add(new Finding("Purchase cap exceeds available bank", money(in.purchaseCap())+" cap against "+money(in.bank())+" available.",
                "Enable limits that follow your bank; review the per-purchase share before applying.","Reduces unaffordable candidates; additional profit is not established."));
            if (in.settings().getOrDefault("buy.scale_with_bank",1d)<.5) changes.put("buy.scale_with_bank",1d);
        }
        if(in.accountListed()>=0 && in.accountSlots()>0 && in.accountListed()<in.accountSlots()/2 && board.sales()>=10)
            findings.add(new Finding("Available listing capacity",in.accountListed()+" of this account's "+in.accountSlots()+" slots are occupied, alongside "+board.sales()+" hive-ledger sales in 24h.",
                "Check whether profitable stock is available but unlisted, or sourcing is not replenishing sales. Add markets only when observed opportunities justify them.",
                "Empty slots are potential capacity, not a measured profit shortfall; no linear profit multiplier assumed."));
        if(board.sales()<10) findings.add(new Finding("Not enough completed sales yet",board.sales()+" local completed sales in 24 hours.",
            "Collect more runtime before replacing markets based on performance.","No reliable improvement estimate yet."));
        var weak=board.rows().stream().filter(r->r.sales()>=10 && r.profit()<0 && in.tracked().contains(base(r.itemId())))
            .min(Comparator.comparingLong(ItemBoard.Row::profit)).orElse(null);
        if(weak!=null) findings.add(new Finding("Review "+weak.name(), weak.sales()+" sales lost "+money(-weak.profit())+" in 24h; "+weak.open()+" positions remain open.",
            "Stop new sourcing in this market as a controlled experiment; preserve normal management of existing stock.",
            "Avoided loss is hypothetical; removing the item does not recover losses already incurred."));
        var profitable=board.rows().stream().filter(r->r.sales()>=10 && r.profit()>0 && r.medianSellMillis()>0)
            .max(Comparator.comparingLong(ItemBoard.Row::perSlotHour)).orElse(null);
        if(profitable!=null) findings.add(new Finding("Proven local performer: "+profitable.name(), profitable.sales()+" sales; "+money(profitable.profit())+" profit; "+money(profitable.perSlotHour())+" per occupied slot-hour.",
            "Compare this market with weaker ones before allocating more stock; keep demand and concentration limits.",
            "One extra fully utilized slot at 50-100% of its observed rate: "+money(profitable.perSlotHour()*.5)+" to "+money(profitable.perSlotHour())+"/h. Conditional scenario, not a forecast."));
        if(!in.tracked().isEmpty()) {
            long noSales=in.tracked().stream().filter(id->board.rows().stream().noneMatch(r->base(r.itemId()).equals(id)&&r.sales()>0)).count();
            if(noSales>0) findings.add(new Finding("Unproven tracked markets",noSales+" of "+in.tracked().size()+" tracked items have no local sale in this 24h window.",
                "Review observed demand and exposure before narrowing the pool; no sales alone does not mean a bad market.","Reading-time savings need measured lap durations; no percentage guessed."));
        }
        Map<String,Long> notices=new HashMap<>();
        for(var seen:in.follows()) if((seen.kind().equals("listed")||seen.kind().equals("bought"))&&!seen.itemId().isBlank()) notices.merge(base(seen.itemId()),1L,Long::sum);
        baseline.add("Follow observations: "+notices.size()+" item markets with public listing/purchase notices; notices are not your profit.");
        var candidate = in.freshMarkets() && in.bank()>0 ? in.opportunities().stream()
            .filter(o->o.listing()!=null&&o.listing().isValid()&&o.listing().observedAt()>0&&in.now()-o.listing().observedAt()<=300_000&&o.stats()!=null&&o.stats().sampleCount()>=10&&o.stats().newestSaleAt()>0&&in.now()-o.stats().newestSaleAt()<=86_400_000L&&o.confidence()>=.6
                && Double.isFinite(o.expectedNetProfit())&&o.expectedNetProfit()>0&&o.buyPrice()>0&&o.buyPrice()<=Math.min(in.bank(),in.purchaseCap())
                && !in.tracked().isEmpty()&&!in.tracked().contains(base(o.listing().itemId())))
            .max(Comparator.comparingDouble(Opportunity::expectedNetProfit)).orElse(null):null;
        if(candidate!=null) {
            String id=base(candidate.listing().itemId());
            findings.add(new Finding("Trial candidate: "+id, candidate.stats().sampleCount()+" observed sales; current buy "+money(candidate.buyPrice())+"; conservative resale "+money(candidate.recommendedSellPrice())+"; follow notices "+notices.getOrDefault(id,0L)+".",
                weak==null?"Consider a small trial; there is no proven weak market to replace automatically.":"Suggested preset swaps the watch entry for "+weak.name()+" with this market; existing item filters and stock management remain in force.",
                "Estimated net profit per quoted trade: "+money(candidate.expectedNetProfit())+". Your fill rate and profit/hour in this market are unmeasured."));
            if(weak!=null){Set<String> proposed=new TreeSet<>(in.tracked());proposed.remove(base(weak.itemId()));proposed.add(id);lists.put("watch.tracked",String.join(", ",proposed));changes.put("watch.tracked_managed",1d);}
        }
        if(candidate==null && in.freshMarkets() && !in.tracked().isEmpty()) {
            var observed=in.markets().stream().filter(m->!in.tracked().contains(base(m.itemKey())) && m.sampleCount()>=20
                && m.confidence()>=.6 && m.newestSaleAt()>0 && in.now()-m.newestSaleAt()<=86_400_000L
                && Double.isFinite(m.quickSalePrice()) && m.quickSalePrice()>0 && Double.isFinite(m.salesPerHour()) && m.salesPerHour()>0)
                .max(Comparator.comparingDouble(dev.doughbay.core.model.MarketStats::salesPerHour)).orElse(null);
            if(observed!=null){String id=base(observed.itemKey());
                findings.add(new Finding("Observed market to investigate: "+id,
                    observed.sampleCount()+" observed sales; "+String.format(Locale.ROOT,"%.1f",observed.salesPerHour())+" market-wide sales/h; conservative resale "+money(observed.quickSalePrice())+" for "+observed.bucket().label()+"; follow notices "+notices.getOrDefault(id,0L)+".",
                    "Compare a fresh acquisition quote and normal risk checks before trading. This is observed demand outside your watch pool, not a proven profitable entry.",
                    "Acquisition cost and your sell-through are unknown; market-wide sales/h is not your throughput. No profit uplift estimated."));
            }
        }
        if(findings.isEmpty()) findings.add(new Finding("No supported changes found","Current observations do not justify a specific settings change.","Keep collecting comparable results.","No uplift estimated."));
        for(var entry:changes.entrySet()) baseline.add("Suggested setting: "+entry.getKey()+" "+in.settings().getOrDefault(entry.getKey(),0d)+" -> "+entry.getValue());
        for(var entry:lists.entrySet()) baseline.add("Suggested list: "+entry.getKey()+" -> "+entry.getValue());
        baseline.add("Historical results reflect earlier configurations too; this report does not claim your current settings caused them.");
        return new Report(in.now(),List.copyOf(baseline),List.copyOf(findings),Map.copyOf(changes),Map.copyOf(lists),"","");
    }
}
