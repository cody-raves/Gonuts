# Execution and safety

GoNuts is fail-closed by design. Live commands and clicks are disabled by
default, and the driver refuses to act on anything it cannot verify. This page
explains the modes and the gates between them.

## The modes

Press **B** to open GoNuts. The **Automation** tab presents, from least to most
capable:

- **Observe (default).** Opportunities and demo data are view-only. Nothing can
  trade. Opening an opportunity's detail only navigates; it never sends a buy.
- **Preflight (one shot, zero clicks).** Uses only the independent
  `preflightEnabled` / `preflightServers` gate. It rebinds the candidate to the
  latest scan, sends one `/ah search`, waits for a stable auction GUI, verifies
  the exact id, stack, seller, and authoritative total price, captures the
  screen and lore evidence, and stops before the first slot click.
- **Single (one complete trade).** Uses the full live and continuous gates. It
  selects one fresh, completed-sales-backed candidate, buys it, prepares the
  exact resale stack, relists it, then monitors until a trusted sale notice and
  a newer active-listing snapshot reconcile the listing as gone. It stops after
  that one fully reconciled trade.
- **Continuous.** Same gates and lifecycle, then chooses another candidate only
  after the previous sale is reconciled and its capital released, always inside
  every configured session limit.

**B** becomes the emergency stop whenever an operation is armed, including an
active preflight.

## What a real trade requires

An automated buy needs an exact item id, exact stack size, exact seller, and one
unambiguous authoritative `Price`, `Cost`, or `Total` lore field in a non-player
GUI slot. GoNuts refuses incidental per-unit or fee amounts, ambiguous listings,
and ambiguous confirmation controls, and marks a purchase complete only after
the expected inventory quantity change.

Continuous automation is gated behind **all** of these at once:

- `authorizedExecutionEnabled: true` and an exact `authorizedServers` match;
- `continuousAutomationEnabled: true` and an exact `continuousAutomationServers` match;
- an exact `notificationServers` match (a trusted sale notice is required before
  a position can be reconciled and capital released);
- `auctionFeesConfirmed: true` with valid `listingFeeFlat`, `listingFeePercent`,
  and `saleTaxPercent` (a confirmed zero is allowed);
- positive `continuousMaxPurchasePrice` and `continuousMaxSessionSpend` caps.

The defaults are disabled, empty, unconfirmed, and zero, so an older, partial,
malformed, or freshly generated config cannot start live automation. A present
field with the wrong type, a non-finite number, or an out-of-range value locks
the matching policy rather than silently substituting a weaker value.

## Reconciliation, not optimism

A trusted sale action-bar event is only a **notice**. Capital stays committed
until two newer, complete, stable, zero-error full-book scans agree the exact
bound listing disappeared. Only then is the sale reconciled and another
candidate eligible. The server reporting a gross sale amount does not by itself
prove fees, cost basis, net profit, or that the sale belongs to a tracked
position.

## Crash recovery

Real-automation checkpoints are written by a dedicated SQLite worker, never on
Minecraft's render thread. Session open, pre-command intent, verified position,
caps, and reconciled closure each get a durable acknowledgement at their safety
boundary. After a crash or restart, any open session or uncertain exposure loads
before Start can unlock and appears as a non-resumable PAUSED session. Clearing
recovered exposure takes two separate confirmations around two newer complete
scans plus an immutable local audit record; caps are kept until the session is
deliberately resumed or ended.

## GUI captures

On the first listing GUI and first confirmation GUI of each session, GoNuts
captures a screenshot and a paired slot and tooltip dump to
`.minecraft/doughbay-captures/`. The dump includes the GUI title, slot numbers,
item id, stack count, every tooltip line, and every parsed price. If the server
changes its lore format, the driver aborts without clicking, and the capture is
the exact fixture needed to update the parser.

## What it will not do

GoNuts does not bypass anti-cheat, conceal that it is automated, guess at
ambiguous controls, or weaken the emergency stop. It does not yet fingerprint
enchantments or arbitrary components from GUI stacks, so it executes only when a
listing is uniquely verifiable by id, stack size, lore price, and optional
seller. Any unknown lore format, duplicate candidate, unclear confirmation,
timeout, disconnect, or unexpected inventory result aborts the operation.

## The rules

**DonutSMP bans scripts, macros, and auto-clickers**, and enforcement can reach
every account linked to a player. Analysis, pricing, and paper trading carry no
such risk. Running live execution is a real ban risk that the account owner
chooses to take. GoNuts is not affiliated with DonutSMP.
