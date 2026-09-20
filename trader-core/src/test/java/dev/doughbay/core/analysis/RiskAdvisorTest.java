package dev.doughbay.core.analysis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskAdvisorTest {

    private static final RiskConfig RISK = RiskConfig.defaults(); // minRoi 12, minConf 0.5, holdCap 2h

    private static boolean touches(List<RiskAdvisor.Suggestion> s, String field) {
        return s.stream().anyMatch(x -> x.field().equals(field));
    }

    @Test
    void staysSilentBelowTheSampleBar() {
        // Great numbers but only 3 trades: no verdict.
        List<RiskAdvisor.Suggestion> s = RiskAdvisor.review(
                3, 90.0, 40.0, 3, 0, 30.0, RISK, 30);
        assertTrue(s.isEmpty());
    }

    @Test
    void suggestsLooseningRoiWhenTooStrict() {
        // 80% win rate, median ROI 30% vs a 12% floor: loosen.
        List<RiskAdvisor.Suggestion> s = RiskAdvisor.review(
                100, 80.0, 30.0, 80, 20, 20.0, RISK, 30);
        assertTrue(touches(s, "minimumRoiPercent"));
        RiskAdvisor.Suggestion roi = s.stream()
                .filter(x -> x.field().equals("minimumRoiPercent")).findFirst().orElseThrow();
        assertTrue(roi.suggested() < roi.current(), "should lower the floor");
    }

    @Test
    void suggestsTighteningConfidenceOnPoorWinRate() {
        List<RiskAdvisor.Suggestion> s = RiskAdvisor.review(
                60, 40.0, 8.0, 24, 36, 30.0, RISK, 30);
        assertTrue(touches(s, "minimumConfidence"));
        RiskAdvisor.Suggestion conf = s.stream()
                .filter(x -> x.field().equals("minimumConfidence")).findFirst().orElseThrow();
        assertTrue(conf.suggested() > conf.current(), "should raise the confidence floor");
    }

    @Test
    void demandsMoreMarginWhenLosersLeadAndRoiUndershoots() {
        List<RiskAdvisor.Suggestion> s = RiskAdvisor.review(
                60, 42.0, 5.0, 25, 35, 30.0, RISK, 30);
        RiskAdvisor.Suggestion roi = s.stream()
                .filter(x -> x.field().equals("minimumRoiPercent")).findFirst().orElseThrow();
        assertTrue(roi.suggested() > roi.current(), "should raise the ROI floor");
    }

    @Test
    void flagsAnOptimisticHoldEstimate() {
        // holdCap is 2h; actual median 6h.
        List<RiskAdvisor.Suggestion> s = RiskAdvisor.review(
                50, 65.0, 15.0, 35, 15, 360.0, RISK, 30);
        assertTrue(touches(s, "maximumExpectedHoldHours"));
    }

    @Test
    void healthyResultsProduceNoSuggestions() {
        // Win rate fine but not extreme, ROI near target, holds within the cap.
        List<RiskAdvisor.Suggestion> s = RiskAdvisor.review(
                80, 62.0, 14.0, 50, 30, 90.0, RISK, 30);
        assertEquals(List.of(), s);
    }
}
