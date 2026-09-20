package dev.doughbay.core.analysis;

import dev.doughbay.core.model.ItemFingerprint;
import dev.doughbay.core.model.NamespacedId;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Commodity gate for the POC. An item is eligible only when it is metadata-plain
 * (no enchantments, custom name, lore, trims, or container contents) AND its ID
 * is in the tracked set. The tracked set exists to focus the API budget, not to
 * declare items profitable — profitability always comes from the data.
 */
public final class CommodityRegistry {

    /** Explicit, auditable reasons an exact item is not safe for commodity stats. */
    public enum RejectionReason {
        MISSING_FINGERPRINT,
        NOT_TRACKED,
        EXPLICITLY_REJECTED,
        DAMAGEABLE_GEAR,
        BOOK_MAP_OR_PLAYER_HEAD,
        POTION_OR_TYPED_PROJECTILE,
        VARIABLE_CONTENT_CONTAINER,
        CUSTOM_NAME,
        ENCHANTMENTS,
        LORE,
        ARMOR_TRIM,
        CONTAINER_CONTENTS,
        UNKNOWN_METADATA
    }

    public record Assessment(boolean eligible, Set<RejectionReason> rejectionReasons) {
        public Assessment {
            rejectionReasons = rejectionReasons == null || rejectionReasons.isEmpty()
                    ? Set.of()
                    : Collections.unmodifiableSet(EnumSet.copyOf(rejectionReasons));
            if (eligible != rejectionReasons.isEmpty()) {
                throw new IllegalArgumentException("eligibility must agree with rejection reasons");
            }
        }
    }

    /** High-volume, stable, vanilla commodity items for the initial POC. */
    public static final Set<String> DEFAULT_COMMODITIES = Set.of(
            // Ores and minerals
            "minecraft:diamond", "minecraft:emerald", "minecraft:gold_ingot",
            "minecraft:iron_ingot", "minecraft:copper_ingot", "minecraft:netherite_scrap",
            "minecraft:netherite_ingot", "minecraft:coal", "minecraft:lapis_lazuli",
            "minecraft:quartz", "minecraft:amethyst_shard", "minecraft:ancient_debris",
            "minecraft:raw_iron", "minecraft:raw_gold", "minecraft:raw_copper",
            "minecraft:diamond_block", "minecraft:emerald_block", "minecraft:gold_block",
            "minecraft:iron_block", "minecraft:netherite_block", "minecraft:coal_block",
            // Redstone materials
            "minecraft:redstone", "minecraft:redstone_block", "minecraft:glowstone_dust",
            "minecraft:glowstone", "minecraft:slime_ball", "minecraft:slime_block",
            "minecraft:observer", "minecraft:piston", "minecraft:sticky_piston",
            "minecraft:hopper", "minecraft:comparator", "minecraft:repeater",
            // Building blocks
            "minecraft:obsidian", "minecraft:crying_obsidian", "minecraft:stone",
            "minecraft:cobblestone", "minecraft:deepslate", "minecraft:sand",
            "minecraft:gravel", "minecraft:oak_log", "minecraft:spruce_log",
            "minecraft:birch_log", "minecraft:dark_oak_log", "minecraft:quartz_block",
            "minecraft:glass", "minecraft:sea_lantern", "minecraft:prismarine",
            "minecraft:terracotta", "minecraft:calcite", "minecraft:tuff",
            // Bulk basics: traded by the stack hundreds of times a day with a
            // wide spread between the cheap sellers and the median.
            "minecraft:dirt", "minecraft:coarse_dirt", "minecraft:grass_block",
            "minecraft:cobbled_deepslate", "minecraft:oak_planks", "minecraft:spruce_planks",
            "minecraft:birch_planks", "minecraft:stick", "minecraft:feather",
            "minecraft:flint", "minecraft:charcoal", "minecraft:baked_potato",
            // Mob drops and utility
            "minecraft:ender_pearl", "minecraft:ender_eye", "minecraft:blaze_rod",
            "minecraft:blaze_powder", "minecraft:gunpowder", "minecraft:string",
            "minecraft:bone", "minecraft:bone_block", "minecraft:spider_eye",
            "minecraft:ghast_tear", "minecraft:shulker_shell", "minecraft:phantom_membrane",
            "minecraft:leather", "minecraft:rotten_flesh", "minecraft:arrow",
            "minecraft:experience_bottle", "minecraft:totem_of_undying",
            "minecraft:wither_skeleton_skull", "minecraft:nether_star",
            // Standardized high-value items. Every one is a single crafted or
            // dropped form with no per-item state, so two are interchangeable
            // — unlike the shulker boxes and written books deliberately kept
            // out of this set.
            "minecraft:beacon", "minecraft:conduit",
            "minecraft:dragon_egg", "minecraft:heart_of_the_sea",
            // Food and farming
            "minecraft:wheat", "minecraft:carrot", "minecraft:potato",
            "minecraft:beetroot", "minecraft:sugar_cane", "minecraft:sugar",
            "minecraft:melon_slice", "minecraft:pumpkin", "minecraft:cactus",
            "minecraft:bamboo", "minecraft:kelp", "minecraft:dried_kelp_block",
            "minecraft:nether_wart", "minecraft:cocoa_beans", "minecraft:egg",
            "minecraft:golden_carrot", "minecraft:golden_apple",
            "minecraft:enchanted_golden_apple", "minecraft:cooked_beef",
            "minecraft:cooked_porkchop", "minecraft:cooked_chicken", "minecraft:bread",
            "minecraft:honey_bottle", "minecraft:honeycomb", "minecraft:wheat_seeds"
    );

