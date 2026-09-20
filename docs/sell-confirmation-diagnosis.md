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
