package dev.doughbay.fabric.discord;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renders every panel page to build/panel/*.png with real item textures, so
 * the look can be reviewed without starting the game. Skips quietly when the
 * client jar named by {@code doughbay.clientJar} is not there.
 */
class PanelPreviewTest {

    private static final Path OUT = Path.of("build", "panel");

    @Test
    void rendersEveryPage() throws Exception {
        Files.createDirectories(OUT);
        loadTextures();

        write("1-money", new StatsImage.Page("Money", "SCANNING · 13:45 · bank $9.1M", "green",
                List.of(new String[] {"Bank", "$9.1M", "gold"}, new String[] {"Profit 1h", "$1.20M", "green"},
                        new String[] {"Profit 24h", "$14.4M", "green"}, new String[] {"Sales 1h", "69"},
                        new String[] {"Sales 6h", "366 · $6.05M"}, new String[] {"Revenue 24h", "$76.3M"},
                        new String[] {"Listed", "32 · cost $3.61M"}, new String[] {"Asking", "$4.88M"},
                        new String[] {"Trades", "112 this session"}),
                32, 90,
                new String[] {"Time", "Last sales", "Cost", "Sold", "Profit"}, new boolean[] {false, false, true, true, true},
                List.of(new String[] {"13:44", "map x64", "$339K", "$420K", "+$81.0K"},
                        new String[] {"13:41", "hopper x64", "$107K", "$134K", "+$27.0K"},
                        new String[] {"13:38", "totem of undying x1", "$84.1K", "$90.0K", "+$5.9K"},
                        new String[] {"13:35", "polished blackstone x64", "$72.0K", "$105K", "+$33.0K"},
                        new String[] {"13:31", "golden apple x16", "$377K", "$400K", "+$22.6K"},
                        new String[] {"13:28", "experience bottle x64", "$358K", "$380K", "+$21.5K"}),
                List.of("minecraft:map", "minecraft:hopper", "minecraft:totem_of_undying",
                        "minecraft:polished_blackstone", "minecraft:golden_apple", "minecraft:experience_bottle"), 1,
                "Watching recent listings for 62 market(s)"));

        write("2-listings", new StatsImage.Page("Listings", "SCANNING · 13:45 · bank $9.1M", "green",
                List.of(new String[] {"Open", "32 of 90"}, new String[] {"Newest 6", "cost $1.42M"},
                        new String[] {"Asking", "$1.94M", "gold"}),
                32, 90,
                new String[] {"Item", "Cost", "Ask", "Margin", "Up for"}, new boolean[] {false, true, true, true, true},
                List.of(new String[] {"map x57", "$304K", "$331K", "+$26.7K", "4 min"},
                        new String[] {"tnt x64", "$112K", "$139K", "+$27.0K", "11 min"},
                        new String[] {"iron ingot x64", "$21.0K", "$22.9K", "+$1.9K", "18 min"},
                        new String[] {"glowstone x16", "$61.7K", "$72.9K", "+$11.2K", "24 min"},
                        new String[] {"emerald block x1", "$35.0K", "$43.0K", "+$8.0K", "40 min"}),
                List.of("minecraft:map", "minecraft:tnt", "minecraft:iron_ingot", "minecraft:glowstone", "minecraft:emerald_block"),
                "Repricing map x57 after 20 min unsold"));

        write("3-opps", new StatsImage.Page("Opportunities", "SCANNING · 13:45 · bank $9.1M", "green",
                List.of(new String[] {"Signals", "84"}, new String[] {"Markets", "97"}, new String[] {"Feed", "LIVE MARKET DATA — OK", "green"}),
                0, 0,
                new String[] {"Item", "Buy", "Sell", "Profit", "ROI", "Conf"}, new boolean[] {false, true, true, true, true, true},
                List.of(new String[] {"respawn anchor x32", "$476K", "$695K", "+$220K", "46%", "43%"},
                        new String[] {"golden apple x32", "$476K", "$630K", "+$154K", "32%", "44%"},
                        new String[] {"end crystal x32", "$476K", "$600K", "+$124K", "26%", "47%"},
                        new String[] {"map x64", "$396K", "$420K", "+$23.8K", "6%", "41%"},
                        new String[] {"iron ingot x64", "$21.0K", "$23.0K", "+$2.0K", "10%", "43%"}),
                List.of("minecraft:respawn_anchor", "minecraft:golden_apple", "minecraft:end_crystal",
                        "minecraft:map", "minecraft:iron_ingot"),
                "84 signals · click a row in game for detail"));

        write("4-orders", new StatsImage.Page("Orders", "BID_DESK · 13:45 · bank $9.1M", "blue",
                List.of(new String[] {"Book", "9,142 open orders"}, new String[] {"Read", "13:37"},
                        new String[] {"Bids", "on · 40 slots", "green"}, new String[] {"Your bids", "6 · holding $412K"}),
                0, 0,
                new String[] {"Your order", "Asked", "Delivered", "Each", "State"}, new boolean[] {false, true, true, true, false},
                List.of(new String[] {"map", "64", "64", "$5.36K", "complete"},
                        new String[] {"blackstone", "64", "41", "$787", "23 to go"},
                        new String[] {"tnt", "64", "64", "$1.89K", "complete"},
                        new String[] {"glowstone", "16", "0", "$3.86K", "16 to go"},
                        new String[] {"iron ingot", "64", "64", "$327", "complete"}),
                List.of("minecraft:map", "minecraft:blackstone", "minecraft:tnt", "minecraft:glowstone", "minecraft:iron_ingot"),
                "Bid desk: collecting 64 map"));

        write("5-rivals", new StatsImage.Page("Rivals", "SCANNING · 13:45 · bank $9.1M", "green",
                List.of(new String[] {"Rivals", "12 tracked · 9 active"}, new String[] {"Bots", "4"},
                        new String[] {"Underdog", "1 shadowed"}),
                0, 0,
                new String[] {"Name", "Sales", "Revenue", "Margin", "Status"}, new boolean[] {false, true, true, true, false},
                List.of(new String[] {"Sn0wF0x", "184", "$14.2M", "38%", "active now"},
                        new String[] {"kaiZen_", "121", "$9.80M", "31%", "active now"},
                        new String[] {"BlockBaron", "96", "$7.10M", "44%", "5 min ago"},
                        new String[] {"↳ red concrete x64", "6 flips", "$18.4K→$193K", "90%", "shadow of Sn0wF0x"}),
                List.of(),
                "rival field is active · rivals 9/12 on"));

        write("6-payroll", new StatsImage.Page("Payroll", "SCANNING · 13:45 · bank $9.1M", "green",
                List.of(new String[] {"Rules", "OfficialGhostMob 10%"}, new String[] {"Paid 24h", "$1.31M in 328", "gold"},
                        new String[] {"Status", "idle · last paid 13:44"}),
                0, 0,
                new String[] {"Time", "To", "Amount", "Status"}, new boolean[] {false, false, true, false},
                List.of(new String[] {"13:44", "OfficialGhostMob", "$8.10K", "PAID"},
                        new String[] {"13:41", "OfficialGhostMob", "$2.70K", "PAID"},
                        new String[] {"13:38", "OfficialGhostMob", "$590", "PAID"},
                        new String[] {"13:35", "OfficialGhostMob", "$3.30K", "PAID"}),
                List.of(),
                "one /pay per rule, per sale"));

        write("7-tune", new StatsImage.Page("Tune", "SCANNING · 13:45 · bank $9.1M", "green",
                List.of(new String[] {"Group", "Orders", "gold"}, new String[] {"Selected", "Bid slots used = 40"},
                        new String[] {"Presets", "Default, Careful, Aggressive, Overnight"}),
                0, 0,
                new String[] {"Setting", "Value", ""}, new boolean[] {false, true, false},
                List.of(new String[] {"Read the order house", "on", "default"},
                        new String[] {"Scan every", "10 min", "default"},
                        new String[] {"Place bids", "on", "changed"},
                        new String[] {"▶ Bid slots used", "40", "changed"},
                        new String[] {"Stacks per bid", "1", "default"},
                        new String[] {"Bid ages out after", "6 h", "default"}),
                List.of(),
                "dropdowns pick the group and the setting; the form takes a value"));

        write("8-search", new StatsImage.Page("Markets", "SCANNING · 13:45 · bank $9.1M", "green",
                List.of(new String[] {"Search", "golden_apple", "gold"}, new String[] {"Asks up", "14"},
                        new String[] {"Lowest ask", "$377K"}),
                0, 0,
                new String[] {"Market", "Quick sale", "Median", "Sales", "Conf"}, new boolean[] {false, true, true, true, true},
                List.of(new String[] {"golden apple x32", "$630K", "$648K", "1.8/h", "44%"},
                        new String[] {"golden apple x16", "$399K", "$405K", "2.4/h", "45%"},
                        new String[] {"golden apple x1", "$26.0K", "$27.1K", "6.1/h", "52%"}),
                List.of("minecraft:golden_apple", "minecraft:golden_apple", "minecraft:golden_apple"),
                "search from the panel with the Search button"));

        assertTrue(Files.list(OUT).count() >= 8);
    }

