package dev.doughbay.storage;

/**
 * Audited proof that the player explicitly verified inventory/auction state
 * outside DoughBay and completed two separate confirmation steps.
 */
public record AutomationManualResolution(
        long positionId,
        long firstConfirmedAt,
        long secondConfirmedAt,
        long evidenceObservedAt,
        String evidenceSummary,
        String resolutionDetail
) {
    public AutomationManualResolution {
        evidenceSummary = evidenceSummary == null ? "" : evidenceSummary.strip();
        resolutionDetail = resolutionDetail == null ? "" : resolutionDetail.strip();
        if (positionId < 0 || firstConfirmedAt <= 0
                || secondConfirmedAt <= firstConfirmedAt
                || evidenceObservedAt <= 0 || evidenceObservedAt > secondConfirmedAt) {
            throw new IllegalArgumentException(
                    "manual resolution requires ordered confirmation/evidence timestamps");
        }
        if (evidenceSummary.isBlank() || resolutionDetail.isBlank()) {
            throw new IllegalArgumentException(
                    "manual resolution requires evidence and an audit detail");
        }
    }
}
