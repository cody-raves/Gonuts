# Discord control panel

A bot that lives inside the mod. It posts one message in a channel of yours and
edits it every 30 seconds with a rendered page of the trader's state. The
buttons under it start, stop, and tune the trader, and only one Discord user,
the operator, can press them.

## Setup, once

1. In the Discord developer portal: **New Application**, add a **Bot**, and copy
   the bot token. No privileged intents are needed.
2. Invite the bot to your server with **View Channel**, **Send Messages**, and
   **Attach Files** (permissions value `35840`).
3. In Discord, enable Developer Mode (Settings, Advanced), then right-click the
   channel and **Copy Channel ID**, and right-click your own name and
   **Copy User ID**.
4. In game: GoNuts, **Automation** tab, press the tools button twice to reach
   **Discord command centre**. Paste the token, the channel id, and your user
   id, then **Save**. The panel appears in the channel within a minute.

## Pages

Money, Listings, Opps, Orders, Rivals (with the underdog's shadow markets),
Payroll, Tune, and a market Search form. **Tune** uses a dropdown for the
settings group and one for the setting, with Toggle, Set value, minus, plus, and
Reset buttons, plus a Presets group.

Everything the panel changes is logged as `GoNuts Discord`. The settings live in
`config/doughbay/discord.json`, and the token is never written to the log.

## Account scope and occupied slots

With one active account, the default page filters ledger entries to that
account. Saved positions belonging to an older account do not appear in its
Listings rows or Money totals. With multiple accounts, the default remains
the combined hive view; selecting an account filters to that owner.

Money and Listings use the same occupied-slot count. For the local account,
the latest server audit, adjusted for subsequent listings and sales, takes
priority over the session's tracked count. Other accounts and the combined
hive use their scoped ledger counts. Costs and asking prices describe tracked
ledger entries, which can lag a server audit.
