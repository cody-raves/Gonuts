package dev.doughbay.fabric;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static dev.doughbay.fabric.AutomatedExecutionDriver.PendingDispatch.*;
import static org.junit.jupiter.api.Assertions.*;

class AutomatedExecutionDriverCommandTest {
    private final AutomatedExecutionDriver driver = new AutomatedExecutionDriver();
    private final FakeTransport transport = new FakeTransport();

    @Test
    void sellRetriesHonorLongServerBackoffWithoutTimingOutInTheQueue() throws Exception {
        activateListing();
        transport.holdUntil = 220_000;
        driver.sendCommand(transport, "ah sell 15500", 100_000, 600);
        assertTrue(transport.sent.isEmpty());
        assertEquals(WAITING, driver.dispatchPendingCommand(transport, 160_000));
        assertEquals(WAITING, driver.dispatchPendingCommand(transport, 219_999));
        assertEquals(SENT, driver.dispatchPendingCommand(transport, 220_000));
        assertEquals(List.of("ah sell 15500"), transport.sent);
    }

    @Test
    void newlyStartedHoldAlsoProtectsAnAlreadyQueuedCommand() throws Exception {
        activateListing();
        set("lastCommandSentAt", 100_000L);
        driver.sendCommand(transport, "ah sell 15500", 100_100, 600);
        transport.holdUntil = 130_000;
        assertEquals(WAITING, driver.dispatchPendingCommand(transport, 106_000));
        assertEquals(SENT, driver.dispatchPendingCommand(transport, 130_000));
        assertEquals(List.of("ah sell 15500"), transport.sent);
    }

    @Test
    void backgroundCommandsCannotBreakTheServerHold() throws Exception {
        transport.holdUntil = 130_000;
        driver.sendWhenClear(transport, "pay Alex 100", 100_000, true);
        driver.drainExternalCommands(transport, 110_000);
        assertTrue(transport.sent.isEmpty());
        driver.drainExternalCommands(transport, 130_000);
        assertEquals(List.of("pay Alex 100"), transport.sent);
    }

    @Test
    void backgroundPaymentCannotDismissSellConfirmationAndSendsAfterOperationEnds() throws Exception {
        activateListing();
        transport.menu = true;
        driver.sendWhenClear(transport, "pay Alex 100", 100_000, true);
        driver.drainExternalCommands(transport, 200_000);
        assertTrue(transport.menu);
        assertEquals(0, transport.closes);
        assertTrue(transport.sent.isEmpty());

        clearOperation();
        driver.drainExternalCommands(transport, 300_000);
        assertEquals(0, transport.closes, "Even a late dialog must not be dismissed by payroll");
        transport.menu = false;
        driver.drainExternalCommands(transport, 400_000);
        assertEquals(List.of("pay Alex 100"), transport.sent);
    }

    @Test
    void backgroundCommandAlsoWaitsWhileConfirmationHasNotArrived() throws Exception {
        activateListing();
        driver.sendWhenClear(transport, "pay Alex 100", 100_000, true);
        driver.drainExternalCommands(transport, 200_000);
        assertTrue(transport.sent.isEmpty());
        clearOperation();
        driver.drainExternalCommands(transport, 300_000);
        assertEquals(List.of("pay Alex 100"), transport.sent);
    }

    @Test
    void deferredSellGetsItsFullResponseBudgetOnlyAfterDispatch() throws Exception {
        activateListing();
        set("lastCommandSentAt", 100_000L);
        set("ticksInPhase", 139);
        driver.sendCommand(transport, "ah sell 20000", 100_100, 10_000);
        assertEquals(WAITING, driver.dispatchPendingCommand(transport, 108_000));
        assertTrue(transport.sent.isEmpty());
        assertEquals(139, get("ticksInPhase"));
        assertEquals(SENT, driver.dispatchPendingCommand(transport, 110_000));
        assertEquals(0, get("ticksInPhase"));
        assertEquals(110_000L, driver.lastCommandSentAt());
        assertEquals(List.of("ah sell 20000"), transport.sent);
        assertEquals(NONE, driver.dispatchPendingCommand(transport, 120_000));
        assertEquals(1, transport.sent.size(), "A dispatched sale must not be replayed");
    }

