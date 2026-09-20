package dev.doughbay.fabric;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SidebarBalanceTest {

    @Test
    void readsTheSidebarMoneyLine() {
        // Seen live: "$ 789M" under the player's name.
        assertArrayEquals(new long[] {789_000_000L, 1_000_000L}, SidebarBalance.parse("$ 789M"));
        assertArrayEquals(new long[] {2_600_000L, 100_000L}, SidebarBalance.parse("Money: $2.6M"));
        assertArrayEquals(new long[] {45_000L, 100L}, SidebarBalance.parse("$ 45K"));
        assertArrayEquals(new long[] {1_234L, 1L}, SidebarBalance.parse("$1,234"));
    }

    @Test
    void ignoresLinesWithoutMoney() {
        assertNull(SidebarBalance.parse("cody_raves"));
        assertNull(SidebarBalance.parse("Kills: 12"));
        assertNull(SidebarBalance.parse(null));
    }
}
