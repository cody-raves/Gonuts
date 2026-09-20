# GoNuts

Auction market analysis for DonutSMP (Minecraft Java 26.2, Fabric). Collects
completed auction sales through the public API, builds its own price history,
values items with robust statistics, and surfaces resale opportunities.

## Build

```bash
./gradlew build          # compiles everything, runs the test suite (63 tests)
```

**Requires JDK 25** — Minecraft 26.2 declares `java-runtime-epsilon` /
major 25. An older JDK fails with cryptic Loom errors.

Artifacts:
- `trader-fabric/build/libs/doughbay-0.1.0+26.2.jar` — the mod, goes in `mods/`
- `trader-cli/build/libs/doughbay.jar` — headless CLI

## Modules

| Module | Contains | Depends on Minecraft? |
|---|---|---|
| `trader-core` | fingerprinting, robust stats, valuation, risk, `ExecutionDriver` | no |
| `trader-api` | Donut API client, rate limiting, defensive parsing | no |
| `trader-storage` | SQLite schema, migrations, repositories | no |
| `trader-engine` | `MarketService` — orchestrates collect/scan/detect | no |
| `trader-paper` | paper trading engine | no |
| `trader-cli` | `doctor`, `collect`, `scan`, `paper`, `import` | no |
| `trader-fabric` | mod entry point, screen, charts, drivers | **yes** |

Only `trader-fabric` touches Minecraft. Keep it that way — the engine is
testable precisely because it knows nothing about the game.

## CLI

```bash
java -jar doughbay.jar doctor    # validate the data pipeline — run this first
java -jar doughbay.jar collect --pages 10
java -jar doughbay.jar collect --loop
java -jar doughbay.jar scan
java -jar doughbay.jar paper --balance 2000000
java -jar doughbay.jar import <file.csv|file.json>
```

API key comes from `DONUT_API_KEY` or `doughbay.json`. Never commit it; it is
redacted from all logs and errors. In the mod it lives in
`config/doughbay/secret.dat`.

## Minecraft 26.2 API notes

26.2 renamed things. Model training data is frequently wrong here — verify with
`javap` against the Loom-cached client jar rather than guessing:

```bash
javap -cp ~/.gradle/caches/fabric-loom/26.2/minecraft-client.jar \
      net.minecraft.client.gui.screens.Screen
```

Specific gotchas already hit:
- Current screen is `Minecraft.getInstance().gui.screen()`, **not** `.screen`
- Open a screen with `client.gui.setScreen(...)`. `setScreenAndShow` force-renders
  a whole frame from inside the tick — a visible hitch per keypress.
- Rendering goes through `extractRenderState(GuiGraphicsExtractor, ...)`, not
  immediate-mode draw calls.
- Container interaction is `handleContainerInput(...)` with the `ContainerInput`
  enum, not the old `slotClicked`/`ClickType`.
- Minecraft's font is **proportional**. Space-padded columns never align — use
  `font.width(...)` to position them.

## Non-obvious design decisions

**SQLite driver is constructed directly, never via `DriverManager`.** Its
service discovery runs once per JVM against the first caller's thread context
class loader; on a ForkJoinPool worker that is the system loader, which cannot
see mod jars, and the failure is cached permanently. This bug is
order-dependent — it passes testing and fails in production. See
`storage/Database.java`.

**Outliers are flagged, never deleted.** `transactions.is_outlier` marks them
so a filtering mistake stays recoverable and auditable.

**Analysis is strictly chronological.** `MarketAnalyzer.analyze(..., asOf)`
ignores anything after `asOf`. This is what makes backtests leak-free — do not
"optimize" it away.

**Demo mode.** With no API key the mod shows invented sample rows so the UI can
be developed. `Snapshot.demo` is flagged and every renderer must label it. Never
let demo figures render as if observed.

**Chart colours are validated, not chosen by eye.** Series use the first three
categorical slots stepped for a dark surface; they pass colour-vision
separation checks against the panel background. Changing them means re-checking.

## Execution modes

`ExecutionDriver` (in `trader-core`) is the extension point: `buy`, `list`,
`inspect`, `emergencyStop`, `modeName`.

- `DisabledExecutionDriver` — refuses everything. The default.
- `AssistedExecutionDriver` — copies the search term, reads the open container
  screen, and reports whether the item matches. The player performs every action.
- `AutomatedExecutionDriver` + `automation/AutomationSessionController` — live
  execution. Contributed separately; see its own docs before changing it.

`AutomationSessionController` is deliberately hard to satisfy. It gates every
irreversible step behind evidence: complete zero-failure scans of the exact
active book, two consecutive scans in which the rows that matter held still,
freshness bounds, and ownership checks. Getting "stuck" in a waiting state is
usually the design working — it refuses to conclude anything about a real trade
from partial evidence. Before loosening a gate, read why it exists; the
alternative to waiting is acting on a guess with real money.

**DonutSMP's published rules ban scripts, macros and auto-clickers, and
enforcement reaches every account linked to a player.** Anything that acts on
the player's behalf carries a real ban risk that the account owner is choosing
to take. Do not add detection evasion — no humanised timing, no concealment.
Automating a task and hiding that it is automated are different things, and the
second is out of scope for this project.

Any automated driver should keep: an explicit opt-in (off by default), per-trade
and per-session spend caps, a dry-run mode, an emergency stop, and abort-rather-
than-guess on any mismatch.

## Known gaps

- **Auction GUI prices are not parsed.** They live in item lore in a
  server-specific format that has never been captured. Verification reports
  price as "unknown" rather than guessing — a confident wrong price is worse
  than an admitted unknown. A screenshot of a real listing tooltip would fix this.
- **Fees are all zero** in `FeeConfig` until confirmed empirically on the server.
- **Jackson is bundled via jar-in-jar** and shares a nested-mod id with any other
  mod shipping it; only one version wins. The loaded version is logged at
  startup so a clash is diagnosable. Shading would fix it properly.
- **Risk thresholds in `RiskConfig` are placeholders** from the original plan,
  meant to be tuned through paper trading, not trusted as-is.

## Working style

- Verify against the real artifact (`javap`, the built jar, the actual API
  response) rather than assuming. Most bugs in this project came from assuming.
- Tests must stay green: `./gradlew build` runs all 63.
- The engine modules must remain free of Minecraft imports.
