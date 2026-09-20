# How it works

## The idea

Current asking prices are hope, not proof. GoNuts values items from **completed
sales only**, builds price bands from them, and treats the live order book only
as a ceiling on the target and a read on competition. If the completed-sale
history is insufficient, stale, too volatile, or falling, GoNuts rejects the
opportunity rather than substituting an asking price.

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
