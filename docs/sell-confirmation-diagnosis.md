# Sell confirmation investigation — 2026-09-21

## Inventory-close recovery and fix at 21:50

An isolated diagnostic stopped automation, verified menu 0 with an empty cursor
and no screen, and called the normal `LocalPlayer.closeContainer()`. Vanilla
bytecode confirms that this sends `ServerboundContainerClosePacket(0)` even
though no InventoryScreen is drawn. After the existing response backoff expired,
the diagnostic sent plain `/ah` through vanilla. At 21:50:43 the same connection
showed `ContainerScreen`, menu id 5, with an empty connection queue. No reconnect
or sale was performed. Unlike the earlier isolated `/ah` test, explicitly closing
the player inventory restored auction access.

The controller's split and swap paths called `handleContainerInput` against
menu 0 without finishing that interaction with a player-inventory close. The
preparation path now closes it after verifying the held stack, source slot,
hotbar selection and empty cursor, waits 600 ms, and revalidates those conditions
before recording LIST intent and sending `/ah sell <target price>`. The plain
`/ah` used in the diagnostic does not replace the sell command.

The first diagnostic also exposed automatic relisting five seconds after the
emergency stop. `autoListRecoveredPurchase` did not honor `pausedByPlayer`.
Recovered-item listing, recovered-listing adoption, buy-exposure adoption and
automatic force reset now honor that flag. The diagnostic temporarily set
`session.auto_resume` to zero in memory (no configuration file was changed) to
keep the old running build stopped. Restarting loads the saved setting again.

The full build passes, including 115 Fabric tests. Three new tests cover the
inventory-close boundary and two cover recovered-item stop behavior. The earlier
checkpoint fix is included. The completed automated sale with this new build
still requires in-game verification; the live diagnostic only opened the auction
browser. The bot was left paused with that browser open.

The following sections preserve the earlier observations chronologically; their
unresolved hypotheses precede the successful inventory-close diagnostic above.

## Fresh launch at 21:45

The recovered map listed at 21:45:30 for 15,200 and sold at 21:45:47. The slot
audit opened at 21:46:04 and completed at 21:46:05. The next position was an
emerald block x1, so no split occurred. Its preparation closed an auction page
at 21:46:12, sent `/ah sell 38000`, and timed out at 21:46:19. Retries also failed.
This reproduces the failure after an audit without a split, so splitting alone
does not explain it. The audit/menu-close handoff remains a candidate, not a
confirmed root cause.

The installed JAR still hashed to `818367218E276A8438A20579C91D14F6956054C602526E4591F8A40D3760787B`;
the watchdog-fixed artifact hashes to `02AECBF108F0CEADDC41FE3FB955C03EE020528E16A662051D21EBC6E9A60FB2`.
Thus this launch does not validate the watchdog fix.

## Follow-up: running client at 21:10–21:21

The installed JAR's SHA-256 matched the previous build. At 21:10:52 the sell
command succeeded and the server reported the listing at 21:10:53. At 21:11:49
`/ah` also opened successfully. The first missing confirmation followed the
21:11:53 inventory split (map x47 into x1 and x46). Later, plain `/ah` also timed
out. This correlation does not prove that splitting caused the server silence.

A one-shot, read-only snapshot of the running client at 21:21:32 found:

- Connected and writable channel; zero pending Minecraft connection actions.
- Approximately 35.7 incoming and 34.2 outgoing packets per second.
- Player inventory menu (id 0), empty cursor, selected slot 8 holding map x1,
  and no screen open.

Thread inspection found the network event loops polling normally and the
persistence worker waiting on an empty work queue. These observations rule out
a queued command or blocked persistence worker at the sampled moment. They do
not establish that the server accepted a particular sell command.

### Confirmed secondary hang fixed

The persistence watchdog treated any retained checkpoint generation as an
unfinished write. A LIST receipt must remain available throughout confirmation
retries, even after it is durable. After two minutes, the watchdog erased that
receipt, and the next retry waited on `durable(0)`, which can never succeed.
This exactly matches the 21:13:53 watchdog warning followed by the 21:14:05
durability wait and 21:14:25 abandonment in the log.

