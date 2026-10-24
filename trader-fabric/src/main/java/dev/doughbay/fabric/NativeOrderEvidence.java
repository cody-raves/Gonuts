package dev.doughbay.fabric;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/** One explicitly requested local inspection. Never opens a menu, clicks, trades, or uploads its result. */
final class NativeOrderEvidence {
    static final String COMMAND="!inspect-orders";
    private static final AtomicBoolean requested=new AtomicBoolean();
    private static final Pattern QUANTITY=Pattern.compile("(?i)([0-9][0-9.,]*\\s*[kmb]?)\\s+requested");
    private static final Pattern DELIVERED=Pattern.compile("(?i)([0-9][0-9.,]*[kmb]?)\\s*/\\s*([0-9][0-9.,]*[kmb]?)\\s+delivered");
    private static final Pattern PAGE=Pattern.compile("(?i)page\\s*[:#(]?\\s*(\\d{1,4})(?:\\s*/\\s*(\\d{1,4}))?");
    private NativeOrderEvidence() {}
    static void request() { requested.set(true); }

    static String opaqueRowFingerprint(CompoundTag tag) {
        // Inspect equality only. The native value is never written or uploaded.
        Tag values=tag.get("PublicBukkitValues");
        if(!(values instanceof CompoundTag nested) || !(nested.get("minecraft:wi") instanceof NumericTag value)) return "";
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(("gonuts-local-native-row:"+value.longValue()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    static List<String> controlWords(String text) {
        Set<String> allowed=Set.of("previous","prev","next","page","go","back","click","left","right","sort",
                "order","orders","new","create","filter","show","active","completed","all","ascending",
                "descending","refresh","cancelled","canceled","claimed","oldest","newest","total","filled","unfilled");
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^a-z]+"))
                .filter(allowed::contains).distinct().toList();
    }

    static Map<String,Object> numericSchema(CompoundTag tag) {
        Map<String,Object> out=new TreeMap<>();
        inspect(tag,"",out,0);
        return out;
    }

    private static void inspect(CompoundTag tag,String parent,Map<String,Object> out,int depth) {
        if(depth>4 || out.size()>=100) return;
        for(String key:tag.keySet().stream().sorted().toList()) {
            String lower=key.toLowerCase(Locale.ROOT);
            if(!key.matches("[a-zA-Z_][a-zA-Z0-9_:.-]{0,63}")
                    || lower.matches(".*(player|owner|profile|auth|token|secret|password|home|name).*")
                    || lower.contains("uuid")&&!lower.contains("order")) continue;
            String path=parent.isEmpty()?key:parent+"/"+key;
            Tag value=tag.get(key);
            if(value instanceof CompoundTag child) inspect(child,path,out,depth+1);
            else if(value instanceof NumericTag number && lower.matches(".*(requested|delivered|amount|quantity|count|size|total|filled|remaining).*"))
                out.put(path,number.longValue());
            else out.put(path,value==null?"missing":value.getClass().getSimpleName());
            if(out.size()>=100) break;
        }
    }

    static void captureIfRequested(Minecraft client,AbstractContainerScreen<?> screen) {
        if(!requested.get() || !screen.getTitle().getString().toLowerCase(Locale.ROOT).contains("your orders")) return;
        if(!requested.compareAndSet(true,false)) return;
        try {
            List<Map<String,Object>> rows=new ArrayList<>(), controls=new ArrayList<>();
            for(int i=0;i<Math.min(54,screen.getMenu().slots.size());i++) {
                Slot slot=screen.getMenu().slots.get(i);
                if(slot.container==client.player.getInventory() || slot.getItem().isEmpty()) continue;
                ItemStack stack=slot.getItem();
                String name=stack.getHoverName().getString().strip();
                List<String> lore=new ArrayList<>();
                var component=stack.get(DataComponents.LORE);
                if(component!=null) component.lines().forEach(line->lore.add(line.getString()));
                String quantity=null, delivered=null;
                for(String line:lore) {
                    var q=QUANTITY.matcher(line);if(q.find())quantity=q.group(1);
                    var d=DELIVERED.matcher(line);if(d.find())delivered=d.group(1)+"/"+d.group(2);
                }
                if(quantity!=null) {
                    Map<String,Object> row=new LinkedHashMap<>();
                    row.put("slot",i);row.put("item",BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                    row.put("requestedDisplay",quantity);if(delivered!=null)row.put("deliveredDisplay",delivered);
                    row.put("requestedExact",OwnOrderQuantity.exact(quantity).isPresent());
                    if(delivered!=null) row.put("deliveredExact",OwnOrderQuantity.exact(delivered.split("/",2)[0]).isPresent());
                    var custom=stack.get(DataComponents.CUSTOM_DATA);
                    row.put("customNumericSchema",custom==null?Map.of():numericSchema(custom.copyTag()));
                    if(custom!=null)row.put("opaqueRowFingerprint",opaqueRowFingerprint(custom.copyTag()));
                    rows.add(row);
                } else if(i>=45) {
                    String lower=(name+" "+String.join(" ",lore)).toLowerCase(Locale.ROOT);
                    String label=lower.contains("next")?"next":lower.contains("previous")?"previous":
                            lower.contains("page")?"page":lower.contains("new order")||lower.contains("create order")?"create order":"other control";
                    Map<String,Object> control=new LinkedHashMap<>();control.put("slot",i);control.put("kind",label);
                    control.put("words",controlWords(lower));
                    var p=PAGE.matcher(name+" "+String.join(" ",lore));
                    if(p.find()){control.put("page",Integer.parseInt(p.group(1)));if(p.group(2)!=null)control.put("pages",Integer.parseInt(p.group(2)));}
                    controls.add(control);
                }
            }
            Map<String,Object> root=new LinkedHashMap<>();root.put("at",System.currentTimeMillis());
            root.put("configuredOrdersPerItem",(int)Tuning.get("orders.max_per_market"));
            root.put("rows",rows);root.put("controls",controls);
            var page=PAGE.matcher(screen.getTitle().getString());if(page.find())root.put("titlePage",Integer.parseInt(page.group(1)));
            Path target=client.gameDirectory.toPath().resolve("config/doughbay/native-order-evidence.json");
            Files.createDirectories(target.getParent());Path temp=target.resolveSibling(target.getFileName()+".tmp");
            dev.doughbay.fabric.hosted.DashboardProtocol.JSON.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(),root);
            try {Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException e){Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);}
            DoughBayClient.LOGGER.info("GoNuts: requested local native-order inspection completed ({} rows)",rows.size());
        } catch(Exception e) { DoughBayClient.LOGGER.warn("GoNuts: local native-order inspection unavailable ({})",e.getClass().getSimpleName()); }
    }
}
