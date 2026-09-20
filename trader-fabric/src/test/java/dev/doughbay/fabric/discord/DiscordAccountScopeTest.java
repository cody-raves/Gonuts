package dev.doughbay.fabric.discord;

import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DiscordAccountScopeTest {
    @Test
    void singleClientDefaultExcludesOldAccountsFromRowsAndTotals() throws Exception {
        for (int activeAccounts : List.of(0, 1)) {
            assertView(DiscordAccountScope.resolve("", "cody_raves", activeAccounts),
                    List.of("map", "light_gray_stained_glass"), 621000);
        }
    }

    @Test
    void hiveDefaultStillIncludesBothAccounts() throws Exception {
        assertView(DiscordAccountScope.resolve("", "cody_raves", 2),
                List.of("emerald_block", "emerald_block", "map", "light_gray_stained_glass"), 681000);
    }

    @Test
    void explicitAccountSelectionOverridesTheHostAndMatchesNameCase() throws Exception {
        assertView(DiscordAccountScope.resolve("YDocD", "cody_raves", 1),
                List.of("emerald_block", "emerald_block"), 60000);
        assertView(DiscordAccountScope.resolve("cody_raves", "ydocd", 2),
                List.of("map", "light_gray_stained_glass"), 621000);
    }

    private static void assertView(DiscordAccountScope scope, List<String> expectedItems, long expectedAsk)
            throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE positions(client TEXT, item_key TEXT, target_price INTEGER, mode TEXT, status TEXT)");
                statement.execute("INSERT INTO positions VALUES "
                        + "('ydocd','emerald_block',30000,'REAL','LISTED'),"
                        + "('ydocd','emerald_block',30000,'REAL','LISTED'),"
                        + "('cody_raves','map',352000,'REAL','LISTED'),"
                        + "('cody_raves','light_gray_stained_glass',269000,'REAL','LISTED'),"
                        + "('cody_raves','sold_item',900000,'REAL','SOLD'),"
                        + "('cody_raves','paper_item',900000,'PAPER','LISTED')");
            }
            // Exercise both the Listings rows and Money aggregates, including
            // binding after an earlier parameter such as a time-window filter.
            String where = " WHERE mode=? AND status='LISTED'" + scope.sql();
            try (var statement = connection.prepareStatement("SELECT item_key FROM positions" + where + " ORDER BY rowid")) {
                statement.setString(1, "REAL");
                assertEquals(scope.account().isBlank() ? 2 : 3, scope.bind(statement, 2));
                var items = new ArrayList<String>();
                try (var result = statement.executeQuery()) {
                    while (result.next()) items.add(result.getString(1));
                }
                assertEquals(expectedItems, items);
            }
            try (var statement = connection.prepareStatement("SELECT COUNT(*), SUM(target_price) FROM positions" + where)) {
                statement.setString(1, "REAL");
                scope.bind(statement, 2);
                try (var result = statement.executeQuery()) {
                    result.next();
                    assertEquals(expectedItems.size(), result.getInt(1));
                    assertEquals(expectedAsk, result.getLong(2));
                }
            }
        }
    }
}