The watchdog now checks the worker's accepted/durable generations, resets its
budget on write progress, and preserves all checkpoint receipts. A genuinely
stalled write pauses the running controller while retaining reconciliation
evidence. Three regressions cover durable LIST receipts across long retries,
late acknowledgement after a stall, and progress resetting the timeout.

### Isolated live auction-open check

At 21:23:08 the controller's normal emergency stop paused automation and canceled
its queued retry. At 21:24:38, after the existing server-response hold expired,
a one-shot diagnostic called vanilla `ClientPacketListener.sendCommand("ah")`.
Bytecode inspection confirms that `ChatScreen` uses this same method for slash
commands. No inventory input or confirmation click was submitted.

At 21:24:57, 19 seconds later, the client still showed no screen, menu id 0, an
empty cursor, and zero queued connection actions. The connection remained
writable with incoming and outgoing traffic. Thus the missing auction response
also occurs independently of the automation command queue and confirmation
parser in this session. The cause of that missing response is not established;
reconnecting and comparing a fresh session is the next separating test. The bot
was left paused. No live trade was submitted during this investigation.

The failing client's log (20:09:50–20:11:05) recorded six `/ah sell 15500`
dispatch calls, each with zero queue delay and no screen after about seven
seconds. A later `/ah` also received no visible response. That log already
contains the earlier queue isolation and post-dispatch timeout changes.
The provided Yes/No sell screen matches the existing DialogScreen handler.
These observations do not establish why the server did not answer.

A confirmed retry defect was visible at 20:10:17: the bot announced a 30-second
server-response hold, then sent another sell at 20:10:23. The command scheduler
now honors that hold for both execution and background commands. An intentional
hold does not consume the stuck-queue timeout or the confirmation wait budget.
Operations already awaiting a server response can still process that response.

Previously, a normally returning `sendCommand` call was logged as dispatched,
although Fabric/client command handlers can cancel that call before a packet
is submitted. CommandSendProbe now verifies that the matching signed or unsigned
command packet reaches the normal connection handoff. It does not bypass command
handlers and it does not claim the server received or accepted the command.
Missing or changed handoffs produce an explicit error instead of waiting for a
dialog that cannot arrive. Ordinary manual commands are not recorded by the probe.

Validation: full Gradle build, 107 Fabric tests passing, including 11 queue tests
and three handoff-probe tests. No live sale or server confirmation was submitted
as part of this investigation. The missing server response still needs a live
reproduction; checking whether manual `/ah` works during the same failure helps
distinguish a bot-specific send problem from a wider session/connection problem.

### September 28: recovery fixes from the supplied diagnostic log

The 21:24–21:57 log exposed three order-read aborts during queued command
backoff, before `/orders` was dispatched. The controller now suspends its
page-progress watchdog while the driver has a pending command and starts the
response window at dispatch. The driver's separate queue timeout remains in
place. Actual page progress still refreshes the navigation timeout.

Player-inventory swaps now register cleanup before sending the input. Before
controller recovery or another operation, cleanup closes the player inventory
and waits 600 ms, including after failed verification, a pause, or an exception.
It waits for an empty cursor and no screen, never closes a foreign container,
and discards cleanup associated with a replaced player connection. The normal
stack, selected-slot, source-slot, and durable-intent checks still run before sale.

An unchanged stack quantity no longer closes and recreates its position, which
previously reset preparation attempts to “1 of 3.” Owned damaged gear now takes
the descriptor-key listing path, including nearly-new gear whose pricing wear
band is empty. Plain-item buying restrictions are unchanged.

Validation: full Gradle build successful; Fabric suite has 151 passing tests
and one skipped mascot GIF export test. Regressions exercise a two-minute queued order
read through the controller, dispatch-relative timeouts, long progressing
sweeps, failed-swap cleanup and unsafe contexts, unchanged position identity,
and owned gear identity. No live server trade was used for validation. The
missing inventory close is a confirmed code gap and a plausible contributor;
the sanitized log does not prove the server's reason for every GUI timeout.

