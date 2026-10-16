package dev.doughbay.fabric;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.doughbay.fabric.automation.AutomationSessionController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The web panel's own pages: Settings, Items and Rivals as the screen in game
 * has them, read and changed from a browser.
 *
 * <p>Everything here goes through the same doors the screen uses: a setting is
 * set with {@link Tuning}, an item is tracked or excluded with
 * {@link ItemLists}, so a change made from a phone is the change the screen
 * would have made, clamped to the same limits and saved to the same file. A
 * change is made on the game's own thread, as a click in game is.
 *
 * <p>One thing a browser is never given: the status webhook, which is an
 * address anyone holding it can post to. The page says whether one is set and
 * nothing more, and cannot set it.
 */
public final class WebPages {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger LOG = LoggerFactory.getLogger("doughbay-web");
    /** Settings a browser is told exist and no more. */
    static final Set<String> SECRET = Set.of("webhook.url");
    private static final int MAX_ROWS = 300;
    private static final int MAX_IDS = 2_000;
    private static final Map<String, byte[]> ICONS = new ConcurrentHashMap<>();
    private static volatile String catalog;

    private WebPages() {
    }

    /** One of the pages as JSON, or null when there is no page by that name. */
    public static String data(String name, Map<String, String> query) {
        return switch (name == null ? "" : name) {
            case "settings" -> settings();
            case "savedpresets" -> savedPresetsData();
            case "analyzer" -> analyzerData();
            case "evasion" -> evasion();
            case "code" -> answer(true, "").put("code", SettingsCode.export()).toString();
            case "items" -> items();
            case "prices" -> pricesData();
            case "rivals" -> ours(rivals(snapshot(), System.currentTimeMillis(), ZoneId.systemDefault()));
            case "staff" -> staff();
            case "reports" -> WebReports.data();
            case "proxies" -> dev.doughbay.fabric.proxy.ProxyAccess.settings().publicJson();
            case "rival" -> ours(rival(snapshot(), unhex(query == null ? null : query.get("id")), System.currentTimeMillis(),
                    ZoneId.systemDefault(), openListings()));
            default -> null;
        };
    }

    /** A change one of the pages asks for, or null when there is no such change. The answer says what was done. */
    public static String change(String name, Map<String, String> form) {
        if ("proxies".equals(name)) return dev.doughbay.fabric.proxy.ProxyAccess.settings().change(form);
        if ("reports".equals(name)) return WebReports.change(form); // Never log report text or previews.
        String done = switch (name == null ? "" : name) {
            case "setting" -> onClient(() -> setting(form));
            case "evasion" -> onClient(() -> changeEvasion(form));
            case "staff" -> onClient(() -> changeStaffResponse(form));
            case "regions" -> onClient(() -> changeRegion(form));
            case "list" -> onClient(() -> list(form));
            case "preset" -> onClient(() -> preset(form.get("name")));
            case "savedpresets" -> onClient(() -> savedPresetsChange(form));
            case "analyzer" -> onClient(() -> { SettingsAnalyzer.refresh(); return answer(true,"Analysis started; settings unchanged").toString(); });
            case "code" -> onClient(() -> importCode(form.get("code")));
            case "items" -> onClient(() -> itemsChange(form.get("ids"), form.get("do")));
            case "rival" -> onClient(() -> rivalMarkets(snapshot(), unhex(form.get("id")), form.get("do")));
            case "knownbots" -> onClient(WebPages::followKnownBots);
            default -> null;
        };
        if (done != null) LOG.info("DoughBay web panel: {} -> {}", name, note(done));
        return done;
    }

    /** An item's picture, or null when the game has none for it. */
    public static byte[] icon(String itemId) {
        if (itemId == null || itemId.isBlank()) return null;
        byte[] hit = ICONS.get(itemId);
        if (hit != null) return hit;
        byte[] png = dev.doughbay.fabric.discord.DiscordBridge.itemPng(itemId);
        if (png != null && ICONS.size() < 4_000) ICONS.put(itemId, png);
        return png;
    }

    // ---------------------------------------------------------------- settings

    static String staff() {
        long now = System.currentTimeMillis();
        StaffWatchReport.Snapshot snapshot = StaffWatchReport.snapshot();
        var root=staffData(snapshot,now,DoughBayClient.account(),ZoneId.systemDefault());
        var config=dev.doughbay.fabric.staffwatch.WatchConfig.get();
        root.put("regionSwitch",config.regionSwitch);
        root.put("escapeEnabled",config.enabled&&config.autoDisconnect);
        root.put("repeatWindowSeconds",120);
        return ours(root.toString());
    }
    private static String changeRegion(Map<String,String> form) {
        String destination=form.getOrDefault("destination","");
        if(!dev.doughbay.fabric.staffwatch.ManualRegionFlow.DESTINATIONS.contains(destination))
            return answer(false,"Choose a valid region").toString();
        String target=form.getOrDefault("account","");
        if(target.isBlank())return answer(false,"Choose one account to switch").toString();
        if(!target.equalsIgnoreCase(DoughBayClient.account())) {
            if(DoughBayClient.hiveAccounts().stream().noneMatch(target::equalsIgnoreCase))
                return answer(false,"Unknown hive account").toString();
            var control=DoughBayClient.hiveControl();
            boolean queued=control!=null && control.post(target,"REGION:"+destination,"Region Map");
            return answer(queued,queued?"Switch request queued for "+target+"; wait for its reply":"Could not queue region switch").toString();
        }
        boolean accepted=dev.doughbay.fabric.staffwatch.ManualRegionTransfer.request(destination);
        return answer(accepted,dev.doughbay.fabric.staffwatch.ManualRegionTransfer.status()).toString();
    }

