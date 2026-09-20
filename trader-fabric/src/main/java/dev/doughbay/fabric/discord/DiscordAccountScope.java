package dev.doughbay.fabric.discord;

import java.sql.PreparedStatement;
import java.sql.SQLException;

/** The ledger owner corresponding to the account shown in the panel. */
record DiscordAccountScope(String account) {
    static DiscordAccountScope resolve(String selected, String current, int hiveAccounts) {
        // With one client the default page represents that client, even when
        // the shared ledger still holds positions from an older account.
        return new DiscordAccountScope(selected.isBlank() && hiveAccounts < 2 ? current : selected);
    }

    String sql() {
        return account.isBlank() ? "" : " AND client=? COLLATE NOCASE";
    }

    int bind(PreparedStatement statement, int index) throws SQLException {
        if (account.isBlank()) return index;
        statement.setString(index, account);
        return index + 1;
    }
}