### October 7: order collection and own-listings navigation

The running client was listing and selling successfully, but logged a completed
kelp order that never reached Collect Items, a New Order step that stayed on Your
Orders, and a pull-back that landed on Orders instead of Your Items.

Several own-order transitions advanced `ownStage` before checking whether their
click was sent. A deferred click therefore left the driver waiting for a page it
had never requested. These transitions now advance only after a successful click;
cancel completion and navigation retry counts follow the same rule. A missing
order row also gets the existing opening window to load before being declared
absent.

Own-listings navigation now requires one unambiguous Your Items/Your Listings
control identified by its name and lore together. The arbitrary chest fallback
is removed. A late unrelated page during pull-back returns to the existing
bounded auction-reopen path instead of clicking controls on that page.

Validation: full Gradle build passed, with 166 Fabric tests passing and one
mascot GIF export test skipped. Five new regressions cover all six deferred
order transitions, late wrong-page recovery without resetting its retry budget,
the captured Your Items control, unnamed controls identified by lore, and
ambiguous or unidentified controls being refused.

Installed after a normal client shutdown, with the previous JAR backed up.
After relaunch, the client auto-connected and resumed at 13:37:00. It verified
eight existing auction listings, read 27 own orders, collected all 256 kelp at
13:37:12, and verified the first 64-kelp listing at 10,579 at 13:37:14. This
confirms the previously failing collection path completed on the live server;
it does not establish that every possible order/navigation failure is resolved.

### October 7: follow-up after the longer run

The 13:36 restart subsequently recorded 37 wall-clock expirations, seven order
navigation stalls, and nine pull-backs whose listing was absent. The message
"render thread throttled" was unconditional; it did not measure frame rate.
The public auction/order grid had to be stable before the driver could even
inspect its navigation button. Continuous unrelated row changes could therefore
block navigation while the client was still ticking normally.

Navigation to Your Items and Your Orders now requires stable evidence for the
selected control, including its menu, slot, item, count, and tooltip. Trading
rows and confirmations retain their full-page stability and identity checks.
Timeout diagnostics report ticks, page changes, current page and stage without
asserting an unmeasured cause. The New Order chooser gets one bounded navigation
retry while still on Your Orders; the final Create Order action is never retried
by that recovery. The outer bid-desk watchdog excludes queued commands and uses
the driver's latest step progress. Navigation/transport failures no longer
permanently exclude the affected item from future bids.

Listing page walks now wait for a changed, loaded page after Next, and count it
only when observed. An unchanged page, empty loading page, failed click,
interrupted walk, or page-limit stop cannot produce a successful partial audit.
Deferred clicks do not append the same page twice. A fully searched but missing
listing requests reconciliation and is labelled unverified rather than still up.
Some absences are real sales: at 15:05:29 the kelp pull-back failed, and the next
audit at 15:05:30 reconciled that position as sold. Such races cannot all be
prevented by changing timeouts, and absence alone is not new proof of a sale.

Validation: full build passes with 176 Fabric tests passing and one mascot
export test skipped. New tests cover focused navigation stability, retained
trade-page checks, bounded chooser retries, dispatch/progress-aware deadlines,
page-arrival acknowledgement, empty loading pages, operation cleanup, and
transient order failure classification.

Deployed on 2026-10-07 at 16:13 local time after closing Minecraft normally.
The installed JAR SHA-256 is
`D246CFFEBFAF0B9685C28C6EEC1FE096935A78518F4AF7040462864797646748`;
the previous JAR was backed up outside the mods directory. The client reconnected
and auto-resumed at 16:14:22. It read its orders, cancelled a map bid, collected
bone meal and verified a 64-item listing at 16:14:56. The initial auction-slot
audit still aborted at 16:14:32 because Next did not produce an observed page
change within eight seconds; the incomplete book was not reconciled and trading
continued. Deployment and trading resumption are verified, but this remaining
page-navigation timeout is not resolved. Filtered evidence is saved in
`artifacts/progress-fix/live-verification.txt`.

