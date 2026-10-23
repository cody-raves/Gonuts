package dev.doughbay.fabric;

import java.util.*;
import java.util.regex.Pattern;

/** Bounded cross-subsystem decision timeline. Never stores descriptions, commands or identities. */
public final class DecisionTrace {
    public static final DecisionTrace INSTANCE=new DecisionTrace();
    public enum Reason {NONE,UNCLASSIFIED,SINGLE_ALLOTMENT,RETAIL_BATCH,STACK_BATCH,ADAPTIVE_BATCH,
        NO_PRICES,STOCK_HELD,ORDER_EXISTS,SERVER_REFUSED,ITEM_FILTER,OWN_PRICE_LIST,HIVE_ALLOCATION,
        NO_MARGIN,NO_DEMAND,NO_CONFIDENCE,NO_SAMPLES,BUY_CAP,SPEND_CAP,LISTING_CAP,DEPTH_CAP,NO_STANDING_BID,
        OFFLINE_REQUIRED,IO_FAILURE,SALE_RECONCILIATION_TIMEOUT,LISTING_RECONCILIATION_TIMEOUT,PERSISTENCE_REFUSED,PERSISTENCE_WAIT,
        RECOVERED_PURCHASE_HOLD,EXPOSURE_HOLD,INVENTORY_CAPACITY,INVENTORY_SAFETY,DRIVER_BUSY,HELD_STOCK,REPRICING,
        FLIP_ALLOWANCE_EXHAUSTED,LISTING_CAPACITY,RETAIL_STOCK_HELD,PURCHASE_CAP,TOP_BID_CEILING,PRICE_SANITY,BUY_FOCUS,
        UNKNOWN_ITEM,SERVER_REFUSED_ITEM,WORLD_RECOVERY_HOLD,PROTECTIVE_HOLD,AMBIGUOUS_EVIDENCE,NOT_ARMED,PLAYER_PAUSE,
        POLICY_UNAVAILABLE,SESSION_CLOSED,NOT_PAUSED,NO_RESUMABLE_SESSION,RETAINED_STOCK_RECOVERY,PLAYER_LEFT_SERVER,
        COOLDOWN,AUTHORIZATION_HOLD,RESUMING,ABOVE_CEILING,UNCLEAR_PRICE,GHOST_ROW}
    public static Reason actionReason(String reason){try{return Reason.valueOf(reason);}catch(RuntimeException unknown){return Reason.UNCLASSIFIED;}}
    private record Entry(long sequence,long at,ActionTrace.Scope scope,ActionTrace.Event event,String line){}
    private final ArrayDeque<Entry> recent=new ArrayDeque<>();
    private final EnumMap<ActionTrace.Scope,Entry> latest=new EnumMap<>(ActionTrace.Scope.class),problems=new EnumMap<>(ActionTrace.Scope.class);
    private long sequence,evicted;
    private long settingsAt;
    private final Map<String,Double> settings=new HashMap<>();
    private static final Pattern LINE=Pattern.compile("dec v=1 at=(\\d{1,19}) scope=([A-Z_]+) event=([A-Z_]+) code=([A-Z][A-Z0-9_]{0,47}) reason=([A-Z_]+) market=(NONE|minecraft:[a-z0-9_]{1,64}) requested=(-?\\d{1,16}) selected=(-?\\d{1,16}) price=(-?\\d{1,16}) limit=(-?\\d{1,16})");
    private static final Set<String> CODES=loadCodes();
    private static Set<String> loadCodes(){
        try(var in=DecisionTrace.class.getResourceAsStream("/report-decision-contract.json")){
            var out=new HashSet<String>();for(var code:new com.fasterxml.jackson.databind.ObjectMapper().readTree(in).path("decisionCodes"))out.add(code.asText());return Set.copyOf(out);
        }catch(Exception invalid){return Set.of();}
    }
    public synchronized void record(ActionTrace.Scope scope,ActionTrace.Event event,Enum<?> code,Reason reason,String market,
                                    long requested,long selected,long price,long limit,long now) {
        if(code==null)return;
        recordToken(scope,event,code.name(),reason,market,requested,selected,price,limit,now);
    }
    private synchronized void recordToken(ActionTrace.Scope scope,ActionTrace.Event event,String code,Reason reason,String market,
                                    long requested,long selected,long price,long limit,long now) {
        if(scope==null||event==null||code==null||reason==null||now<=0||!CODES.contains(code))return;
        String item=market!=null&&market.matches("minecraft:[a-z0-9_]{1,64}")?market:"NONE";
        String line="dec v=1 at="+now+" scope="+scope+" event="+event+" code="+code+" reason="+reason+" market="+item
                +" requested="+number(requested)+" selected="+number(selected)+" price="+number(price)+" limit="+number(limit);
        Entry old=latest.get(scope);
        // Identical recurring observations keep their first/last timestamps without flooding the timeline.
        if(old!=null&&now>=old.at&&now-old.at<60_000&&shape(old.line).equals(shape(line)))return;
        Entry e=new Entry(++sequence,now,scope,event,line);latest.put(scope,e);recent.addLast(e);
        if(Set.of(ActionTrace.Event.REFUSED,ActionTrace.Event.FAULT,ActionTrace.Event.ABORTED,ActionTrace.Event.EMERGENCY_STOPPED).contains(event))problems.put(scope,e);
        while(recent.size()>2048){recent.removeFirst();evicted++;}
        org.slf4j.LoggerFactory.getLogger("DoughBay").info("GoNuts decision {}",line);
    }
    /** Numeric projection only. Changes are bounded observation intervals, never claimed as exact mutation times. */
    public synchronized void observeSettings(long now) {
        if(settingsAt>0&&now-settingsAt<1000)return;settingsAt=now;
        for(var setting:Tuning.SETTINGS) {
            String key=setting.key();if(!SettingsCode.REPORT_KEYS.contains(key))continue;
            double value=Tuning.get(key);Double before=settings.put(key,value);
            if(before!=null&&Double.compare(before,value)!=0)recordToken(ActionTrace.Scope.CONTROLLER,ActionTrace.Event.OBSERVATION,
                    "SET_"+key.toUpperCase(Locale.ROOT).replace('.','_'),Reason.NONE,"NONE",
                    Math.round(before*1000),Math.round(value*1000),1000,-1,now);
        }
    }
    private static long number(long n){return Math.max(-1,Math.min(1_000_000_000_000_000L,n));}
    private static String shape(String line){return line.substring(line.indexOf(" scope="));}
    public static boolean valid(String line) {
        if(line==null||line.length()>500)return false;var m=LINE.matcher(line);if(!m.matches())return false;
        try {
            if(Long.parseLong(m.group(1))<=0)return false;
            ActionTrace.Scope.valueOf(m.group(2));ActionTrace.Event.valueOf(m.group(3));Reason.valueOf(m.group(5));if(!CODES.contains(m.group(4)))return false;
            for(int n=7;n<=10;n++){long v=Long.parseLong(m.group(n));if(v< -1||v>1_000_000_000_000_000L)return false;}
            return true;
        }catch(IllegalArgumentException invalid){return false;}
    }
    public static String fromLog(String raw){int start=raw.indexOf("GoNuts decision ");if(start<0)return null;String line=raw.substring(start+16);return valid(line)?line:null;}
    /** Latest evidence and last failure from every subsystem survive a topic-specific burst. */
    public synchronized String report(long now,ReportTopic topic,int budget) {
        var selected=new LinkedHashMap<Long,Entry>();
        var baseline=new ArrayList<>(latest.values());baseline.addAll(problems.values());baseline.sort(Comparator.comparingLong(Entry::sequence).reversed());
        for(var e:baseline)selected.put(e.sequence,e);
        var tail=new ArrayList<>(recent);Collections.reverse(tail);
        // Reserve broad context first; topic may only prioritize the remaining tail.
        tail.sort(Comparator.comparingInt(e->topic.relevant(e.scope)?0:1));
        int used=selected.values().stream().mapToInt(e->e.line.length()+1).sum();
        for(var e:tail)if(!selected.containsKey(e.sequence)&&used+e.line.length()+1<=budget&&selected.size()<64){selected.put(e.sequence,e);used+=e.line.length()+1;}
        var ordered=new ArrayList<>(selected.values());ordered.sort(Comparator.comparingLong(Entry::sequence));
        while(used>budget&&!ordered.isEmpty()){var e=ordered.removeFirst();used-=e.line.length()+1;}
        long first=ordered.isEmpty()?0:ordered.getFirst().at,last=ordered.isEmpty()?0:ordered.getLast().at;
        String coverage="cov v=1 captured_at="+Math.max(1,now)+" first_at="+first+" last_at="+last+" retained="+ordered.size()+" omitted="+Math.max(0,sequence-ordered.size())+" evicted="+evicted;
        return coverage+"\n"+ordered.stream().map(e->e.line+"\n").collect(java.util.stream.Collectors.joining());
    }
    public static boolean validCoverage(String line){
        if(line==null||!line.matches("cov v=1 captured_at=\\d{1,19} first_at=\\d{1,19} last_at=\\d{1,19} retained=\\d{1,10} omitted=\\d{1,19} evicted=\\d{1,19}( history_omitted=\\d{1,10})?"))return false;
        try{for(String pair:line.substring(8).split(" ")){String[] p=pair.split("=");long n=Long.parseLong(p[1]);if(n<0||p[0].equals("captured_at")&&n==0||p[0].equals("retained")&&n>2048)return false;}return true;}
        catch(RuntimeException invalid){return false;}
    }
    public static Reason gate(String raw) {
        return switch(raw==null?"":raw){
            case "no prices" -> Reason.NO_PRICES;
            case "retail stock already held" -> Reason.STOCK_HELD;
            case "bid or reserve already out" -> Reason.ORDER_EXISTS;
            case "server refuses an order for it" -> Reason.SERVER_REFUSED;
            case "not allowed by Items" -> Reason.ITEM_FILTER;
            case "on your own price list","only your price list is traded" -> Reason.OWN_PRICE_LIST;
            case "the other account's market" -> Reason.HIVE_ALLOCATION;
            case "no margin at the going order price","retail batch margin too small" -> Reason.NO_MARGIN;
            case "over the buy cap","no whole stack fits the caps","one whole stack exceeds the buy cap" -> Reason.BUY_CAP;
            case "no room left" -> Reason.SPEND_CAP;
            case "market already at its listing cap","single slots still full" -> Reason.LISTING_CAP;
            case "thinner than one stack","full reserve batch waits for caps/depth" -> Reason.DEPTH_CAP;
            case "retail needs a standing bid" -> Reason.NO_STANDING_BID;
            default -> Reason.UNCLASSIFIED;
        };
    }
    public static final class Counts extends LinkedHashMap<String,Integer> {
        private String market="NONE";private long requested=-1;
        public void candidate(String item,long quantity){market=item;requested=quantity;}
        public void rejected(String reason){INSTANCE.record(ActionTrace.Scope.DESK,ActionTrace.Event.REFUSED,DecisionGate.of(reason),gate(reason),market,requested,0,-1,-1,System.currentTimeMillis());}
    }
    public enum Code {ORDER_PLAN,ORDER_REJECTED,PLAN_SUMMARY,WATCH_SELECTED,ORDER_DEPTH,ORDER_SALES,ORDER_MARKET_CAP,ORDER_BUDGET}
}
