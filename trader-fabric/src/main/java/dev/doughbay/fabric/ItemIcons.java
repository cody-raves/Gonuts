package dev.doughbay.fabric;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;

/** Item stacks to draw as icons on the HUD and in alerts, from a position key or a display name. */
final class ItemIcons {
    private ItemIcons() {
    }

    /** A stack for "minecraft:redstone" or "minecraft:shulker_box#1a2b3c4d"; null when unknown. */
    static ItemStack stackFor(String itemKey, int count) {
        if (itemKey == null || itemKey.isBlank()) return null;
        try {
            String id = itemKey;
            int hash = id.indexOf('#');
            if (hash >= 0) id = id.substring(0, hash);
            Identifier ident = Identifier.tryParse(id);
            if (ident == null) return null;
            Item item = BuiltInRegistries.ITEM.getValue(ident);
            if (item == null) return null;
            return new ItemStack(item, Math.max(1, count));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** "Ender Chest" or "Golden Carrots" to "minecraft:ender_chest"; "" when no item matches. */
    static String keyForDisplayName(String name) {
        if (name == null || name.isBlank()) return "";
        String base = name.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", "").replace(' ', '_');
        for (String candidate : new String[] {base, base.endsWith("s") ? base.substring(0, base.length() - 1) : base,
                base.endsWith("es") ? base.substring(0, base.length() - 2) : base}) {
            if (candidate.isEmpty()) continue;
            Identifier ident = Identifier.tryParse("minecraft:" + candidate);
            if (ident != null && BuiltInRegistries.ITEM.containsKey(ident)) return "minecraft:" + candidate;
        }
        return "";
    }
}
