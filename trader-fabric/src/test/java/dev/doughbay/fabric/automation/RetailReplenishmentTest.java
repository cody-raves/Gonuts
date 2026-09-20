package dev.doughbay.fabric.automation;

import dev.doughbay.core.model.*;
import dev.doughbay.fabric.AutomatedExecutionDriver;
import dev.doughbay.fabric.Tuning;
import dev.doughbay.storage.RetailLot;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RetailReplenishmentTest {
    private static Position stock(long id, int count, PositionStatus status) {
        return new Position(id, "REAL", "minecraft:diamond_block", StackBucket.of(count), count,
                count * 10_000L, count * 20_000L, 10_000, status == PositionStatus.LISTED ? 11_000 : 0,
                0, 0, Double.NaN, status);
    }
    @SuppressWarnings("unchecked")
    @Test void cappedRemainderWaitsThenReplenishesWithoutBeingBookedAgain() throws Exception {
        var controller = new AutomationSessionController(new AutomatedExecutionDriver());
        Position remainder = stock(3, 63, PositionStatus.PURCHASED);
        ((Map<Long, RetailLot>)field(controller, "retailLots")).put(3L, new RetailLot(3, 1, 20_000));
        ((List<Position>)field(controller, "recoveredQueue")).add(remainder);
        List<Position> listed = (List<Position>)field(controller, "openListings");
        double enabled = Tuning.get("list.singles"), cap = Tuning.get("list.singles_max");
        try {
            Tuning.set("list.singles", 1);
            Tuning.set("list.singles_max", 2);
            listed.add(stock(4, 1, PositionStatus.LISTED));
            listed.add(stock(5, 1, PositionStatus.LISTED));
            assertTrue(waiting(controller, remainder));
            assertEquals(List.of(remainder), controller.stockInHand());
            assertTrue(hasStock(controller, "minecraft:diamond_block"));
            assertFalse(hasStock(controller, "minecraft:emerald"));
            listed.removeFirst(); // Sale confirmation frees one single listing.
            assertFalse(waiting(controller, remainder));
            assertEquals(63, controller.stockInHand().getFirst().quantity());
            assertEquals(630_000, controller.stockInHand().getFirst().purchasePrice());
            Tuning.set("list.singles", 0);
            assertTrue(waiting(controller, remainder), "disabling peeling must not dump reserved stock as a stack");
            assertFalse(waiting(controller, stock(99, 64, PositionStatus.PURCHASED)), "ordinary stock must keep moving");
        } finally {
            Tuning.set("list.singles", enabled);
            Tuning.set("list.singles_max", cap);
        }
    }
    private static boolean waiting(AutomationSessionController c, Position p) throws Exception {
        var m = c.getClass().getDeclaredMethod("retailWaiting", Position.class, long.class);
        m.setAccessible(true);
        return (boolean)m.invoke(c, p, 20_000L);
    }
    private static boolean hasStock(AutomationSessionController c, String item) throws Exception {
        var m = c.getClass().getDeclaredMethod("hasRetailStock", String.class);
        m.setAccessible(true);
        return (boolean)m.invoke(c, item);
    }
    private static Object field(Object target, String name) throws Exception {
        var f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }
}
