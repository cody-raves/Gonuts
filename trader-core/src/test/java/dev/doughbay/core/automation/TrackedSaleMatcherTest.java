package dev.doughbay.core.automation;

import dev.doughbay.core.model.Position;
import dev.doughbay.core.model.PositionStatus;
import dev.doughbay.core.model.StackBucket;
import dev.doughbay.core.text.DonutSaleMessageParser;
import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackedSaleMatcherTest {
    private final TrackedSaleMatcher matcher = new TrackedSaleMatcher();

    @Test
    void exactItemQuantityAndPriceMatch() {
        Position listed = position(16);
        assertTrue(matcher.match(listed,
                new DonutSaleMessageParser.SaleNotice(
                        "Buyer_1", "Ender Pearl", OptionalInt.of(16), 2_000)).matched());
    }

    @Test
    void omittedQuantityMatchesOnItemAndPrice() {
        var noCount = new DonutSaleMessageParser.SaleNotice(
                "Buyer_1", "Ender Pearl", OptionalInt.empty(), 2_000);
        assertTrue(matcher.match(position(16), noCount).matched());
        assertTrue(matcher.match(position(1), noCount).matched());
        assertFalse(matcher.match(position(16), new DonutSaleMessageParser.SaleNotice(
                "Buyer_1", "Ender Pearl", OptionalInt.of(32), 2_000)).matched());
    }

    @Test
    void abbreviatedPriceBandAndBedrockBuyerMatch() {
        var banded = new DonutSaleMessageParser.SaleNotice(
                ".Bedrock_1", "Ender Pearl", OptionalInt.empty(), 2_000, 2_099);
        assertTrue(matcher.match(position(16), banded).matched());
        Position dearer = new Position(1, "REAL", "minecraft:ender_pearl",
                StackBucket.X16, 16, 1_000, 2_100,
                1, 2, 0, 0, Double.NaN, PositionStatus.LISTED);
        assertFalse(matcher.match(dearer, banded).matched());
    }

    @Test
    void wrongPriceOrRichFingerprintNeverClosesPosition() {
        assertFalse(matcher.match(position(16),
                new DonutSaleMessageParser.SaleNotice(
                        "Buyer_1", "Ender Pearl", OptionalInt.of(16), 1_999)).matched());
        Position rich = new Position(1, "REAL", "minecraft:ender_pearl#abc",
                StackBucket.X16, 16, 1_000, 2_000,
                1, 2, 0, 0, Double.NaN, PositionStatus.LISTED);
        assertFalse(matcher.match(rich,
                new DonutSaleMessageParser.SaleNotice(
                        "Buyer_1", "Ender Pearl", OptionalInt.of(16), 2_000)).matched());
    }

    @Test
    void clientDisplayNameAliasMatches() {
        TrackedSaleMatcher aliased = new TrackedSaleMatcher(
                key -> key.equals("minecraft:ender_pearl") ? "Pearl of Ender" : "");
        var notice = new DonutSaleMessageParser.SaleNotice(
                "Buyer_1", "Pearl of Ender", OptionalInt.empty(), 2_000);
        assertTrue(aliased.match(position(1), notice).matched());
        assertTrue(aliased.match(position(1), new DonutSaleMessageParser.SaleNotice(
                "Buyer_1", "Ender Pearl", OptionalInt.empty(), 2_000)).matched());
        assertFalse(matcher.match(position(1), notice).matched());
    }

    private static Position position(int quantity) {
        return new Position(1, "REAL", "minecraft:ender_pearl",
                StackBucket.of(quantity), quantity, 1_000, 2_000,
                1, 2, 0, 0, Double.NaN, PositionStatus.LISTED);
    }
}