    /**
     * Bounded, high-volume POC universe used by the live watcher by default.
     * Each market is exhaustively paginated, so keeping this set deliberately
     * small preserves a prompt refresh cycle and leaves request budget for
     * execution-time reconciliation scans. This is an API-budget choice, not a
     * hard-coded profitability claim.
     */
    public static final Set<String> DEFAULT_FOCUSED_COMMODITIES = Set.of(
            "minecraft:ender_pearl",
            "minecraft:redstone",
            "minecraft:obsidian",
            "minecraft:diamond",
            "minecraft:netherite_scrap",
            "minecraft:gold_ingot",
            "minecraft:iron_ingot",
            "minecraft:emerald",
            "minecraft:coal_block",
            "minecraft:glowstone"
    );

    private static final Set<String> DAMAGEABLE_IDS = Set.of(
            "minecraft:bow", "minecraft:crossbow", "minecraft:trident",
            "minecraft:shield", "minecraft:elytra", "minecraft:mace",
            "minecraft:shears", "minecraft:fishing_rod", "minecraft:flint_and_steel",
            "minecraft:carrot_on_a_stick", "minecraft:warped_fungus_on_a_stick"
    );
    /**
     * Ids whose items carry hidden per-item state, so two with the same
     * fingerprint still are not interchangeable: book text, map art, a head's
     * skin.
     *
     * <p>{@code minecraft:map} is deliberately absent. That is the blank map —
     * paper and a compass, stackable to 64, with no NBT until someone uses it.
     * The art lives under {@code minecraft:filled_map}, which stays listed.
     * Blocking the blank one removed a genuinely fungible market for no gain.
     */
    private static final Set<String> BOOK_MAP_HEAD_IDS = Set.of(
            "minecraft:writable_book", "minecraft:written_book", "minecraft:enchanted_book",
            "minecraft:filled_map", "minecraft:player_head"
    );
    private static final Set<String> TYPED_CONSUMABLE_IDS = Set.of(
            "minecraft:potion", "minecraft:splash_potion", "minecraft:lingering_potion",
            "minecraft:tipped_arrow", "minecraft:suspicious_stew", "minecraft:goat_horn"
    );
    private static final Set<String> VARIABLE_CONTAINER_IDS = Set.of(
            "minecraft:bundle"
    );

    private final Set<String> trackedIds;
    private final Set<String> explicitlyRejectedIds;
    private final Set<String> descriptorMarkets;

    public CommodityRegistry(Set<String> trackedIds) {
        this(trackedIds, Set.of());
    }

    public CommodityRegistry(Set<String> trackedIds, Set<String> explicitlyRejectedIds) {
        this(trackedIds, explicitlyRejectedIds, Set.of());
    }

    /**
     * @param descriptorMarkets items whose enchantments, trim and name are part
     *                          of their identity rather than a disqualification
     */
    public CommodityRegistry(Set<String> trackedIds, Set<String> explicitlyRejectedIds,
            Set<String> descriptorMarkets) {
        this.trackedIds = normalizeIds(trackedIds, "trackedIds");
        this.explicitlyRejectedIds = normalizeIds(explicitlyRejectedIds, "explicitlyRejectedIds");
        this.descriptorMarkets = normalizeIds(descriptorMarkets, "descriptorMarkets");
    }

    public Set<String> descriptorMarkets() {
        return descriptorMarkets;
    }

    /**
     * Whether this item is priced by its exact make-up rather than as a plain
     * stack.
     *
     * <p>The gate was written when "commodity" meant "plain": an enchantment
     * made two stacks incomparable, so anything carrying one was dropped before
     * it could be priced. That is right for a market where the enchantment is
     * incidental and wrong for one where it <em>is</em> the product. A netherite
     * chestplate is not a chestplate plus decoration; the kit is the item, every
     * copy of a kit is interchangeable, and they clear within a tenth of each
     * other.
     *
     * <p>So for these items the descriptor stops being a reason to look away and
     * becomes part of the key: each kit gets its own market, its own statistics
     * and its own price. Deliberately a named list, because being wrong here
     * means pricing one make of a thing off another's history.
     */
    public boolean pricedByDescriptor(String itemId) {
        return descriptorMarkets.contains(NamespacedId.normalize(itemId));
    }

