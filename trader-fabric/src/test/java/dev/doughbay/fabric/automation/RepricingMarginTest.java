package dev.doughbay.fabric.automation;

import dev.doughbay.core.analysis.FeeConfig;
import dev.doughbay.core.automation.ContinuousAutomationPolicy;
import dev.doughbay.core.model.*;
import dev.doughbay.fabric.AutomatedExecutionDriver;
import dev.doughbay.fabric.Tuning;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RepricingMarginTest {
    private static AutomationSessionController controller() throws Exception {
        var c = new AutomationSessionController(new AutomatedExecutionDriver());
        var f = c.getClass().getDeclaredField("policy"); f.setAccessible(true);
        f.set(c, ContinuousAutomationPolicy.fromConfigValues(1000, 100000000, 10000000,
                500, 5, 20, 1, 0, 1440, 90));
        return c;
    }
    private static Position stack(long purchased, long target) {
        return new Position(1, "REAL", "minecraft:crying_obsidian", StackBucket.of(64),
                64, 716800, target, purchased, purchased + 1000, 0, 0, Double.NaN, PositionStatus.LISTED);
    }
    private static long call(AutomationSessionController c, String method, Position p, long now) throws Exception {
        var m = c.getClass().getDeclaredMethod(method, Position.class, long.class); m.setAccessible(true);
        return (long)m.invoke(c, p, now);
    }
    @Test void expensiveStackDoesNotFallToFiveHundredProfitAfterSeventeenMinutes() throws Exception {
        var c = controller();
        double roi = Tuning.get("reprice.minimum_roi_pct");
        try {
            Tuning.set("reprice.minimum_roi_pct", 5);
            long floor = call(c, "repriceFloor", stack(1000000, 766300), 2020000);
            assertEquals(752640, floor);
            c.setAuctionFeePolicy(true, new FeeConfig(1000, 2, 1));
            long withFees = call(c, "repriceFloor", stack(1000000, 766300), 2020000);
            assertTrue(new FeeConfig(1000, 2, 1).netSale(withFees) >= 752640);
        } finally { Tuning.set("reprice.minimum_roi_pct", roi); }
    }
    @Test void missingMarketDoesNotCauseBlindCutsBeforeStopLoss() throws Exception {
        var c = controller();
        double market = Tuning.get("reprice.to_market"), dead = Tuning.get("demand.dead_pct");
        try {
            Tuning.set("reprice.to_market", 1); Tuning.set("demand.dead_pct", 0);
            assertEquals(766300, call(c, "repricedTarget", stack(1000000, 766300), 2020000));
        } finally { Tuning.set("reprice.to_market", market); Tuning.set("demand.dead_pct", dead); }
    }
    @Test void existingStopLossStillReleasesTheProfitFloor() throws Exception {
        var c = controller();
        double stop = Tuning.get("reprice.stop_loss_min"), below = Tuning.get("reprice.below_cost_min");
        try {
            Tuning.set("reprice.stop_loss_min", 240); Tuning.set("reprice.below_cost_min", 720);
            assertEquals(716800, call(c, "repriceFloor", stack(1000000, 766300), 1000000 + 241 * 60000L));
        } finally { Tuning.set("reprice.stop_loss_min", stop); Tuning.set("reprice.below_cost_min", below); }
    }
}
