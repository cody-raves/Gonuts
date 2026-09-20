package dev.doughbay.fabric;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Opening a chest that is already there.
 *
 * <p>A ninety-slot auction book is the scarce thing; storage is not. Every
 * stack the bot holds on the auction house is holding a slot, and the price
 * ladder exists mostly to get slots back rather than because the stock was
 * mispriced. Somewhere to put stock that is not a listing turns that ladder
 * from a necessity into a choice.
 *
 * <p>Which makes this the first thing the mod has ever done to the world, and
 * the world is where the checks are. So the design gives away as much of that
 * surface as it can:
 *
 * <ul>
 *   <li><b>It never places.</b> You put one ender chest down by hand. Ender
 *       chest contents belong to the player rather than the block, so one is
 *       all there will ever be, and nothing can be stolen from it.
 *   <li><b>It never breaks.</b> Break time is exactly computable from tool and
 *       block, so any deviation is arithmetic rather than a guess - the
 *       cheapest flag in the game. Nothing here digs.
 *   <li><b>It never aims.</b> The interaction is built from
 *       {@link Minecraft#hitResult}, which vanilla computed this frame from
 *       the player's real rotation. The reach distance and the hit vector are
 *       therefore the same bytes a hand on the mouse would have sent, because
 *       they came from the same place. Nothing synthesises an angle, and no
 *       rotation packet is sent at all: set the home looking at the chest and
 *       the player arrives already facing it.
 * </ul>
 *
 * <p>What is left is one interaction on a block directly in front of the
 * player, and then container clicks - which is what the mod has been sending
 * all day to the auction house, paced by the same gate.
 */
public final class StashDesk {

    private StashDesk() {
    }

    /**
     * Where the chest lives: the off hand.
     *
     * <p>Nothing needed reserving in the end. Every sweep that hunts the pack
     * for stock walks the inventory, and the off hand is not in it - the list
     * is called non-equipment items and the off hand is equipment. So a chest
     * kept there is not hidden from the trading by a rule that could be
     * forgotten; it is somewhere the trading structurally cannot look.
     *
     * <p>It also behaves correctly without being told to. With the main hand
     * empty, looking at the ground places from the off hand; looking at a
     * chest that is already there opens it, because a block interaction beats
     * an item use. One action, the right answer both times.
     */
    private static final int OFFHAND_MENU_SLOT = 45;

    /**
     * How far down to look when placing a chest in front of the feet - forward
     * and down, so the crosshair meets the ground about a block ahead rather
     * than the block the feet occupy. That is the fix: the chest is placed
     * against the ground in front with one right-click, the same interaction
     * that opens one and works on the real server every time. The jump it
     * replaces only ever reached three-quarters of a block there.
     */
    private static final float PLACE_PITCH = 48f;

    /** Whether the crosshair is resting on an ender chest that can be opened. */
    private static boolean chestUnderCrosshair(Minecraft client) {
        HitResult hit = client.hitResult;
        return hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK
                && client.level.getBlockState(b.getBlockPos()).getBlock()
                == net.minecraft.world.level.block.Blocks.ENDER_CHEST;
    }

    /** The pack slot a withdrawal lands in on the way to the off hand. */
    public static int pocketSlot() {
        return (int) Math.round(Tuning.get("stash.pocket_slot"));
    }

    /** Whether the off hand already holds the chest. */
    private static boolean chestInOffhand(Minecraft client) {
        var off = client.player.getOffhandItem();
        return !off.isEmpty() && off.getHoverName().getString()
                .toLowerCase(java.util.Locale.ROOT).contains("ender chest");
    }

    /** Off until it is asked for, so nothing here can fire on the live account by accident. */
    public static boolean enabled() {
        return Tuning.get("stash.enabled") >= 0.5;
    }

    /** Until when a fresh run is pointless; set when one cannot start. */
    private static long notBefore;
    /** Runs that failed in a row, so the same attempt is not repeated on a beat. */
    private static int failedRuns;

    /**
     * How long to wait after a failed run, doubling each time.
     *
     * <p>A run that fails sends the same interaction at the same block from
     * the same place. Repeating that every thirty seconds is a far louder
     * pattern than the action itself: identical packets on a regular beat is
     * precisely the shape anything watching would pick out, and it achieves
     * nothing, because whatever stopped the first attempt is usually still
     * true on the second.
     *
     * <p>So it backs off - half a minute, then a minute, then two - and after
     * six failures stops trying until something changes. Better to leave the
     * stock in the pack and say so than to keep knocking.
     */
    private static void runFailed(Minecraft client, String why) {
        failedRuns++;
        long wait = Math.min(600_000L, 30_000L * (1L << Math.min(5, failedRuns - 1)));
        notBefore = System.currentTimeMillis() + wait;
        DoughBayClient.LOGGER.info("DoughBay stash: {} ({} in a row); waiting {} s before trying again",
                why, failedRuns, wait / 1000);
        if (failedRuns >= 6) {
            say(client, "The shelf run keeps failing; leaving the stock in the pack until something changes");
        }
    }

    /** Nothing in progress, in the air waiting for room, or waiting to land. */
    private enum Stage {
        IDLE,
        /** Waiting for the pack to open, the swap to land and the pack to close. */
        EQUIPPING,
        /** Turning to look at the ground, over several ticks rather than at once. */
        LOOKING,
        /** In the air, waiting for room underneath. */
        RISING,
        /** Chest placed, waiting to land on it. */
        PLACED,
        /** Landed, waiting a moment before reaching for it. */
        SETTLING,
        /** Right-clicked; the shelf handles itself from here. */
        OPENING,
        /** Waiting a beat after bringing the chest out of the off hand. */
        SWAPPING,
        /** Turning back to wherever the view was pointing before. */
        RESTORING
    }

    private static Stage stage = Stage.IDLE;
    private static double groundY;
    /** The highest the jump actually got, so a short one can be reported. */
    private static double highestY;
    private static int stageTick;
    /** Wall-clock moment a run must end by, whatever state it is stuck in. */
    private static long runDeadline;
    /** Whether the off-hand place has already been tried and failed this run. */
    private static boolean placedFromOffhand;
    /** Whether the turn has finished and only the settling pause is left. */
    private static boolean lookSettled;
    /** Where the view was pointing before the run, so it can be put back. */
    private static float pitchBefore;
    /** Which hotbar slot was selected before the run; -1 when nothing was changed. */
    private static int slotBefore = -1;

    /**
     * Empties the main hand, without emptying it.
     *
     * <p>Placing from the off hand only happens when the main hand has nothing
     * to say. Vanilla offers the main hand first, so a selected slot holding
     * trading stock gets used instead - and if that stock is a block, which a
     * great deal of it is, the right-click puts sixty-four quartz on the
     * ground rather than a chest.
     *
     * <p>So the hand is emptied by looking away from what it holds: an empty
     * hotbar slot is selected for the moment it takes to place, and the slot
     * that was selected before comes back afterwards. Nothing is moved,
     * nothing leaves the pack, and the off hand keeps the chest where the
     * trading cannot see it.
     */
    private static boolean freeTheHand(Minecraft client) {
        var inventory = client.player.getInventory();
        if (inventory.getSelectedItem().isEmpty()) return true;
        for (int i = 0; i < 9; i++) {
            if (!inventory.getItem(i).isEmpty()) continue;
            slotBefore = inventory.getSelectedSlot();
            inventory.setSelectedSlot(i);
            if (client.getConnection() != null) {
                client.getConnection().send(
                        new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(i));
            }
            DoughBayClient.LOGGER.info(
                    "DoughBay stash: hand was holding {}; selecting empty slot {} so the off hand places",
                    inventory.getItem(slotBefore).getHoverName().getString(), i + 1);
            return true;
        }
        say(client, "Every hotbar slot is full; cannot free the hand to place from the off hand");
        return false;
    }

    /**
     * Brings the chest out of the off hand into the empty hand.
     *
     * <p>Placing straight from the off hand ought to work - vanilla offers the
     * main hand first and falls through when it is empty - but ought to is not
     * the same as watched working, and this runs unattended on the account
     * that holds the stock. The swap removes the question: whatever vanilla
     * does about off-hand placement, a block in the main hand places.
     *
     * <p>It costs nothing to be sure here, because the hand was emptied first.
     * The swap exchanges an empty slot for the chest, so no trading stock ever
     * passes through the off hand - which is the one thing that would have
     * made this worse than the assumption it replaces.
     */
    private static void swapOffhandIntoHand(Minecraft client) {
        if (client.getConnection() == null) return;
        client.getConnection().send(new net.minecraft.network.protocol.game.ServerboundPlayerActionPacket(
                net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND,
                net.minecraft.core.BlockPos.ZERO,
                net.minecraft.core.Direction.DOWN));
        DoughBayClient.LOGGER.info("DoughBay stash: swapped the chest out of the off hand into the empty hand");
    }

    /**
     * Puts everything back the way it was found, chest included.
     *
     * <p>A run swaps the chest out of the off hand to place it, and the off
     * hand is the only slot the trading cannot see. So a run that gives up
     * after the swap leaves the chest in the hotbar, in plain view of a desk
     * that trades ender chests for a living - which is exactly what happened:
     * listed at 5,500, entirely correctly, and the shelf lost its key.
     *
     * <p>Anything the run borrowed goes back before the run ends.
     */
    private static void restoreTheHand(Minecraft client) {
        // The chest first: if it is still in hand, it belongs in the off hand.
        if (client.player != null && holdingEnderChest(client)
                && client.player.getOffhandItem().isEmpty()) {
            swapOffhandIntoHand(client);
            DoughBayClient.LOGGER.info(
                    "DoughBay stash: run ended holding the chest; put it back in the off hand");
        }
        if (slotBefore < 0) return;
        client.player.getInventory().setSelectedSlot(slotBefore);
        if (client.getConnection() != null) {
            client.getConnection().send(
                    new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(slotBefore));
        }
        slotBefore = -1;
    }
    private static float lookFrom;
    private static float lookTo;
    private static int lookTick;
    private static int lookTicks;
    /** Ticks to wait in the current holding state. */
    private static int hold;

    /**
     * Starts a turn, over time rather than in a tick.
     *
     * <p>This is the only thing in the mod that writes to where the player is
     * looking, and it is the part worth being careful about. A rotation that
     * arrives complete in one tick is not a fast hand, it is a thing no hand
     * can do - the reason aim checks are the cheapest ones to run. So the turn
     * takes about half a second, eases in and out the way a wrist does, and
     * carries a little noise so the curve is not perfectly smooth either.
     */
    private static void startLook(Minecraft client, float toPitch) {
        lookFrom = client.player.getXRot();
        lookTo = toPitch;
        lookTick = 0;
        lookTicks = 8 + (int) (Math.random() * 10);
    }

    /** Advances the turn; true once it has arrived. */
    private static boolean stepLook(Minecraft client) {
        lookTick++;
        float t = Math.min(1f, lookTick / (float) lookTicks);
        float eased = t * t * (3 - 2 * t);
        float pitch = lookFrom + (lookTo - lookFrom) * eased;
        if (t < 1f) pitch += (float) ((Math.random() - 0.5) * 0.3);
        client.player.setXRot(pitch);
        return lookTick >= lookTicks;
    }
    /**
     * Clicks still to send, as {slot, button}. Drained one a tick through the
     * driver's own gate, which refuses anything closer together - so a
     * three-click withdrawal takes about a second and looks like one.
     */
    private static final java.util.ArrayDeque<Click> pending = new java.util.ArrayDeque<>();
    /** What to do once the queue empties; the step after a run of clicks. */
    private static Runnable afterClicks;

    /** One click: which slot, which button, and which kind. */
    private record Click(int slot, int button, net.minecraft.world.inventory.ContainerInput input) {
    }

    /**
     * One action, and what it does depends on what is in front of you.
     *
     * <p>A container already open gets read; a held ender chest with the
     * ground underfoot starts the jump; otherwise the block you are looking at
     * gets right-clicked, which opens what is there - vanilla decides, from
     * the same call, exactly as it would for a hand on the mouse.
     */
    /**
     * Runs the whole thing, with nobody pressing anything.
     *
     * <p>The steps are the ones that were being driven by hand: bring a chest
     * forward, look down, jump and place it, land, open it, take one out of
     * the reserve, close, and put the view back where it was. What was added
     * to automate it was not the steps - those all worked - but the gaps
     * between them, which a person supplies for free by being slow.
     */
    public static boolean run(Minecraft client) {
        if (client == null || client.player == null || client.level == null) return false;
        if (!enabled() || stage != Stage.IDLE || !pending.isEmpty()) return false;
        if (System.currentTimeMillis() < notBefore) return false;
        if (!client.player.onGround() || client.gui.screen() != null) return false;
        pitchBefore = client.player.getXRot();
        // The clock starts now. It was left wherever the last run finished, so
        // the first run of a session worked and every one after it gave up on
        // the tick it started - the timeout had already elapsed before the
        // jump had begun.
        stageTick = 0;
        hold = 0;
        // However a run gets stuck - a server screen frozen open mid-move, a
        // step that never advances - it ends by this moment and hands the
        // player back. Nothing else guards that: the tick's own timeout sits
        // below the screen check and never runs while a screen is up, which is
        // exactly when a run hangs. Trading was frozen behind one for minutes.
        runDeadline = System.currentTimeMillis() + 20_000L + networkSlackTicks(client) * 50L;
        placedFromOffhand = false;
        lookSettled = false;
        if (enderChestUnderfoot(client)) {
            // Already standing on one: no chest needed, no jump needed.
            stage = Stage.LOOKING;
            startLook(client, 89f);
            DoughBayClient.LOGGER.info("DoughBay stash: a chest is already underfoot; looking down to open it");
            return true;
        }
        if (!holdingAnyEnderChest(client)) {
            // Nothing will change about this within a second, and saying so
            // every tick buries everything else in the log.
            notBefore = System.currentTimeMillis() + 60_000L;
            say(client, "No ender chest to place; waiting a minute before trying again");
            return false;
        }
        if (chestInOffhand(client) || holdingEnderChest(client)) {
            if (!freeTheHand(client)) return false;
            stage = Stage.LOOKING;
            startLook(client, PLACE_PITCH);
        } else if (equipChest(client)) {
            stage = Stage.EQUIPPING;
        } else {
            return false;
        }
        DoughBayClient.LOGGER.info("DoughBay stash: starting a run from y={} (network slack {} tick(s))",
                client.player.getY(), networkSlackTicks(client));
        return true;
    }

    /** Whether there is a chest to place after landing somewhere new. */
    public static boolean hasChest(Minecraft client) {
        return client != null && client.player != null && holdingAnyEnderChest(client);
    }

    /** Until when the shelf still counts as having the player, after a run ends. */
    private static long settlingUntil;

    /**
     * Whether the shelf has the player, including a moment either side.
     *
     * <p>A run is not finished when the last click lands. The chest was placed
     * a second ago, a screen is closing, the server is about to push the
     * auction page back open, and the view is still coming back up. Handing the
     * player straight back to the trading in that state is how a run and a
     * listing ended up interleaved - twelve things at once, none of them
     * finishing.
     *
     * <p>So the shelf keeps the player for a few seconds after it is done. A
     * few seconds is nothing against a trade every fifteen, and it is the
     * difference between a sequence and a scramble.
     */
    public static boolean busy() {
        if (stage != Stage.IDLE || !pending.isEmpty()) {
            settlingUntil = System.currentTimeMillis() + 4_000L;
            return true;
        }
        return System.currentTimeMillis() < settlingUntil;
    }

    public static void act(Minecraft client) {
        if (client == null || client.player == null) return;
        if (client.gui.screen()
                instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> open) {
            index(client, open);
            if (!holdingAnyEnderChest(client)) takeOneFromReserve(client, open);
            return;
        }
        if (stage != Stage.IDLE) {
            say(client, "Already mid-jump");
            return;
        }
        // Standing on one already? Then there is nothing to place. This is the
        // ordinary case after the first hop of a session, and jumping to put a
        // second chest on top of the first wastes a chest and spends a world
        // interaction to achieve nothing.
        if (client.player.onGround() && enderChestUnderfoot(client)) {
            openWhatYouAreLookingAt(client);
            return;
        }
        // Manual K just starts the same run the shelf uses; it places in
        // front now, with no jump anywhere.
        if (client.player.onGround() && holdingAnyEnderChest(client)) {
            run(client);
            return;
        }
        openWhatYouAreLookingAt(client);
    }

    /**
     * A chest is only fourteen-sixteenths tall, so standing on one puts your
     * feet at y.875 and {@code blockPosition()} rounds down onto the chest
     * itself rather than the ground under it. Looking only one block down
     * therefore found grass and decided to place a second chest on top of the
     * first. Both are checked.
     */
    private static boolean enderChestUnderfoot(Minecraft client) {
        var at = client.player.blockPosition();
        return isEnderChest(client, at) || isEnderChest(client, at.below());
    }

    private static boolean isEnderChest(Minecraft client, net.minecraft.core.BlockPos pos) {
        return client.level.getBlockState(pos).getBlock()
                == net.minecraft.world.level.block.Blocks.ENDER_CHEST;
    }

    /**
     * Takes one chest out of the reserve without emptying it.
     *
     * <p>Three clicks rather than one: pick the stack up, right-click a free
     * inventory slot to drop a single chest there, put the rest back. A
     * shift-click would be one click and would move all sixty-four, which
     * empties the shelf's own resupply into the pocket it is meant to refill.
     */
    private static boolean takeOneFromReserve(Minecraft client,
                                              net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> screen) {
        var inventory = client.player.getInventory();
        int reserve = -1;
        int free = -1;
        for (var slot : screen.getMenu().slots) {
            boolean mine = slot.container == inventory;
            var stack = slot.getItem();
            boolean isChest = !stack.isEmpty() && stack.getHoverName().getString()
                    .toLowerCase(java.util.Locale.ROOT).contains("ender chest");
            if (!mine && isChest && reserve < 0) reserve = slot.index;
            // One particular pocket, and only that one. The trading sweeps
            // skip it, so a chest sitting there is not stray stock and will
            // not be picked up and sold - which is what happened to the first
            // one, at five and a half thousand, entirely reasonably.
            if (mine && stack.isEmpty() && slot.getContainerSlot() == pocketSlot()) {
                free = slot.index;
            }
        }
        if (reserve < 0) {
            say(client, "No ender chests in the reserve - buy a stack");
            return false;
        }
        if (free < 0) {
            say(client, "The chest pocket (slot " + (pocketSlot() + 1) + ") is not free");
            return false;
        }
        pending.clear();
        var pickup = net.minecraft.world.inventory.ContainerInput.PICKUP;
        pending.add(new Click(reserve, 0, pickup));   // pick the stack up
        pending.add(new Click(free, 1, pickup));      // right-click drops exactly one
        pending.add(new Click(reserve, 0, pickup));   // put the rest back
        afterClicks = () -> {
            say(client, "Took one chest out of the reserve");
            // Not on the same tick as the last click. A container that shuts in
            // the same breath as the click that finished with it is a thing no
            // hand does, and leaving it open is worse - an open container
            // blocks the next auction step, which is how the session used to
            // jam.
            closeIn = 6 + (int) (Math.random() * 14);
        };
        DoughBayClient.LOGGER.info("DoughBay stash: taking one chest from reserve slot {} into slot {}",
                reserve, free);
        return true;
    }

    /**
     * Moves the stock the trading side asked for, in both directions.
     *
     * <p>One click a stack: a number key over a slot is a swap, and a
     * shift-click is a move, and vanilla will take a whole stack across on
     * either. There is no reason to pick stacks up and put them down one at a
     * time when the game has a gesture for exactly this and every player uses
     * it.
     *
     * <p>Putting away comes before fetching. The shelf has twenty-six usable
     * slots and the pack has rather more, so the space is worth clearing
     * before anything is brought back into it.
     */
    private static boolean moveStock(Minecraft client,
                                     net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> screen) {
        var wanted = toFetch;
        var unwanted = toPutAway;
        if (wanted.isEmpty() && unwanted.isEmpty()) return false;
        var inventory = client.player.getInventory();
        var quickMove = net.minecraft.world.inventory.ContainerInput.QUICK_MOVE;
        java.util.List<Click> away = new java.util.ArrayList<>();
        java.util.List<Click> back = new java.util.ArrayList<>();
        java.util.List<String> cameBack = new java.util.ArrayList<>();
        for (var slot : screen.getMenu().slots) {
            var stack = slot.getItem();
            if (stack.isEmpty()) continue;
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(stack.getItem()).toString();
            boolean mine = slot.container == inventory;
            // Any of the player's slots, hotbar included. The stock to put
            // away often sits in the hotbar - it was just pulled back from the
            // auction house - and restricting deposits to the main pack left
            // it there, so the visit found nothing to move and fired again
            // every few seconds. The chest lives in the off hand, which is not
            // among these slots, so it is never swept in; and the trading is
            // frozen while a run holds the player, so no reserved slot is in
            // use to protect.
            if (mine && unwanted.contains(id)) {
                away.add(new Click(slot.index, 0, quickMove));
            } else if (!mine && wanted.contains(id)) {
                back.add(new Click(slot.index, 0, quickMove));
                cameBack.add(id);
            }
        }
        if (away.isEmpty() && back.isEmpty()) return false;
        pending.clear();
        pending.addAll(away);
        pending.addAll(back);
        int puts = away.size();
        int gets = back.size();
        afterClicks = () -> {
            fetched.addAll(cameBack);
            say(client, "Put away " + puts + " stack(s) and fetched " + gets);
            closeIn = 6 + (int) (Math.random() * 14);
        };
        DoughBayClient.LOGGER.info("DoughBay stash: putting away {} stack(s) and fetching {}", puts, gets);
        return true;
    }

    /**
     * Puts a chest in the hand, for exactly as long as it takes to place it.
     *
     * <p>The withdrawal deliberately drops chests in the pack rather than the
     * hotbar, because the hotbar is the trading bench: one slot is reserved for
     * moving stacks to the auction house and the selected slot is what
     * {@code /ah sell} reads. So the chest only comes forward at the moment it
     * is needed, and placing it empties the slot again.
     *
     * <p>The move itself is the one a player makes: the pack open, and a number
     * key pressed over the stack. Vanilla calls that a swap, and it is a single
     * click rather than a pick-up-and-put-down pair.
     */
    private static boolean equipChest(Minecraft client) {
        if (chestInOffhand(client)) return true;
        var inventory = client.player.getInventory();
        var menu = client.player.inventoryMenu;
        int source = -1;
        for (var slot : menu.slots) {
            var stack = slot.getItem();
            if (stack.isEmpty() || slot.container != inventory) continue;
            if (!stack.getHoverName().getString().toLowerCase(java.util.Locale.ROOT)
                    .contains("ender chest")) {
                continue;
            }
            if (source < 0) source = slot.index;
        }
        if (source < 0) {
            say(client, "No ender chest in the pack to move to the off hand");
            return false;
        }
        if (!client.player.getOffhandItem().isEmpty()) {
            say(client, "The off hand is holding something else");
            return false;
        }
        client.gui.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(client.player));
        pending.clear();
        var pickup = net.minecraft.world.inventory.ContainerInput.PICKUP;
        pending.add(new Click(source, 0, pickup));               // pick the chest up
        pending.add(new Click(OFFHAND_MENU_SLOT, 0, pickup));    // and put it in the off hand
        afterClicks = () -> {
            say(client, "Chest in the off hand");
            closeIn = 6 + (int) (Math.random() * 14);
        };
        reactIn = rollReaction();
        DoughBayClient.LOGGER.info(
                "DoughBay stash: moving a chest from menu slot {} to the off hand in {} tick(s)",
                source, reactIn);
        return true;
    }

    /** Whether the pocket already has one, anywhere in it. */
    private static boolean holdingAnyEnderChest(Minecraft client) {
        if (chestInOffhand(client)) return true;
        for (var stack : client.player.getInventory().getNonEquipmentItems()) {
            if (!stack.isEmpty() && stack.getHoverName().getString()
                    .toLowerCase(java.util.Locale.ROOT).contains("ender chest")) {
                return true;
            }
        }
        return false;
    }

    private static boolean holdingEnderChest(Minecraft client) {
        return client.player.getMainHandItem().getHoverName().getString()
                .toLowerCase(java.util.Locale.ROOT).contains("ender chest");
    }

    /**
     * Jumps, and puts the chest in the hole underneath on the way up.
     *
     * <p>There is nowhere else to put it. A random teleport lands you on
     * whatever the world happens to hold - a slope, a wall, water, a tree -
     * and the one block you can always count on is the one you are standing
     * on. Stepping off it to place beside you means walking, which is the
     * packet nobody should be sending; jumping off it means the space is free
     * for a moment and you land back on what you put there.
     *
     * <p>It is also the most ordinary thing anybody does in this game. The
     * physics are vanilla's own - {@code jumpFromGround} and then the client
     * simulates the arc itself - so the movement that goes out is the arc a
     * hand on the space bar produces, not one this code invented.
     */
    private static void startJump(Minecraft client) {
        if (!enabled()) {
            say(client, "The stash desk is off (stash.enabled)");
            return;
        }
        if (client.gui.screen() != null) {
            // The server pushes the auction page back open between steps, so
            // this is a moment to wait through rather than a reason to stop -
            // and stopping here left the chest in the hotbar, which is how the
            // desk came to sell it. Waiting keeps it in hand; the tick guard
            // above holds everything still until the screen goes.
            // Not counted as a failure: a screen the server opened is a
            // moment to wait through, not a thing that went wrong.
            say(client, "A screen is open; waiting before the jump");
            restoreTheHand(client);
            stage = Stage.IDLE;
            notBefore = System.currentTimeMillis() + 5_000L;
            return;
        }
        // Room to jump into, before jumping.
        //
        // A jump needs a clear block above the head or it is cut short against
        // the ceiling, and a chest needs a full block of rise to fit
        // underneath. Standing under anything - a roof, an overhang, the
        // inside of a hole - means the jump can never clear the space, and the
        // run then spends twelve seconds waiting for a height it cannot reach.
        var head = client.player.blockPosition().above(2);
        if (!client.level.getBlockState(head).isAir()) {
            say(client, "No headroom to jump - " + client.level.getBlockState(head).getBlock()
                    .getName().getString() + " overhead");
            stage = Stage.IDLE;
            runFailed(client, "No headroom for the jump");
            return;
        }
        groundY = client.player.getY();
        highestY = groundY;
        stage = Stage.RISING;
        stageTick = 0;
        client.player.jumpFromGround();
        DoughBayClient.LOGGER.info("DoughBay stash: jumping from y={} to place a chest underneath", groundY);
    }

    /**
     * Carries the jump through. Called every client tick; does nothing at all
     * unless a jump is actually in progress.
     */
    /** Ticks left watching for a screen after an open; diagnostics only. */
    private static int watchTicks;
    /** Ticks a screen the server put up has been in the way of a moving stage. */
    private static int strayTicks;
    /** How long to notice it before closing it; re-rolled each time, like a person would vary. */
    private static int strayCloseAt = 8;
    private static boolean watchSaw;
    /** Ticks until the chest is closed again, once there is nothing left to do in it. */
    private static int closeIn;
    /**
     * Ticks to wait before touching anything in a screen that just opened.
     *
     * <p>The container arrives and the first click went out on the same tick,
     * which is the loudest thing in the whole sequence: a person has to see
     * the window, find the slot and move the mouse to it, and that is never
     * free. Everything else here was already spaced - the jump by physics, the
     * clicks by the driver's gate, the close by its own delay - and this was
     * the one gap left at zero.
     */
    private static int reactIn;
    /**
     * Item ids the trading side wants put away, and item ids it wants back.
     *
     * <p>The shelf does not decide what belongs on it. It is asked, at the one
     * moment it is already open, and the asking is done by the part that knows
     * what a thing is worth and whether there is a slot free for it.
     */
    private static volatile java.util.Set<String> toPutAway = java.util.Set.of();
    private static volatile java.util.Set<String> toFetch = java.util.Set.of();

    public static void putAway(java.util.Collection<String> itemIds) {
        toPutAway = java.util.Set.copyOf(itemIds);
    }

    public static void fetch(java.util.Collection<String> itemIds) {
        toFetch = java.util.Set.copyOf(itemIds);
    }

    /**
     * Item ids actually moved back into the pack, taken once.
     *
     * <p>The ledger claims its stock from here rather than from the request,
     * because asking for something and getting it are different events and
     * only the second one is a fact. Booking a position as back when the click
     * had not happened is how listings became orphans this morning.
     */
    private static final java.util.List<String> fetched =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    public static java.util.List<String> takeFetched() {
        synchronized (fetched) {
            if (fetched.isEmpty()) return java.util.List.of();
            var taken = java.util.List.copyOf(fetched);
            fetched.clear();
            return taken;
        }
    }

    /** Chests left in the reserve as of the last look inside; -1 before the first. */
    private static int reserveChests = -1;

    /** How many chests are left to place, as of the last time the shelf was open. */
    public static int reserveChests() {
        return reserveChests;
    }

    /**
     * Ticks of slack for the round trip to the server.
     *
     * <p>Every delay in this run was tuned in a single-player world, where the
     * server is in the same process and answers before the next tick. On a real
     * server each step has to cross the network twice, and the whole sequence
     * failed there while working locally every time - the same code, the same
     * order, only the distance different.
     *
     * <p>So the waits ask the connection how far away it is rather than
     * assuming. Nothing here is urgent: a few extra ticks against a trade every
     * fifteen seconds costs nothing, and it is the difference between a
     * sequence that completes and one that never gets past the second step.
     */
    private static int networkSlackTicks(Minecraft client) {
        try {
            if (client.getConnection() == null || client.player == null) return 0;
            var info = client.getConnection().getPlayerInfo(client.player.getUUID());
            if (info == null) return 0;
            int ping = Math.max(0, Math.min(1000, info.getLatency()));
            // A remote server that reports nothing is not a server with no
            // distance to it. DonutSMP leaves the tab-list latency at zero, so
            // asking politely returned zero and every delay stayed at the
            // value it had in a world with no network at all - which is
            // exactly the case this was written to fix.
            //
            // So a reported zero on a real server is treated as unknown rather
            // than as instant, and a hundred milliseconds is assumed. Being
            // wrong that way costs four ticks; being wrong the other way is
            // the whole evening.
            boolean remote = client.getSingleplayerServer() == null;
            if (remote && ping <= 0) ping = 100;
            // Two round trips' worth, in ticks, so the server has seen the
            // step before the next one is sent.
            return (int) Math.ceil(ping * 2 / 50.0);
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    /** How long a person takes to notice a window and reach for it. */
    private static int rollReaction() {
        double min = Tuning.get("pace.react_min_sec");
        double span = Math.max(0.0, Tuning.get("pace.react_max_sec") - min);
        double seconds = min + span * Math.random();
        // And sometimes longer, for the same reason clicks sometimes are: a
        // tight band around a mean is still a machine, just a politer one.
        if (Math.random() < 0.15) seconds += span * (1.0 + Math.random());
        return (int) Math.max(1, Math.round(seconds * 20));
    }

    public static void tick(Minecraft client) {
        if (client == null || client.player == null) return;
        if (reactIn > 0) reactIn--;
        if (closeIn > 0 && --closeIn == 0) {
            if (client.gui.screen()
                    instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) {
                client.player.closeContainer();
                say(client, "Closed the chest");
            }
        }
        if (watchTicks > 0) {
            watchTicks--;
            var screen = client.gui.screen();
            if (screen != null && !watchSaw) {
                if (screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> other
                        && !other.getTitle().getString().contains("Ender Chest")) {
                    // Not ours. The server pushed its own page open where the
                    // chest was expected, and taking it for the chest would
                    // have indexed an auction page as the shelf's contents.
                    // Close it and keep waiting for the real one.
                    DoughBayClient.LOGGER.info(
                            "DoughBay stash: '{}' opened after the click, not the chest; closing it and waiting",
                            other.getTitle().getString());
                    client.player.closeContainer();
                    return;
                }
                watchSaw = true;
                DoughBayClient.LOGGER.info("DoughBay stash: a screen opened {} tick(s) after the click: {}",
                        40 - watchTicks, screen.getClass().getName());
                // And get on with it. The key that opened this cannot be
                // pressed again while it is up - Minecraft only delivers
                // keybinds when no screen is showing - and waiting for a press
                // was never the plan anyway: the bot will open this on its own
                // and needs to know what to do next without being told.
                if (screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> open) {
                    index(client, open);
                    boolean work = moveStock(client, open);
                    if (!work && !chestInOffhand(client)) {
                        work = takeOneFromReserve(client, open);
                    }
                    if (work) {
                        reactIn = rollReaction();
                        DoughBayClient.LOGGER.info(
                                "DoughBay stash: waiting {} tick(s) before reaching into the chest", reactIn);
                    } else {
                        // Nothing to move and nowhere to put a chest: there is
                        // no reason to keep the container open, and keeping it
                        // open was a deadlock. The OPENING stage waits for the
                        // screen to close, and if nothing here closes it the
                        // whole run hangs with the trading frozen behind it.
                        DoughBayClient.LOGGER.info(
                                "DoughBay stash: nothing to do in the chest; closing it");
                        closeIn = 6 + (int) (Math.random() * 14);
                    }
                }
            } else if (screen == null && watchSaw) {
                DoughBayClient.LOGGER.warn(
                        "DoughBay stash: the screen was closed again {} tick(s) after the click - "
                                + "something on this side shut it, the interaction worked",
                        40 - watchTicks);
                watchTicks = 0;
            } else if (watchTicks == 0 && !watchSaw) {
                DoughBayClient.LOGGER.warn(
                        "DoughBay stash: no screen ever arrived after the click - the server did not open one");
            }
        }
        // Queued clicks first, and only while the container is still open -
        // and never before the eyes have caught up with it.
        if (!pending.isEmpty() && reactIn <= 0) {
            if (!(client.gui.screen()
                    instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> open)) {
                pending.clear();
            } else {
                Click next = pending.peekFirst();
                if (DoughBayClient.executionDriver()
                        .stashClick(client, open, next.slot(), next.button(), next.input())) {
                    pending.removeFirst();
                    if (pending.isEmpty()) {
                        Runnable then = afterClicks;
                        afterClicks = null;
                        if (then != null) then.run();
                    }
                }
                return;
            }
        }
        if (stage == Stage.IDLE) return;
        // The hard deadline first, before anything can return early. A run that
        // has outlived it is force-ended: any screen closed, the hand and view
        // restored, and a back-off set, so the interlock can never hold the
        // player - and freeze the trading - indefinitely.
        if (System.currentTimeMillis() > runDeadline) {
            if (client.gui.screen() != null) client.player.closeContainer();
            pending.clear();
            afterClicks = null;
            restoreTheHand(client);
            stage = Stage.IDLE;
            runFailed(client, "Run ran past its deadline (stuck); handing the player back");
            return;
        }
        // Nothing moves while a container is open. With a screen up the mouse
        // drives a cursor rather than the view, and walking and jumping are
        // simply not delivered - so a turn or a jump that happened anyway
        // would be a thing no player can do, sent from a client claiming to be
        // one. The clicks and the close timer above are the only things
        // allowed to run in here, because they are the only things a person
        // with a chest on screen can actually be doing.
        if (client.gui.screen() != null) {
            switch (stage) {
                case LOOKING, SWAPPING, RISING, PLACED, SETTLING, RESTORING -> {
                    // Nothing of ours is open in these stages, so whatever this
                    // is, the server put it up - the auction page it pushes
                    // back open a second after almost every step. Waiting for
                    // it to go away was the stall: nothing else closes it, so
                    // the run sat behind it until the deadline killed it. A
                    // person closes an unwanted popup and carries on; so does
                    // this, after the moment it takes to notice.
                    if (client.gui.screen()
                            instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> stray
                            && ++strayTicks >= strayCloseAt) {
                        DoughBayClient.LOGGER.info(
                                "DoughBay stash: '{}' opened mid-run during {}; closing it and carrying on",
                                stray.getTitle().getString(), stage);
                        client.player.closeContainer();
                        strayTicks = 0;
                        strayCloseAt = 6 + (int) (Math.random() * 10);
                    }
                    return;
                }
                default -> {
                }
            }
        }
        strayTicks = 0;
        stageTick++;
        if (stageTick > 200 + networkSlackTicks(client) * 8) {
            restoreTheHand(client);
            String how = stage == Stage.RISING
                    ? String.format(java.util.Locale.ROOT,
                    "Gave up: the jump only reached %.2f blocks and a chest needs a full one",
                    highestY - groundY)
                    : "Gave up on the run at " + stage;
            stage = Stage.IDLE;
            runFailed(client, how);
            return;
        }
        switch (stage) {
            case EQUIPPING -> {
                // The pack has to be shut before the placement; a right-click
                // would be swallowed by the open screen otherwise.
                if (closeIn > 0 || client.gui.screen() != null) return;
                stage = Stage.LOOKING;
                startLook(client, PLACE_PITCH);
            }
            case LOOKING -> {
                if (!lookSettled) {
                    if (!stepLook(client)) return;
                    // The turn is finished here, not landed. The place is aimed
                    // by the crosshair, and the server has to have been told
                    // where the crosshair is pointing before the block goes
                    // down - rotation travels in the movement packets, which
                    // are a round trip behind. Jumping on the tick the turn
                    // ends means placing against the direction the server still
                    // thinks we are facing.
                    lookSettled = true;
                    hold = 4 + (int) (Math.random() * 6) + networkSlackTicks(client);
                    return;
                }
                if (hold-- > 0) return;
                if (enderChestUnderfoot(client)) {
                    stage = Stage.SETTLING;
                    hold = rollReaction() + networkSlackTicks(client);
                    return;
                }
                // Straight from the off hand, if vanilla will do it.
                //
                // The hand is empty, and with an empty main hand vanilla falls
                // through and places whatever the off hand holds. If that
                // works the chest never enters the hotbar at all - and the
                // hotbar is the only place the trading can see it, which is
                // how four of them came to be sold tonight. A lock stops that
                // happening; never moving the chest means it cannot.
                //
                // The swap is still there as a fallback, tried only if the
                // jump comes back with the chest still in the off hand. So
                // whichever way vanilla behaves, the run works - and the safe
                // path is the one it tries first.
                // Something openable already under the crosshair - a chest we
                // placed, or one we are standing on - is opened, not placed
                // over.
                if (chestUnderCrosshair(client) || enderChestUnderfoot(client)) {
                    stage = Stage.SETTLING;
                    hold = rollReaction() + networkSlackTicks(client);
                    return;
                }
                // The chest has to be in the hand to place it. The run holds
                // the player - trading is frozen behind it - so the hand is a
                // safe place for it: the desk that sold four of them cannot run
                // while this does.
                if (!holdingEnderChest(client)) {
                    if (chestInOffhand(client)) {
                        swapOffhandIntoHand(client);
                        stage = Stage.SWAPPING;
                        hold = 4 + (int) (Math.random() * 8) + networkSlackTicks(client);
                        return;
                    }
                    restoreTheHand(client);
                    stage = Stage.IDLE;
                    runFailed(client, "No chest in hand to place");
                    return;
                }
                // Place against the ground in front. One right-click, no jump.
                HitResult look = client.hitResult;
                if (!(look instanceof BlockHitResult ground) || look.getType() != HitResult.Type.BLOCK) {
                    DoughBayClient.LOGGER.info(
                            "DoughBay stash: nothing to place against in front - pitch {}, hit {}",
                            String.format(java.util.Locale.ROOT, "%.1f", client.player.getXRot()),
                            look == null ? "null" : look.getType());
                    restoreTheHand(client);
                    stage = Stage.IDLE;
                    runFailed(client, "Nothing to place the chest against in front");
                    return;
                }
                // Never against the block directly underfoot: placing on its
                // top face puts the chest where the player is standing, and the
                // server refuses a block inside a body. The crosshair has to be
                // on the block ahead, so if it came back on our own column the
                // aim is too steep and there is nothing useful to do with it.
                var here = client.player.blockPosition();
                var target = ground.getBlockPos();
                if (target.getX() == here.getX() && target.getZ() == here.getZ()) {
                    DoughBayClient.LOGGER.info(
                            "DoughBay stash: crosshair is on our own block {}; nothing in front to place on",
                            target);
                    restoreTheHand(client);
                    stage = Stage.IDLE;
                    runFailed(client, "Aim landed on the block underfoot, not the one in front");
                    return;
                }
                rightClick(client);
                DoughBayClient.LOGGER.info("DoughBay stash: placed a chest against {} face {} ({} ahead)",
                        target, ground.getDirection(),
                        Math.abs(target.getX() - here.getX()) + Math.abs(target.getZ() - here.getZ()));
                stage = Stage.RISING;
                stageTick = 0;
            }
            case SWAPPING -> {
                if (hold-- > 0) return;
                if (!holdingEnderChest(client)) {
                    restoreTheHand(client);
                    stage = Stage.IDLE;
                    runFailed(client, "The chest did not come out of the off hand");
                    return;
                }
                // In hand now; back to LOOKING, which places straight away
                // because the aim is already settled and the wait is spent.
                stage = Stage.LOOKING;
                hold = 0;
            }
            case SETTLING -> {
                if (hold-- > 0) return;
                openWhatYouAreLookingAt(client);
                stage = Stage.OPENING;
                stageTick = 0;
            }
            case OPENING -> {
                // The shelf looks after itself once it is open - index, take
                // one, close - so this only waits for all of that to finish.
                if (client.gui.screen() != null || !pending.isEmpty() || closeIn > 0) {
                    stageTick = 0;
                    return;
                }
                stage = Stage.RESTORING;
                startLook(client, pitchBefore);
            }
            case RESTORING -> {
                if (!stepLook(client)) return;
                restoreTheHand(client);
                say(client, "Run finished");
                failedRuns = 0;
                stage = Stage.IDLE;
            }
            case RISING -> {
                // The chest was placed a moment ago; wait for the world to show
                // it under the crosshair, then open it. On a server that is a
                // round trip away, not the same tick.
                if (chestUnderCrosshair(client)) {
                    stage = Stage.SETTLING;
                    hold = rollReaction() + networkSlackTicks(client);
                    return;
                }
                if (stageTick > 40 + networkSlackTicks(client) * 4) {
                    // Placed but nothing came up: the spot would not take a
                    // chest - a slope, a wall, a claim. Give it up and back
                    // off rather than knock at the same block again.
                    restoreTheHand(client);
                    stage = Stage.IDLE;
                    runFailed(client, "Placed but no chest appeared in front");
                    return;
                }
            }
            case PLACED -> {
                // No longer used: nothing jumps, so nothing lands.
            }
            case IDLE -> {
            }
        }
    }

    /**
     * Writes down what is in the open container.
     *
     * <p>Reading only, and off the menu the client already holds: the slots
     * are in memory the moment the server sends them, so this sends nothing
     * at all. Only the container's own slots are listed - the player's
     * inventory shares the same menu and is not part of the shelf.
     */
    public static void index(Minecraft client,
                             net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> screen) {
        var inventory = client.player == null ? null : client.player.getInventory();
        int used = 0;
        int total = 0;
        StringBuilder held = new StringBuilder();
        java.util.Map<String, Integer> counted = new java.util.LinkedHashMap<>();
        for (var slot : screen.getMenu().slots) {
            if (inventory != null && slot.container == inventory) continue;
            total++;
            var stack = slot.getItem();
            if (stack.isEmpty()) continue;
            used++;
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(stack.getItem()).toString();
            counted.merge(id, stack.getCount(), Integer::sum);
            if (!held.isEmpty()) held.append(", ");
            held.append(stack.getCount()).append("x ").append(stack.getHoverName().getString());
        }
        // Written down while it is in front of us, because every decision
        // about the shelf is taken when it is not.
        Shelf.observed(counted);
        DoughBayClient.LOGGER.info("DoughBay stash: {} holds {} of {} slot(s): {}",
                screen.getTitle().getString(), used, total, held.isEmpty() ? "(empty)" : held);
        // How many chests are left to place. This is the shelf own resupply,
        // and running it to nothing is the only way to strand the bot: with no
        // chest there is nothing to place, and with nothing placed there is no
        // way to reach the ones inside.
        int chests = 0;
        for (var slot : screen.getMenu().slots) {
            if (inventory != null && slot.container == inventory) continue;
            var stack = slot.getItem();
            if (!stack.isEmpty() && stack.getHoverName().getString()
                    .toLowerCase(java.util.Locale.ROOT).contains("ender chest")) {
                chests += stack.getCount();
            }
        }
        reserveChests = chests;
        int low = (int) Math.round(Tuning.get("stash.restock_below"));
        if (chests <= low) {
            DoughBayClient.LOGGER.warn(
                    "DoughBay stash: only {} chest(s) left in the reserve (restock at {}); buy a stack",
                    chests, low);
            say(client, "Only " + chests + " chest(s) left in the reserve - buy a stack");
        }
        say(client, screen.getTitle().getString() + ": " + used + " of " + total
                + " slot(s) used" + (held.isEmpty() ? "" : " - " + held));
    }

    /**
     * Right-clicks whatever the player is looking at, if it is a block within
     * reach. Places what is held, or opens what is there.
     *
     * <p>Deliberately not "act on the block at these coordinates". Asking
     * vanilla what the player is looking at means the ray was cast from the
     * rotation the server already knows about, at the reach the server already
     * allows. A hand-built hit vector is the thing that gets caught, and there
     * is no reason to build one when the correct one is sitting in a field.
     */
    public static void openWhatYouAreLookingAt(Minecraft client) {
        if (client == null || client.player == null || client.level == null) return;
        if (!enabled()) {
            say(client, "The stash desk is off (stash.enabled)");
            return;
        }
        HitResult hit = client.hitResult;
        if (hit == null || hit.getType() != HitResult.Type.BLOCK || !(hit instanceof BlockHitResult block)) {
            say(client, "Nothing in reach - stand in front of the chest and look at it");
            return;
        }
        BlockState state = client.level.getBlockState(block.getBlockPos());
        String name = state.getBlock().getName().getString();
        String holding = client.player.getMainHandItem().isEmpty()
                ? "nothing" : client.player.getMainHandItem().getHoverName().getString();
        double reach = client.player.getEyePosition().distanceTo(block.getLocation());
        DoughBayClient.LOGGER.info(
                "DoughBay stash: right-clicking {} at {} holding {} - {} blocks from the eye, face {}, hit {}",
                name, block.getBlockPos(), holding,
                String.format(java.util.Locale.ROOT, "%.2f", reach),
                block.getDirection(), block.getLocation());
        DoughBayClient.LOGGER.info(
                "DoughBay stash: state before the click - sneaking={}, screen={}, onGround={}, pose={}",
                client.player.isShiftKeyDown(),
                client.gui.screen() == null ? "none" : client.gui.screen().getClass().getSimpleName(),
                client.player.onGround(), client.player.getPose());
        rightClick(client);
        say(client, "Right-clicked " + name + " holding " + holding + " ("
                + String.format(java.util.Locale.ROOT, "%.2f", reach) + " blocks)");
        // Watch for the screen the server is meant to send back. If one opens
        // and then goes again, something on this side is closing it and the
        // click was never the problem.
        watchTicks = 40;
        watchSaw = false;
    }

    /**
     * A right-click, done by the client's own hand.
     *
     * <p>Not a reconstruction of one. This calls the method the mouse button
     * calls, so the hand, the hit result, the arm swing and the delay between
     * clicks are all whatever vanilla would have done - there is no version of
     * this that behaves differently from a person clicking, because there is
     * no separate implementation to drift.
     */
    private static void rightClick(Minecraft client) {
        ((dev.doughbay.fabric.mixin.MinecraftInvoker) client).doughbay$startUseItem();
    }

    private static void say(Minecraft client, String text) {
        DoughBayClient.LOGGER.info("DoughBay stash: {}", text);
        if (client.player != null) {
            client.player.sendSystemMessage(Component.literal("[GoNuts] " + text));
        }
    }
}
