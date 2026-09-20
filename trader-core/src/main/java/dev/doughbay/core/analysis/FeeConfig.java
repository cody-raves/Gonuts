package dev.doughbay.core.analysis;

/**
 * Auction-house costs. All zero until empirically confirmed on the target
 * server (Phase 0 reconnaissance); keep configurable, never hardcode.
 */
public record FeeConfig(
        long listingFeeFlat,
        double listingFeePercent,
        double saleTaxPercent
) {
    public static FeeConfig zero() {
        return new FeeConfig(0, 0.0, 0.0);
    }

    /** Net proceeds of selling at {@code grossSalePrice}. */
    public double netSale(double grossSalePrice) {
        double fee = listingFeeFlat + grossSalePrice * listingFeePercent / 100.0;
        double tax = grossSalePrice * saleTaxPercent / 100.0;
        return grossSalePrice - fee - tax;
    }
}
