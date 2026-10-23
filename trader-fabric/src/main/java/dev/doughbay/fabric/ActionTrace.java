package dev.doughbay.fabric;

import java.util.*;

/** Process-local, bounded action evidence. No messages, names, paths, commands or identifiers. */
public final class ActionTrace {
    public enum Scope {CONTROLLER, DRIVER, DESK, FLIPS, RECOVERY, MARKET, HIVE, DASHBOARD, EVASION, RESTART, COMMUNITY, PERSISTENCE}
    public enum Event {STATE, PHASE, REQUEST, RESULT, REFUSED, FAULT, CONNECTED, DISCONNECTED, RETRY, OBSERVATION, SUCCEEDED, ABORTED, EMERGENCY_STOPPED}
    public enum Code {NONE, CREATE, JOIN, COMBINE, SOCKET, SOCKET_4401, SOCKET_4403, SOCKET_4409, SOCKET_1000, SOCKET_1006,
        API, KEYLESS, ESCAPE, RECOVERY_AUDIT, ITEM_TRACK, ITEM_UNTRACK, TOP_BID_INCREMENT, HTTP_OK, HTTP_401, HTTP_403, HTTP_429, HTTP_SERVER_ERROR, HTTP_OTHER}
    public static final ActionTrace INSTANCE = new ActionTrace();
    private static final Set<String> REASONS = Set.of("UNCLASSIFIED", "NONE", "OFFLINE_REQUIRED", "IO_FAILURE",
            "SALE_RECONCILIATION_TIMEOUT", "LISTING_RECONCILIATION_TIMEOUT", "PERSISTENCE_REFUSED", "PERSISTENCE_WAIT",
            "RECOVERED_PURCHASE_HOLD", "EXPOSURE_HOLD", "INVENTORY_CAPACITY", "INVENTORY_SAFETY", "DRIVER_BUSY",
            "HELD_STOCK", "REPRICING", "FLIP_ALLOWANCE_EXHAUSTED", "LISTING_CAPACITY", "RETAIL_STOCK_HELD",
            "PURCHASE_CAP", "TOP_BID_CEILING", "PRICE_SANITY", "BUY_FOCUS", "UNKNOWN_ITEM", "SERVER_REFUSED_ITEM",
            "WORLD_RECOVERY_HOLD", "PROTECTIVE_HOLD", "AMBIGUOUS_EVIDENCE", "NOT_ARMED", "PLAYER_PAUSE",
            "POLICY_UNAVAILABLE", "SESSION_CLOSED", "NOT_PAUSED", "NO_RESUMABLE_SESSION", "RETAINED_STOCK_RECOVERY",
            "PLAYER_LEFT_SERVER", "COOLDOWN", "AUTHORIZATION_HOLD", "RESUMING", "ABOVE_CEILING", "UNCLEAR_PRICE", "GHOST_ROW");
    private record Entry(long sequence, Scope scope, Event event, String code, String reason,
                         long first, long last, long count, long quantity, long price, long ceiling, String market) { }
    private final ArrayDeque<Entry> recent = new ArrayDeque<>();
    private final EnumMap<Scope, Entry> latest = new EnumMap<>(Scope.class);
    private final EnumMap<Scope, Entry> lastProblem = new EnumMap<>(Scope.class);
    private final EnumMap<Scope, Long> loggedAt = new EnumMap<>(Scope.class);
    private long sequence;
    static boolean knownReason(String reason){return REASONS.contains(reason);}

