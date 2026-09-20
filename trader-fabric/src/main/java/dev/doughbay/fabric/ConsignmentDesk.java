package dev.doughbay.fabric;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Takes filled boxes handed over by friends and pays them a share of what
 * they sell for.
 *
 * <p>A friend stands next to the bot holding a shulker. A moment later they
 * are not holding it and the bot is. Nothing else can have happened, so the
 * box is theirs and the sale is split with them. Minecraft does not tell a
 * client who threw an item — the thrower lives on the server — but it does
 * say what every nearby player is holding, and that is enough.
 *
 * <p>Only names on the ally list count. Not because a stranger's box is worth
 * less, but because the payout goes out on its own: a wrong guess here is real
 * money sent to somebody who never gave us anything, and the ally list is the
 * one place where you have already said who you trust.
 */
final class ConsignmentDesk {
    /** How long after a friend last held it that an arriving box still counts as theirs. */
    private static final long CLAIM_WINDOW_MILLIS = 20_000L;

    /** What each nearby friend was last seen holding, and when. */
    private record Held(String player, String itemId, long at) {
    }

    private final List<Held> recentlyHeld = new ArrayList<>();
    /**
     * Boxes taken but not yet listed. No promise is made here.
     *
     * <p>A hand-over is not a contract. Between taking the box and getting it
     * onto the auction it is still sitting in our inventory, where dying drops
     * it: the giver could take their own box back off the ground and still be
     * owed a share of a sale that never happened. The obligation is opened when
     * the box is up on the market and nothing can take it back.
     */
    private final Map<String, Pending> pending = new HashMap<>();

    private record Pending(String from, long value, long at) {
    }
    /** Container stacks already in our inventory, so only new arrivals are credited. */
    private final Map<String, Integer> known = new HashMap<>();
    private boolean primed;

    void tick(Minecraft client) {
        if (Tuning.get("consign.enabled") < 0.5) return;
        if (client == null || client.player == null || client.level == null) return;
        long now = System.currentTimeMillis();
        try {
            noteWhatFriendsHold(client, now);
            noteWhatArrived(client, now);
            settlePending(client, now);
        } catch (Throwable t) {
            DoughBayClient.LOGGER.warn("DoughBay consignment: {}", t.toString());
        }
    }

    /** Remembers every container a friend within reach is holding. */
    private void noteWhatFriendsHold(Minecraft client, long now) {
        double range = Tuning.get("consign.range");
        for (Player p : client.level.players()) {
            if (p == client.player) continue;
            String name = p.getName().getString();
            if (!SafeHomes.isAlly(name)) continue;
            if (p.distanceTo(client.player) > range) continue;
            for (ItemStack stack : List.of(p.getMainHandItem(), p.getOffhandItem())) {
                if (stack == null || stack.isEmpty()) continue;
                ItemDescriptor d = ItemDescriptor.of(stack);
                if (!d.isContainer()) continue;
                boolean fresh = recentlyHeld.stream()
                        .noneMatch(h -> h.player().equals(name) && h.itemId().equals(d.itemId()));
                recentlyHeld.removeIf(h -> h.player().equals(name) && h.itemId().equals(d.itemId()));
                recentlyHeld.add(new Held(name, d.itemId(), now));
                if (fresh) {
                    DoughBayClient.LOGGER.info("DoughBay consignment: saw a friend holding one - {} has {} at {}b",
                            name, d.itemId(), Math.round(p.distanceTo(client.player)));
                }
            }
        }
        recentlyHeld.removeIf(h -> now - h.at() > CLAIM_WINDOW_MILLIS);
    }

    /** Credits a container that has appeared in our inventory to whoever was holding one. */
    private void noteWhatArrived(Minecraft client, long now) {
        Map<String, Integer> present = new HashMap<>();
        for (int i = 0; i < client.player.getInventory().getContainerSize(); i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (stack == null || stack.isEmpty()) continue;
            ItemDescriptor d = ItemDescriptor.of(stack);
            if (!d.isContainer() || d.contents().isEmpty()) continue;
            present.merge(d.itemId() + "#" + d.hash(), 1, Integer::sum);
        }
        // The first pass only learns what is already here; a box bought off the
        // auction must never be credited to a friend who happens to be standing
        // nearby holding one of their own.
        if (!primed) {
            known.putAll(present);
            primed = true;
            return;
        }
        for (Map.Entry<String, Integer> e : present.entrySet()) {
            int before = known.getOrDefault(e.getKey(), 0);
            if (e.getValue() <= before) continue;
            String itemId = e.getKey().substring(0, e.getKey().indexOf('#'));
            DoughBayClient.LOGGER.info("DoughBay consignment: a container arrived - {} (was {}, now {})",
                    e.getKey(), before, e.getValue());
            claim(client, itemId, e.getKey(), now);
        }
        known.clear();
        known.putAll(present);
    }

