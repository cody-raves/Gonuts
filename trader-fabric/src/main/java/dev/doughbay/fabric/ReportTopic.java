package dev.doughbay.fabric;

import java.util.*;

/** Text selects a fixed evidence policy, never a log query, path, command or output string. */
public enum ReportTopic {
    GENERAL, FLIPS, AUCTION, RECOVERY, HIVE, DASHBOARD, EVASION, COMMUNITY;
    public static ReportTopic infer(String happened,String expected){return infer(Objects.requireNonNullElse(happened,"")+"\n"+Objects.requireNonNullElse(expected,""));}
    public static ReportTopic infer(String description) {
        String s=description==null?"":description.toLowerCase(Locale.ROOT);
        if(s.contains("flip")||s.contains("order")||s.contains("bid"))return FLIPS;
        if(s.contains("hive")||s.contains("combine")||s.contains("joining"))return HIVE;
        if(s.contains("dashboard")||s.contains("link client")||s.contains("web panel"))return DASHBOARD;
        if(s.contains("recover")||s.contains("persist")||s.contains("checkpoint")||s.contains("durable"))return RECOVERY;
        if(s.contains("evasion")||s.contains("escape")||s.contains("staff watch")||s.contains("teleport"))return EVASION;
        if(s.contains("swarm")||s.contains("community"))return COMMUNITY;
        if(s.contains("auction")||s.contains("purchase")||s.contains("sell")||s.contains("buy"))return AUCTION;
        return GENERAL;
    }
    public boolean relevant(ActionTrace.Scope scope) {
        if(this==GENERAL)return true;
        return switch(this) {
            case FLIPS -> Set.of(ActionTrace.Scope.FLIPS,ActionTrace.Scope.DESK,ActionTrace.Scope.DRIVER).contains(scope);
            case AUCTION -> scope==ActionTrace.Scope.DRIVER||scope==ActionTrace.Scope.MARKET;
            case RECOVERY -> scope==ActionTrace.Scope.RECOVERY||scope==ActionTrace.Scope.PERSISTENCE;
            case HIVE -> scope==ActionTrace.Scope.HIVE;
            case DASHBOARD -> scope==ActionTrace.Scope.DASHBOARD;
            case EVASION -> scope==ActionTrace.Scope.EVASION||scope==ActionTrace.Scope.RESTART;
            case COMMUNITY -> scope==ActionTrace.Scope.COMMUNITY||scope==ActionTrace.Scope.MARKET;
            default -> true;
        };
    }
    boolean relevant(String sanitized) {
        if(this==GENERAL)return true;
        for(var scope:ActionTrace.Scope.values())if(sanitized.contains("act scope="+scope+" "))return relevant(scope);
        if(this==FLIPS)return sanitized.contains("order ")||sanitized.contains("BID_DESK")||sanitized.contains(".Order")||sanitized.contains(".Flip");
        if(this==HIVE)return sanitized.contains(".Hive");
        if(this==DASHBOARD)return sanitized.contains(".hosted.Dashboard")||sanitized.contains(".hosted.HostedDashboardClient");
        if(this==RECOVERY)return sanitized.contains("recovery ")||sanitized.contains("PERSISTENCE")||sanitized.contains(".AutomationPersistence");
        if(this==EVASION)return sanitized.contains("evasion ")||sanitized.contains("restart ")||sanitized.contains(".Evasion")||sanitized.contains(".Restart");
        return false;
    }
}
