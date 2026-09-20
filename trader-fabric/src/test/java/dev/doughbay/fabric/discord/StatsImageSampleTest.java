package dev.doughbay.fabric.discord;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Writes a sample page to build/discord-sample.png so the look can be checked by eye. */
class StatsImageSampleTest {

    @Test
    void writesSample() throws Exception {
        StatsImage.Page page = new StatsImage.Page("Money", "SCANNING · 13:20 · bank $9.1M", "green",
                List.of(new String[] {"Bank", "$9.1M", "gold"}, new String[] {"Profit 1h", "$1.0M", "green"},
                        new String[] {"Profit 24h", "$12.3M", "green"}, new String[] {"Sales 1h", "58"},
                        new String[] {"Sales 6h", "295 · $4.96M"}, new String[] {"Revenue 24h", "$62.6M"},
                        new String[] {"Listed", "49 · cost $5.62M"}, new String[] {"Asking", "$7.64M"},
                        new String[] {"Trades", "53 this session"}),
                43, 90,
                new String[] {"Time", "Last sales", "Cost", "Sold", "Profit"}, new boolean[] {false, false, true, true, true},
                List.of(new String[] {"13:19", "map x64", "$339K", "$420K", "+$81.0K"},
                        new String[] {"13:18", "hopper x64", "$107K", "$134K", "+$27.0K"},
                        new String[] {"13:16", "totem of undying x1", "$84.1K", "$90.0K", "+$5.9K"},
                        new String[] {"13:12", "polished blackstone x64", "$72.0K", "$105K", "+$33.0K"}),
                List.of("minecraft:map", "minecraft:hopper", "minecraft:totem_of_undying", "minecraft:polished_blackstone"),
                "Watching recent listings for 62 market(s)");
        byte[] png = StatsImage.render(page);
        Path out = Path.of("build", "discord-sample.png");
        Files.createDirectories(out.getParent());
        Files.write(out, png);
        assertTrue(Files.size(out) > 2000);
    }
}
