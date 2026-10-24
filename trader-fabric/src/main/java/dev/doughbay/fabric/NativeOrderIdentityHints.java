package dev.doughbay.fabric;

import java.util.*;
import java.util.regex.Pattern;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.*;

/** Local hashed observations only; neither uniqueness nor ownership is inferred from a field label. */
final class NativeOrderIdentityHints {
    private static final Pattern UUID=Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern LABEL=Pattern.compile("(?i)\\b(?:order|auction)[ _-]?(?:id|uuid)\\s*[:#=]\\s*([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|[0-9]{1,20})(?![a-z0-9_-])");
    private static final Set<String> FIELDS=Set.of("orderid","order_id","order-id","orderuuid","order_uuid","order-uuid","auctionid","auction_id","auctionuuid","auction_uuid");
    private NativeOrderIdentityHints() {}

    static Map<String,String> fields(CompoundTag tag) {
        Map<String,String> result=new TreeMap<>();fields(tag,"",result,0);return Map.copyOf(result);
    }
    private static void fields(CompoundTag tag,String parent,Map<String,String> result,int depth) {
        if(depth>4||result.size()>=32)return;
        for(String key:tag.keySet().stream().sorted().toList()) {
            String lower=key.toLowerCase(Locale.ROOT);
            if(!key.matches("[a-zA-Z_][a-zA-Z0-9_:.-]{0,63}")||lower.matches(".*(player|owner|profile|auth|token|secret|password|home).*") )continue;
            String path=parent.isEmpty()?key:parent+"/"+key;
            Tag value=tag.get(key);
            if(value instanceof CompoundTag child){fields(child,path,result,depth+1);continue;}
            String local=lower.substring(lower.lastIndexOf(':')+1);
            if(!FIELDS.contains(local))continue;
            String raw=value instanceof LongTag n?Long.toString(n.value())
                    :value instanceof IntTag n?Integer.toString(n.value())
                    :value instanceof StringTag s?s.value():"";
            String hash=hash(raw);if(hash!=null)result.put(path,hash);
            if(result.size()>=32)return;
        }
    }
    static List<String> text(Component component) {
        Set<String> result=new LinkedHashSet<>();text(component,result,0);return List.copyOf(result);
    }
    private static void text(Component component,Set<String> result,int depth) {
        if(depth>2||result.size()>=32)return;
        var match=LABEL.matcher(component.getString(2048));
        while(match.find()&&result.size()<32){String hash=hash(match.group(1));if(hash!=null)result.add(hash);}
        component.visit((style,part)->{
            if(style.getHoverEvent() instanceof HoverEvent.ShowText hover)text(hover.value(),result,depth+1);
            return Optional.empty();
        },Style.EMPTY);
    }
    private static String hash(String raw) {
        if(raw==null||raw.length()>36||!UUID.matcher(raw).matches()&&!raw.matches("[0-9]{1,20}"))return null;
        String canonical=UUID.matcher(raw).matches()?raw.toLowerCase(Locale.ROOT):new java.math.BigInteger(raw).toString();
        if(canonical.equals("0"))return null;
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(("gonuts-local-order-id:v1:"+canonical).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
