# GoNuts changelog

What each build changed, newest first. Times are UTC. The mod shows these in
the update notice, after an update installs, and under What's new on the
About tab.

## 2026-10-14 21:55 UTC
- Fixed: with two clients sharing one ledger, retail singles could be prepared with a position id the other client had already used, which refused the save ("Retail child position id is already in use"), stopped all further record-keeping and left the bid desk pausing and auto-resuming for hours without being able to stop; ids are now unique per account and a refused save no longer takes the ledger writer down with it. (#5)

## 2026-10-14 17:21 UTC
- Bug reports can now include a locally filtered GoNuts log excerpt after an authenticated receiver-capabilities check. Include log is enabled by default when the receiver advertises schema 2; older receivers retain diagnostics-only schema 1. The attachment is shown in the preview before explicit public-report consent, and changing the attachment choice requires preparing and reviewing again.
- Privacy: the excerpt regenerates recognized state/action/exception events and verified GoNuts source frames instead of copying log messages. Chat, usernames, identities, coordinates, credentials, addresses, personal paths and foreign stack frames are omitted. Both client and receiver enforce byte and line limits; there is no raw-log fallback.
- Compatibility: existing numeric GoNuts API origins are accepted only after an off-thread DNS check confirms they match gonuts.at. No VPS IP is embedded in the JAR, report traffic always uses HTTPS, and unrelated proxy or official DonutSMP credentials remain blocked.
- Fixed the Report bug button being hidden at ordinary GUI widths and widened the Feature request button so its label remains readable. Added an opt-in offline GUI integration test, excluded from production JARs, and shareable screenshots of the About entry point and report form.
- Verified actual GUI submission creates a labeled GitHub issue with the filtered excerpt, private samples and the install token absent. Retrying the same identifier returns the same issue. Feature requests remain separate and are considered, not guaranteed.

## 2026-10-14 16:51 UTC
- Added direct reviewed bug-report submission over HTTPS to gonuts.at/v1/bugs, matching the receiver's exact six-field schema, fixed state enum and five counters. The client reuses only a GoNuts token configured for gonuts.at; official DonutSMP keys and unrelated proxy keys are never sent to this receiver. GitHub fallback remains available.
- Added explicit public-report confirmation and a scrollable local preview. No raw logs or attachments are uploaded. Reports include the mod build timestamp where supported so builds sharing the same JAR name can be distinguished. Private chat is excluded from diagnostic counting as well as report output.
- Success and duplicate replies provide an Open issue button. Retries reuse the unchanged report's idempotency key; rate limits honor Retry-After, and receiver/GitHub outages leave the report available for a manual retry. HTTP requests run off the game thread, do not follow redirects and bound response size/time. Tokens and response bodies are never logged or shown.
- Added a separate feature-request form stating requests are considered, not guaranteed. Direct reporting needs the VPS GitHub Issues credential to be installed; a 503 is shown as temporary unavailability, never a successful submission. Sanitized log attachments and automated draft-patch generation still require their own receiver/worker implementation.

## 2026-10-14 16:22 UTC
- Added About → Report bug: describe the problem and expected behavior, prepare a report locally, review it, and copy it into GitHub's issue form. GitHub submission is explicit and public; no credentials are bundled into the mod and no report is uploaded in the background.
- Privacy: raw logs are never attached. Only a bounded current-log sample is reduced to fixed diagnostic counters and a recognized automation state. Chat, player names, log messages, settings, databases and process arguments are excluded. Typed fields are filtered for common credentials, URLs, addresses, UUIDs and paths and still require user review. Failed preparation blocks sharing instead of falling back to raw logs.
- Added a daily GitHub bug-report digest at 16:00 UTC, with links to new reports and an issue template requiring a privacy check. Reports are treated as untrusted input. VPS direct delivery and automatic draft-patch generation are not deployed; documented the receiver contract and review requirements for the VPS agent.

## 2026-10-14 15:59 UTC
- Fixed: Skip the restart waiting room now disconnects and joins directly through East or West instead of sending /leave. It stays on the working regional address; it no longer reconnects to the main address after a short regional visit. Failed or limbo joins alternate through the configured same-network addresses with a retry delay.
- Recovery starts only after the server reports actual proxy limbo. If the server releases the waiting room while recovery is arming, the existing connection is kept. Disconnects wait for an idle driver and an empty cursor, and a regional world must remain stable with a newly read balance sidebar before trading continues; the old connection's cached balance is not accepted as proof.
- Fixed: ordinary two-minute reconnects no longer capture an intentional restart hop. Staff Watch's actual alert disconnect and 5-10 minute cooldown retain priority. Trading stays parked while restart recovery owns the connection. Restart departures remain intentional, so they do not trigger kick alerts or click-spam pacing recovery.
- Restart resume retains the existing session's trade/spend caps and uses safe inventory/exposure reconciliation. It cannot rearm an operator-paused or stopped session, and it does not invoke the operator-only force-reset paths. Updated Session help and removed the obsolete /leave-versus-address selector; existing Skip setting and regional-address list remain available.
- Verified regional addresses remain authorized when donutsmp.net is on the bot's server allowlists. This does not change market selection, buy limits or trade pacing.

## 2026-10-14 09:26 UTC
- Fixed: clicking Follow known bots again restarts a stopped queue even when its names are already on the list. An explicit known-bot request takes priority over automatic counterparties, retries unavailable/unanswered bundled names and preserves confirmed or in-flight follows. The client cap accounts for existing confirmed names without double-counting overlaps; normal command pacing, menu safety and server refusal handling remain in force.
- Fixed: English item names in DonutSMP follow notices now resolve against Minecraft's default English translations as well as the client's localized/resource-pack names. Previously, a non-English item-name table could leave notices unresolved and prevent rival profiles.
- Fixed: clients sharing a hive folder now keep follow confirmations per account. Shared legacy confirmations are preserved but are not imported as proof that every account follows the same players; the existing toggle-recovery path restores follows that the server reports were already present.
- Changed: in-game and web follow buttons report queued intent rather than claiming server confirmation, and remain available for retry. The Rivals page and minute-by-minute diagnostic logs distinguish confirmed follows, queued commands, unavailable/unanswered names, received chat notices and unresolved item names. Empty profiles explain the requirement for eight orders/listings per player; purchases, payments and logins alone do not qualify. A follow count is not proof of active trade data, and asking prices remain distinct from completed sales.

## 2026-10-14 08:28 UTC
- Fixed: recent-auction watches and live cheapest-listing buys now skip listings whose recognized seller field belongs to another account in the same hive, as well as the local account. This also covers filled-container watch rows. Accounts can share item markets without buying each other's listed stock through these paths.
- Seller checks use recognized ownership fields and exact account names; arbitrary username mentions in item lore do not block unrelated sellers. Normal non-hive listings remain eligible, and no spending or confirmation checks are relaxed.

## 2026-10-14 07:36 UTC
- Fixed: auction watches previously reserved the automation controller for 24 looks, often several minutes with no purchase, delaying order collection, inventory listing and stale-stock maintenance. Pacing now includes a maximum time per auction watch, defaulting to 45 seconds. The limit ends only an unselected watch; a selected purchase keeps its normal confirmation and inventory verification. Deep box sweeps receive at least 150 seconds, and 0 disables the limit.
- Fixed: a watch ending without a qualifying offer is treated as a normal market outcome rather than an unanswered server operation. Repeated empty watches no longer trigger artificial server-strain backoff.
- Click spacing, command spacing, purchase ceilings and accounting rules are unchanged. This improves scheduling; it does not guarantee faster sales or a particular profit.

## 2026-10-14 06:47 UTC
- Fixed: the keyless market feed could fail with SQLITE_BUSY_SNAPSHOT when another client committed to the shared ledger while its order query was open. The feed now reads and closes the order snapshot before inserting derived observations, avoiding the stale read-to-write promotion.
- Validated with two real SQLite connections: the previous read/write pattern reproduces the error, while the new sequence allows the sibling commit and subsequent write. Historical warmup still emits no artificial sales, and the next live delivered-count increase is retained.

## 2026-10-14 05:58 UTC
- New: Buying settings have a separate new-purchase market focus. A focused item list limits auction purchases and new buy orders without disabling collection, listing or repricing of stock already owned. Blank keeps normal selection.
- Changed: buy-order candidates rank by expected profit per resale lot, weighted by diminishing market volume, rather than percentage margin multiplied by volume. A cheap, busy market no longer wins solely because it has a large percentage spread.
- Changed: changing the buy focus cancels unfilled orders outside it and drops queued new bids before they are sent. Already-delivered materials continue through existing inventory and sale handling. Live price checks stay focused on buying candidates and stock still held. Normal item restrictions, price evidence and capital limits still apply.

## 2026-10-14 04:58 UTC
- Fixed: auction slot audits no longer add a balance-rounding allowance to sale income. An unchanged balance and no auction earnings can no longer book missing listings as sold. Missing balance measurements supply zero sale budget instead of an unlimited allowance.
- Fixed: inferred sales use the larger of unexplained cash movement and server auction earnings, consume that budget once, and require a positive sale value. Buy-order spending, refunds and already-recorded sales remain accounted for separately. The audit log now shows the evidence and budget used.
- Existing history is preserved. This prevents new tolerance-only sale/profit entries; it does not retroactively certify or rewrite old inferred sales. Existing restart recovery and Resume controls are included in this build; older running clients must close and reopen to install a downloaded update.

## 2026-10-14 04:16 UTC
- Fixed: paced auction filters no longer consume click attempts before a click is sent. Auction page turns now keep waiting for their click gap instead of closing the watch early or advancing page counters for unsent clicks. Each sent navigation click receives its configured settle delay.
- Fixed: public order-house scans retry deferred Next Page clicks on the next tick. They no longer mistake an unsent click for a server response that failed to arrive, which could add the full response timeout to every page and stall scans for many minutes. Retry limits count sent clicks only.
- Fixed: order-house navigation settles its page title and controls independently of continuously changing public delivery counters. Transaction screens retain their full verification. Safer click pacing and click-spam recovery remain enabled.

## 2026-10-14 03:18 UTC
- New: unexpected Donut SMP disconnects now appear in Evasion history and send a dedicated Discord bot notification with the account, server reason, recovery action and reconnect policy. Enabled alert webhooks receive the same event. Pending bot notifications survive restarts in a separate queue created by schema migration 18.
- New: an explicit Click-Spam kick enables slower pacing, disables fast listing, and progressively increases click, command, step and reaction delays for the affected account only. Recovery is enabled by default, bounded, persistent, and can be disabled under Pacing. It never automatically speeds the account back up.
- Fixed: local pacing overrides are no longer copied into shared settings when another setting is saved. Intentional quits, Staff Watch departures, GoNuts evasion/restart departures and server transfers do not trigger kick recovery. Ordinary reconnects retain the two-minute interval; Staff Watch retains its five-to-ten-minute delay.

## 2026-10-14 02:43 UTC
- Fixed: existing version-16 ledgers now receive the Staff Watch notification table through a new version-17 migration. This stops the repeating "staff notification pending: no such table" warning and restores staff notification storage on upgraded installations. Existing evasion history and queued/delivered notifications are preserved.

## 2026-10-14 02:29 UTC
- Fixed: HeadlessMC clients could crash when Staff Watch first displayed a staff row. The optional HUD now skips native graphics initialization when OpenGL or GLSL is unavailable. Staff detection, disconnect/reconnect handling, and web/Discord alerts continue to run.
- Fixed: a native HUD initialization or rendering failure disables that overlay for the session instead of closing the client or retrying every frame. Normal graphical clients retain the Staff Watch HUD.

## 2026-10-14 02:10 UTC
- New: Staff Watch is part of the main GoNuts GUI. Open its Operations tab or use /staffwatch for detection settings, live staff rows, and the reconnect server selector.
- New: choose Donut SMP Main (donutsmp.net), East (east.donutsmp.net), or West (west.donutsmp.net). Staff alerts close the connection immediately, then wait 5â€“10 minutes before reconnecting. Other disconnects and failed connections retry every two minutes.
- New: staff and vanish panels sit at the top center, with the special watcher warning directly below. The warning remains visible over menus after leaving. Sounds are off by default.
- New: staff escapes appear in the shared web/Discord Evasion history. The Discord bot sends a dedicated notification with the client, watcher, time, destination, and reconnect delay; unsent notifications remain queued.
- Changed: a staff disconnect pauses trading and takes priority over other reconnect helpers. Cancel stops its reconnect chain. Single-player worlds are never disconnected by Staff Watch.
- Appearance choices remain available: Classic, Midnight, Mint and Rose themes, plus Original, Mochi and Sprout animated mascots. Themes and characters can be selected independently.

## 2026-10-14 02:00 UTC
- Fixed: the pack cleanup could stick on an item it was never going to sell. With something in your inventory that the bot may not trade (a few granite, say, while you trade only your own price list), it chose that stack again every tick for a minute, wrote "pack sweep listing" to the log each time (thousands of lines), and never reached the stacks behind it. It looked as if the bot had wandered off to a random market. Nothing was listed or sold by it, and trading carried on underneath. Such stacks are now passed over.

## 2026-10-14 01:55 UTC
- New: an Appearance tab on the GoNuts screen. Pick one of four colour themes (Classic, Midnight, Mint, Rose) and one of three mascots (Original, Mochi, Sprout), each on its own. There is a live preview of every mascot mood, and the choice is saved in appearance.json and kept across restarts.
- The Discord and web panel pictures show the mascot you picked.
- Nothing about trading changes: these are looks only. Classic with the Original mascot is what you had before, and it stays the default.

## 2026-10-14 00:54 UTC
- Fixed: "Run from hostile mobs within" could not be set above 24 blocks. An older setting of the same name, with that limit, was still in the list, and it won. A larger number was quietly cut to 24. There is one setting now and it goes to 64.
- Fixed: with that setting on, an older check ran beside the new one and ignored its rules. It ran from endermen, it waited up to a minute for a trade before leaving, and it had no limit on escapes in a night full of mobs. Mobs within the distance now go through the one set of rules. Archers and arrows keep their own settings, and no longer wait a minute for a trade either.

## 2026-10-13 23:39 UTC
- Fixed: amounts of a billion or more were still written in millions, so a bank of 1.01 billion showed as $1014.00M. They now read $1.01B on the Discord panel and its pictures, the GoNuts screen, the web panel, the charts and the webhook reports.

## 2026-10-13 23:32 UTC
- New: "Run from hostile mobs within" (Settings, Evasion). Off (0) unless you set a distance. A hostile mob that close sends the bot away before it can be hit, the same way a player coming close does. A random teleport lands an unarmoured, standing-still account in the open, and one bot died three times in a day to whatever walked up.
- Only mobs that attack unprovoked count (not endermen or zombified piglins), and a mob the bot cannot see, such as one in a cave underneath, only counts once it is within ten blocks.
- After five escapes from mobs in ten minutes the distance is ignored for the next ten, so a night full of mobs does not turn into nothing but teleporting. Being hit still sends the bot away at once.

## 2026-10-13 23:25 UTC
- Fixed: a bot being hit waited up to a minute for the trade in flight before it escaped. That wait was meant for a player walking up from a distance. A mob hits an unarmoured account for 4.5 at a time, so the bot took its first hit, kept waiting for a listing to finish, and was dead in under ten seconds with its teleport still unsent. A hit now waits a second and a half at most.
- A death now shows on the Evasion page (in game, on Discord and on the web panel), with what was being held and what was lost. It was only in the log and on the webhook before.
- The damage line in the log and in chat now names the mobs within twelve blocks. It used to say "nobody the client can see" when it meant no player, with a zombie standing on the bot.

## 2026-10-13 21:42 UTC
- New: "Least a single must earn" (Settings, Selling). Off (0) unless you set it. With "Piece out premium markets" on, an item is sold one at a time only when one of them earns at least this much over its cost. Under that, the stock goes up in stacks.
- Why: every listing takes the bot the same few seconds whatever it is worth, and a cheap item that sells the instant it is listed gets listed again at once. One bot spent an hour selling ender chests for 1,600 profit each, 421 of its 546 sales, and made a third of what the hour before had made. A lot already set aside for singles follows the floor too: what is left of it goes up in stacks.

## 2026-10-13 21:30 UTC
- Fixed: after /leave took the bot out of the restart waiting room, it stayed paused. A second restart showed /leave does what it says: the bot was in another region within a second. But the step that follows waited for a balance to show on the sidebar before believing it, did not see one, and so never sent /rtp or started the session again. Being out of the waiting room for a few seconds is now enough.
- The log now says what it is waiting on while it waits, so a slow one can be explained afterwards.

## 2026-10-13 21:08 UTC
- Fixed: "Skip the restart waiting room" could disconnect a bot that was already trading again. At the first real restart the server parked the bot and put it back by itself four seconds later, just before the bot sent /leave. The bot then waited ninety seconds for a move that had already happened, decided /leave had failed, and left and rejoined the server for nothing.
- It now tells "still parked" from "let go" by whether the client is still in the waiting room world, not by timing. If the server has already put it back, no /leave is sent. And it never disconnects on its own: if /leave does not work it simply waits the restart out.
- The bot also resumes sooner after a short restart: being put back within a few seconds is now recognised.

## 2026-10-13 20:05 UTC
- Fixed: the bot could drown. Players flood the server and a random teleport can land in water. The evasion guard only reacted once damage started, and then waited for the trade in flight before escaping, so the bot lost two health a second while it finished a listing and died as its teleport went out.
- New: "Get out of water" (Settings, Evasion), on whenever the evasion guard is on. When the bot's head is under water and its air is going down, the session stops and it teleports away at once. That is about thirteen seconds before the first damage, and nothing is waited for. With teleports switched off it leaves the server instead.

## 2026-10-13 16:52 UTC
- New: a Flips page on the Discord panel. It shows your Flips list with what each item is bought and sold at, the market price, and how much is on order and listed. Pick an item, then press "Sell price" or "Buy price" and type the new figure (20k and 1.5m work). "Add item" takes an entry typed out, like `obsidian 250 900`, and "Remove" takes the selected one off.
- A price that cannot be right is refused with the reason, and the list is left as it was. On the web panel the same list is edited on the Your prices page.

## 2026-10-13 16:42 UTC
- New: "Run from withers" (Settings, Evasion), on whenever the evasion guard is on. Players spawn withers where random teleports land, and the guard only ever looked for players, so a wither next to the bot went unseen until it killed it. When a wither's boss bar shows at the top of the screen the session now stops and the bot teleports away at once, without waiting for a trade in flight. With teleports switched off it leaves the server instead.
- A bar that is still showing after three escapes in five minutes is taken for one of the server's own and left alone for half an hour, so a server notice can never send the bot teleporting for ever.

## 2026-10-13 15:47 UTC
- Fixed: tracking a very long list of items stopped the bot pricing the markets it could actually trade. The keyless price sweep checks one market at a time, the one checked longest ago first, and every tracked item counted as never checked. With all 1,544 items ticked it worked through spawn eggs and dragon heads for about twenty hours a lap, and the markets with real sales were not priced again.
- Now the markets the bot holds, bids on or has valued come first, and the rest of the tracked list gets one sweep turn in four. Tracking everything is still slow to cover, so track the items you care about rather than all of them.

## 2026-10-13 15:18 UTC
- New: "Skip the restart waiting room" now gets out the way the waiting room itself offers. It sends /leave, which puts you in another region of the network, then /rtp to get away from where that set you down, and starts the session again there. The auction is the same in every region.
- "Way out of the waiting room" (Settings, Session) picks between that (1, the default) and the earlier way of rejoining through another address (2). If /leave ends up somewhere that is not a world to trade in, the client rejoins the usual address by itself.

## 2026-10-13 15:12 UTC
- Changed: "Skip the restart waiting room" starts the session again by itself once it is back in the world. It no longer needs "Resume after leaving the server" switched on: that setting is for when you leave the server, and this leaving is the bot's own.

## 2026-10-13 15:09 UTC
- Fixed: an item on your Flips list went up at three times what it cost when your sell price was higher than that. The bot has a safety cap for prices it works out itself, and it was applied to prices you set. An item ordered at 250 to sell at your price was listed at 750 instead. Your price is now listed exactly as you set it. Prices on the Flips list are per item, so check that the sell price is what one item should sell for.
- Fixed: an account that logged off hurt was treated as attacked every time it joined. The game starts a join believing in full health and is told the real figure a moment later, and the bot read that as a hit, fled, and when the teleport was refused it left the server and joined again, round and round. Health reported in the first five seconds after joining or respawning is no longer taken for damage.

## 2026-10-13 15:04 UTC
- Changed: after a server restart the bot starts again as soon as it is put back in the world. It used to wait a fixed ten minutes from the restart notice, even when the server was back after four. It now takes being moved back to a world with the money sidebar showing as the end of the restart.
- New: "Skip the restart waiting room" (Settings, Session). Off unless you turn it on. When the server parks you for a restart, the client leaves, joins through another address of the same network (east and west of your usual address, or the ones you list in "Addresses to come back in through"), then joins the usual address again, which puts it straight back in the world. The session stays stopped until the world is really there.
- The server warns that leaving the waiting room can lose your position, so only use the skip where that does not matter. It only ever joins addresses of the same network as the one you were on.

## 2026-10-13 14:28 UTC
- New: the game can be closed from outside it. A line reading `!quit` in `send-command.txt` in the game folder closes the game the same way closing its window does, and it works on the title screen too. This is for running the game without a window, where there is no window to close and ending the process by force can drop whatever is on the cursor.

## 2026-10-13 14:20 UTC
- Fixed: a lot kept for selling one at a time could freeze its whole market. If the bot had it recorded as one more than the inventory really held (127 recorded, 126 carried), it waited for the missing item for good: nothing of that item was listed, and no new orders went up for it either, for hours.
- Now, when the inventory has held the same smaller count for three minutes, the missing part is written off, the log says so with the cost, and the bot carries on selling what is really there. A lot is never written off whole: an inventory with none of the item in it changes nothing.

## 2026-10-13 06:54 UTC
- New: an exact name in the item lists. An entry like `glass` covers everything ending in it, so it also means every stained glass. Written `=glass` it means plain glass and nothing else. Works in "Only trade these", "Never trade these" and "Sell off and stop buying these".
- Changed: with the markets split between accounts, an item on the Flips list is ordered by one account only, the one the market belongs to. Two accounts each bidding just over the top order would have been bidding over each other.

## 2026-10-13 06:40 UTC
- New, for two or more accounts sharing one ledger: "Split the markets between accounts" (Settings, Multi client). Off unless you turn it on. Two accounts in the same markets sell to the same buyers and bid against each other. With the split on, each market has one account buying in it. The accounts divide the markets between them by themselves, and each pulls the orders it had up in the other's markets.
- Only buying is split. An account still lists, reprices and sells everything it already holds. Items on the Flips list are not split.
- If an account goes offline it keeps its markets for ten minutes, so a restart changes nothing. After that the others take them over until it is back.
- "Reshuffle the split" deals the markets out differently if one account ended up with the better half. The log says every ten minutes how many markets each account has and what share of the sales they carry.

## 2026-10-13 05:03 UTC
- Fixed: an order from your Flips list at a price of 1,000 or more was cancelled and placed again every couple of minutes. The order house shows prices rounded (1,150 reads as 1.1K), and the bot took the rounded figure for a different price from the one you set. It now knows the order is its own and leaves it up.
- Changed: "Over top order" bids at least 100 over the best order when prices are in the thousands (100,000 over in the millions). The order house rounds what it shows, so a smaller step could land under the order it was meant to beat.

## 2026-10-13 04:58 UTC
- Fixed: after "Trade only these" or "Wind down" was switched on, the bot still placed the orders it had already planned, for a few minutes. Steps the new mode does not allow are now dropped the moment they come up.
- Changed: with "Trade only these" on, the orders the bot had placed for other items are cancelled (the money comes back), so they stop buying. Turning it off lets the bot place its own orders again.

## 2026-10-13 04:27 UTC
- Changed: with "Trade only these" on, the bot no longer price-checks markets of its own choosing on the auction either. The only pages it opens are your orders, your listings, and a look-up of your own items. The background price sweep, which checked every market one at a time, is off in that mode too.

## 2026-10-13 04:17 UTC
- Changed: with "Trade only these" on, the bot no longer reads the whole order house to pick markets. That sweep takes minutes and only serves the normal trading. Your own items are still looked up by name.
- Changed: with "Trade only these" on, an item sold one at a time may fill every auction slot you have, not just the "Singles up per market at once" number.
- Changed: "Singles up per market at once" can now be set as high as 90 (it stopped at 30).

## 2026-10-13 04:00 UTC
- Changed: the Flips tab has an item picker. Under your list is the same grid of item icons the Items tab has. Type part of a name in the item box to narrow it, scroll for more, and click an icon to choose it, so the name never has to be typed out. Items already on your list are ringed in green, and clicking one loads it to change.
- New: right-click any item on the Items tab to set it up on the Flips tab.
- Changed: each row of your list on the Flips tab shows the item's icon. Click a row to load it into the boxes, change any of its prices or options, and press Save.

## 2026-10-13 03:56 UTC
- Fixed: an item you sell one at a time (Flips, "One at a time") waited for a whole stack to be delivered before the bot collected anything, so selling started late. It now collects the first items as soon as they arrive and starts selling while the order is still filling. After that it collects eight or more at a time, or whatever is left once the order is done.

## 2026-10-13 03:36 UTC
- New: a Flips tab in the sidebar, under Items. It is "Your own prices" with boxes to fill in, so nothing has to be typed as a list. Pick the item, the most to pay for one, the price to sell one at, how many an order asks for, whether it bids a fixed price or just over the top order (and by how much), whether it sells whole stacks or one at a time, and an optional total and budget. Press Add. Click a row to change it, or Remove to take it off.
- The tab shows each item's open order, how many were bought, what each cost, how many are held, and what the market pays.
- New: "Trade only these", a button on the Flips tab (also "Trade only your own price list" in Settings, Orders). On, the bot buys nothing except what is on your list: no orders of its own choosing and no auction buys. What it already holds is still sold. Off, as it starts, your list runs alongside the normal trading.

## 2026-10-13 03:03 UTC
- New: big orders of things that do not stack. In "Your own prices" the number after the x is how many an order asks for when the item does not stack, up to 999: `totem of undying top 45k 50k x200`. The bot collects what fits in the pack, lists it, and goes back for the rest, which stays on the order until then.
- Changed: collecting from any order now stops while four slots of the pack are still free, and leaves the rest on the order for the next trip. A pack filled to the last slot could not list, reprice or buy.
- New on the web panel: a "Your prices" page. It has the price list to edit, and for each entry what is on order, how many were bought and for how much, what each cost on average, how many are held, what they are worth at your price, the profit if they sell, and what the market pays now.

## 2026-10-13 02:54 UTC
- New in "Your own prices": bid just over the top order. Write `top` or `top+100` before the prices, like `observer top+100 600 900`. The bot asks the order house what the best order for that item pays, bids one step or that amount over it, and never more than your buy price (600 here). If someone outbids it, it moves the order up again, at most once every ten minutes. If being top would cost more than your buy price, it places no order and says so in the log.
- New: `total 1000` stops ordering once that many have been bought, and `budget 2m` once that much has been spent on the item. Changing the entry starts the count again.
- All of it together: `observer top+100 600 900 x15 singles total 1000`.

## 2026-10-13 02:44 UTC
- New: sell one at a time. In "Your own prices", add the word singles to an entry, like `observer 500 900 x15 singles`. The bot orders the stacks at your buy price and sells them one item at a time at your sell price, never as a stack.
- "Singles up per market at once", under Selling, sets how many are listed together (5 unless you change it, up to 30). While a lot is still being sold, the bot does not collect or order more of that item; what is not collected yet stays in the order.
- Fixed: a listing at a price you set could be undercut to match the cheapest live listing when it was pulled back and listed again. It now goes back up at your price.

## 2026-10-13 02:40 UTC
- New: wind down, a soft stop. In Settings, under Session, turn on "Wind down (soft stop)". The bot stops buying: no new orders, no auction buys. It cancels its open orders (the money comes back) and collects what they already delivered. It keeps listing, repricing and selling what it holds on the auction, and ends the session by itself when nothing is left.
- Pressing Start switches it back off. It is off unless you turn it on, and it does not travel in a settings code. Accounts sharing one settings file wind down together.

## 2026-10-13 02:27 UTC
- New: your own prices. In Settings, under Items, "Your own prices" takes a list of items with a buy price and a sell price you choose, like `diamond 80 120, golden apple 4k 5.2k x3`. Prices are per item, and x3 means three stacks an order. Blank, as it starts, changes nothing.
- For an item on the list the bot keeps a buy order up at your buy price, lists what the order delivers at your sell price in the stacks it arrives in, and leaves the listing at that price until it sells. It does not raise the order to follow the market, pull it for being old or outbid, or walk the listing down.
- It also stops buying those items on the auction, so they only come in through your order.
- Guards: the sell price has to be above the buy price. An order still has to fit your per-purchase cap and the money left. The bot stops re-ordering an item while a full set of its listings is still up unsold. A buy price over three times what the item sells for is treated as a typo and not ordered. Anything it will not order is said in the log, and the web panel says at once if an entry cannot be read.
- Needs "Place bids" on, like any other order.

## 2026-10-13 01:05 UTC
- Fixed: two accounts sharing one folder (multi mode) with no API key each thought it was running alone. The Discord panel showed one account, and both clients posted over each other. The clients now find each other without the API: one of them hosts the panel, both accounts are on it, and the account picker (everyone, or either one) is back.
- Fixed: in multi mode each account now keeps its own order and balance records, in a folder under its own name. They were single shared files, so after a restart one account could load the other's open orders and bank balance. An account running alone keeps its files where they were.

## 2026-10-13 00:26 UTC
- New: the web panel has real pages for Settings, Items and Rivals, laid out like the screen in game. They replace the Tune and Rivals pictures. The other pages are still pictures for now and come next.
- Settings: every setting in its group, with search. Tap a row to read what it does and change it; switches flip in place. Presets, reset to default, the item lists, and copying or pasting a settings code all work from the browser. Changes apply at once, with no restart.
- Items: the item grid with search, a group filter, and All, Included, Excluded or Tracked. Tick items and press Exclude, Include, Clear, Track or Untrack, the same buttons as in game.
- Rivals: the headline cards, the rivals table, Underdog and Repeat buyers. Tap a player for their figures, their hours and their trades, and to include or track their markets. The "Follow known bots" button is there too.
- The status webhook is never sent to the browser and cannot be changed from it. The PIN and the Wi-Fi switch are still set only in game.
- Fixed: a setting kept in doughbay-local.json (one client's own file) went back to its old value at the next start, or as soon as a second account saved the shared settings. A change made in game or from the web panel is now saved to that file too.
- Faster: ticking many items on the Items tab and pressing a button saves each list once, not once per item.

## 2026-10-12 22:17 UTC
- New: follow the known bots with one click. The Rivals tab has a "Follow known bots (500)" button. It adds the 500 busiest seller accounts to your follow list, turns following on, and raises the follow limit to fit them if yours was lower. Nothing to download and no file to edit. Names you already follow are left alone, and a name the server does not know in your region is skipped.
- The bot follows them a few seconds apart in the background, so the full list takes about 40 minutes. Their orders and listings then show up on the Rivals tab and feed its prices.

## 2026-10-12 18:51 UTC
- Changed: settings codes no longer carry the follow settings. Whether an account follows other players, whom it adds, and how many is left as it is on the account that imports a code, the same as slot counts. A code made by an older build that still has them in it is accepted, and those values are ignored.

## 2026-10-12 16:37 UTC
- Fixed: the bot freezing with "Multiple distinct exact sale notices are ambiguous" until someone pressed Resume. It happened when two identical stacks sold to different buyers moments apart, which is normal on a busy book, and it stopped one trader three times in thirteen hours. The second sale is now given to another listing of the same item, or the auction page is recounted if there is none, and the session keeps running.

## 2026-10-12 15:31 UTC
- Removed: the row of slot squares at the top of the web panel. The Auction slots tile already gives the count, and the row took a lot of room to say the same thing.

## 2026-10-12 15:25 UTC
- Changed: the web panel is now set in a pixel font, so it reads like the screen in game. The font is Monocraft by Idrees Hassan, bundled with the mod under the SIL Open Font License; nothing is fetched from the internet.

## 2026-10-12 15:13 UTC
- Changed: the web panel now looks like the GoNuts screen in game. Same dark panels, edges, gold titles and button states, with the GoNuts logo on the page and on the PIN screen.
- New on the web panel: the HUD across the top (state, auction slots, bank, trades, account, what the bot is doing now, and the capacity squares), and the pages down the side as buttons in place of a dropdown. On a phone the pages become a row you swipe.
- The web panel is under Autopilot on the GoNuts screen: click the mode button through to Web panel.

## 2026-10-12 14:50 UTC
- Fixed: the session pausing over and over on "Recovered purchase is next; listing it". When several listings of one item left your auction page and one stack of it was in your inventory, every one of them was queued as a purchase waiting to be listed. Only one stack was really there, so the bot paused on each of the others in turn. One stack in the inventory now stands for one listing.
- Fixed: sales still being booked as "returned to you, not sold". Many sales are announced only as "You earned $89.8K from auction", with no item named. Those amounts are now added up between slot checks and used as proof that missing listings sold, so the decision no longer rests on your balance alone. In one 75 minute log, 34 listings were booked as returns while these messages reported more than enough earned to cover them at each check.

## 2026-10-12 12:55 UTC
- Fixed: the bot saying your auction slots are full when they are not. A listing that leaves your auction page is kept on the books if the same stack is sitting in your inventory, on the idea that it was pulled back. One stack in the inventory was excusing every missing listing of that item, so nine sold stacks of emeralds stayed counted as listed because a tenth had just been collected from an order: the panel read 45 of 45 with 35 on the page, and nothing more was listed into the empty slots. One stack now stands for one listing; the rest are settled as sold or returned at the next slot check.

## 2026-10-12 12:48 UTC
- Fixed: sales booked as "returned to you, not sold". When a listing leaves your auction page with no sale message, the bot checks your balance to decide whether it sold. That check did not count money locked into the bot's own buy orders, so after a large order the balance looked short and real sales were recorded as returns: 13 in half an hour in one log, right after a 14.8M order. Your money was never affected, but the profit shown was too low and the bot believed it still held stock it had sold. Money put into an order and money refunded by a cancelled one are now both counted.

## 2026-10-12 11:11 UTC
- New: a web panel. The same pages and buttons as the Discord panel (money, listings, items, orders, rivals, settings, Start and Stop) in a browser, with no Discord and no bot token. Open the GoNuts screen, go to Automation and click through to Web panel, and set a PIN of 4 to 8 digits: that turns it on. The card then shows the address to open.
- New: use it from your phone. Turn on "Phones on your Wi-Fi" on the same card and the panel also answers devices on your home network, at the second address the card shows. It never answers anything from outside your network.
- Safety: nothing is shown or done without the PIN. Five wrong PINs lock the panel for a minute, and each lock after that is twice as long, up to 15 minutes. Only a hash of the PIN is saved. The PIN and the phone switch can only be changed in game, never from the web page itself, and neither travels in a settings code.

## 2026-10-11 19:42 UTC
- Fixed: orders that failed to place on a busy server. When Your Orders was slow the bot clicked it a second time after 8 seconds, and the second page then arrived in the middle of the order form and wiped it. Of 32 placements, the 8 that asked once all worked and 10 of the 24 that asked twice failed, about 40 seconds each. The second click now waits for as long as the page has lately been taking, and an order whose form is replaced by a late page starts again from that page at once.
- Changed: after placing an order the bot tries to place the next one from the same page, without waiting for Your Orders again. If the server does not answer on that page within 3 seconds it goes back to asking for a new one.
- Removed: the wait added in the last build for a page the server was thought to send back after an order. It does not send one.

## 2026-10-11 19:27 UTC
- Changed: a step that ends on Your Orders (reading or cancelling) leaves the page open for the next step to start from, which saves one slow page load each time. There is a switch for it under Orders in Settings (Stay in the order house between steps, on by default).
- Fixed: a sell command the server ignores is retried at once after closing the player inventory, the same cure already used for the auction and order commands. Before, the bot waited out five tries with longer and longer holds, which cost four and a half minutes each time the server thought a menu was still open.
- Collecting is unchanged: one order at a time, with the stock listed before the next, so nothing piles up in the inventory.

## 2026-10-11 18:34 UTC
- Changed: the bot now collects from an order only when a whole stack is waiting or the order is completely filled. It used to make a trip for a quarter of a stack, or for whatever was there when an order went quiet, which is where listings of 3, 14 or 53 of something came from. A part stack is only taken when the order itself is being pulled.
- Changed: a small delivery no longer sends the bot to Your Orders at once. It goes when an order completes or a stack has built up, which saves a slow page load each time on a busy server.
- New: the switch for this is in Settings under Orders, "Collect whole stacks only". It is on by default.

## 2026-10-11 17:37 UTC
- New: when the order house stops answering, the bot no longer stands waiting at it. After three failed tries it works the auction side (listing what it holds, repricing, watching for deals) and tries Your Orders again every few minutes until it comes back.

## 2026-10-11 17:28 UTC
- Fixed: the wait for the Your Orders page now follows how slow the page really is. The build before this one went back to a 20 second wait after every page, which on a busy server (the page was taking about a minute) made it give up too early over and over. It now waits a bit over twice as long as the last page took, up to 90 seconds, and eases back down as the server speeds up.

## 2026-10-11 17:14 UTC
- Fixed: three-minute stalls when the Your Orders page does not open. The bot waited a fixed 90 seconds before asking again and 90 more before giving up, with listing stopped the whole time. It now waits 20 seconds, and only waits longer (45, then 90) if the page keeps failing. On a busy server this was costing several minutes of every hour.

## 2026-10-11 15:14 UTC
- New: a switch for whether your limits follow your bank (Settings, Buying, "Limits follow your bank"). On, which is how it has always worked, one buy may cost a share of your balance and a share may be out at once, so the limits grow and shrink with the bank. Off, the Max per trade and Max per session you typed on Autopilot are the limits whatever your balance.
- Fixed: the Autopilot setup card said the bot never spends past the two amounts typed there. With limits following the bank that was not true once your balance had been read. It now says which is in force.

## 2026-10-11 13:43 UTC
- New: an item board. On the Performance tab, press Items to see every item you have traded with its profit, sales, profit per sale, return on money spent, how long it takes to sell, and what is tied up in it now. Click a column to sort by it, and again to see the worst first. Today, 24 hours, 7 days or all time.
- New: profit per slot-hour, on the same board. It is what an item earns for each hour it holds an auction slot, which tells a quick small earner from a slow big one.
- New: click an item on the board for the rest: what it costs and sells for, singles against stacks, its best and worst sale, its best hour, and how it is doing against the stretch before.
- New: an Items page on the Discord panel with the same board, and buttons for the range, the order, and best or worst first.

## 2026-10-11 12:52 UTC
- Fixed: auto-update could skip installing when the game was closed. The helper sometimes saw the game as still running in the instant it exited, left the jar alone, and the notice came back on the next start.
- New: the update banner lists the first few changes, so you can see what an update does without opening anything.
- Changed: following stops at 1,000 players, which is the server's limit.

## 2026-10-11 12:40 UTC
- Changed: the mod now notices a new build within two minutes instead of up to three hours. The notice still shows once per build per session, so checking more often does not repeat it.

## 2026-10-11 12:25 UTC
- Fixed: with a Donut+ rank (45 auction slots) the slot check and every pull-back failed, and the panel stuck at 45 of 45. The bot was turning to a second listings page that, for that rank, is all locked cells.
- Changed: the bot reads your rank's slot count off your listings pages and lowers the Auction slots setting to match. It says so in chat when it does.
- New: after you buy a rank, the bot tells you there are more slots to use. Raising the setting stays your choice.

## 2026-10-11 12:10 UTC
- New: a changelog. Every update now says what it changes: in the update notice, after it installs, and under What's new on the About tab.
- Fixed: the bot waits while the update notice is open, the same as it does for the GoNuts screen.

## 2026-10-11 11:52 UTC
- Fixed: the key that opens GoNuts now works while the bot has an auction or orders page up. The bot finishes its step, closes the page and opens the screen, instead of the two fighting over it.

## 2026-10-11 10:22 UTC
- Fixed: a settings code no longer carries the sender's slot counts. Importing one leaves your auction and bid slots as they are.

## 2026-10-11 10:15 UTC
- Changed: the update banner appears at top centre, clear of the alert cards.
- New: Preview update notice on the About tab.

## 2026-10-11 10:03 UTC
- Changed: the update notice is a pop-up with buttons (open the download page, turn on auto-update, later) instead of a line of chat.

## 2026-10-11 09:42 UTC
- New: auto-update. Turn on "Install a newer build by itself" in Settings. A new build downloads, is checked against the hash GitHub gives for it, and installs when you close the game.

## 2026-10-11 09:28 UTC
- New: Money view on the Rivals tab: who pays whom among the players you follow, and how much.
- New: the mod tells you when a newer build is on GitHub.
- New: hide name also covers the GoNuts screens and the Discord panel.
