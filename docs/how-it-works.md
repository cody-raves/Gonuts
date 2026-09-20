# How it works

## The idea

GoNuts compares acquisition cost with expected resale proceeds, after fees.
The API-backed analysis below uses completed sales. In keyless mode the client
also learns price estimates from visible orders and auction asks, and records
its own sales. Those estimates are not proof that an item sold; retail demand
checks exclude synthetic order and ask observations.

## How a decision is made

1. **Normalize.** Completed sales for the exact market (item fingerprint plus
   stack bucket) are converted to unit prices; malformed records are rejected.
2. **Filter outliers.** A modified z-score on log prices flags outliers, which
   are excluded from valuation but **kept** in the raw history (never deleted),
   so troll sales cannot inflate values and any decision can be reproduced.
3. **Weight and band.** Remaining sales are recency-weighted (`2^(-age/half-life)`)
   and weighted percentiles produce the price bands. The **quick-sale value**
   (around the 37.5th percentile) is the conservative resale target.
4. **Cap by the book.** The target is capped by the next comparable active
   listing minus an undercut, but only after the exact active book reaches a
   clean empty final page. If coverage is incomplete or the cap kills the
   margin, the trade is rejected.
5. **Score and rank.** Fees and taxes are subtracted; liquidity (sales per hour,
   queue ahead) estimates the hold time; bankroll, volatility, trend, staleness,
   sample count, and manipulation checks filter the rest. Survivors are scored
   and ranked.
6. **Prove on paper.** Paper trading simulates execution latency, checks the
   listing still exists at buy time, and only counts a resale as sold once
   enough later comparable sales at or above the target clear the queue ahead of
   it. Backtests are strictly chronological: nothing after the decision time can
   affect the decision.

## What keeps it honest over time

- **Manipulation checks** lower confidence on markets that look engineered
  (one seller dominating, price up on thin volume, clustered outliers, stale
  comparables). They can only lower confidence, never raise it.
- **Auto-prune** benches markets whose recent settled trades add up to a loss,
  judged on a rolling window so an old loss ages out and a market that recovers
  flows back in for a fresh try. Pinned and held markets are never pruned.
- **Time-of-day weighting** nudges scanning toward the hours each market actually
  fills, so slots follow real demand.
- **Rival intelligence** tracks who else is trading and adjusts pacing and
  targets around active competition.

## Keyless orders and individual resale

For markets with evidence that singles command a premium, the order desk now
compares a full stack's resale with selling every unit separately. The expected
batch profit is:

`quantity * net proceeds per single - quantity * order price per item`

Each single pays its own configured listing fees and tax. The default minimum
expected batch profit is $100,000 (`list.singles_batch_profit`); the global profit
and ROI floors also apply. Diagnostic order descriptions distinguish $100K-$500K,
$500K-$1M, and above-$1M batch estimates. These are estimates across the whole
purchase, not profit promised on each single or in any particular hour.

A retail purchase requires all of the following:

- Established completed-sale history showing a single-item premium and demand.
  The profile looks back 30 days and excludes `keyless-orders` and `keyless-asks`.
- Separate live auction quotes for a single and a full stack, each less than five
  minutes old with at least three positive comparable asks. Conservative quote
  prices cap the historic single price; the net retail proceeds must beat the
  net stack proceeds by at least 20%.
- A competitive standing buy order that the bot can outbid within its profit
  ceiling. Existing purchase/spend limits, item rules, and price checks remain.

The desk initially orders one full stack per retail market. It saves the resale
intent before sending the order and checks the quotes again when the queued
placement actually runs. At most four retail quote checks are added per planning
cycle, leaving room in the six-check refresh budget for ordinary stack markets.

With `list.singles=1`, a filled retail order is split and listed one unit at a
time. `list.singles_max` limits simultaneous single listings per item (default
5; an existing configured value is retained). Once that cap is reached, the
remainder waits in inventory while other markets continue. A confirmed sale
opens room for another single. A full inventory or failed split holds the stock
for retry instead of switching it to a bulk listing. Turning singles off also
holds already reserved retail stock.

The split closes the parent and saves both the single and remainder in one
SQLite transaction. Their quantities and costs add up exactly to the parent;
rounding stays with the remainder. Every child retains the original batch ID,
and recovery does not count each split as another purchase. Listing waits for
the persistence acknowledgement. Inventory rescue paths skip reserved stock
rather than book its cost again. Schema version 16 adds `retail_positions`;
`orders-retail.txt` retains order resale intent across restarts.

Automated checks cover fee-adjusted batch pricing, stale/invalid quote rejection,
profit tiers, all 64 successive splits, restart recovery, transaction rollback,
account ownership, persistence acknowledgements, and replenishment at the cap.
The actual server inventory clicks, command timing, fill rate and realized
profit still require an observed in-game run of the new JAR.

## Inventory recovery, collected costs, and repricing

A retail split checks the player, active menu, position, source quantity,
empty destination and cursor quantity before each click. A late auction page
interrupts the split. Cursor recovery uses only player-inventory slots in the
currently active menu, never cached slot numbers from a different container.
Recovery runs before pending inventory-close cleanup and waits for one second
of a clear cursor in the player inventory before another operation can begin.
If recovery cannot settle within 15 seconds, the session pauses with a reason.
An interrupted retail batch retains its durable cost and returns to its retry
queue after recovery. Explicitly stopping the session still stops automation.

Delivery notifications are not complete acquisition records. If one notice
arrives for an inventory stack of 39, all 39 units now receive an acquisition
cost estimate at the recorded unit bid. Merged quantities scale the cost in
both directions; a larger physical stack cannot acquire free units merely
because notifications were delayed. Where exact lot provenance is unavailable,
this remains an estimate based on the recorded bid, not a recovered receipt.
Existing historical profit records are not rewritten by this change.

An own-listings page-turn timeout can restart the scan from page one once.
The restart discards all partial rows and page counters. It does not repeat a
pending cancellation or count an unchanged page twice; a second failure aborts
without reconciling an incomplete book.

`reprice.minimum_roi_pct` defaults to 5: until the existing stop-loss window,
repricing protects the larger of the configured minimum cash profit and 5% of
acquisition cost, including fees. Existing liquidation and aged-stock loss
settings still apply. New configurations enable `reprice.to_market` by default;
existing choices are retained. Market pricing prefers a fresh quote for the
exact stack size. With market pricing enabled, missing/stale evidence holds
the ask until the stop-loss window, and a valid quote moves an overpriced ask
to that market instead of applying an additional fixed percentage cut.

Item allow/deny rules, sample requirements and spending limits still apply.
Installing a new JAR does not expand an existing user's configured item list
or establish profitable demand in additional markets.

## Modules

The analysis engine is plain Java with no Minecraft dependency. Only
`trader-fabric` touches the game.

```
trader-core     item fingerprinting, robust price stats, liquidity,
                opportunity scoring, risk and manipulation checks
trader-api      Donut API client: auth, pagination, rate limiting,
                defensive response parsing, key redaction
trader-storage  SQLite schema, migrations, repositories, raw JSON archive
trader-paper    paper-trading engine (simulated bankroll, leak-free)
trader-engine   shared collection and analysis orchestration
trader-cli      collect / scan / paper commands, fat-jar build
trader-server   self-hostable data server (one key feeds many clients)
trader-fabric   Minecraft 26.2 UI and automated execution driver
```

Live execution is isolated behind a five-method `ExecutionDriver` contract, so
swapping execution modes never changes collection, pricing, scoring, or
scanning.
