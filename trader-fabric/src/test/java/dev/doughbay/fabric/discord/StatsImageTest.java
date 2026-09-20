package dev.doughbay.fabric.discord;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatsImageTest {

    @Test
    void rendersAPageToPng() throws Exception {
        StatsImage.Page page = new StatsImage.Page("Money", "SCANNING · 13:20 · bank $9.1M", "green",
                List.of(new String[] {"Bank", "$9.1M", "gold"}, new String[] {"Profit 1h", "+$1.0M", "green"},
                        new String[] {"Sales 1h", "58"}),
                43, 90,
                new String[] {"Time", "Last sales", "Cost", "Sold", "Profit"}, new boolean[] {false, false, true, true, true},
                List.of(new String[] {"13:19", "map x64", "$339K", "$420K", "+$81K"},
                        new String[] {"13:18", "hopper x64", "$107K", "$134K", "+$27K"}),
                "Watching recent listings");
        byte[] png = StatsImage.render(page);
        assertTrue(png.length > 2000, "png is " + png.length + " bytes");
        assertEquals((byte) 0x89, png[0]);
        assertEquals('P', png[1]);
        assertEquals('N', png[2]);
        assertEquals('G', png[3]);
    }

    @Test
    void moneyFormatting() {
        assertEquals("$1.28M", DiscordBridge.money(1_277_409));
        assertEquals("$22.9K", DiscordBridge.money(22_900));
        assertEquals("$500", DiscordBridge.money(500));
        assertEquals("-$1,200", DiscordBridge.money(-1_200));
        assertEquals("+$81.0K", DiscordBridge.signed(81_000));
        assertEquals("polished blackstone", DiscordBridge.item("minecraft:polished_blackstone"));
        assertEquals("shulker box", DiscordBridge.item("minecraft:shulker_box#5168e324"));
    }
}
