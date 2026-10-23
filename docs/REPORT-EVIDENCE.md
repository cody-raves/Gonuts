# Current bug-report evidence

Current-report repair; separate from the staged continuous 24-hour cloud stream.
An attachment is bounded diagnostic evidence, not an unfiltered Minecraft log.

## Coverage

| Area | Evidence included |
| --- | --- |
| Controller | State transitions, native reason categories, current session/queue/cap state |
| Execution driver | Phases, successful/refused/aborted outcomes, item/count/price/ceiling where known |
| Order planning | Requested and selected quantity, order-shape reason, depth and sales sizing inputs, budget, 37 closed rejection codes |
| Auction scheduling | Selected watch market and candidate count; driver watch phases/outcomes |
| Flips | Existing quote, ceiling and market decision traces |
| Inventory | Current and previous blocker snapshot, driver inventory-safety phases |
| Recovery/persistence | Retained exposure, native hold/refusal reasons, accepted/durable generation and pending-write state |
| Market/community | Request, success, retry and HTTP outcome traces |
| Hive/dashboard | Existing join/combine/link/connection/request/result traces |
| Evasion/restart | Existing route, phase and outcome traces |
| Faults | Current sanitized fault type/stage and verified compiled stack frames, recent recognized exception lines, fault/action timeline |
| Settings | Current numeric report code and observed numeric changes, without private text fields |

The new `dec` records contain fixed fields and closed compiled codes/reasons.
`requested` and `selected` mean quantities for ORDER_PLAN; ORDER_DEPTH uses stack
counts; ORDER_SALES records requested stacks, sized stacks, recent sold count and
market room. WATCH_SELECTED records the candidate count and selected first market;
it is not proof that a purchase happened. SET_* records are numeric observations:
requested/selected are old/new multiplied by 1000, with price=1000 indicating the
scale. They are not exact mutation timestamps or proof of fault causation.

## Assembly and limits

Both what-happened and expected-behavior text guide priority. They cannot exclude
the baseline across subsystems. The bounded timeline keeps the latest record and
last problem for each subsystem before filling remaining space with recent
decisions. It holds 2,048 process-local records and selects at most 64 per report.
Identical recurring decisions within a minute do not flood this journal.

The `cov` line states capture time, first/last selected timestamps, selected and
omitted records, ring evictions, and attachment-history lines removed for limits.
This distinguishes a useful sample from a complete incident history. The existing
file fallback still reads only the last 256 KiB of latest.log and keeps at most
100 recognized lines. Its source bounds remain stated in the attachment.

Attachment limits: 24,576 UTF-8 bytes, 200 lines, 500 characters per line. Full
manual/automatic request limit: 65,536 bytes. The sender requires a compatible
receiver advertisement before using these larger limits. A machine without the
new client cannot retroactively produce decision records it never collected.

No usernames, chat, home names, commands, secrets, free-form exception messages,
raw settings files or pending request bodies are copied into the decision stream.
The shared `report-decision-contract.json` specifies closed codes/reasons and
limits for client/receiver validator parity. A well-shaped unknown code is invalid.

## Acceptance checks

ReportSystemCoverageTest generates actual manual and persisted automatic report
payloads. It verifies broad subsystem coverage despite a 3,000-event desk burst,
the one-item-order/watch fixture, use of both text fields, numeric setting-change
observations, coverage bounds, and privacy canary rejection. Existing transport,
sanitizer, fault, automatic-retry and controller regressions remain required.

The worker must assess the reported behavior separately from liveness. Active
cycles only refute a complete freeze. A decision line alone is not proof of
correctness, and an intended protective hold does not explain its original cause.
Reports #236/#257 and the original refusal in #256 remain unresolved unless
separate causal evidence establishes a fix or a supported explanation.
