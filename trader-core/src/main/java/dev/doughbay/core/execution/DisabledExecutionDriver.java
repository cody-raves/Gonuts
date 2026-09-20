package dev.doughbay.core.execution;

import dev.doughbay.core.model.Opportunity;
import dev.doughbay.core.model.Position;

/**
 * Refuses every action. The default driver, so nothing can act on a trade
 * unless a mode is deliberately selected.
 */
public final class DisabledExecutionDriver implements ExecutionDriver {

    private static final String REASON =
            "Execution is disabled. DoughBay is analyzing only.";

    @Override
    public ExecutionResult buy(Opportunity opportunity) {
        return ExecutionResult.refused(REASON);
    }

    @Override
    public ExecutionResult list(Position position, long price) {
        return ExecutionResult.refused(REASON);
    }

    @Override
    public ExecutionStatus inspect() {
        return ExecutionStatus.disabled();
    }

    @Override
    public void emergencyStop() {
        // Nothing can be in flight.
    }

    @Override
    public String modeName() {
        return "Disabled";
    }
}
