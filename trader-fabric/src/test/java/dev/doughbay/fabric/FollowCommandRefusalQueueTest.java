package dev.doughbay.fabric;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FollowCommandRefusalQueueTest {
    @TempDir Path directory;

    @Test void unsupportedBundledNamesCannotStopTheRemainingAllowedBatch() throws Exception {
        double enabled = Tuning.get("follow.enabled"), cap = Tuning.get("follow.max");
        try {
            var driver = new AutomatedExecutionDriver();
            var follows = new Follows(directory, driver);
            follows.followKnownBots();
            var selected = new ArrayList<String>();
            for (int i = 0; i <= Follows.knownBots().size(); i++) {
                String next = follows.nextRequestedName();
                if (next == null) break;
                assertTrue(next.matches("[A-Za-z0-9]+"), "unsupported name reached the command handoff");
                selected.add(next);
                // Synthetic server confirmation in this isolated temporary fixture only.
                state(follows).put(next.toLowerCase(Locale.ROOT), "FOLLOWED");
            }
            assertEquals(Follows.knownBots().stream().filter(n -> n.matches("[A-Za-z0-9]+" )).toList(), selected);
            assertNull(follows.nextRequestedName());
            assertFalse(driver.externalCommandQueued());
            assertFalse(follows.status().contains("stopped:"));
            assertTrue(follows.status().contains("126 names refused by command policy"));
            assertTrue(follows.status().contains("0 list names waiting"));
            assertEquals(selected.size(), state(follows).size(), "refusals must not fabricate server outcomes");
        } finally { Tuning.set("follow.enabled", enabled); Tuning.set("follow.max", cap); }
    }

    @Test void anUnsupportedAutomaticCounterpartyDoesNotBlockTheNextAllowedOne() {
        var driver = new AutomatedExecutionDriver();
        var follows = new Follows(directory, driver);
        follows.request(List.of("Unsupported_Name", "Allowed123"));
        assertEquals("Allowed123", follows.nextRequestedName());
        assertNull(follows.nextRequestedName());
        assertFalse(follows.status().contains("stopped:"));
        assertFalse(driver.externalCommandQueued());
    }

    @Test void retryQueueAppliesTheSameRefusalBeforeSelectingTheNextName() throws Exception {
        var driver = new AutomatedExecutionDriver();
        var follows = new Follows(directory, driver);
        @SuppressWarnings("unchecked") var retries = (ArrayDeque<String>) field("retries").get(follows);
        retries.add("Unsupported_Name");
        retries.add("Allowed123");
        assertEquals("Allowed123", follows.nextRequestedName());
        assertNull(follows.nextRequestedName());
        assertTrue(state(follows).isEmpty());
        assertFalse(driver.externalCommandQueued());
    }

    @SuppressWarnings("unchecked") private Map<String, String> state(Follows follows) throws Exception {
        return (Map<String, String>) field("state").get(follows);
    }
    private Field field(String name) throws Exception {
        Field f = Follows.class.getDeclaredField(name); f.setAccessible(true); return f;
    }
}