    @Test
    void menuClosingDoesNotPretendCommandWasSent() throws Exception {
        activateListing();
        transport.menu = true;
        transport.stickyMenu = true;
        driver.sendCommand(transport, "ah sell 20000", 100_000, 600);
        assertEquals(WAITING, driver.dispatchPendingCommand(transport, 100_600));
        assertEquals(0, driver.lastCommandSentAt());
        assertTrue(transport.sent.isEmpty());
        assertEquals(EXPIRED, driver.dispatchPendingCommand(transport, 145_000));
        assertTrue(transport.sent.isEmpty());
    }

    @Test
    void changedSendHistoryDoesNotGetBypassedWhenQueuedCommandBecomesDue() throws Exception {
        activateListing();
        set("lastCommandSentAt", 100_000L);
        driver.sendCommand(transport, "ah sell 20000", 100_100, 600);
        set("lastCommandSentAt", 100_500L);
        assertEquals(WAITING, driver.dispatchPendingCommand(transport, 100_600));
        assertEquals(SENT, driver.dispatchPendingCommand(transport, 101_100));
        assertEquals(List.of("ah sell 20000"), transport.sent);
    }

    @Test
    void terminalCleanupDiscardsQueuedSaleAndStaleCooldownRetry() throws Exception {
        activateListing();
        set("lastCommandSentAt", 100_000L);
        driver.sendCommand(transport, "ah sell 20000", 100_100, 600);
        clearOperation();
        activateListing();
        driver.observeGameMessage("You need to wait another 0.25 seconds to execute a command");
        assertEquals(NONE, driver.dispatchPendingCommand(transport, 110_000));
        assertTrue(transport.sent.isEmpty());
        assertEquals(100_000L, driver.lastCommandSentAt(), "Keep pacing history across operations");
    }

    @Test
    void cooldownForPreviousCommandCannotReplacePendingSale() throws Exception {
        activateListing();
        driver.sendCommand(transport, "ah", 100_000, 600);
        driver.sendCommand(transport, "ah sell 20000", 100_100, 600);
        driver.observeGameMessage("You need to wait another 0.25 seconds to execute a command");
        assertEquals(SENT, driver.dispatchPendingCommand(transport, 100_600));
        assertEquals(List.of("ah", "ah sell 20000"), transport.sent);
    }

    @Test
    void explicitServerCooldownRetriesDispatchedSaleButNotAfterConfirmation() throws Exception {
        activateListing();
        long now = System.currentTimeMillis();
        driver.sendCommand(transport, "ah sell 20000", now, 600);
        driver.observeGameMessage("You need to wait another 0.25 seconds to execute a command");
        assertEquals(SENT, driver.dispatchPendingCommand(transport, now + 20_000));
        enumValue("phase", "WAITING_FOR_COMPLETION");
        driver.observeGameMessage("You need to wait another 0.25 seconds to execute a command");
        assertEquals(NONE, driver.dispatchPendingCommand(transport, now + 30_000));
        assertEquals(List.of("ah sell 20000", "ah sell 20000"), transport.sent);
    }

    private void activateListing() throws Exception {
        enumValue("operation", "LIST");
        enumValue("phase", "WAITING_FOR_CONFIRMATION");
    }

    private void clearOperation() throws Exception {
        var method = AutomatedExecutionDriver.class.getDeclaredMethod("clearTarget");
        method.setAccessible(true);
        method.invoke(driver);
    }

    private void enumValue(String name, String value) throws Exception {
        Field field = field(name);
        for (Object candidate : field.getType().getEnumConstants()) {
            if (((Enum<?>) candidate).name().equals(value)) {
                field.set(driver, candidate);
                return;
            }
        }
        fail("Missing enum value " + value);
    }

    private void set(String name, Object value) throws Exception { field(name).set(driver, value); }
    private Object get(String name) throws Exception { return field(name).get(driver); }
    private Field field(String name) throws Exception {
        Field field = AutomatedExecutionDriver.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static final class FakeTransport implements AutomatedExecutionDriver.CommandTransport {
        boolean menu;
        boolean stickyMenu;
        int closes;
        long holdUntil;
        final List<String> sent = new ArrayList<>();
        public boolean menuOpen() { return menu; }
        public long notBeforeMillis() { return holdUntil; }
        public void closeMenu() { closes++; if (!stickyMenu) menu = false; }
        public void send(String command) { sent.add(command); }
    }
}
