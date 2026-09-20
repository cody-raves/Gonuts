package dev.doughbay.fabric;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.doughbay.core.analysis.RiskConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opportunity thresholds are tuning, not permission. Unlike the execution
 * gates, a malformed value here falls back to the shipped default rather than
 * locking: a bad ROI floor produces poor suggestions, never an unauthorized
 * action, so refusing to start would be the worse failure.
 */
class DoughBayConfigRiskPolicyTest {

    private static JsonNode json(String body) throws Exception {
        return new ObjectMapper().readTree(body);
    }

    @Test
    void absentRiskObjectKeepsEveryDefault() throws Exception {
        RiskConfig parsed = DoughBayConfig.parseRiskConfig(
                json("{\"collectionEnabled\":true}"), RiskConfig.defaults());

        assertEquals(RiskConfig.defaults(), parsed);
    }

    @Test
    void percentagesAreConvertedToTheFractionsTheAnalyzerUses() throws Exception {
        RiskConfig parsed = DoughBayConfig.parseRiskConfig(json("""
                {"risk":{"minimumConfidencePercent":25,"maximumVolatilityPercent":80}}
                """), RiskConfig.defaults());

        assertEquals(0.25, parsed.minimumConfidence(), 1e-9);
        assertEquals(0.80, parsed.maximumVolatility(), 1e-9);
    }

    @Test
    void minutesAreConvertedToMillis() throws Exception {
        RiskConfig parsed = DoughBayConfig.parseRiskConfig(
                json("{\"risk\":{\"maximumSaleAgeMinutes\":15}}"), RiskConfig.defaults());

        assertEquals(15L * 60_000L, parsed.maximumNewestSaleAgeMillis());
    }

    @Test
    void unsetFieldsKeepTheirDefaultsAlongsideOverriddenOnes() throws Exception {
        RiskConfig parsed = DoughBayConfig.parseRiskConfig(
                json("{\"risk\":{\"minimumConfidencePercent\":10}}"), RiskConfig.defaults());

        assertEquals(0.10, parsed.minimumConfidence(), 1e-9);
        assertEquals(RiskConfig.defaults().minimumProfit(), parsed.minimumProfit());
        assertEquals(RiskConfig.defaults().minimumSamples(), parsed.minimumSamples());
    }

    @Test
    void outOfRangeAndNonNumericValuesFallBackWithoutLocking() throws Exception {
        RiskConfig parsed = DoughBayConfig.parseRiskConfig(json("""
                {"risk":{"minimumConfidencePercent":500,"minimumSamples":"twenty",
                         "maximumHoldHours":-3,"minimumProfit":8000}}
                """), RiskConfig.defaults());

        assertEquals(RiskConfig.defaults().minimumConfidence(), parsed.minimumConfidence());
        assertEquals(RiskConfig.defaults().minimumSamples(), parsed.minimumSamples());
        assertEquals(RiskConfig.defaults().maximumExpectedHoldHours(),
                parsed.maximumExpectedHoldHours());
        // The one valid sibling still applies: one bad field is not a reason
        // to discard the whole object.
        assertEquals(8_000L, parsed.minimumProfit());
    }

    @Test
    void booleansAreOverridable() throws Exception {
        RiskConfig parsed = DoughBayConfig.parseRiskConfig(
                json("{\"risk\":{\"skipFallingMarkets\":false}}"), RiskConfig.defaults());

        assertFalse(parsed.skipFallingMarkets());
        assertTrue(parsed.commoditiesOnly(), "unset booleans keep their default");
    }

    @Test
    void aNonObjectRiskValueIsIgnored() throws Exception {
        assertEquals(RiskConfig.defaults(),
                DoughBayConfig.parseRiskConfig(json("{\"risk\":\"loose\"}"),
                        RiskConfig.defaults()));
    }

    @Test
    void aByteOrderMarkDoesNotDiscardTheWholeConfig() throws Exception {
        // Windows editors and PowerShell's Out-File prepend a UTF-8 BOM.
        // Jackson rejects it, so the whole file fails to parse and every
        // setting silently reverts to its default while the file on screen
        // looks perfectly correct.
        String withBom = "﻿{\"risk\":{\"minimumConfidencePercent\":25}}";

        RiskConfig parsed = DoughBayConfig.parseRiskConfig(
                new ObjectMapper().readTree(DoughBayConfig.stripByteOrderMark(withBom)),
                RiskConfig.defaults());

        assertEquals(0.25, parsed.minimumConfidence(), 1e-9);
    }

    @Test
    void strippingLeavesOrdinaryJsonUntouched() {
        assertEquals("{\"a\":1}", DoughBayConfig.stripByteOrderMark("{\"a\":1}"));
        assertEquals("", DoughBayConfig.stripByteOrderMark(""));
        assertEquals("{\"a\":1}", DoughBayConfig.stripByteOrderMark("﻿{\"a\":1}"));
    }
}