    public static CommodityRegistry defaults() {
        return new CommodityRegistry(DEFAULT_COMMODITIES);
    }

    public static CommodityRegistry focusedDefaults() {
        return new CommodityRegistry(DEFAULT_FOCUSED_COMMODITIES);
    }

    public Set<String> trackedIds() {
        return trackedIds;
    }

    public Set<String> explicitlyRejectedIds() {
        return explicitlyRejectedIds;
    }

    public boolean isEligible(ItemFingerprint fingerprint) {
        return assess(fingerprint).eligible();
    }

    /**
     * Returns every applicable rejection rather than a single opaque boolean,
     * allowing API diagnostics to explain exactly why a record was isolated.
     */
    public Assessment assess(ItemFingerprint fingerprint) {
        if (fingerprint == null) {
            return new Assessment(false, Set.of(RejectionReason.MISSING_FINGERPRINT));
        }

        EnumSet<RejectionReason> reasons = EnumSet.noneOf(RejectionReason.class);
        String itemId = fingerprint.itemId();
        boolean byDescriptor = descriptorMarkets.contains(itemId);
        if (!trackedIds.contains(itemId)) reasons.add(RejectionReason.NOT_TRACKED);
        if (explicitlyRejectedIds.contains(itemId)) reasons.add(RejectionReason.EXPLICITLY_REJECTED);
        classifyUnsafeId(itemId, reasons);
        // The make-up of a descriptor market is its identity, so the things
        // that would otherwise disqualify it are exactly what tell its kits
        // apart. Everything else - lore, container contents, metadata we do
        // not understand - still disqualifies, and so does an item id that is
        // not on the list.
        if (byDescriptor) {
            reasons.remove(RejectionReason.DAMAGEABLE_GEAR);
            reasons.remove(RejectionReason.BOOK_MAP_OR_PLAYER_HEAD);
        }

        if (!byDescriptor && !fingerprint.displayName().isEmpty()) reasons.add(RejectionReason.CUSTOM_NAME);
        if (!byDescriptor && !fingerprint.enchantments().isEmpty()) reasons.add(RejectionReason.ENCHANTMENTS);
        if (!fingerprint.loreHash().isEmpty()) reasons.add(RejectionReason.LORE);
        if (!byDescriptor
                && (!fingerprint.trimMaterial().isEmpty() || !fingerprint.trimPattern().isEmpty())) {
            reasons.add(RejectionReason.ARMOR_TRIM);
        }
        if (!fingerprint.containerHash().isEmpty()) reasons.add(RejectionReason.CONTAINER_CONTENTS);
        if (!fingerprint.extraMetadataHash().isEmpty()) reasons.add(RejectionReason.UNKNOWN_METADATA);
        return new Assessment(reasons.isEmpty(), reasons);
    }

    private static void classifyUnsafeId(String itemId, EnumSet<RejectionReason> reasons) {
        String path = itemId.substring(itemId.indexOf(':') + 1).toLowerCase(Locale.ROOT);
        if (DAMAGEABLE_IDS.contains(itemId)
                || endsWithAny(path, "_sword", "_pickaxe", "_axe", "_shovel", "_hoe",
                "_helmet", "_chestplate", "_leggings", "_boots")) {
            reasons.add(RejectionReason.DAMAGEABLE_GEAR);
        }
        if (BOOK_MAP_HEAD_IDS.contains(itemId)) {
            reasons.add(RejectionReason.BOOK_MAP_OR_PLAYER_HEAD);
        }
        if (TYPED_CONSUMABLE_IDS.contains(itemId)) {
            reasons.add(RejectionReason.POTION_OR_TYPED_PROJECTILE);
        }
        if (VARIABLE_CONTAINER_IDS.contains(itemId) || path.endsWith("shulker_box")) {
            reasons.add(RejectionReason.VARIABLE_CONTENT_CONTAINER);
        }
    }

    private static boolean endsWithAny(String value, String... endings) {
        for (String ending : endings) {
            if (value.endsWith(ending)) return true;
        }
        return false;
    }

    private static Set<String> normalizeIds(Set<String> rawIds, String fieldName) {
        Objects.requireNonNull(rawIds, fieldName);
        Set<String> normalized = new TreeSet<>();
        for (String id : rawIds) normalized.add(NamespacedId.normalize(id));
        return Collections.unmodifiableSet(normalized);
    }
}
