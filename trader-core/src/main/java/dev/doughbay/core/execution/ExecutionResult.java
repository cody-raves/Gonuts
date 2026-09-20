package dev.doughbay.core.execution;

/**
 * Outcome of asking a driver to act.
 *
 * @param outcome what happened
 * @param detail  human-readable explanation, always populated on a refusal so
 *                the reason is visible rather than a silent no-op
 */
public record ExecutionResult(Outcome outcome, String detail) {

    public enum Outcome {
        /** The driver does not perform this action at all. */
        REFUSED,
        /** Simulated only; nothing left the client. */
        SIMULATED,
        /** Prepared and verified; the player must now act. */
        AWAITING_PLAYER,
        /** The player's action was observed and matched the intent. */
        CONFIRMED,
        /** Something did not line up; the trade should not proceed. */
        ABORTED
    }

    public static ExecutionResult refused(String detail) {
        return new ExecutionResult(Outcome.REFUSED, detail);
    }

    public static ExecutionResult awaitingPlayer(String detail) {
        return new ExecutionResult(Outcome.AWAITING_PLAYER, detail);
    }

    public static ExecutionResult aborted(String detail) {
        return new ExecutionResult(Outcome.ABORTED, detail);
    }

    public boolean ok() {
        return outcome != Outcome.REFUSED && outcome != Outcome.ABORTED;
    }
}