    /** Opens the consignment: the friend is the lender, the contents are the stake. */
    private void claim(Minecraft client, String itemId, String key, long now) {
        String from = "";
        long best = 0;
        for (Held h : recentlyHeld) {
            if (h.itemId().equals(itemId) && h.at() >= best) {
                best = h.at();
                from = h.player();
            }
        }
        if (from.isEmpty()) {
            DoughBayClient.LOGGER.info("DoughBay consignment: {} arrived but no friend was seen holding one "
                    + "(held recently: {}); treating it as ours", key, recentlyHeld);
            return;
        }
        long value = valueOf(client, key);
        if (value < Tuning.get("consign.min_value")) {
            DoughBayClient.LOGGER.info("DoughBay consignment: {} from {} valued {}, under the {} floor "
                    + "(0 means the contents could not all be priced); treating it as ours",
                    key, from, value, Math.round(Tuning.get("consign.min_value")));
            return;
        }
        final String giver = from;
        recentlyHeld.removeIf(h -> h.player().equals(giver) && h.itemId().equals(itemId));
        pending.put(key, new Pending(giver, value, now));
        DoughBayClient.LOGGER.info("DoughBay consignment: {} handed over {} worth {}; nothing is owed until it is listed",
                giver, key, value);
    }

    /**
     * Turns a box that has reached the auction into an actual obligation, and
     * forgets one that never got there.
     */
    private void settlePending(Minecraft client, long now) {
        if (pending.isEmpty()) return;
        double share = Tuning.get("consign.share_pct");
        long giveUp = (long) (Tuning.get("consign.abandon_min") * 60_000L);
        List<String> done = new ArrayList<>();
        for (Map.Entry<String, Pending> e : pending.entrySet()) {
            String key = e.getKey();
            Pending p = e.getValue();
            boolean listed = false;
            try {
                var session = DoughBayClient.automationSessionController();
                listed = session != null && session.isListed(key);
            } catch (Throwable ignored) {
                // no session yet; try again next tick
            }
            if (listed) {
                done.add(key);
                HiveBank bank = DoughBayClient.hiveBank();
                if (bank != null) bank.consign(p.from(), key, p.value(), share);
                DoughBayClient.LOGGER.info("DoughBay consignment: {} is on the market; {} is owed {}% of what it makes",
                        key, p.from(), Math.round(share));
                StatusWebhook hook = DoughBayClient.webhook();
                if (hook != null) {
                    List<String[]> lines = new ArrayList<>();
                    lines.add(new String[] {"from", p.from()});
                    lines.add(new String[] {"box", key});
                    lines.add(new String[] {"contents valued", "$" + String.format(Locale.ROOT, "%,d", p.value())});
                    lines.add(new String[] {"their share", Math.round(share) + "% of the profit, paid when it sells"});
                    hook.alert("Box listed on consignment", 0x74E48B, lines, p.from());
                }
            } else if (giveUp > 0 && now - p.at() > giveUp) {
                // Never reached the market: lost, taken back, or dropped on
                // death. Nothing was promised, so nothing is owed.
                done.add(key);
                DoughBayClient.LOGGER.info("DoughBay consignment: {} from {} never reached the market; no debt stands",
                        key, p.from());
            }
        }
        for (String key : done) pending.remove(key);
    }

    /** What the contents are worth, by the same reckoning the auction boxes use. */
    private long valueOf(Minecraft client, String key) {
        try {
            for (int i = 0; i < client.player.getInventory().getContainerSize(); i++) {
                ItemStack stack = client.player.getInventory().getItem(i);
                if (stack == null || stack.isEmpty()) continue;
                ItemDescriptor d = ItemDescriptor.of(stack);
                if (!(d.itemId() + "#" + d.hash()).equals(key)) continue;
                MarketWatcher watcher = DoughBayClient.watcher();
                MarketWatcher.Snapshot snapshot = watcher == null ? null : watcher.snapshot();
                if (snapshot == null) return 0;
                ComponentValuer.Valuation v = ComponentValuer.value(d, snapshot);
                return v.complete() ? v.total() : 0;
            }
        } catch (Throwable ignored) {
            // an unpriceable box is simply not a consignment
        }
        return 0;
    }
}
