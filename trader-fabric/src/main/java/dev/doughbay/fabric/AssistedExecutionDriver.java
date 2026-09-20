package dev.doughbay.fabric;

import dev.doughbay.core.execution.ExecutionDriver;
import dev.doughbay.core.execution.ExecutionResult;
import dev.doughbay.core.execution.ExecutionStatus;
import dev.doughbay.core.model.Opportunity;
import dev.doughbay.core.model.Position;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Assisted execution: the mod works out the trade and checks your work, and
 * <em>you</em> perform every in-game action.
 *
 * <p>Concretely it copies the exact search term to your clipboard, then — once
 * you have the auction screen open — reads what is actually on screen and tells
 * you whether the item in front of you matches the one that was analyzed. It
 * synthesizes no clicks, sends no packets, and types nothing. Reading the
 * contents of a screen you opened yourself is observation, the same as looking
 * at it.
 *
 * <p>This is the strongest form of help that does not put your account at
 * risk: DonutSMP's rules ban scripts, macros and auto-clickers, so a driver
 * that clicked for you would be a ban waiting to happen.
 *
 * <p>The value here is catching the mistakes that actually cost money — buying
 * the wrong stack size, a near-identical item with different enchantments, or
 * a listing whose price moved since it was analyzed.
 */
public final class AssistedExecutionDriver implements ExecutionDriver {

    private final AtomicReference<Opportunity> armed = new AtomicReference<>();
    private final AtomicReference<String> lastMessage = new AtomicReference<>("Idle");

    @Override
    public ExecutionResult buy(Opportunity opportunity) {
        armed.set(opportunity);
        String searchTerm = searchTermFor(opportunity);
        copyToClipboard(searchTerm);
        String message = String.format(Locale.ROOT,
                "Armed: %s x%d at %,d. Search term copied — open the auction, paste, and verify before buying.",
                shortName(opportunity.listing().itemId()), opportunity.listing().itemCount(),
                opportunity.buyPrice());
        lastMessage.set(message);
        return ExecutionResult.awaitingPlayer(message);
    }

    @Override
    public ExecutionResult list(Position position, long price) {
        String message = String.format(Locale.ROOT,
                "List %s x%d at %,d. DoughBay cannot list for you — run the sell command yourself.",
                shortName(position.itemKey()), position.quantity(), price);
        lastMessage.set(message);
        copyToClipboard(String.valueOf(price));
        return ExecutionResult.awaitingPlayer(message);
    }

    @Override
    public ExecutionStatus inspect() {
        Opportunity target = armed.get();
        if (target == null) {
            return new ExecutionStatus(ExecutionStatus.State.IDLE, lastMessage.get(), false);
        }
        return new ExecutionStatus(ExecutionStatus.State.PREPARED, lastMessage.get(), true);
    }

    @Override
    public void emergencyStop() {
        armed.set(null);
        lastMessage.set("Stopped — nothing armed");
    }

    @Override
    public String modeName() {
        return "Assisted (you click)";
    }

    /** The opportunity currently armed for verification, or null. */
    public Opportunity armedOpportunity() {
        return armed.get();
    }

    /**
     * One line of the verification checklist: what to confirm, and whether it
     * currently holds.
     */
    public record Check(String label, String expected, String observed, Status status) {
        public enum Status { MATCH, MISMATCH, UNKNOWN }
    }

    /**
     * Compares the armed opportunity against whatever container screen the
     * player currently has open.
     *
     * <p>Item identity and stack size are checked generically and are reliable.
     * Price is deliberately reported as UNKNOWN: it lives in item lore in a
     * server-specific format, and guessing at that format would produce
     * confident-looking wrong answers. Mapping it needs a real capture of
     * DonutSMP's auction GUI — until then the price line says "read it
     * yourself", which is honest.
     */
    public List<Check> verifyAgainstOpenScreen() {
        List<Check> checks = new ArrayList<>();
        Opportunity target = armed.get();
        if (target == null) {
            return checks;
        }

        String expectedItem = shortName(target.listing().itemId());
        String expectedCount = String.valueOf(target.listing().itemCount());
        String expectedPrice = String.format(Locale.ROOT, "%,d", target.buyPrice());

        Screen screen = Minecraft.getInstance().gui.screen();
        if (!(screen instanceof AbstractContainerScreen<?> container)) {
            checks.add(new Check("Auction screen open", "an auction/container screen",
                    "no container screen open", Check.Status.UNKNOWN));
            checks.add(new Check("Item", expectedItem, "—", Check.Status.UNKNOWN));
            checks.add(new Check("Stack size", expectedCount, "—", Check.Status.UNKNOWN));
            checks.add(new Check("Price", expectedPrice, "read it on screen", Check.Status.UNKNOWN));
            return checks;
        }

        checks.add(new Check("Auction screen open", "an auction/container screen",
                "open", Check.Status.MATCH));

        // Look for a slot holding the exact item, and report the closest match.
        ItemStack best = ItemStack.EMPTY;
        for (var slot : container.getMenu().slots) {
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            String id = itemId(stack);
            if (id.equals(target.listing().itemId())) {
                if (best.isEmpty() || stack.getCount() == target.listing().itemCount()) {
                    best = stack;
                }
            }
        }

        if (best.isEmpty()) {
            checks.add(new Check("Item", expectedItem,
                    "not visible on this screen", Check.Status.MISMATCH));
            checks.add(new Check("Stack size", expectedCount, "—", Check.Status.UNKNOWN));
        } else {
            checks.add(new Check("Item", expectedItem, shortName(itemId(best)), Check.Status.MATCH));
            boolean countMatches = best.getCount() == target.listing().itemCount();
            checks.add(new Check("Stack size", expectedCount, String.valueOf(best.getCount()),
                    countMatches ? Check.Status.MATCH : Check.Status.MISMATCH));
        }

        // Price parsing is server-specific; say so rather than guess.
        checks.add(new Check("Price", expectedPrice,
                "verify on screen — not parsed", Check.Status.UNKNOWN));
        return checks;
    }

    private static String itemId(ItemStack stack) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(stack.getItem()).toString();
    }

    /** The text to paste into the auction search box. */
    public static String searchTermFor(Opportunity opportunity) {
        String bare = opportunity.listing().itemId();
        bare = bare.contains(":") ? bare.substring(bare.indexOf(':') + 1) : bare;
        return bare.replace('_', ' ');
    }

    private static void copyToClipboard(String text) {
        try {
            Minecraft.getInstance().keyboardHandler.setClipboard(text);
        } catch (Exception e) {
            DoughBayClient.LOGGER.warn("DoughBay could not set the clipboard: {}", e.toString());
        }
    }

    private static String shortName(String itemId) {
        String bare = itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1) : itemId;
        return bare.replace('_', ' ');
    }
}
