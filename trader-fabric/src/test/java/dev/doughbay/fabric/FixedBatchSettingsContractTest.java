package dev.doughbay.fabric;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FixedBatchSettingsContractTest {
    private static String code(String value) throws Exception {
        return SettingsCode.encode(("{\"v\":1,\"n\":{\"orders.fixed_batch\":"+value+"},\"t\":{}}")
                .getBytes(StandardCharsets.UTF_8));
    }
    @Test void acceptsBothClosedValuesAndOldCodesWithTheKeyAbsent() throws Exception {
        assertDoesNotThrow(()->SettingsCode.validateReportCode(code("0")));
        assertDoesNotThrow(()->SettingsCode.validateReportCode(code("1")));
        assertDoesNotThrow(()->SettingsCode.validateReportCode(SettingsCode.encode(
                "{\"v\":1,\"n\":{},\"t\":{}}".getBytes(StandardCharsets.UTF_8))));
        assertTrue(SettingsCode.REPORT_KEYS.contains("orders.fixed_batch"));
    }
    @Test void invalidToggleCannotMutateAnySettings() throws Exception {
        double before=Tuning.get("orders.fixed_batch");
        for(String value:new String[]{"-1","2","0.5","true","\"1\"","null","1e400"}) {
            assertThrows(IllegalArgumentException.class,()->SettingsCode.validateReportCode(code(value)),value);
            assertFalse(SettingsCode.apply(code(value)).ok(),value);
            assertEquals(before,Tuning.get("orders.fixed_batch"));
        }
        assertEquals(0,Tuning.setting("orders.fixed_batch").clamp(Double.NaN));
        assertEquals(0,Tuning.setting("orders.fixed_batch").clamp(Double.POSITIVE_INFINITY));
    }
    @Test void telemetryHasAnExplicitNumericSettingChangeToken() {
        var trace=new DecisionTrace();double before=Tuning.get("orders.fixed_batch");
        try {
            Tuning.set("orders.fixed_batch",0);trace.observeSettings(1000);
            Tuning.set("orders.fixed_batch",1);trace.observeSettings(2100);
            var report=trace.report(2200,ReportTopic.GENERAL,11000);
            assertTrue(report.contains("code=SET_ORDERS_FIXED_BATCH"));
            assertTrue(report.contains("selected=1000"));
        }finally{Tuning.set("orders.fixed_batch",before);}
    }
}
