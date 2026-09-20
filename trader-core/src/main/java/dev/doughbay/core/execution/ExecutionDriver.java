package dev.doughbay.core.execution;

import dev.doughbay.core.model.Opportunity;
import dev.doughbay.core.model.Position;

/**
 * Pluggable execution back end, so the market engine stays independent of how
 * (or whether) a trade is actually carried out.
 *
 * <p>Implementations differ in who acts:
 * <ul>
 *   <li>{@link DisabledExecutionDriver} — refuses everything. The default.</li>
 *   <li>A paper driver — simulates fills against observed data.</li>
 *   <li>An assisted driver — prepares and verifies a trade that <em>the
 *       player</em> then performs by hand.</li>
 * </ul>
 *
 * <p>The core module ships only the contract and safe disabled implementation.
 * A client integration may supply an execution driver for a private server or
 * another environment where automation is explicitly permitted, but it must
 * remain authorization-gated and fail closed. The interface is not permission
 * to bypass server rules, rate limits, confirmations, or anti-cheat systems.
 */
public interface ExecutionDriver {

    /**
     * Act on an opportunity. What "act" means is driver-specific: refuse,
     * simulate, or prepare and verify for a human.
     */
    ExecutionResult buy(Opportunity opportunity);

    /** Act on relisting a held position at {@code price}. */
    ExecutionResult list(Position position, long price);

    /** Current driver state, for display and for safety interlocks. */
    ExecutionStatus inspect();

    /** Abandon anything in flight and return to a safe idle state. */
    void emergencyStop();

    /** Human-readable mode name for the UI. */
    String modeName();
}