### Empty auction page / stale full-slot count (2026-10-07)

At 23:44–23:52, repeated audit and cancellation walks aborted with "Next listing
page did not change within 8 seconds". The HUD remained at 90/90 despite a live
Your Items page containing only vacant sell placeholders. The progress fix had
required a priced or collectible item to acknowledge a loaded next page; a fully
populated empty page could therefore never arrive. Next remains available on
the server's empty pages. Archived server captures identify vacant slots as
`minecraft:gray_stained_glass_pane`, count 1, with `List` / `Click to sell an item`.

Own-listing audits and searches now classify all 45 content slots. Each must be
a priced listing, a collectible expired item, or that explicit vacant control.
Air, unknown controls, missing slots and ambiguous prices cannot establish a
complete page. A fully vacant page acknowledges navigation and ends the walk,
even with Next present; its slots must settle for ten observations before use.
Rows gathered on earlier pages are preserved, and actual listed panes are still
counted as listings. No ledger or configured slot limit was edited manually.

Full Gradle build passed: 182 Fabric tests passed, one export test skipped.
Six new regression tests cover verified vacancy, incomplete menus, populated
to empty navigation, expired rows, unknown furniture and priced panes.
Installed JAR SHA-256:
`5D00B2C83CECB6F6631664983905C9AA0A988E65B68B69CEBF1BB2FC8C0A65FD`.
The prior JAR and pre-restart log are backed up under `artifacts/empty-slots-fix/`.
The new client auto-resumed at 23:53:46; its audit completed at 23:53:47 and
reconciled 90 stale listing records through the existing reconciliation logic.
Its summary retained one open record, rather than reporting 90 occupied slots.
Spruce-log and bone-meal orders were successfully placed at 23:53:55 and 23:54:00.
Filtered evidence is saved in `artifacts/empty-slots-fix/live-verification.txt`.

### Order planner idle after fills (2026-10-08)

The 09:13 startup audit correctly cleared the auction slots. Two spruce-log
orders were placed at 09:14:02 and 09:14:27 and collected, but the last own-order
read at 09:19:44 queued only a collection. After it finished, the bot repeatedly
checked auction prices and watched listings without rebuilding its order plan.
`deskReadDue` was only set by fill notifications, the first session read, or a
public order-house scan (configured for 20 minutes). New market snapshots did
not cause a plan refresh. This could leave an empty queue waiting for a fill
from an order it had never placed.

The idle desk now refreshes its own orders after 90 seconds, between operations,
independently of the public scan. Existing queued work finishes first. Attempt
and successful-read timestamps both pace retries so a failed read cannot spin.
No price-confidence, margin, allowance or spending settings were changed.
Current price evidence is also weak in several markets: a read-only analyzer
check around 09:32 gave spruce logs 0.278 confidence, bone meal 0.168, maps 0.140,
and blue glass 0.106 against the configured 0.2 bid floor. Several other markets'
latest accepted samples were over 16 hours old. Regular replanning fixes the
scheduling gap but does not make those markets automatically eligible.

Full build passed with 186 Fabric tests passing and one export test skipped.
Four new regression tests cover first read, periodic refresh, failure cooldown
and slow completion. Installed JAR SHA-256:
`1BF09572E25C9E3F693CB005B098788AB2A2B67F2CFC6CCC945380CD08BD222F`.
The prior JAR and pre-restart log are backed up in `artifacts/order-refresh-fix/`.
Live verification: client auto-resumed at 09:33:55 and placed a 192-log spruce
order at 09:34:05. It collected the fill and listed three stacks by 09:34:31.
After the auction watch ended at 09:37:27, the periodic refresh read Your Orders
without a new fill notification or public sweep triggering it. It queued and
placed another 64-log order at 09:37:36, collected it at 09:37:46, and listed it
at 09:37:49. Thus the refresh is eligible after 90 seconds but waits for the
current operation; it is not a promise of an order every 90 seconds. Evidence:
`artifacts/order-refresh-fix/live-verification.txt`.

## 2026-10-09: Multi-stack buy-order repricing