    public void record(Scope scope, Event event, Enum<?> code, String reason, long quantity, long price, long ceiling) {
        record(scope, event, code, reason, quantity, price, ceiling, System.currentTimeMillis());
    }
    synchronized void record(Scope scope, Event event, Enum<?> code, String reason, long quantity, long price, long ceiling, long now) {
        recordMarket(scope,event,code,reason,quantity,price,ceiling,"NONE",now);
    }
    public void recordMarket(Scope scope, Event event, Enum<?> code, String reason, long quantity, long price, long ceiling, String market) {
        recordMarket(scope,event,code,reason,quantity,price,ceiling,market,System.currentTimeMillis());
    }
    public void httpResult(int status) {
        Code code=switch(status){case 401->Code.HTTP_401;case 403->Code.HTTP_403;case 429->Code.HTTP_429;default->status>=500?Code.HTTP_SERVER_ERROR:status>=200&&status<300?Code.HTTP_OK:Code.HTTP_OTHER;};
        record(Scope.COMMUNITY,status>=200&&status<300?Event.SUCCEEDED:Event.REFUSED,code,"NONE",-1,-1,-1);
    }
    private synchronized void recordMarket(Scope scope, Event event, Enum<?> code, String reason, long quantity, long price, long ceiling, String market, long now) {
        if (scope == null || event == null || code == null || now <= 0) return;
        String token = code.name();
        if (!token.matches("[A-Z][A-Z0-9_]{0,47}")) return;
        String safeReason = REASONS.contains(reason == null ? "" : reason) ? reason : "UNCLASSIFIED";
        String safeMarket = market != null && market.matches("minecraft:[a-z0-9_]{1,64}") ? market : "NONE";
        Entry old = latest.get(scope);
        boolean same = old != null && old.event == event && old.code.equals(token) && old.reason.equals(safeReason)
                && old.quantity == quantity && old.price == price && old.ceiling == ceiling && old.market.equals(safeMarket);
        Entry next = new Entry(++sequence, scope, event, token, safeReason,
                same ? old.first : now, now, same ? Math.min(1_000_000_000L, old.count + 1) : 1,
                Math.max(-1, quantity), Math.max(-1, price), Math.max(-1, ceiling), safeMarket);
        if (!same) DecisionTrace.INSTANCE.record(scope,event,code,DecisionTrace.actionReason(safeReason),safeMarket,
                -1,quantity,price,ceiling,now);
        if (same) recent.remove(old);
        recent.addLast(next);
        while (recent.size() > 48) recent.removeFirst();
        latest.put(scope, next);
        if(event==Event.REFUSED||event==Event.FAULT||event==Event.ABORTED||event==Event.EMERGENCY_STOPPED)lastProblem.put(scope,next);
        // At most one safe line per second per subsystem. Full recent evidence stays in memory.
        long logged = loggedAt.getOrDefault(scope,0L);
        if (!same && now - logged >= 1000) {
            loggedAt.put(scope,now);
            org.slf4j.LoggerFactory.getLogger("DoughBay").info("GoNuts action {}", line(next,now).stripTrailing());
        }
    }

    /** Reserve evidence for each subsystem before filling the rest with recent events. */
    public synchronized String report(long now) {
        return report(now,ReportTopic.GENERAL);
    }
    public synchronized String report(long now,ReportTopic topic) {
        var selected = new LinkedHashMap<Long, String>();
        int bytes = 0;
        var candidates = new ArrayList<>(latest.values());
        candidates.sort(Comparator.comparingLong(Entry::sequence).reversed());
        var problems=new ArrayList<>(lastProblem.values());problems.sort(Comparator.comparingLong(Entry::sequence).reversed());candidates.addAll(problems);
        var tail = new ArrayList<>(recent); Collections.reverse(tail); candidates.addAll(tail);
        // Stable partition: the report's subsystem gets first use of the byte budget.
        candidates.sort(Comparator.comparingInt(e->topic.relevant(e.scope)?0:1));
        for (Entry e : candidates) {
            if (selected.containsKey(e.sequence)) continue;
            String line = line(e,now);
            if(topic!=ReportTopic.GENERAL&&topic.relevant(e.scope)&&bytes+line.length()>2000)continue;
            if (bytes + line.length() > 2990) continue;
            selected.put(e.sequence, line); bytes += line.length();
        }
        return "Action evidence v1 (newest per subsystem first; ages at capture; -1 unknown)\n" + String.join("", selected.values());
    }
    private static String line(Entry e,long now) {
        return "act scope=" + e.scope + " event=" + e.event + " code=" + e.code + " reason=" + e.reason
                + " age_ms=" + age(now, e.last) + " duration_ms=" + age(e.last, e.first) + " count=" + e.count
                + " quantity=" + e.quantity + " price=" + e.price + " ceiling=" + e.ceiling + " market=" + e.market + "\n";
    }
    /** Strict re-parser for persisted structured events; never copies arbitrary log messages. */
    static String sanitizedLogLine(String raw) {
        int start=raw.indexOf("GoNuts action act ");
        if(start<0)return null;
        String candidate=raw.substring(start+"GoNuts action ".length());
        var m=java.util.regex.Pattern.compile("act scope=([A-Z_]+) event=([A-Z_]+) code=([A-Z][A-Z0-9_]{0,47}) reason=([A-Z_]+) age_ms=(-?\\d{1,19}) duration_ms=(-?\\d{1,19}) count=(\\d{1,10}) quantity=(-?\\d{1,19}) price=(-?\\d{1,19}) ceiling=(-?\\d{1,19}) market=(NONE|minecraft:[a-z0-9_]{1,64})").matcher(candidate);
        if(!m.matches()||!REASONS.contains(m.group(4)))return null;
        try {
            Scope.valueOf(m.group(1));Event.valueOf(m.group(2));
            for(int n=5;n<=10;n++){long value=Long.parseLong(m.group(n));if(value< -1||n==7&&(value<1||value>1_000_000_000L))return null;}
            return candidate;
        }catch(IllegalArgumentException e){return null;}
    }
    private static long age(long now, long at) { return now < at ? -1 : now - at; }
}
