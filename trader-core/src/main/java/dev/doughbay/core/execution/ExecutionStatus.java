package dev.doughbay.core.execution;

/**
 * What a driver is currently doing, and whether it is safe to proceed.
 *
 * @param state       coarse lifecycle state
 * @param description what the player should know right now
 * @param armed       true when a trade is prepared and awaiting the player
 */
public record ExecutionStatus(State state, String description, boolean armed) {

    public enum State {
        DISABLED,
        IDLE,
        PREPARED,
        VERIFYING,
        ABORTED
    }

    public static ExecutionStatus disabled() {
        return new ExecutionStatus(State.DISABLED, "Execution disabled — analysis only", false);
    }

    public static ExecutionStatus idle() {
        return new ExecutionStatus(State.IDLE, "Idle", false);
    }
}