    private static String changeStaffResponse(Map<String,String> form) {
        if (!"response".equals(form.get("action")) || !Set.of("switch","disconnect").contains(form.getOrDefault("mode","")))
            return answer(false,"Choose switch or disconnect").toString();
        var config=dev.doughbay.fabric.staffwatch.WatchConfig.get();
        boolean previous=config.regionSwitch;
        config.regionSwitch="switch".equals(form.get("mode"));
        try { config.save(); }
        catch(java.io.IOException e) { config.regionSwitch=previous; return answer(false,"Could not save Staff Watch response").toString(); }
        return answer(true,config.regionSwitch?"First alert switches region; same staff within two minutes disconnects":"Staff alerts disconnect immediately").toString();
    }
    static ObjectNode staffData(StaffWatchReport.Snapshot snapshot,long now,String observer,ZoneId zone) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("observer",observer);
        root.put("status", snapshot.status(now));
        root.put("observedAt", snapshot.at());
        root.put("fresh", snapshot.fresh(now));
        root.put("region", snapshot.region());
        root.put("watchedMillis",snapshot.watchedMillis());
        root.put("watchingObservers",snapshot.observers());
        root.put("timeZone",zone.getId());
        root.put("note", "Shared hive observations, retained for 30 days. No extra game requests. Durations exclude observation gaps and overlapping sightings are merged. Mapped areas are estimates, not verified shard identifiers. These are observed hours, not staff timezones or guaranteed availability. Absence does not prove offline.");
        ArrayNode rows = root.putArray("rows");
        var grouped = new java.util.LinkedHashMap<String, List<StaffWatchReport.Row>>();
        for (var row : snapshot.current(now))
            grouped.computeIfAbsent(row.name().toLowerCase(Locale.ROOT), ignored -> new ArrayList<>()).add(row);
        for (var sightings : grouped.values()) {
            var item = rows.addObject();
            item.put("name", dev.doughbay.fabric.staffwatch.util.NameFilter.apply(sightings.getFirst().name()));
            item.put("presence", staffEvidence(sightings, row -> row.presence().equals("VANISHED") ? "Hidden from TAB" : row.presence().equals("SPECTATOR") ? "Spectator observed" : "Online in player list"));
            item.put("region", staffEvidence(sightings, row -> row.nearby() ? "Nearby" : row.sameRegion() ? "Same-region signal" : "Region unconfirmed"));
            item.put("observer", sightings.stream().map(StaffWatchReport.Row::observer).distinct().collect(java.util.stream.Collectors.joining(", ")));
            item.put("scope", staffEvidence(sightings, row -> StaffWatchReport.scopeLabel(row.region())));
        }
        root.set("history", staffHistoryData(snapshot.history()));
        ArrayNode summaries=root.putArray("summaries");
        for(var summary:snapshot.summaries()) {
            var item=summaries.addObject();item.put("name",dev.doughbay.fabric.staffwatch.util.NameFilter.apply(summary.name()));
            item.put("intervals",summary.intervals());item.put("observedMillis",summary.observedMillis());item.put("firstSeen",summary.firstSeen());item.put("lastSeen",summary.lastSeen());
            item.put("usualHours",summary.usualHours());
        }
        return root;
    }

    /** Collapse near-simultaneous cross-observer evidence, never a repeated event from the same observer. */
    static ArrayNode staffHistoryData(List<StaffWatchReport.Sighting> sightings) {
        var groups = new ArrayList<List<StaffWatchReport.Sighting>>();
        var latest = new java.util.HashMap<String, List<StaffWatchReport.Sighting>>();
        for (var sighting : sightings.stream().sorted(java.util.Comparator.comparingLong(StaffWatchReport.Sighting::at).reversed()).toList()) {
            String name = sighting.name().toLowerCase(Locale.ROOT);
            var group = latest.get(name);
            if (group == null || group.getFirst().event() != sighting.event()
                    || group.getFirst().at() - sighting.at() > 10_000
                    || group.stream().anyMatch(other -> other.observer().equals(sighting.observer()))) {
                group = new ArrayList<>(); groups.add(group); latest.put(name, group);
            }
            group.add(sighting);
        }
        ArrayNode result = MAPPER.createArrayNode();
        for (var group : groups) {
            var first = group.getFirst(); var item = result.addObject();
            item.put("at", first.at());
            item.put("firstAt", group.getLast().at());
            item.put("name", dev.doughbay.fabric.staffwatch.util.NameFilter.apply(first.name()));
            item.put("event", StaffWatchReport.label(first.event()));
            item.put("observer", group.stream().map(StaffWatchReport.Sighting::observer).distinct().collect(java.util.stream.Collectors.joining(", ")));
            var observations = group.stream().map(row -> new StaffWatchReport.Row(row.name(), "", row.sameRegion(), false, row.observer(), row.region())).toList();
            item.put("region", staffEvidence(observations, row -> row.sameRegion() ? "Same-region signal" : "Region unconfirmed"));
            item.put("area", staffEvidence(observations, row -> StaffWatchReport.scopeLabel(row.region())));
        }
        return result;
    }

    /** Merge identical evidence; keep conflicting observations explicitly attributed. */
    private static String staffEvidence(List<StaffWatchReport.Row> rows, java.util.function.Function<StaffWatchReport.Row, String> value) {
        var values = rows.stream().map(value).distinct().toList();
        if (values.size() == 1) return values.getFirst();
        return rows.stream().map(row -> row.observer() + ": " + value.apply(row)).distinct()
            .collect(java.util.stream.Collectors.joining("; "));
    }

    static String settings() {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode groups = root.putArray("groups");
        for (String group : Tuning.groups()) {
            ObjectNode g = groups.addObject();
            g.put("name", group);
            ArrayNode rows = g.putArray("settings");
            for (Tuning.Setting s : Tuning.SETTINGS) {
                if (!s.group().equals(group)) continue;
                double v = Tuning.get(s.key());
                ObjectNode row = rows.addObject();
                row.put("key", s.key());
                row.put("label", s.label());
                row.put("description", s.description());
                row.put("value", v);
                row.put("shown", s.format(v));
                row.put("standard", s.format(s.defaultValue()));
                row.put("changed", !Tuning.isDefault(s.key()));
                row.put("min", s.min());
                row.put("max", s.max());
                row.put("least", s.format(s.min()));
                row.put("most", s.format(s.max()));
                row.put("step", step(s));
                row.put("toggle", s.kind() == Tuning.Kind.TOGGLE);
                row.put("unit", unit(s.kind()));
                row.put("local", Tuning.thisClientOnly(s.key()));
            }
        }
        ArrayNode lists = root.putArray("lists");
        for (Tuning.ListSetting l : Tuning.LISTS) {
            ObjectNode row = lists.addObject();
            String value = Tuning.text(l.key());
            row.put("key", l.key());
            row.put("label", l.label());
            row.put("description", l.description());
            row.put("local", Tuning.thisClientOnly(l.key()));
            if (SECRET.contains(l.key())) {
                row.put("locked", true);
                row.put("shown", value.isBlank() ? "not set" : "set");
                continue;
            }
            row.put("locked", false);
            row.put("value", value);
            int n = Tuning.itemSet(l.key()).size();
            row.put("shown", n == 0 ? (l.key().endsWith("allow") ? "everything" : "nothing") : n + ": " + value);
        }
        ArrayNode presets = root.putArray("presets");
        presets.add("Default");
        for (String name : Tuning.PRESETS.keySet()) presets.add(name);
        return root.toString();
    }

    /** How far one press of plus or minus moves a setting; the same steps the Discord panel takes. */
    static double step(Tuning.Setting st) {
        double span = st.max() - st.min();
        if (st.kind() == Tuning.Kind.PERCENT) return span >= 50 ? 5 : 1;
        if (span <= 20) return 1;
        if (span <= 200) return 5;
        return Math.max(1, Math.floor(span / 40));
    }

    private static String unit(Tuning.Kind kind) {
        return switch (kind) {
            case PERCENT -> "%";
            case MINUTES -> "min";
            case SECONDS -> "s";
            case BLOCKS -> "blocks";
            case RATE -> "/h";
            case FACTOR -> "x";
            case COUNT, TOGGLE -> "";
        };
    }

    private static Tuning.Setting known(String key) {
        if (key == null) return null;
        for (Tuning.Setting s : Tuning.SETTINGS) if (s.key().equals(key)) return s;
        return null;
    }

    /** What somebody typed for a setting, as the number it means; null when it is not one. */
    static Double typed(String text, boolean toggle) {
        if (text == null) return null;
        String t = text.strip().toLowerCase(Locale.ROOT);
        if (toggle && (t.equals("on") || t.equals("yes") || t.equals("true"))) return 1.0;
        if (toggle && (t.equals("off") || t.equals("no") || t.equals("false"))) return 0.0;
        t = t.replaceAll("[^0-9.\\-]", "");
        if (t.isEmpty()) return null;
        try {
            double v = Double.parseDouble(t);
            return Double.isFinite(v) ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String setting(Map<String, String> form) {
        Tuning.Setting s = known(form.get("key"));
        if (s == null) return answer(false, "There is no such setting.").toString();
        if (form.containsKey("reset")) {
            Tuning.reset(s.key());
            if (s.key().equals("evasion.enabled") && DoughBayClient.evasionGuard() != null)
                DoughBayClient.evasionGuard().setEnabled(Tuning.get(s.key()) >= .5);
            return answer(true, s.label() + " reset to " + s.format(Tuning.get(s.key()))).toString();
        }
        boolean toggle = s.kind() == Tuning.Kind.TOGGLE;
        Double asked = form.containsKey("toggle") && toggle
                ? Double.valueOf(Tuning.get(s.key()) >= 0.5 ? 0 : 1) : typed(form.get("value"), toggle);
        if (asked == null) {
            return answer(false, "Not a number: " + form.getOrDefault("value", "").strip()).toString();
        }
        double applied = Tuning.set(s.key(), asked);
        if (s.key().equals("evasion.enabled") && DoughBayClient.evasionGuard() != null)
            DoughBayClient.evasionGuard().setEnabled(applied >= .5);
        String note = s.label() + " set to " + s.format(applied);
        if (Math.abs(applied - asked) > 1e-9) {
            note += ", the " + (asked > applied ? "most" : "least") + " it can be";
        }
        return answer(true, note).toString();
    }

    static String evasion() {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(settings());
            ArrayNode groups = MAPPER.createArrayNode();
            for (var group : root.path("groups")) if (group.path("name").asText().equals("Evasion")) groups.add(group);
            root.set("groups", groups);
            root.remove(List.of("lists", "presets"));
            var guard=DoughBayClient.evasionGuard();
            root.put("enabled", guard != null && guard.enabled());
            root.put("guardStatus", guard == null ? "off" : guard.status());
            root.put("pauseRing", (int)Tuning.get("evasion.radius_blocks"));
            root.put("escapeRing", (int)Tuning.get("evasion.disconnect_blocks"));
            int mode=(int)Tuning.get("evasion.escape");
            root.put("onContact",mode==0?"pause only":mode==1?"teleport away":"leave the server");
            root.set("history",EvasionHistory.read(EvasionGuard.ledgerPath(),System.currentTimeMillis()));
            root.put("useHomes", Tuning.get("evasion.use_homes") >= .5);
            var names = root.putArray("homes"); SafeHomes.homes().forEach(names::add);
            return ours(root.toString());
        } catch (java.io.IOException e) { return answer(false, "Evasion settings are unavailable").toString(); }
    }

    static String changeEvasion(Map<String, String> form) {
        String action = form.getOrDefault("action", "");
        if (action.equals("homes")) {
            try { SafeHomes.saveHomes(form.getOrDefault("names", "")); }
            catch (IllegalArgumentException e) { return answer(false, e.getMessage()).toString(); }
            catch (java.io.IOException e) { return answer(false, "Could not save home names; existing file kept").toString(); }
            return answer(true, "Saved " + SafeHomes.homes().size() + " home(s); used in entered order").toString();
        }
        if (action.equals("route")) {
            String route = form.getOrDefault("route", "");
            if (!route.equals("home") && !route.equals("rtp")) return answer(false, "Choose /home or /rtp").toString();
            Tuning.set("evasion.use_homes", route.equals("home") ? 1 : 0);
            return answer(true, "Teleport route saved").toString();
        }
        if (action.equals("guard")) {
            var guard = DoughBayClient.evasionGuard();
            if (guard == null) return answer(false, "Evasion guard is not ready").toString();
            boolean enabled = !guard.enabled();
            Tuning.set("evasion.enabled", enabled ? 1 : 0); guard.setEnabled(enabled);
            return answer(true, enabled ? "Evasion enabled" : "Evasion disabled").toString();
        }
        return answer(false, "Unknown evasion action").toString();
    }

    static String list(Map<String, String> form) {
        String key = form.getOrDefault("key", "");
        Tuning.ListSetting found = null;
        for (Tuning.ListSetting l : Tuning.LISTS) if (l.key().equals(key)) found = l;
        if (found == null) return answer(false, "There is no such list.").toString();
        if (SECRET.contains(key)) {
            return answer(false, found.label() + " is set on the GoNuts screen in game, not from here.").toString();
        }
        String text = form.getOrDefault("text", "").replaceAll("[\\r\\n\\t]+", " ").strip();
        if (text.length() > 8_000) return answer(false, "That is too long to save.").toString();
        Tuning.setText(key, text);
        if (key.equals(OwnPrices.KEY) && !text.isEmpty()) {
            // Said back at once: a price list with a slip in it should not look saved and working.
            OwnPrices.Parsed read = OwnPrices.current();
            String note = read.entries().size() + " item(s) on your price list";
            if (!read.problems().isEmpty()) {
                return answer(false, note + ". Not understood: " + String.join("; ", read.problems())).toString();
            }
            return answer(true, note).toString();
        }
        return answer(true, found.label() + (text.isEmpty() ? " cleared" : " saved")).toString();
    }

    private static String savedPresetsData() {
        var out=answer(true, "");
        try { out.set("presets",MAPPER.valueToTree(SavedPresets.current().entries())); }
        catch(Exception e){ return answer(false,"Saved presets could not be read; existing file kept").toString(); }
        return out.toString();
    }
    private static String analyzerData() {
        var out=answer(true,SettingsAnalyzer.status());
        out.set("report",MAPPER.valueToTree(SettingsAnalyzer.report()));return out.toString();
    }
    private static String savedPresetsChange(Map<String,String> form) {
        try {
            var store=SavedPresets.current(); String id=form.getOrDefault("id",""),action=form.getOrDefault("action","");
            switch(action){
                case "save", "replace" -> {
                    String code;
                    if("proposal".equals(form.get("source"))){var report=SettingsAnalyzer.report();if(report==null||(report.numbers().isEmpty()&&report.lists().isEmpty()))return answer(false,"No supported suggested settings to save").toString();code=report.proposedCode();}
                    else code=SettingsCode.exportPreset(Map.of(),Map.of());
                    store.save(action.equals("save")?"":id,form.get("name"),code);
                }
                case "rename" -> {var old=store.get(id);store.save(id,form.get("name"),old.code());}
                case "delete" -> store.delete(id);
                case "apply" -> {var result=SettingsCode.apply(store.get(id).code());if(!result.ok())return answer(false,"Preset was not applied: "+result.error()).toString();}
                default -> {return answer(false,"Unknown preset action").toString();}
            }
            return answer(true,action.equals("apply")?"Preset applied; saved copy kept":"Saved presets updated; current settings unchanged").toString();
        }catch(Exception e){return answer(false,e.getMessage()==null?"Could not update presets":e.getMessage()).toString();}
    }

    static String preset(String name) {
        String match = null;
        if ("Default".equalsIgnoreCase(name)) match = "Default";
        for (String p : Tuning.PRESETS.keySet()) if (p.equalsIgnoreCase(name)) match = p;
        if (match == null) return answer(false, "There is no such preset.").toString();
        int changed = Tuning.applyPreset(match);
        return answer(true, "Preset " + match + " applied: " + changed + " setting(s) changed").toString();
    }

    static String importCode(String code) {
        SettingsCode.Result r = SettingsCode.apply(code == null ? "" : code.strip());
        if (!r.ok()) return answer(false, "That code was not applied: " + r.error() + ".").toString();
        return answer(true, r.applied() + " setting(s) applied from the code"
                + (r.kept() > 0 ? "; your slot counts and follow settings are left as they were" : "")
                + (r.skipped() > 0 ? "; " + r.skipped() + " this version does not know were skipped" : "")).toString();
    }

    // ------------------------------------------------------------- your prices

    private static List<AutomationSessionController.OwnPriceView> ownPriceViews() {
        try {
            return DoughBayClient.automationSessionController().ownPriceViews();
        } catch (Throwable e) {
            return List.of();
        }
    }

    /**
     * The player's own price list as a table: what each entry has bought and
     * for how much, what it holds, and what that is worth at the price they
     * set and at the price the market pays now.
     */
    private static String pricesData() {
        try {
            ObjectNode root=(ObjectNode)MAPPER.readTree(prices(ownPriceViews(),OwnPrices.current().problems(),Tuning.text(OwnPrices.KEY)));
            root.put("only",FlipsMode.only());root.put("modeDescription",FlipsMode.description());return root.toString();
        } catch(java.io.IOException e) { return answer(false,"Could not read Flips settings").toString(); }
    }

    static String prices(List<AutomationSessionController.OwnPriceView> views, List<String> problems, String text) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("text", text == null ? "" : text);
        ArrayNode notUnderstood = root.putArray("problems");
        for (String p : problems) notUnderstood.add(p);
        ArrayNode rows = root.putArray("rows");
        for (AutomationSessionController.OwnPriceView v : views) {
            OwnPrices.Entry e = v.entry();
            ObjectNode row = rows.addObject();
            row.put("icon", "minecraft:" + e.itemId());
            row.put("name", item(e.itemId()));
            row.put("buy", (e.top() ? "top" + (e.over() > 0 ? "+" + Figures.grouped(e.over()) : "") + ", at most " : "")
                    + Figures.money(e.buy()));
            row.put("sell", Figures.money(e.sell()) + (e.singles() ? ", one at a time" : ""));
            row.put("order", v.onOrder() > 0
                    ? Figures.grouped(v.onOrder()) + " at " + Figures.money(v.orderUnit()) : "none up");
            row.put("bought", Figures.grouped(v.bought())
                    + (e.total() > 0 ? " of " + Figures.grouped(e.total()) : ""));
            row.put("spent", Figures.money(v.spent())
                    + (e.budget() > 0 ? " of " + Figures.money(e.budget()) : ""));
            double each = v.bought() > 0 ? v.spent() / (double) v.bought() : 0;
            row.put("each", v.bought() > 0 ? Figures.money(each) : "-");
            int held = v.listedUnits() + v.inHandUnits();
            row.put("held", held > 0 ? Figures.grouped(held) + " (" + v.listings() + " listed)" : "none");
            // What is held, against what it cost: the average of what was bought, or the buy price before anything was.
            double cost = held * (each > 0 ? each : e.buy());
            long atYours = (long) held * e.sell();
            row.put("yours", held > 0 ? Figures.money(atYours) : "-");
            row.put("profit", held > 0 ? Figures.signedMoney(Math.round(atYours - cost)) : "-");
            row.put("profitTone", atYours - cost >= 0 ? "good" : "bad");
            row.put("market", v.marketUnit() > 0 ? Figures.money(v.marketUnit()) + " each" : "not known");
            // The player's price against the market's: above it the listing waits, at or under it it sells.
            row.put("marketTone", v.marketUnit() <= 0 ? "muted" : v.marketUnit() >= e.sell() ? "good" : "warn");
        }
        return root.toString();
    }

    // ------------------------------------------------------------------- items

    /** Every item with its name and group, then the three lists that colour the grid. */
    static String items() {
        String all = catalog;
        if (all == null) {
            List<String[]> rows = onClient(WebPages::catalogRows, List.of());
            ArrayNode array = MAPPER.createArrayNode();
            for (String[] row : rows) {
                ArrayNode one = array.addArray();
                for (String cell : row) one.add(cell);
            }
            all = array.toString();
            if (!rows.isEmpty()) catalog = all;
        }
        ObjectNode lists = lists(MAPPER.createObjectNode());
        String tail = lists.toString();
        return "{\"items\":" + all + "," + tail.substring(1);
    }

    private static List<String[]> catalogRows() {
        try {
            return DoughBayScreen.itemCatalog();
        } catch (Throwable e) {
            return List.of();   // not running inside the game
        }
    }

    private static ObjectNode lists(ObjectNode into) {
        add(into.putArray("allow"), Tuning.itemSet("items.allow"));
        add(into.putArray("deny"), Tuning.itemSet("items.deny"));
        add(into.putArray("tracked"), ItemLists.tracked());
        return into;
    }

    private static void add(ArrayNode array, Collection<String> values) {
        for (String v : new java.util.TreeSet<>(values)) array.add(v);
    }

    static String itemsChange(String ids, String what) {
        LinkedHashSet<String> picked = new LinkedHashSet<>();
        for (String raw : (ids == null ? "" : ids).split(",")) {
            String id = Tuning.normalizeItem(raw);
            if (id.matches("[a-z0-9_.\\-]{1,64}")) picked.add(id);
            if (picked.size() >= MAX_IDS) break;
        }
        if (picked.isEmpty()) return answer(false, "Tick some items first.").toString();
        String did;
        switch (what == null ? "" : what) {
            case "exclude" -> {
                ItemLists.exclude(picked);
                did = "Excluded";
            }
            case "include" -> {
                ItemLists.include(picked);
                did = "Included";
            }
            case "clear" -> {
                ItemLists.clear(picked);
                did = "Cleared from both lists:";
            }
            case "track" -> {
                ItemLists.track(picked, true);
                did = "Tracking";
            }
            case "untrack" -> {
                ItemLists.track(picked, false);
                did = "No longer tracking";
            }
            default -> {
                return answer(false, "There is no such action.").toString();
            }
        }
        return lists(answer(true, did + " " + picked.size() + " item(s)")).toString();
    }

    // ------------------------------------------------------------------ rivals

    private static RivalIntel.Snapshot snapshot() {
        try {
            RivalIntel intel = DoughBayClient.rivalIntel();
            return intel == null ? RivalIntel.Snapshot.empty("Rival intel is not running") : intel.snapshot();
        } catch (Throwable e) {
            return RivalIntel.Snapshot.empty("Rival intel is not running");
        }
    }

    private static List<AutomationSessionController.OpenListingView> openListings() {
        try {
            return DoughBayClient.automationSessionController().openListingViews();
        } catch (Throwable e) {
            return List.of();
        }
    }

    /** Our own names taken out, when the player has asked for them to be hidden. */
    private static String ours(String json) {
        try {
            return dev.doughbay.fabric.discord.DiscordPrivacy.payload(json);
        } catch (Throwable e) {
            return json;
        }
    }

    /**
     * A rival is asked for by its name written in hex: a name can be hidden on
     * its way to the page, and the page still has to be able to ask for it.
     */
    static String hex(String name) {
        return HexFormat.of().formatHex(name.getBytes(StandardCharsets.UTF_8));
    }

    static String unhex(String id) {
        if (id == null || id.isEmpty() || id.length() > 128) return "";
        try {
            return new String(HexFormat.of().parseHex(id), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static String item(String itemId) {
        return MarketQueryClient.displayName(itemId);
    }

    private static ObjectNode cell(ObjectNode into, String text, String tone) {
        into.put("t", text);
        into.put("tone", tone);
        return into;
    }

    static String rivals(RivalIntel.Snapshot s, long now, ZoneId zone) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("fromFollows", s.fromFollows());
        root.put("title", s.fromFollows() ? "the players you follow, last 24 h"
                : "flippers seen in the sales feed, last 24 h");
        root.put("stamp", s.refreshedAt() > 0 ? "updated " + Figures.relativeAge(now - s.refreshedAt()) : s.status());
        root.put("nowHour", Instant.ofEpochMilli(now).atZone(zone).getHour());
        root.put("knownBots", Follows.knownBots().size());
        root.put("followStatus", s.status());

        int repeat = 0;
        for (RivalIntel.Buyer b : s.buyers()) if (b.purchases() >= 2) repeat++;
        ArrayNode cards = root.putArray("cards");
        card(cards, "Auto-traders (est.)",
                s.traders() > 0 ? String.format(Locale.ROOT, "%.0f%%", s.botSharePercent()) : "n/a",
                s.likelyBots() + " of " + s.traders() + " sellers with 20+ sales look automated", "text");
        card(cards, "Rivals active", s.activeNow() + " of " + s.rivals().size(),
                s.quietField() ? "field is quiet: bot looks faster, wider small pool"
                        : "sold or listed in the last 10 min", s.quietField() ? "good" : "text");
        card(cards, "Crowded markets", Figures.grouped(s.crowded().size()),
                "a rival is working these right now", s.crowded().isEmpty() ? "text" : "warn");
        card(cards, "Rival-proven markets", Figures.grouped(s.proven().size()),
                "3+ reconstructed rival flips today", "text");
        card(cards, "Repeat buyers", Figures.grouped(repeat), "bought from you more than once", "text");

        ArrayNode heads = root.putArray("heads");
        for (String h : s.fromFollows()
                ? new String[] {"Name", "Listed", "Items", "Shared", "Asked", "Seen", "Status", "Bot", "24h"}
                : new String[] {"Name", "Sales", "Items", "Shared", "Revenue", "Seen", "Status", "Bot", "24h"}) {
            heads.add(h);
        }
        ArrayNode rows = root.putArray("rivals");
        for (RivalIntel.Rival r : s.rivals()) {
            if (rows.size() >= MAX_ROWS) break;
            ObjectNode row = rows.addObject();
            row.put("id", hex(r.name()));
            row.put("name", r.name());
            row.put("sales", Figures.grouped(r.sales()));
            row.put("items", Figures.grouped(r.items()));
            row.put("shared", r.overlap());
            row.put("revenue", Figures.money(r.revenue()));
            row.put("seen", r.lastSeenAt() > 0 ? Figures.relativeAge(now - r.lastSeenAt()) : "never");
            row.put("status", r.status(now));
            row.put("bot", r.botScore());
            ArrayNode hours = row.putArray("hourly");
            if (r.hourly() != null) for (int v : r.hourly()) hours.add(v);
        }
        root.put("more", Math.max(0, s.rivals().size() - rows.size()));

        boolean on = Tuning.get("underdog.enabled") >= 0.5;
        boolean asksTraded = Tuning.get("underdog.from_follows") >= 0.5;
        double undercut = Tuning.get("underdog.undercut_pct") / 100.0;
        ObjectNode underdog = root.putObject("underdog");
        underdog.put("title", "Underdog  ·  " + (on ? "on" : "off, enable in Settings")
                + "  ·  " + s.shadows().size() + " shadowed market(s)");
        underdog.put("empty", "No rival market has enough reconstructed flips and margin yet.");
        List<RivalIntel.ShadowMarket> shown = new ArrayList<>(s.shadows());
        // Markets read from followed rivals' asking prices are listed even
        // while the underdog is not trading on them, and say which they are.
        if (!asksTraded) shown.addAll(s.askShadows());
        ArrayNode shadows = underdog.putArray("rows");
        for (RivalIntel.ShadowMarket m : shown) {
            if (shadows.size() >= 60) break;
            boolean traded = on && (!m.fromAsks() || asksTraded);
            long list = (long) Math.floor(m.medianSale() * (1.0 - undercut));
            long real = s.realPrice(m.itemId(), m.count());
            ObjectNode row = shadows.addObject();
            row.put("icon", m.itemId());
            row.put("name", item(m.itemId()) + " x" + m.count());
            row.put("value", Figures.money(m.medianBuy()) + " → " + Figures.money(list) + "  ("
                    + (m.fromAsks() ? Figures.askOverOrder(m.medianBuy(), m.medianSale())
                    : String.format(Locale.ROOT, "%.0f%%", m.marginPercent())) + ")");
            row.put("tone", traded ? "good" : "secondary");
            row.put("note", m.rival() + "  ·  " + (m.fromAsks() ? m.flips() + " listing(s)" : m.flips() + " flip(s)")
                    + (m.fromAsks() && real > 0 ? "  ·  seen sold at " + Figures.money(real) : "")
                    + (m.fromAsks() ? "" : "  ·  they sell at " + Figures.money(m.medianSale()))
                    + (m.fromAsks() && !asksTraded ? "  ·  shown only" : "")
                    + (m.medianTurnaroundMillis() > 0
                    ? "  ·  " + Figures.relativeAge(m.medianTurnaroundMillis()).replace(" ago", "") + " turn" : ""));
        }

        ObjectNode buyers = root.putObject("buyers");
        buyers.put("empty", "No buyers recorded yet. Every sale line from now on keeps the buyer's name.");
        ArrayNode buyerRows = buyers.putArray("rows");
        for (RivalIntel.Buyer b : s.buyers()) {
            if (buyerRows.size() >= 60) break;
            ObjectNode row = buyerRows.addObject();
            row.put("name", b.name());
            row.put("value", b.purchases() + "x  ·  " + Figures.money(b.spent()));
            row.put("tone", b.purchases() >= 2 ? "good" : "secondary");
            row.put("note", "last " + Figures.relativeAge(now - b.lastAt()) + "  ·  " + item(b.lastItem()));
        }
        return root.toString();
    }

    private static void card(ArrayNode cards, String label, String value, String note, String tone) {
        ObjectNode c = cards.addObject();
        c.put("label", label);
        c.put("value", value);
        c.put("note", note);
        c.put("tone", tone);
    }

    private static RivalIntel.Rival find(RivalIntel.Snapshot s, String name) {
        if (name == null || name.isEmpty()) return null;
        for (RivalIntel.Rival r : s.rivals()) if (r.name().equals(name)) return r;
        return null;
    }

    private static void pair(ArrayNode pairs, String key, String value, String tone) {
        ObjectNode p = pairs.addObject();
        p.put("k", key);
        p.put("v", value);
        p.put("tone", tone);
    }

    /** One rival opened up: the figures, the hours they work, and their trades, as the screen in game lays them out. */
    static String rival(RivalIntel.Snapshot s, String name, long now, ZoneId zone,
                        List<AutomationSessionController.OpenListingView> mine) {
        RivalIntel.Rival r = find(s, name);
        if (r == null) return answer(false, "That player is no longer on the list.").toString();
        boolean follows = s.fromFollows();
        ObjectNode root = answer(true, "");
        root.put("id", hex(r.name()));
        root.put("name", r.name());
        root.put("title", r.name() + "  ·  " + r.status(now) + "  ·  seen "
                + (r.lastSeenAt() > 0 ? Figures.relativeAge(now - r.lastSeenAt()) : "never"));
        ArrayNode pairs = root.putArray("pairs");
        pair(pairs, follows ? "Listings today" : "Sales today",
                Figures.grouped(r.sales()) + " across " + r.items() + " items", "text");
        pair(pairs, follows ? "Asked for them" : "Revenue today", Figures.money(r.revenue()), "text");
        pair(pairs, follows ? "Asked over paid" : "Est. profit today",
                Figures.money(r.estimatedProfit()) + (follows ? " if it all sells"
                        : r.profitFromFlips() ? "" : " (assumed 20%)"), "good");
        pair(pairs, "Bot likelihood", r.botScore() + "%  ·  " + r.activeHours() + "/24 h active",
                r.botScore() >= 60 ? "warn" : "secondary");
        pair(pairs, "Markets shared with you", Figures.grouped(r.overlap()), r.overlap() > 0 ? "good" : "muted");
        StringBuilder tops = new StringBuilder();
        for (String top : r.topItems()) {
            if (tops.length() > 0) tops.append(", ");
            tops.append(item(top));
        }
        pair(pairs, "Top items", tops.length() == 0 ? "unknown" : tops.toString(), "secondary");
        pair(pairs, follows ? "Orders matched to listings" : "Reconstructed flips",
                Figures.grouped(r.flips().size()), "text");
        if (follows) {
            // An ask over an order is two wishes, not a profit; it is shown as
            // how many times the order price they ask, the middle one of them.
            pair(pairs, "Typical ask over order", r.flips().isEmpty() ? "unknown"
                    : String.format(Locale.ROOT, "x%.0f", 1 + r.averageMarginPercent() / 100.0), "secondary");
        } else {
            pair(pairs, "Average margin", r.flips().isEmpty() ? "unknown"
                            : String.format(Locale.ROOT, "%.1f%%", r.averageMarginPercent()),
                    r.averageMarginPercent() >= 0 ? "good" : "bad");
        }
        pair(pairs, "Median turnaround", r.medianTurnaroundMillis() > 0
                ? Figures.relativeAge(r.medianTurnaroundMillis()).replace(" ago", "") : "unknown", "text");

        root.put("strip", follows ? "Activity by hour  ·  listings and orders per hour of day, gold = now"
                : "Activity by hour  ·  sales per hour of day, gold = now");
        root.put("nowHour", Instant.ofEpochMilli(now).atZone(zone).getHour());
        ArrayNode hours = root.putArray("hourly");
        if (r.hourly() != null) for (int v : r.hourly()) hours.add(v);

        // Lessons: their listing price against your target on the same market.
        ArrayNode lessons = root.putArray("lessons");
        for (AutomationSessionController.OpenListingView own : mine) {
            if (lessons.size() >= 4) break;
            String mineId = own.itemKey().indexOf('#') >= 0
                    ? own.itemKey().substring(0, own.itemKey().indexOf('#')) : own.itemKey();
            long sum = 0;
            int n = 0;
            long turn = 0;
            int turns = 0;
            for (RivalIntel.Flip f : r.flips()) {
                if (!f.itemKey().equals(mineId) || f.count() != own.quantity()) continue;
                sum += f.listPrice();
                n++;
                if (f.soldAt() > 0 && f.boughtAt() > 0) {
                    turn += f.soldAt() - f.boughtAt();
                    turns++;
                }
            }
            if (n == 0) continue;
            long theirs = sum / n;
            cell(lessons.addObject(), item(mineId) + " x" + own.quantity() + ": they list at "
                    + Figures.money(theirs) + ", you at " + Figures.money(own.targetPrice())
                    + (turns > 0 ? "; they turn in " + Figures.relativeAge(turn / turns).replace(" ago", "") : ""),
                    theirs > own.targetPrice() ? "good" : "secondary");
        }
        if (lessons.isEmpty()) {
            cell(lessons.addObject(),
                    "No overlap between their reconstructed trades and your open listings right now.", "muted");
        }

        ArrayNode heads = root.putArray("heads");
        for (String h : follows
                ? new String[] {"Item", "Orders at (ea)", "Asks (ea)", "Seen sold (ea)", "Ask / order", "Listed"}
                : new String[] {"Item", "Bought", "Listed", "Sold", "Margin", "Turnaround"}) {
            heads.add(h);
        }
        root.put("empty", follows ? "No order and listing of the same item seen from this player yet."
                : "No trades reconstructed yet. A flip is a sale of the same stack by someone else"
                + " shortly before this seller listed it higher.");
        ArrayNode flips = root.putArray("flips");
        for (RivalIntel.Flip f : r.flips()) {
            if (flips.size() >= MAX_ROWS) break;
            ObjectNode row = flips.addObject();
            row.put("icon", f.itemKey());
            ArrayNode cells = row.putArray("cells");
            cell(cells.addObject(), item(f.itemKey()) + " x" + f.count(), "text");
            if (follows) {
                // Per item, so a lot of 16 and a lot of 64 read against each
                // other, and no percentage: neither price is a trade.
                int each = Math.max(1, f.count());
                long real = s.realPrice(f.itemKey(), f.count());
                cell(cells.addObject(), Figures.money(f.buyPrice() / each), "secondary");
                cell(cells.addObject(), Figures.money(f.listPrice() / each), "secondary");
                cell(cells.addObject(), real > 0 ? Figures.money(real / each) : "-", real > 0 ? "good" : "muted");
                cell(cells.addObject(), Figures.askOverOrder(f.buyPrice(), f.listPrice()), "secondary");
                cell(cells.addObject(), f.listedAt() > 0 ? Figures.relativeAge(now - f.listedAt()) : "-", "muted");
                continue;
            }
            cell(cells.addObject(), Figures.money(f.buyPrice()), "secondary");
            cell(cells.addObject(), Figures.money(f.listPrice()), "secondary");
            cell(cells.addObject(), f.soldPrice() > 0 ? Figures.money(f.soldPrice()) : "still up",
                    f.soldPrice() > 0 ? "text" : "muted");
            cell(cells.addObject(), Figures.signedMoney(f.margin())
                    + String.format(Locale.ROOT, " (%.0f%%)", f.marginPercent()), f.margin() >= 0 ? "good" : "bad");
            cell(cells.addObject(), f.soldAt() > 0 && f.boughtAt() > 0
                    ? Figures.relativeAge(f.soldAt() - f.boughtAt()).replace(" ago", "") : "-", "secondary");
        }
        return root.toString();
    }

    /** The markets a rival has been seen in, tracked or let through the trade filter, as the buttons in game do. */
    static String rivalMarkets(RivalIntel.Snapshot s, String name, String what) {
        RivalIntel.Rival r = find(s, name);
        if (r == null) return answer(false, "That player is no longer on the list.").toString();
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (RivalIntel.Flip f : r.flips()) {
            String bare = Tuning.normalizeItem(f.itemKey());
            if (!bare.isEmpty()) ids.add(bare);
        }
        if (ids.isEmpty()) return answer(false, "No markets seen from them yet.").toString();
        if ("track".equals(what)) {
            ItemLists.track(ids, true);
            return answer(true, "Tracking " + ids.size() + " of their market(s)").toString();
        }
        if ("include".equals(what)) {
            ItemLists.include(ids);
            return answer(true, ids.size() + " of their market(s) added to the items the bot may trade").toString();
        }
        return answer(false, "There is no such action.").toString();
    }

    static String followKnownBots() {
        Follows follows;
        try {
            follows = DoughBayClient.follows();
        } catch (Throwable e) {
            follows = null;
        }
        if (follows == null) return answer(false, "Following is not running yet. Join the server first.").toString();
        try {
            int added = follows.followKnownBots();
            return answer(true, "Known bots queued (" + added + " new list entries). " + follows.status()
                    + ". Close in-game menus to allow commands; this is not a server confirmation.").toString();
        } catch (java.io.IOException e) {
            LOG.warn("DoughBay follows: the known bots could not be added: {}", e.toString());
            return answer(false, "Could not write the follow list.").toString();
        }
    }

    // ------------------------------------------------------------------ shared

    private static ObjectNode answer(boolean ok, String note) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("ok", ok);
        root.put("note", note);
        return root;
    }

    private static String note(String json) {
        try {
            return MAPPER.readTree(json).path("note").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private static String onClient(Supplier<String> work) {
        return onClient(work, answer(false, "The game did not answer in time. Try again.").toString());
    }

    /**
     * Runs on the game's own thread and waits for it, the way a click on the
     * screen would have run. Outside the game, as in a test, it simply runs.
     */
    private static <T> T onClient(Supplier<T> work, T otherwise) {
        net.minecraft.client.Minecraft mc;
        try {
            mc = net.minecraft.client.Minecraft.getInstance();
        } catch (Throwable e) {
            mc = null;
        }
        if (mc == null) return work.get();
        CompletableFuture<T> done = new CompletableFuture<>();
        mc.execute(() -> {
            try {
                done.complete(work.get());
            } catch (Throwable e) {
                done.completeExceptionally(e);
            }
        });
        try {
            return done.get(4, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.warn("DoughBay web panel: {}", e.toString());
            return otherwise;
        }
    }
}