    private static void write(String name, StatsImage.Page page) throws Exception {
        Files.write(OUT.resolve(name + ".png"), StatsImage.render(page));
    }

    /** Feeds real item textures out of the client jar when one is named. */
    private static void loadTextures() {
        String jar = System.getProperty("doughbay.clientJar", "");
        if (jar.isEmpty() || !Files.exists(Path.of(jar))) return;
        String[] ids = {"map", "hopper", "totem_of_undying", "polished_blackstone", "golden_apple",
                "experience_bottle", "tnt", "iron_ingot", "glowstone", "emerald_block", "respawn_anchor",
                "end_crystal", "blackstone"};
        try (ZipFile zip = new ZipFile(jar)) {
            for (String id : ids) {
                for (String path : new String[] {"assets/minecraft/textures/item/" + id + ".png",
                        "assets/minecraft/textures/block/" + id + ".png",
                        "assets/minecraft/textures/block/" + id + "_top.png",
                        "assets/minecraft/textures/block/" + id + "_side.png",
                        "assets/minecraft/textures/block/" + id + "_outside.png"}) {
                    var entry = zip.getEntry(path);
                    if (entry == null) continue;
                    try (InputStream in = zip.getInputStream(entry)) {
                        BufferedImage image = ImageIO.read(in);
                        if (image == null) continue;
                        if (image.getHeight() >= image.getWidth() * 2) {
                            image = image.getSubimage(0, 0, image.getWidth(), image.getWidth());
                        }
                        ItemTextures.seed("minecraft:" + id, image);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            // no textures: the pages still render, just without pictures
        }
    }
}
