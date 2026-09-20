package dev.doughbay.core.analysis;

import dev.doughbay.core.model.MarketStats;
import dev.doughbay.core.model.Sale;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Heuristics for markets that look engineered rather than organic. Completed
 * sales don't expose buyer identity, so wash trading can't be proven — these
 * checks only lower confidence; they never raise it.
 */
public final class ManipulationDetector {

    public record Assessment(double penalty, List<String> warnings) {
        /** 1.0 = clean; multiply into confidence. */
    }

    /**
     * @param kept sales that survived outlier filtering, oldest-first or any order
     */
    public Assessment assess(MarketStats stats, List<Sale> kept) {
        List<String> warnings = new ArrayList<>();
        double penalty = 1.0;

        // One or two sellers producing most completed sales.
        if (!kept.isEmpty()) {
            Map<String, Integer> bySeller = new HashMap<>();
            for (Sale s : kept) bySeller.merge(s.sellerUuid(), 1, Integer::sum);
            int top = bySeller.values().stream().mapToInt(Integer::intValue).max().orElse(0);
            double topShare = (double) top / kept.size();
            if (bySeller.size() >= 2 && topShare > 0.6) {
                warnings.add(String.format("Single seller accounts for %.0f%% of completed sales", topShare * 100));
                penalty *= 0.6;
            }
            if (bySeller.size() == 1 && kept.size() >= 5) {
                warnings.add("All completed sales come from one seller");
                penalty *= 0.4;
            }
        }

        // Price jumped without volume growth: strong upward trend on thin flow.
        if (stats.trend() > 0.30 && stats.salesPerHour() < 2.0) {
            warnings.add(String.format("Price up %.0f%% recently on low volume", stats.trend() * 100));
            penalty *= 0.6;
        }

        // Extreme sales clustered together show up as a high outlier share.
        int total = stats.sampleCount() + stats.outlierCount();
        if (total > 0 && (double) stats.outlierCount() / total > 0.25) {
            warnings.add("High share of outlier-priced sales");
            penalty *= 0.7;
        }

        // "Cheap" only against stale data.
        long newestAge = stats.calculatedAt() - stats.newestSaleAt();
        if (stats.newestSaleAt() > 0 && newestAge > 6L * 3600_000L) {
            warnings.add("Newest comparable sale is over 6 hours old");
            penalty *= 0.5;
        }

        return new Assessment(penalty, warnings);
    }
}
