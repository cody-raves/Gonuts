package dev.doughbay.fabric.automation;

/**
 * How one buy order is sized and capped, by the way its goods are sold.
 *
 * <p>An item that sells best one at a time has a fixed allotment of single
 * listings; whatever a lot holds beyond it goes up as stacks, at what a stack
 * fetches. Pricing every order off the single price alone bought emerald
 * blocks at 30,000 each when a stack of them sells for 12,000 a block: the
 * singles cleared 500 apiece and the stack half could never sell above its
 * cost. Sizing off the single market alone counted one item as "a stack", so
 * the same blocks were ordered three and twelve at a time.
 *
 * <p>So an order is one of two things. Where the going price sits under what a
 * stack resells for, it is whole stacks, the singles being the better half of
 * them. Where only the singles pay, it is exactly the single allotment and no
 * more. Pure arithmetic, no game types, so it is tested directly.
 */
final class OrderShape {
    enum Mode {
        /** Whole stacks; what does not fit the single slots sells as stacks at a profit. */
        STACKS,
        /** Only what the single slots hold; a stack of it would not pay. */
        SINGLES,
        /** Neither pays at the going price. */
        NONE
    }

    /** One step above the best standing order, which is all it takes to be filled first; 0 with no order to beat. */
    static long topOfBook(long standing) {
        if (standing <= 0) return 0;
        long step = Math.max(1L, (long) Math.ceil(standing * 0.01));
        return standing > Long.MAX_VALUE - step ? 0 : standing + step;
    }

    /** How an order at {@code offer} each can be sold at a profit. */
    static Mode mode(long offer, long singleCeiling, long stackCeiling) {
        if (offer <= 0) return Mode.NONE;
        if (offer <= stackCeiling) return Mode.STACKS;
        if (offer <= singleCeiling) return Mode.SINGLES;
        return Mode.NONE;
    }

    /**
     * The most an order of {@code count} may pay per item. An order that fits
     * the single slots is worth the single price; a larger one has a stack
     * part, and that part has to pay by itself. Zero means unknown, which
     * neither cancels an order nor lets it be moved up.
     */
    static long ceilingUnit(int count, int singleSlots, long singleCeiling, long stackCeiling) {
        long stack = Math.max(0, stackCeiling);
        if (singleCeiling <= 0 || singleSlots <= 0) return stack;
        if (count <= singleSlots) return Math.max(singleCeiling, stack);
        return stack;
    }

    /** Whole stacks that fit the money: never part of one, and at least one for the caller to refuse. */
    static int fitStacks(int stacks, int per, long unit, long budget) {
        if (stacks <= 1 || per <= 0 || unit <= 0) return Math.max(1, stacks);
        long each = unit * per;
        long fit = each <= 0 ? 1 : budget / each;
        return (int) Math.max(1, Math.min(stacks, fit));
    }

    private OrderShape() { }
}