The live client cancelled sea-lantern orders at 2,600 per item with reasons
claiming the market was worth only 7 or 4 per item, then placed replacement
orders at 2,625 or 2,730. Order review passed the entire requested quantity
(320 or 576) into the resale valuation. Those quantities selected `OTHER`,
whose prices are already per item, then divided that price by the order
quantity again. This also made profitable upward chasing fail its ceiling.

`OrderBidPricing` now values multi-stack orders using the item's ordinary
resale stack. Exact smaller stack markets retain their own prices. Unknown
stack prices return no ceiling rather than inventing a price from unrelated
single/OTHER statistics. Fees, profit floors, confidence and live-price gates
remain in force. Upward chasing stays enabled through `orders.chase`, with
its existing margin and cooldown; merely considering a chase while collecting
pending fills no longer starts that cooldown. Successful cancellations request
an own-order refresh after queued work, before starting another auction watch.

Regression tests cover quantities 64 through 960, rising/falling markets,
missing stack evidence, smaller stack sizes, fees, live price caps and the
upward-chase ceiling. Full Gradle build passed (203 Fabric tests passed, one
export test skipped). The build is staged at
`artifacts/order-repricing-fix/doughbay-0.1.0+26.2.jar`.

At the user's explicit request, Minecraft was closed and this JAR installed,
with the prior JAR saved as `artifacts/order-repricing-fix/previous.jar`.
Installed SHA-256: `C3566BBE11C7B0960AB2C6CA6DDF0B0CF68757D1566AA6DCFF649DA952210FA8`.
The client reconnected and auto-resumed at 14:58:49. It confirmed seven live
auction listings, cancelled an outbid deepslate-diamond-ore order at 8,400
against a competing 10,300 bid, and placed orders for 384 white stained glass
at 1,035 each and 192 sea lanterns at 2,730 each by 14:59:09. Logs are retained
in `artifacts/order-repricing-fix/live-verification.txt`. This confirms restart
and trading activity; a complete replacement/fill/sale cycle is not yet verified.

## 2026-10-09: Your Orders recovery stops yielding to auction watches

At 16:36:45 and 16:40:30, own-order reads aborted at stage 0 on the public
`Orders (Page 1)` screen. Each failure was followed by a multi-minute auction
watch, despite outstanding sea-lantern fill notices. The last new listing before
that report was at 16:28:53; existing auctions continued to sell.

The driver now closes and reopens `/orders` once when public-page navigation
stalls. This retry applies only before reaching Your Orders, never after a
create/collect/cancel confirmation. The controller retries failed reads after
5 seconds, then 15 seconds, then at most once per minute, respecting server
holds. Recovery stays ahead of auction watching and public-book sweeps; stock
already in hand can still take the normal listing path. A failure to navigate
does not consume item-specific collect or cancel attempts.

Code inspection also found that a player tick counter reset could make the
click cooldown's elapsed time negative, blocking clicks until the new counter
caught up with the previous world's value. The click gate now clears that old
cooldown when the counter moves backwards. This is a regression-tested fault;
the old logs did not record tick values, so its role in this incident is not
confirmed. New navigation-retry logs include both counters for diagnosis.

The full build passed. Six new regression tests cover the tick reset, bounded
navigation retries, recovery backoff, success reset and failure classification.
At the user's explicit request, Minecraft was closed, the previous JAR backed
up, and this build installed from `artifacts/order-navigation-recovery-fix/`.
Installed SHA-256: `13815465BC868F151EA736ACD1F15C94A2DF896033AFF12197679A83D33BC389`.
The client reconnected and auto-resumed at 16:55:17. Its auction audit found
two remaining listings. The first own-order read still stalled at stage 1;
the one-time reopen ran at 16:55:45, followed by a failed read at 16:56:10.
The new controller recovery retried at 16:56:15 instead of starting an auction
watch. Thus deployment and the recovery scheduling are verified, but successful
navigation, collection and a new listing are not yet verified. Evidence is in
`artifacts/order-navigation-recovery-fix/live-verification.txt`.
