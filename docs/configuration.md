# Configuration

## The API key

The key lives outside version control. Two ways to provide it:

- **In game (easiest).** Open GoNuts with **B**, go to the **Automation** tab in
  Observe mode, and use the **API KEY** card. Every character renders as `*`, so
  the key is never legible on screen or in a stream. **Save** writes it to
  `secret.dat` and restarts collection at once; enter it once and it is reused at
  every later launch. **Test history** then makes a single read-only request and
  reports how many sales parsed and how recent the newest is; it writes nothing,
  so it is safe to repeat. An unsaved draft is tested as typed, so a bad paste is
  caught before it can replace a working key.
- **Environment.** `export DONUT_API_KEY=<your key>`. If set, it wins over
  `secret.dat`, and the card says so rather than silently disagreeing.

`secret.dat` is plaintext, and so is `doughbay.db`. Treat both as secrets and
keep them out of screenshots, archives, and support bundles. Saving a key in
game changes nothing else: every permission, allowlist, fee value, and limit is
carried over from startup, so an in-game key can never widen what GoNuts may do.

## config.json

The Fabric mod keeps its settings under `.minecraft/config/doughbay/config.json`:

```json
{
  "collectionEnabled": true,
  "authorizedExecutionEnabled": false,
  "authorizedServers": [],
  "notificationServers": ["donutsmp.net", "play.donutsmp.net"],
  "preflightEnabled": false,
  "preflightServers": [],
  "continuousAutomationEnabled": false,
  "continuousAutomationServers": [],
  "continuousMaxPurchasePrice": 0,
  "continuousMaxSessionSpend": 0,
  "continuousMaxTradesPerSession": 1,
  "continuousMinimumProfit": 5000,
  "continuousMinimumRoiPercent": 12,
  "continuousMinimumConfidencePercent": 80,
  "continuousCooldownSeconds": 10,
  "continuousReservedHotbarSlot": 8,
  "continuousMaximumHoldMinutes": 120,
  "auctionFeesConfirmed": false,
  "listingFeeFlat": 0,
  "listingFeePercent": 0.0,
  "saleTaxPercent": 0.0,
  "startingPaperBalance": 2000000
}
```

The API key is **not** here; it goes in the adjacent `secret.dat`.

## The allowlists

GoNuts uses separate, exact-address allowlists so that one permission can never
imply another. Addresses are exact lowercase strings, including a port when it
is part of the address, for example `["private.example.net:25565"]`. Use
`["singleplayer"]` for an integrated test world.

- **`authorizedServers`** with `authorizedExecutionEnabled: true` is the only
  gate that permits an actual trade. Keep both empty and false for normal
  Observe mode.
- **`notificationServers`** lets GoNuts parse Donut-style sale confirmations
  from action-bar messages, and nothing else. It grants no command, click,
  purchase, or listing permission. It stops another server or ordinary chat from
  imitating a sale notice.
- **`preflightServers`** with `preflightEnabled: true` gates the search-only
  preflight check, which sends one `/ah search`, inspects the screen, and stops.
  It never grants live execution.

A missing, malformed, empty, or older allowlist stays fail-closed, with the
corresponding capability disabled.

## Fees and taxes

Fees default to unconfirmed zero. Observe and paper analysis may use those
values, but live Single and Continuous modes stay locked until
`auctionFeesConfirmed` is explicitly `true` and all three fee fields are present
and valid. If the server truly charges nothing, confirm all three zeroes
explicitly rather than relying on a missing default.

## Server address (for a shared server)

To point a client at a self-hosted data server instead of the Donut API, set
`apiBaseUrl` to the server's origin, for example `"http://your-server:8787"`, or
in game switch the API-key card to **Key type: Server** and paste the issued
key. No server address is baked into the mod; you always point at your own. See
[Hive and clan server](server.md).

## Tuning

Everything else, the trading behaviour, is tuned live in the **Settings** tab
(see the [GUI tour](gui-tour.md)) and stored in
`.minecraft/config/doughbay/tuning.json`. The money-moving behaviours ship off
by default.
