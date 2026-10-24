package dev.doughbay.fabric;

/** Shared navigation for the game, local panel and hosted panel. */
public enum SettingsSection {
    AUCTION("Auction", "Find auction deals, list stock and manage selling prices."),
    ORDERS("Orders", "Place buy orders, choose batch sizes and collect filled stock."),
    ITEMS("Items & focus", "Choose what to watch and what may be bought. Focus restricts new purchases."),
    CAPITAL("Money & limits", "Set spending limits, listing capacity and allocation between trade sizes."),
    SAFETY("Safety & recovery", "Evasion, recovery and reconnect behavior. Existing safety gates still apply."),
    HIVE("Hive & sharing", "Coordinate accounts, share work and manage lending and consignment."),
    DISPLAY("Display & alerts", "Appearance, notifications, privacy and keyboard shortcuts."),
    GENERAL("Session & data", "Pacing, market data, startup, updates and support."),
    ALL("All settings", "Search every setting, including advanced options.");

    public final String label, description;
    SettingsSection(String label,String description){this.label=label;this.description=description;}
    public boolean includes(String key,String group){return this==ALL||of(key,group)==this;}
    /** Common controls come first; storage order and existing values are untouched. */
    public static int priority(String key){
        var first=java.util.List.of("orders.enabled","orders.bid","orders.bid_slots","orders.bid_stacks","orders.size_by_sale","orders.depth_share_pct","orders.bid_min_samples","orders.bid_on_real_trades","orders.collect","orders.collect_whole_stacks","orders.chase","orders.reserve_stock","orders.reserve_batches","orders.reserve_stacks","buy.focus","buy.focus_items","watch.tracked","items.allow","items.deny","buy.scale_with_bank","buy.purchase_cap_pct","buy.outstanding_cap_pct");
        int index=first.indexOf(key);return index<0?100:index;
    }
    public static String group(String key,String original){
        if(!original.equals("Orders"))return original.equals("Underdog")?"Rival orders":original;
        if(java.util.Set.of("orders.enabled","orders.scan_min","orders.full_sweep_min","orders.pages","orders.one_trip","upload.enabled").contains(key))return "Read the order book";
        if(java.util.Set.of("orders.bid","orders.bid_slots","orders.bid_min_room","quiet.bid_boost").contains(key))return "Place buy orders";
        if(key.startsWith("orders.reserve_")||java.util.Set.of("orders.bid_stacks","orders.fixed_batch","orders.depth_share_pct","orders.size_by_sale").contains(key))return "Order size & stock";
        if(key.startsWith("orders.chase")||key.equals("orders.nofill_min"))return "Re-check existing bids";
        if(key.startsWith("orders.collect")||java.util.Set.of("orders.deliver","orders.source","orders.max_targets").contains(key)||key.startsWith("inventory."))return "Fills & delivery";
        return "Order price & evidence";
    }
    public static java.util.List<String> groups(){
        var groups=new java.util.LinkedHashSet<String>();
        // Put the everyday order controls before the background scanning controls.
        groups.addAll(java.util.List.of("Place buy orders","Order size & stock","Order price & evidence","Fills & delivery","Re-check existing bids","Read the order book"));
        for(var setting:Tuning.SETTINGS)groups.add(group(setting.key(),setting.group()));
        return java.util.List.copyOf(groups);
    }
    public static SettingsSection of(String key,String group){
        if(key.startsWith("items.")||key.startsWith("watch.tracked")||key.equals("buy.focus")||key.equals("buy.focus_items")||key.startsWith("follow."))return ITEMS;
        if(key.startsWith("evasion.")||key.startsWith("recovery.")||key.startsWith("liveness.")||key.equals("session.auto_respawn_sec"))return SAFETY;
        if(key.startsWith("buy.scale_")||key.equals("buy.purchase_cap_pct")||key.equals("buy.outstanding_cap_pct")||key.startsWith("tier.")||key.startsWith("slots."))return CAPITAL;
        if(key.startsWith("hud.")||key.startsWith("quiet.hide_")||key.startsWith("quiet.vitals")||key.startsWith("camera.")||key.startsWith("privacy.")||key.startsWith("alerts.")||key.startsWith("key.")||key.startsWith("webhook."))return DISPLAY;
        if(group.equals("Multi client")||group.equals("Consignment")||key.startsWith("hive."))return HIVE;
        if (group.equals("Orders")||group.equals("Underdog"))return ORDERS;
        if (java.util.Set.of("Buying","Selling","Pull-backs","Boxes","Flips","Adaptive market","Rivals","Stash").contains(group)){
            if(key.startsWith("feed.")||key.startsWith("keyless."))return GENERAL;
            return AUCTION;
        }
        if(key.equals("prices.own")||key.equals("boxes.sweep_term"))return AUCTION;
        return GENERAL;
    }
}
