# GoNuts VPS collector — build plan

Hand this to the agents on the VPS. It describes one machine's job: run a
read-only Minecraft client on the secondary account that walks the auction and
order pages continuously and uploads what it sees to the GoNuts API.

The trading bot on the home PC is a separate thing and is not touched by this
work. Nothing in this plan may buy, sell, list, pay, or place an order.

---

## 1. Why this machine exists

Donut's API returns sales and listings, minutes stale, and returns nothing at
all for:

- the order house (`/orders`), which is GUI only,
- item components: enchantment levels, shulker contents, trims,
- who is listing and who is buying.

A client sees all of it. The home account already collects this while it
trades (228,195 order rows, 6.8M listing snapshots in two days), but only while
someone is playing. This machine makes the feed 24/7 and widens the coverage to
markets the trader never visits.

## 2. Hard rules

1. **Never sign in to the secondary account from the home PC**, and never sign
   in to the main account here. Account association is not reversible.
2. **Read only.** The collector never sends a command other than what is
   needed to open a page, never clicks a buy or a listing, never uses `/pay`.
3. **No fixed rhythm.** Jitter every interval by ±30% and take an idle gap of a
   few minutes every hour. A perfectly regular menu-walker is a signature even
   when no single action is flagged.
4. **Nothing here changes the trading config on the home PC.** Separate
   machine, separate config directory, separate ledger.
5. If the server ever warns about command rate, back off and log it; do not
   retry in a tight loop. (The only server pushback ever seen was a command
   cooldown, twice, on 2026-09-03.)

## 3. What runs on this machine

| Piece | What it is | State |
|---|---|---|
| Minecraft client, Fabric 26.2 | the collector, secondary account | to install |
| GoNuts mod jar | `trader-fabric/build/libs/doughbay-0.1.0+26.2.jar` | built on the home PC; copy it over |
| Collector mode | a mod mode that sweeps but never trades | **to build (task A)** |
| Uploader | posts sweeps to the GoNuts API | **to build (task B)** |
| GoNuts API server | `trader-server`, collector + REST | exists; needs tokens (task C) |

## 4. Environment setup

1. Windows on the VPS, 8 GB RAM or better, a region close to the server.
2. Java 21 runtime for the game; the build toolchain is only needed if this
   machine also compiles (JDK 25 path is in the repo's build notes).
3. Minecraft launcher, Fabric loader 0.19.3 for 26.2, signed in on the
   **secondary** account only.
4. Mods: Fabric API, the GoNuts jar. Sodium/Iris are optional and only help
   frame rate; a headless-ish window is fine.
5. Config directory: `%APPDATA%\.minecraft\config\doughbay\`. Start it empty so
   the collector builds its own ledger.

## 5. Task A — collector mode (mod change)

Goal: the mod can run a session that reads and never acts.

- Add a run mode `COLLECT` beside `CONTINUOUS` in
  `AutomationSessionController.RunMode`, or a `collector.enabled` toggle in
  `Tuning` if that is simpler to gate.
- In collector mode the session may only:
  - open the order house and sweep pages (`AutomatedExecutionDriver.scanOrders`),
  - open the auction "recently listed" pages and read rows (the existing watch
    path, with the buy step skipped),
  - read its own listings page (there will be none).
- It must never enter `BUYING`, `PREPARING_LIST`, `LISTING`, `REPRICING`,
  `BID_DESK`, or `DELIVER`. Assert this: a collector session that reaches those
  states is a bug, not a warning.
- Intervals come from `Tuning`, jittered per rule 3 above:
  - order house full sweep every 30–45 min,
  - auction page walk continuously, pausing 2–5 min every hour.
- The existing safety gates (`authorizedExecutionEnabled`, the continuous
  policy) should stay **off** on this machine. Collector mode must not require
  them.

## 6. Task B — the uploader (mod change)

Goal: what the collector sees reaches the API within seconds.

- New class `dev.doughbay.fabric.upload.FeedUploader`.
- Batches, gzipped, POSTed to the GoNuts API with the token and the client's
  UUID:
  - `POST /v1/ingest/orders` — the rows from an order sweep
    (item id, key, descriptor json, parts, unit price, delivered, total, page,
    observed_at).
  - `POST /v1/ingest/listings` — live auction rows seen on page walks
    (listing key, seller, item key, count, total price, observed_at).
  - `POST /v1/ingest/components` — item components read from tooltips
    (item id, descriptor hash, enchantments/levels, container contents, trim).
- Rules: never block the game thread; a failed upload is retried with backoff
  and dropped after an hour; every row carries `observed_at` so a stale
  contributor cannot overwrite a fresher number.
- The same uploader ships in the trading mod on the home PC, so both accounts
  contribute.

## 7. Task C — API server changes

Repo: `trader-server`.

- **Tokens.** Table `tokens(token, discord_id, minecraft_uuid, issued_at,
  revoked_at, last_seen_at)`. Every `/v1/*` route requires
  `Authorization: Bearer <token>`; a revoked or unknown token gets 401 with a
  plain reason. Admin routes stay behind the existing admin token.
- **Ingest routes** from task B, which must validate that the token's UUID
  matches the one in the payload.
- **Merge rules.** Ingested orders merge by (item key, parts, unit price,
  total); newest `observed_at` wins. Listings are append-only snapshots.
  Components are upserted by descriptor hash.
- **Serve back** what the mod needs: `/v1/orders` (the shared book),
  `/v1/market/{key}`, `/v1/markets`, `/v1/components/{itemId}`, plus the
  demand and population summaries.
- Version handshake: the mod sends `X-GoNuts-Mod: <version>`; the server
  answers `/v1/capabilities` with the features it supports so an older mod
  degrades instead of failing.

## 8. Order of work

1. Task C tokens, so nothing is ever open to the internet unauthenticated.
2. Task A collector mode, and run it here for a day with the uploader stubbed
   out, checking the local ledger fills with orders and listings.
3. Task B uploader, pointed at the API.
4. Then the home PC's trading mod gets the same uploader.

## 9. Verification before it is left alone

- The collector has run 24 hours with no `[BUYING]`, `[LISTING]`,
  `[REPRICING]`, or `[BID_DESK]` line in its log, and no `/pay`, `/ah sell`, or
  buy click anywhere.
- Its ledger holds order rows and listing snapshots at a steady rate.
- The API shows rows arriving from this UUID, and rejects a request with a
  revoked token.
- No server warning about command rate in the log.
- The account is still able to log in after a full day (check by hand).

## 10. Open question, ask before scaling

Whether DonutSMP's rules allow an account that only collects data, and whether
their API terms allow serving that data to other players. The answer changes
the shape of the product, not this machine's build steps. Ask staff before the
feed has users.
