package dev.doughbay.fabric;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.equipment.trim.ArmorTrim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Everything that makes one stack worth what it is: the base item and
 * count, the enchantments on it (or stored in a book), the potion or arrow
 * effect, the armor trim, and for shulker boxes and bundles the stacks
 * inside, each described the same way. The client has all of this on the
 * stack itself; the API feed hides it behind a hash.
 *
 * <p>{@link #key()} is a canonical text form, stable across equal stacks,
 * used as the identity for observation and pricing.
 */
public record ItemDescriptor(
        String itemId,
        int count,
        Map<String, Integer> enchantments,
        String potion,
        List<String> effects,
        String trim,
        boolean renamed,
        String wearBand,
        List<ItemDescriptor> contents) {

    public ItemDescriptor {
        enchantments = Collections.unmodifiableMap(new TreeMap<>(enchantments == null ? Map.of() : enchantments));
        potion = potion == null ? "" : potion;
        effects = List.copyOf(effects == null ? List.of() : effects);
        trim = trim == null ? "" : trim;
        wearBand = wearBand == null ? "" : wearBand;
        contents = List.copyOf(contents == null ? List.of() : contents);
    }

    /** Reads a stack; never throws, and describes an unreadable part as plain. */
    public static ItemDescriptor of(ItemStack stack) {
        return of(stack, 0);
    }

    private static ItemDescriptor of(ItemStack stack, int depth) {
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        Map<String, Integer> enchants = new TreeMap<>();
        String potion = "";
        List<String> effects = new ArrayList<>();
        String trim = "";
        boolean renamed = false;
        String wear = "";
        List<ItemDescriptor> contents = new ArrayList<>();
        try {
            readEnchantments(stack.get(DataComponents.ENCHANTMENTS), enchants);
            readEnchantments(stack.get(DataComponents.STORED_ENCHANTMENTS), enchants);
            PotionContents potionContents = stack.get(DataComponents.POTION_CONTENTS);
            if (potionContents != null) {
                potion = potionContents.potion().map(ItemDescriptor::holderName).orElse("");
                for (MobEffectInstance effect : potionContents.customEffects()) {
                    effects.add(holderName(effect.getEffect()) + "/" + (effect.getAmplifier() + 1)
                            + "/" + effect.getDuration());
                }
            }
            ArmorTrim armorTrim = stack.get(DataComponents.TRIM);
            if (armorTrim != null) {
                trim = holderName(armorTrim.pattern()) + "+" + holderName(armorTrim.material());
            }
            renamed = stack.has(DataComponents.CUSTOM_NAME);
            wear = wearOf(stack);
            if (depth < 3) {
                ItemContainerContents container = stack.get(DataComponents.CONTAINER);
                if (container != null) {
                    for (ItemStackTemplate template : container.nonEmptyItems()) {
                        ItemStack inner = template.create();
                        if (!inner.isEmpty()) contents.add(of(inner, depth + 1));
                    }
                }
                BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
                if (bundle != null) {
                    for (ItemStackTemplate template : bundle.items()) {
                        ItemStack inner = template.create();
                        if (!inner.isEmpty()) contents.add(of(inner, depth + 1));
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // A component the reader does not understand leaves the rest intact.
        }
        return new ItemDescriptor(id, stack.getCount(), enchants, potion, effects, trim, renamed, wear, contents);
    }

    private static void readEnchantments(ItemEnchantments enchantments, Map<String, Integer> into) {
        if (enchantments == null || enchantments.isEmpty()) return;
        for (Object2IntMap.Entry<Holder<Enchantment>> entry : enchantments.entrySet()) {
            into.merge(holderName(entry.getKey()), entry.getIntValue(), Math::max);
        }
    }

    private static String holderName(Holder<?> holder) {
        return holder == null ? "" : holder.unwrapKey().map(k -> k.identifier().toString())
                .orElse(holder.getRegisteredName());
    }

    /** True when something beyond item and count affects the value. */
    public boolean hasParts() {
        return !enchantments.isEmpty() || !potion.isEmpty() || !effects.isEmpty() || !trim.isEmpty()
                || !contents.isEmpty();
    }

    /** A plain stack: nothing but item and count, so the completed-sale median prices it. */
    public boolean plain() {
        return !hasParts() && !renamed;
    }

    public boolean isContainer() {
        return !contents.isEmpty();
    }

    /** Canonical identity text, e.g. {@code minecraft:diamond_sword x1 {sharpness:5,mending:1}}. */
    public String key() {
        StringBuilder out = new StringBuilder(itemId).append(" x").append(count);
        if (!enchantments.isEmpty()) {
            out.append(" {");
            boolean first = true;
            for (Map.Entry<String, Integer> e : enchantments.entrySet()) {
                if (!first) out.append(',');
                first = false;
                out.append(shortId(e.getKey())).append(':').append(e.getValue());
            }
            out.append('}');
        }
        if (!potion.isEmpty()) out.append(" potion=").append(shortId(potion));
        if (!effects.isEmpty()) out.append(" effects=").append(String.join("|", effects));
        if (!trim.isEmpty()) out.append(" trim=").append(trim);
        // A trident at 3% and a fresh one are not the same market. Only a worn
        // item says so, so nothing that cannot take damage changes key and the
        // history already gathered stays valid.
        if (!wearBand.isEmpty()) out.append(" wear=").append(wearBand);
        if (!contents.isEmpty()) {
            out.append(" [");
            List<String> inner = new ArrayList<>();
            for (ItemDescriptor d : contents) inner.add(d.key());
            Collections.sort(inner);
            out.append(String.join("; ", inner)).append(']');
        }
        return out.toString();
    }

    /** Short identity hash used in position keys: {@code minecraft:shulker_box#1a2b3c4d}. */
    /**
     * How worn a damageable item is, in bands.
     *
     * <p>Exact durability would give every trident its own market and none of
     * them any history; bands keep enough of them together to price while still
     * separating a nearly-new one from a nearly-broken one. An undamaged item,
     * and anything that cannot take damage at all, returns "" and keeps the key
     * it has always had.
     */
    private static String wearOf(ItemStack stack) {
        try {
            if (!stack.isDamageableItem()) return "";
            int max = stack.getMaxDamage();
            if (max <= 0) return "";
            int left = max - stack.getDamageValue();
            int pct = (int) Math.round(100.0 * left / max);
            if (pct >= 96) return "";        // as good as new; the plain market
            if (pct >= 75) return "75";
            if (pct >= 50) return "50";
            if (pct >= 25) return "25";
            return "low";
        } catch (Throwable t) {
            return "";
        }
    }

    public String hash() {
        return Integer.toHexString(key().hashCode());
    }

    /** A compact JSON form for the observation log. */
    public String toJson() {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"item\":\"").append(itemId).append("\",\"count\":").append(count);
        if (!enchantments.isEmpty()) {
            sb.append(",\"enchantments\":{");
            boolean first = true;
            for (Map.Entry<String, Integer> e : enchantments.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(e.getKey()).append("\":").append(e.getValue());
            }
            sb.append('}');
        }
        if (!potion.isEmpty()) sb.append(",\"potion\":\"").append(potion).append('"');
        if (!effects.isEmpty()) {
            sb.append(",\"effects\":[");
            for (int i = 0; i < effects.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append('"').append(effects.get(i)).append('"');
            }
            sb.append(']');
        }
        if (!trim.isEmpty()) sb.append(",\"trim\":\"").append(trim).append('"');
        if (renamed) sb.append(",\"renamed\":true");
        if (!contents.isEmpty()) {
            sb.append(",\"contents\":[");
            for (int i = 0; i < contents.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(contents.get(i).toJson());
            }
            sb.append(']');
        }
        return sb.append('}').toString();
    }

    /** Human-readable summary for logs and tooltips. */
    public String describe() {
        StringBuilder out = new StringBuilder(LedgerStatsProvider.displayName(itemId)).append(" x").append(count);
        if (!enchantments.isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, Integer> e : enchantments.entrySet()) {
                parts.add(titleCase(shortId(e.getKey())) + " " + roman(e.getValue()));
            }
            out.append(" (").append(String.join(", ", parts)).append(')');
        }
        if (!potion.isEmpty()) out.append(" of ").append(titleCase(shortId(potion)));
        if (!effects.isEmpty()) out.append(" +").append(effects.size()).append(" effect(s)");
        if (!trim.isEmpty()) out.append(" trimmed");
        if (!contents.isEmpty()) out.append(" holding ").append(contents.size()).append(" stack(s)");
        return out.toString();
    }

    static String shortId(String id) {
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }

    private static String titleCase(String value) {
        StringBuilder out = new StringBuilder();
        for (String word : value.replace('_', ' ').split(" ")) {
            if (word.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return out.toString();
    }

    private static String roman(int level) {
        return switch (level) {
            case 1 -> "I"; case 2 -> "II"; case 3 -> "III"; case 4 -> "IV"; case 5 -> "V";
            default -> String.valueOf(level);
        };
    }
}
