# GoNuts changelog

## 2026-10-26 16:12 UTC

- **PR #377: Keep dashboard sign-in usable when the browser does not open.** The Swarm panel always displays the public dashboard address and offers **Copy website address**, without requiring pairing. Silent desktop-launcher failures leave a manual route; browser or clipboard exceptions show concise guidance instead of escaping the GUI callback. The panel never treats a returned launch request as proof that a browser opened.
- Actual offline Minecraft GUI regression: old panel fails with missing Copy website address; repaired panel passes silent/throwing injected launchers, copy without pairing, compact resize, scrolling and rebuild. Four failure-path unit regressions pass. Clean production build: **1,743 tests, zero failures/errors, two skipped**; normal client entrypoint only, no smoke classes. Native desktop and compact screenshots inspected. Report223 stays open: its original Linux environment/launcher cause and affected-client recovery remain unverified. Pairing/authentication, settings, records and safety gates are unchanged; no live client install/restart.
- Client artifact published **2026-10-26T16:12:36Z**. Build `1793030883148`; client SHA256 `f2856528dcc69c175298246db668b44802ee0e1431130be8a8b6566d50c68f82`. Source SHA256 `f7545a53d44466ebb699b747e7fee2db43b9a284c05e2776ed44aa144e7dc73d`, published 2026-10-26T16:12:39Z. Public client/source/checksums independently downloaded and verified. PR377 merge `e7ba4d60bd513c2e4208060911b79ca8c394b656`; tested source `7d54c57320d5c194656da21ef7b9bde3175c946c`. Documentation only; earlier separate dated client publications remain below.

## 2026-10-26 13:26 UTC

- **PR #375: Preserve native auction-row ownership during quantity reconciliation.** Report370 exposed a resize fallback that could take another parked position's exact native row after the repricing pass correctly reserved it. Equal quantities could enter that fallback too, cancelling/reopening the missing record at another price before ordinary sale-budget reconciliation and leaving the real owner without its row.
- Require a genuine quantity change for resize, refuse any row that exactly matches another open owner's quantity/key/price, and preserve box-content identity on both sides. Unowned bounded quantity correction and normal same-count manual repricing remain available. Existing sale budgets, fees, inventory/ownership checks and safety/spending gates are unchanged. No historical positions, credits or profit are rewritten; no live installation/restart accompanies publication.
- Clean full build: **1,739 tests, zero failures/errors, two skipped**. Six actual controller/persistence regressions cover original sale price/profit and owner retention in either order, different-size exact ownership, no invented sale without earnings, valid resize/manual reprice and box identity. Four fail on the previous source; all six pass with the repair. Report370 remains open pending affected adoption/native-audit verification; this is not a claim that its whole incident or historical ledger has recovered.
- Client artifact published **2026-10-26T13:26:12Z**. Build `1793020911015`; client SHA256 `cc411b54818004a285fa82452c96df662ab04e0f0fafd175d765fa477841d49d`. Source SHA256 `7f008ca466fd51ca2d1a053dc28e223144f69488608f551e3f45b1e11db9fbe2`, published 2026-10-26T13:26:17Z. Public client/source/checksums independently downloaded and verified. PR375 merge `2f0dc7c4f072a7cb2423d347fdd500bb017277fd`; tested source `c8020528b19721d07f2b792aa214504356e535dc`. Documentation only; previous separate dated client publications remain below.

## 2026-10-26 12:37 UTC

- **PR #371: Refuse background commands without crashing Minecraft.** A PR368 Follow request with punctuation correctly triggered the strict command policy, but the exception escaped into the client tick and crashed bot A. Background commands now refuse invalid text before touching transport; Follow stops before creating pending state. Existing followed-player notices continue. ASCII letters, digits and spaces remain the only allowed command text; no stripping, substitutions or relaxed guards.
- Clean full build: **1,733 tests, zero failures/errors, two skipped**. The crash regression fails before and passes after the fix; refusal leaves no queued command or pending Follow. A compatible necessary repair is limited to the confirmed crashed client; its saved settings, positions and native recovery state are preserved. Peer clients are not routinely upgraded or restarted.
- Client artifact published **2026-10-26T12:37:15Z**. Build `1793017987735`; client SHA256 `fbc6a58a01949ba3f9fcf51fd22a0a726de154657e88466e120a5dc42a612f73`. Source SHA256 `7c4d70ca0f1c0bd30dfdb7a5dfe971609f79ceeb5e55dee8fbdae6d41805483e`, published 2026-10-26T12:37:20Z. Public client/source/checksums independently downloaded and verified. PR371 merge `e5bbfe640ed2cbcf7eea0c5f0dc85b2e8a338e01`; tested source `b6b0e3663f30cb23093fbea6096a2a45ab87d6ff`. This documentation entry creates no additional binary; all earlier dated publications remain below.

## 2026-10-26 12:00 UTC

- **PR #368: Strict ASCII command text and item-search construction.** At the owner's explicit request, bot command text accepts only ASCII A-Z, a-z, 0-9 and spaces. All punctuation, emoji, private-use font glyphs, formatting codes, other Unicode and controls are refused before queueing/menu closure/dispatch and checked again at actual connection handoff. This is deliberately stricter than Minecraft's rules: underscored recipients, decimal amounts and namespaced command text are refused, never rewritten.
- Client-provided item display names are checked before constructing auction-search commands, including retries and filtered watches. Decorated/unsupported labels are refused; no stripping or identifier fallback. Plain Cooked Chicken and Block of Iron remain valid. Invalid labels stay inside normal failure handling. The manual panel removes its normal UI slash prefix before validating the outgoing command. Ownership, spending, pacing, safety gates, settings and durable records remain unchanged.
- Clean full build: **1,731 tests, zero failures/errors, two skipped**. Ten added tests cover exhaustive UTF-16 acceptance/refusal, decorated labels, plain-name construction, missing/malformed labels and no menu close/queue/send on refused commands. Existing Unicode-acceptance tests now assert the requested refusal. The reporter's resource pack was not inspected; the separate B-key report remains undiagnosed. No live installation/restart accompanies publication.
- Client artifact published **2026-10-26T12:00:09Z**. Build `1793015679829`; client SHA256 `aab489229a73e97980e52f95ac0a514d50b2cbbaecce30235948d5369d85c6c1`. Public client/source/checksums independently downloaded and verified. Source SHA256 `99bd904f218042e323fa80b2205a10c05eaa91116655e63e2ab6f464360ae8d3`, published 2026-10-26T12:00:11Z. PR368 merge `eeb5a8279065bb40a73b8329303302a5109662b5`; tested source `1f3e70c5db337d2acc65b4c442bc02a11e6e99de`. Documentation only; prior separate dated publications remain below.

## 2026-10-26 10:20 UTC

- **PR #366: Separate audited historical receipts from current native stock exposure.** PR363 physically collected 192 items and preserved the receipt. When that completed row disappeared, the cumulative receipt exceeded the smaller visible cohort. Existing capital/Reserve Stock checks interpreted this as unknown exposure and blocked bids. That safety hold prevented spending; boot/collection alone did not establish recovery.
- Keep all durable receipt history intact. For explicitly physically audited keys, credit none of those historical receipts against another visible row in the derived current-book view. Conservatively reserve all current deliveries and unfilled funds, and collect only through ordinary native matching/quantity/inventory/shelf/capacity guards. Native row disappearance removes its visible stock. Oversized unmarked credits, negative counters and incomplete books retain existing refusals. No records, credits, settings, caps or guards are reset.
- Clean full build: **1,721 tests, zero failures/errors, two skipped**. Two new actual controller regressions cover the failing-before capital hold, conservative visible/partially filled stock exposure, collection planning, preserved receipts and corrupt/incomplete evidence guards. All 46 native menu/controller regressions pass. Live recovery and sustained profitability require separate verification; no live installation accompanies publication. PR363 remains a separate dated client update below.
- Client artifact published **2026-10-26T10:20:44Z**. Build `1793009842276`; client SHA256 `ec7d32edbcf1f052fb215c63e12f438d6360b03fe0ed23cbffe8de65a9cfb0b6`. Public client/source/checksums independently downloaded and verified. Source SHA256 `d0b876e3b17110224e8c18e8c12227beb92d658e13985dc344e4e5c6d1aedb8f`, published 2026-10-26T10:20:46Z. PR366 merge `32fa5aba30672af916cffb2bf3e6149889f70470`; tested source `5e9d40d2d7391729d7b3ee29d6bbb12fc9c37bef`. Documentation only; prior dated entries preserved.

## 2026-10-26 10:01 UTC

- **PR #363: Inspect completed native stock hidden by pooled historical credits.** A live completed order still held 192 physical items even though credits shared by identical item/price/quantity rows covered the visible delivered total. The planner skipped those goods and reached repeated full-history placement responses. Parsed completed history is not an active-order count.
- After a full-history response, queue one paced native audit requiring fresh complete ownership evidence. Retain native matching, bounded duplicate-row rotation/empty attempts, inventory/shelf/auction-capacity and whole-stack quantity guards. Only successful physical transfer adds credit. Preserve physical receipts across smaller visible cohorts/reload in the same atomically replaced existing credit file with backward-compatible metadata. No records/history/settings/ownership/credits/limits reset.
- Clean full build: **1,719 tests, zero failures/errors, two skipped**. Three new controller/persistence/guard regressions include the failing-before path; all 46 native menu/controller regressions pass, including actual 192-item transfer. Live collection, active capacity and sustained profitability require separate verification. No live installation accompanies publication.
- Client artifact published **2026-10-26T10:01:54Z**. Build `1793008710088`; client SHA256 `22963601096abaced8b10c04d51b74e3de89399cb1a90e57b2f743d3267ec737`. Public client/source/checksums independently downloaded and verified. Source SHA256 `3dcf544da8f2c5573c4b20cf4730eb955048413cd7a7337e199696ddcee44439`, published 2026-10-26T10:01:59Z. PR363 merge `c2f93c78a07cbc68e57a2b1591b3f532120b535d`; tested source `d894be84a6e658b91b6c0176cfd9da6e7f1697b5`. Documentation only; all separately published dated entries remain below.

## 2026-10-26 04:08 UTC

- **PR #361: Handle an answered empty Collect control without navigation recovery.** Live native evidence showed the exact system reply `You have no items to collect!` after a matched owned Collect control, while Edit Order remained open and inventory stayed unchanged. The driver ignored this answer, later called it a missing menu and fed navigation/server-failure holds.
- Recognize that exact reply only for a pending matched Collect control. Retain the aborted/no-transfer outcome without adding a global server failure. Existing bounded duplicate-row rotation remains; an actual stock menu/inventory transfer outranks the reply, and prior partial-stock evidence retains its ordinary hold. Preserve the delivery/credit discrepancy and bound unchanged attempts. Newer complete native evidence of increased deliveries rearms guarded collection. No settings, history, positions, ownership, credits, spending/quantity/capacity limits or guards are reset.
- Clean full build: **1,716 tests, zero failures/errors, two skipped**. Four native/controller regressions cover the failing-before reply, exact system/operation/stage boundaries, stock-menu precedence and preserved credits with newer-delivery rearming. All 46 native menu/controller tests pass.
- This repairs false navigation recovery and repeated empty attempts; it does not reconcile historical collection credits or prove old goods sold. No live installation/restart accompanies publication.
- Client artifact published **2026-10-26T04:08:11Z**. Build `1792987455334`; client SHA256 `10e91cf3a5886367fa487d635125b858e422ed769c642cdd5c7f94c378e72be8`. Public client/source/checksums independently downloaded and verified. Source ZIP SHA256 `d5edb3bed3d41df3921de5073c3dbe1b57a690873f37e23e1df0052e2f87e951`, published 2026-10-26T04:08:13Z. PR361 merge `92a974bc8e031fcb776188cbfa3beead88fec0a6`; tested source `edc9efb7965b8ecccf8064a32375f84747243ad2`. Documentation only; prior separately published client entries remain preserved.

## 2026-10-26 03:52 UTC

- **PR #359: Prevent discovery probes from starving due order-source reads.** Investigating report #358 reproduced a scheduling defect: with a mostly empty auction book and no due reprice, optional discovery probes could repeatedly take the desk-first turn before an already-due public order-house read. That can delay the order/fill evidence used to find trades.
- A due source read now gets its normal guarded turn while the optional probe remains queued for afterward. Owned-order maintenance keeps its existing priority. Fresh or disabled source schedules, own-price-only mode and recovery holds are respected. No scan intervals, human pacing, confidence/sample/ROI requirements, caps, spending, ownership, settings or credits are relaxed or reset.
- Clean full build: **1,712 tests, zero failures/errors, two skipped**. Four actual controller/native-fixture regressions cover due-source fairness, non-due discovery, disabled-source discovery and owned-order maintenance. The due-source test fails on PR356 and passes after repair; all 42 native menu/controller tests pass.
- Report #358 remains open pending affected-client verification. This is a reproduced related scheduling repair, not proof that all causes of no purchases are resolved or that profit is guaranteed. No live installation or restart accompanies publication.
- Client artifact published **2026-10-26T03:52:10Z**. Build `1792986486690`; client SHA256 `9117a14af07572ce23aa6827265fcfe660e20eb3701a7c88a46744303d11ee5b`. Public client/source/checksums independently downloaded and verified. Source ZIP SHA256 `811b8e22e7f15341953f8ed1b58ad7814a23606377f94d034a0e8dbcc1ff6ec2`, published 2026-10-26T03:52:13Z. PR359 merge `1392a006a04972b7531624395eba7849f920700a`; tested source `d888d8503e513d9640fe6afee3afbd00a2a8e997`. This entry is documentation only; prior separately published builds remain preserved.

## 2026-10-26 02:32 UTC

- **PR #356: Pace retries when the native order book is full.** A successful no-op saying the native book was full invalidated the complete book and immediately replanned another placement. A reproduced this loop while its native book contained 90 rows, 88 completed, and physical replenishment had stopped. Keep the complete read and pace new bids until the ordinary 90-second availability refresh.
- Collections and existing owned-order maintenance remain available during the bounded new-bid cooldown. No orders/history are canceled or erased; no credits, ownership, quantities, prices, settings, caps or guards are reset. This repairs the rapid retry loop only. The underlying native creation availability and A's replenishment remain unresolved; no whole-incident recovery or extra usable capacity is claimed. No live installation or restart accompanies publication.
- Clean full build: **1,708 tests, zero failures/errors, two skipped**. Three controller regressions verify retained evidence/no fabricated bid, bounded retry and collections during the cooldown; the first two fail before the repair.
- Client artifact published **2026-10-26T02:32:47Z**. Build `1792981761875`; client SHA256 `493a7a78e2d044dc0e3a5f2ac6ce1aa5a98b853362cdd7685069a607946fd9d9`. Public client/source/checksums independently downloaded and verified. Source ZIP SHA256 `af0be33d0a41987a786c8888e19fa0845b3a3ba137d38f7215a6ec8a0e152a42`, published 2026-10-26T02:32:49Z. PR356 merge `b707f3888976ead6ef6c1a9464a2825bbd5b025f`; tested source `cfc98d48910d6e1236970e3839ad6c7b0109799d`. This entry is documentation only; previous separately published updates remain preserved.

## 2026-10-26 01:05 UTC

- **PR #354: Read the disabled 27-slot section of Your Orders.** Recognize the native 18 available / 27 disabled red-pane layout without requiring upgrade advertising. PR352 still reproduced 27 rejected items and zero rows with blank or ordinary disabled labels; the actual Minecraft menu regressions now complete empty and mixed books and retain owned rows from earlier pages.
- The complete expected layout is required: valid orders, existing empty-slot controls or air in the prefix, followed by native one-item red panes with blank/default or closed disabled labels. Unknown material/name/count, broken prefix cells and order-like quantity/price fields invalidate this recognition. Real red-pane orders still parse as orders. Disabled cells are excluded from placement searches and do not add capacity. Ownership, collected credits, spending caps, settings and other safety gates remain intact.
- Clean full build: **1,705 tests, zero failures/errors, two skipped**. Five added native tests cover blank/disabled labels, mixed and accumulated rows, placement exclusion, malformed/unknown-content refusal and a real red-pane order. The two failing-before regressions remain recorded. Reporter adoption and subsequent complete reads/new bids are still being verified; related reports remain open. No live bot installation or restart accompanied publication.
- Client artifact published **2026-10-26T01:05:15Z**. Build `1792976477342`; client SHA256 `0f2d6a4ba9ac644cda58914fbdd1609fc9aa8f59ceb21e68373244ecbf2c27c7`. Public client, source ZIP and checksum manifest independently downloaded and verified. Source ZIP SHA256 `d62bab9d2cc32c386cff15972282d6d60ef03d78e1876a063ee67c559bae0f40`, published 2026-10-26T01:05:21Z. PR354 merge `63f018de971d3c4840956b49b519d0ee182b6284`; tested source `1b21da86aa9431aab7792d779a04938b8680c1b6`. This dated entry is documentation only; prior separately published updates, including PR352, remain preserved.

## 2026-10-26 00:51 UTC

- **PR #352: Recognize the Donut+ own-order slot control.** Accept the exact `Max Orders` / `Buy Donut+ for even more order slots` control alongside the existing Donut++ variant. The former reader rejected this variant as 27 unknown items in an actual Minecraft menu fixture with 18 gray New Order controls and 27 red upgrade tiles. Empty and mixed books now complete while retaining real orders.
- Color and layout alone do not identify a safe control. Exact wording remains required; malformed order quantities/prices and unknown content still fail closed. Locked controls do not become usable placement slots or increase capacity. No trading settings, ownership, spending caps or collected credits are changed.
- **The screenshot cohort remains unverified.** Its new screenshot shows the 18/27 layout but not the red tiles' tooltip. This release fixes the reproduced Donut+ variant; affected-reporter complete-read/new-bid evidence is still needed before claiming the wider incident resolved. Reports remain open. No local bot installation or restart was performed for this publication.
- Clean full build: **1,700 tests, zero failures/errors, two skipped**. Two new native menu regressions fail before/pass after; the focused native/evidence suite passes 39 tests, including malformed red-pane order and locked-placement guards.
- Client artifact published **2026-10-26T00:51:20Z**. Build `1792975530091`; client SHA256 `465e4ca8dec3a90fb3478f068d578765c9d5896549ba3fb68940233b71424407`. Public client, source ZIP and checksum manifest independently downloaded and verified. Source ZIP SHA256 `dcf63055fe36a025a81cf1d2c3bad427c7ad01cb91a0461a4b4c589a46a513d7`, published 2026-10-26T00:51:22Z. PR352 merge `e5366626795d3f14349a40a96ada69ce2eacf3ef`; tested source `6d86c2694c8378f94b11e0da154e386301a135bf`. This entry is documentation only; earlier separately published client updates remain preserved.

## 2026-10-25 22:13 UTC

- **PR #350: Persistent Report Bug button and native rejection evidence.** A large **REPORT BUG** button is available in a reserved footer across GoNuts workspaces, settings, setup guides and other mod screens. It stays visible through tab changes and resizing, opens the existing review/send form without collecting or submitting automatically, and returns to the previous screen. The layout preserves usable content space and native focus/narration.
- Fix a reproduced local-capture omission: all 27 rejected non-quantity content-grid tiles were previously omitted. Explicit local captures now retain their closed structural masks. Existing diagnostic snapshots include six fixed numeric summaries for rejected names/tooltips, item categories, parsing patterns, bounded shape count and observation time. No raw names, lore, NBT or identities are sent; tested additive receiver/dashboard support was deployed before publication.
- **The 27-unparsed order-reading incident remains unresolved.** This is a GUI and evidence repair, not a claim that affected clients can bid again. Incomplete reads retain observed rows, fail closed and do not renew freshness. Quantity, price, capacity, confidence, ownership and spending guards remain unchanged; no records or credits are reset. No local bot was updated or restarted for this release.
- Clean full build: **1,697 tests, zero failures/errors, two skipped**. Actual Minecraft GUI verification passed **192 checks** across the five workspaces, Simple/Advanced settings, guides, report form/return and two viewport sizes. The native capture regression failed before (27 expected, zero retained) and passes after; privacy, typed context and evidence-clear regressions pass.
- Client artifact published **2026-10-25T22:13:18Z**. Build `1792966163163`; client SHA256 `c4ff2e1bfd0627ce45a2507f27b2e6b8205d9787fd37e2ba5864398bccc37b7d`. Public client, source ZIP and checksum manifest independently downloaded and verified. Source ZIP SHA256 `f5d1f34144484cb1046dbb067649fc40338faf399a8230079143f9e594f5f1db`, published 2026-10-25T22:13:21Z. PR350 merge `2993b27be4e3459ea0f16c15e1e5b1a4f07ef60e`; tested source `ce1924ca38bc9f04998c066e01a15999685b083d`. This dated entry is documentation only. Earlier separately published client updates are preserved.

## 2026-10-25 20:16 UTC

- **PR #345: Recover a verified listing after a protective pause.** A durable LISTED acknowledgement arriving while paused could leave ordinary protection recovery treating that known listing as unresolved exposure forever. The exact live-pause case now uses ordinary Resume authorization, server, driver-idle and hold-time checks after the existing protection all-clear. Pending writes, uncertain exposure, manual stops, restart-loaded pauses and transfer holds remain refused. Protection recognition, timing and routes are unchanged.
- A late parked sale now preserves an already accepted session-close checkpoint rather than reopening the session while its acknowledgement is pending. Legitimate sales and cost releases remain recorded; unrelated positions, spending and caps remain intact.
- Includes PR344's bounded setting-change decision token for the existing competitive-bid increment. No numeric setting, confidence or sample-count change.
- Clean full build: **1,689 tests, zero failures/errors, two skipped**. Both recovery races were reproduced with failing-before/passing-after regressions; negative cases retain persistence, manual-stop and exposure guards.
- Client artifact published **2026-10-25T20:16:46Z**. Build `1792959164747`; client SHA256 `b3a1b9642465a8bb1eb3f3eed6f29e5c8e0cfe588863edf1d7c8ff3fc893173a`. Public client, source ZIP and checksum manifest independently downloaded and verified. Source ZIP SHA256 `167fdeac6859ba12bc9eccf4b80598d55b8de1ffe2e7d767a88a6bfd38430cf8`, published 2026-10-25T20:16:48Z. PR345 merge `a4b0680c9a04bb53213c015a482b3a853ed45320`; tested source `9292c3b19c00c61268589efa8574d1c8d414cb37`. This publication entry is documentation only. Necessary affected-client recovery is verified separately; healthy peers are not automatically upgraded.

## 2026-10-25 19:41 UTC

- **PR #342: Configure the percentage above competing buy orders.** Add `Bid: above competing orders` (`orders.outbid_pct`), preserving the public default of 1%. A 3% selection targets 10,300 per item against a 10,000 best observed competing bid. Competitive new bids, retail/shaped bids and bid chasing use the selected increment; existing higher normal offers remain higher. Explicit player prices stay unchanged.
- Resale ceilings, required margins, live-price evidence, ownership, quantities and spending/exposure limits still apply. A higher increment can reduce eligible markets; faster fills, utilization and profit are not guaranteed. Confidence and sample-count defaults remain unchanged.
- Clean full build: **1,684 tests, zero failures/errors, two skipped**. Configured target, default, ceiling-refusal, overflow, lot eligibility and report-safe round-trip tests passed. Additive receiver/dashboard setting support was deployed before this publication.
- Client artifact published **2026-10-25T19:41:42Z**. Build `1792956835800`; client SHA256 `7b65e2e7f177dbea46cf403529711a23927f0d640d8422e5673d687760254660`. Public client, source ZIP and checksum manifest independently downloaded and verified. Source ZIP SHA256 `d920f760ab54cc8660ac54ce15aa4772bb026c84796bf9a04713305c81c39a6a`, published 2026-10-25T19:41:45Z. PR342 merge `21818feb6f99f9fe47c84ea44ca1a2d6e105c4a2`; tested source `6d794340ae1ea914c5b331d5d0ad78c361d92f1f`. This publication entry is documentation only. Explicit local owner configuration and rollout are verified separately; publication does not automatically upgrade existing clients.

## 2026-10-25 18:03 UTC

- **PR #339: Read locked native order-slot controls.** Recognize the reported `Max Orders` rank-upgrade control, with its exact `Buy Donut++ for even more order slots` text, when reading Your Orders. A page filled with these locked tiles can end the bounded read while retaining real orders from earlier pages; mixed pages keep every parsed native order.
- Locked controls never become clickable empty placement slots and do not increase order or auction capacity. Unknown controls, malformed order rows and unsupported abbreviated quantities remain incomplete; ownership, credits, spending limits and collection guards are unchanged.
- Related reports #335 and #338 remain open pending affected-client verification. No healthy live bot rollout is implied.
- Clean full build: **1,681 tests, zero failures/errors, two skipped**. Both reproduced native-menu failures pass after repair; 28 native-menu tests retain unknown-content, malformed-order, exact-count and locked-placement negatives.
- Client artifact published **2026-10-25T18:03:41Z**. Build `1792951169298`; client SHA256 `8160a1adb6a07acb37c1e3ea629a933401254aa31194eec5de34fe0442aa095f`. Public client, source ZIP and checksum manifest independently downloaded and verified. Source ZIP SHA256 `82bcc43b85f13c33ce74ecd827afe5ea2b921beba22744956725ef7d20446da8`, published 2026-10-25T18:03:45Z. PR339 merge `9da43b9d5b3cb5763600cf274c59d8f0296da10e`; tested source `8a216011e3996dc315f593511e94a8a33ebccfa8`. This dated publication entry is documentation only. No live installation or restart; A remains PR336 and B/C remain PR331. Affected-reporter recovery is not yet verified.


## 2026-10-25 17:15 UTC

- **PR #336: Recover incomplete native reads and bounded partial cleanup.** An unparsed own-order page no longer reports a successful empty book while returning rows accumulated from earlier pages. The read fails explicitly into existing paced recovery, retains observed owned rows, and does not renew book freshness. Reports include the existing book-completeness flag and an unparsed-item count; unknown native tooltip text is omitted. This repairs a reproduced path related to report335; the reporter's exact native content and whole incident remain unverified.
- Bounded collection now respects an already-authorized partial-stack cleanup operation. Previously the whole-stack preference was disabled for cleanup before cancelling an order, but the capacity check still rejected its unfinished tail. The repair keeps the same quantity ceiling and native inventory transfer/credit checks. Whole-stack-only collection and insufficient capacity still refuse partial stock. A local client repeatedly revisited a native five-diamond tail without transferring it; exact repair progress is verified separately.

- Client artifact published **2026-10-25T17:15:15Z**. Build `1792947905537`; client SHA256 `0af041eb12155880c161cd5df7d844c8e29d55bfb92a858436536973641728fd`. Public client, source ZIP and checksum manifest independently downloaded and verified. Source ZIP SHA256 `60afdab3efe927058fa5b1428df42700f5269b4f42c102617bdcf18ff56ee891`, published 2026-10-25T17:15:18Z. PR336 merge `f2967e74cdf4ec8fe8487a3a03f78369194b0aac`; tested source `47c49e11c04161cc432570789badcbd4a7073c10`. This dated entry is documentation only. A necessary repair of only the confirmed affected local client is verified separately; healthy peers retain PR331.
- Clean full build: **1,674 tests, zero failures/errors, two skipped**. Both actual native-menu regressions fail on prior public code and pass after repair. Controller coverage verifies paced read recovery, retained rows/credits and no freshness renewal; negative cases retain whole-stack policy and quantity limits. Typed report-context validation excludes unknown/raw fields. Report #335 remains open pending affected-client evidence; no whole-incident recovery claim.

## 2026-10-25 16:05 UTC

- **PR #333: Refresh expired native auction occupancy.** Re-arm the native auction audit when its count is missing or older than the existing10-minute validity limit, even when listings remain below capacity and market data is unavailable. Previously Stop/Start or a quiet long-running session could retain an unknown count indefinitely; collection sizing then refused new batches without an audit to recover.
- Maintenance only begins while scanning, with no pending purchase or tracked position, no active driver operation, no uncertain exposure or staff transfer hold, and an empty-cursor native inventory boundary. A refused missing-count maintenance attempt retries no faster than once per minute. Successful native audits remain authoritative; no count, capacity, credit or position is fabricated or reset. No automatic start, spending-policy change or safety-gate relaxation.

- Client artifact published **2026-10-25T16:05:15Z**. Build `1792943935123`; client SHA256 `88154a50028831d53fc27d2cb7678643263330038ece6a8dfac62044430aa124`. Public client, source ZIP and checksum manifest independently downloaded and verified. Source ZIP SHA256 `b59fb5872c6c9351a29ccade5c70a3a607737691bd63f6507034a895e3c4fcf8`, published 2026-10-25T16:05:19Z. PR333 merge `d91fef18b75cda220617225efb19c8e284a0408d`; source `ab51fa2c9a0a025c025406f04377144a29fcbb73`. This dated publication entry is documentation only. No live installation or restart; all three current clients retain PR331.
- Clean full build: **1,671 tests, zero failures/errors, two skipped**. Native-controller regressions cover expired below-capacity evidence without a market snapshot, bounded retries and fresh/busy/container/transfer exclusions. Related report #330 remains open pending reporter installation and recovery evidence; this patch does not establish remote recovery.

## 2026-10-25 15:47 UTC

- **PR #331: Restore Staff Watch completion replies.** Restore server replies to the shard-local Staff Watch completion probe by sending `/minecraft:msg ` with its leading slash. A bounded live comparison observed the old slashless request passing the outbound hook with no reply, while the slash-prefixed request received197 suggestions, including the controlled requesting account and excluding two controlled accounts in other mapped areas. The ordinary `/msg ` response was much larger and is not substituted as local proof.
- Use the same request for Anti Vanish probes and the existing manual/automatic region-arrival round trips. No recognition filters, locality requirements, request frequency, cooldowns, transfers, persistence, ownership, spending or trading guards change. This repairs a reproduced input-compatibility failure; the date the live server behavior changed and the whole historical staff-alert incident are not established.
- An isolated real-Minecraft replay passes on both the original import and current code: packet recognition, global-versus-local negative cases, detector-triggered warning banner, owned completion handling and real vanilla completion transport. Test harnesses are excluded from production jars. A separately packaged temporary four-request diagnostic records only counts and controlled-account match flags, not suggestion names.

- Client artifact published **2026-10-25T15:47:03Z**. Build `1792942964371`; client SHA256 `06158a45913024cd9206d269d345d5da0d30ee4f57ee6d84a2f276db7a41c5a0`. Public client, source ZIP and checksum manifest independently downloaded and verified. Source ZIP SHA256 `6ab203ed2359596af9a6f050988320bd2c998b15877ec8b7d10cf8c17e30c7f3`, published 2026-10-25T15:47:06Z. PR331 merge `35be35311c638215a57f851d77cb90269e8c6b69`; tested source `6098986dc66729eaca0f6216f9d8054a1e4d0d6e`. This dated publication entry is documentation only. All-three installation was explicitly requested and is verified separately.
- Clean production build: **1,669 tests, zero failures/errors, two skipped**. The repaired disposable runtime replay passed15checks, including actual detector-to-banner delivery and real vanilla completion transport. Original-import and pre-fix replays passed14each. No synthetic staff event was injected into a live detector or shared sighting feed.

## 2026-10-25 14:00 UTC

- **PR #328: Explain connection loss separately from Staff Watch detection.** Timeout, explicit click-spam disconnect and other connection-loss alerts now state the reported reason, that this event was not initiated by local Staff Watch detection, the server, settings recovery and the next recovery action. Unknown underlying causes remain unknown. Immediate webhook and durable feed delivery share the clearer message.
- Reconnect reporting checks existing region/restart ownership, enabled state and user cancellation instead of claiming a scheduled retry solely from enabled settings. Native recovery checks still determine whether trading can resume. Count-only Staff Watch diagnostics separate received suggestion packets, unmatched replies and local probe-send failures. No extra requests or changes to recognition rules, local-proof requirements, escape decisions, cooldowns, trading guards or settings. Historical spectator-alert silence remains under investigation; this is not a claimed detector repair.
- Client artifact published **2026-10-25T14:00:59Z**. Build `1792936640029`; client SHA256 `d8f6cf6e361bf86cd61fd41d281f5840a4b34587dd86d15233fc3f1edb98bd1b`. Client, source ZIP and checksum manifest independently downloaded and verified. Source ZIP SHA256 `417a000c3bb1dd4fad7d5f02e4e8702a58a4917ff88f56c59794b400253b0b3f`, published 2026-10-25T14:01:02Z. PR328 merge `7d0cb51825bb31c527e39fa1ed9b72ccc7993ebe`; tested source `12409a00126b3ea8da0ecc31aecad545980d3e64`. This dated publication entry is documentation only. No live installation or restart for this alert/reporting release; existing live clients retain PR326.
- Full test/build: **1,669 tests, zero failures/errors, two skipped**. Offline regressions cover timeout/unknown-cause wording, explicit click-spam distinction, missing reasons, immediate/durable field parity and separate reply/send counters. No test notification was sent to Discord. Publication does not establish detector health or bot recovery.

## 2026-10-25 13:09 UTC

- **PR #326: Join recovery through separate regional servers.** After a confirmed network join failure, the existing reconnect flow advances through explicit regional addresses from the restart-recovery route list. Retrying the generic address could route back to the same unavailable region. Default routes cover east, west, Asia, Oceania, EU Central and EU West; custom same-network ordering and ports are preserved. The saved preferred server is unchanged and the existing two-minute retry delay remains.
- Authentication/enforcement refusals, unknown errors, manual cancellation, intentional stops, staff cooldowns and external restart ownership do not gain a regional bypass. No additional reconnect loop or worker. Trading resumes only through existing fresh-world/balance, persistence, ownership, exposure and safety gates. Manual attempts now record the actual selected address.
- Client artifact published **2026-10-25T13:09:09Z**. Build `1792933552584`; client SHA256 `b7c084c6e80afd901f911eae7b8d9be6a6681283f3c04f42a58b3047055ae5ce`. Public client, source ZIP and checksum manifest independently downloaded and verified. Source ZIP SHA256 `508ab1ecdeddf0339d51ab334778934f652bde7854789823193fbec1a4df0a83`, published 2026-10-25T13:09:12Z. PR326 merge `1258e6596dff36243551b9449a0ad9587f6201c5`; tested source `6e6af639998011bda18db82566b211c84bc1d95a`. This publication entry is documentation only.
- Full test/build: **1,665 tests, zero failures/errors, two skipped**. The failed-join lifecycle regression fails on prior routing (expected explicit east address, observed generic host) and passes with regional rotation. Tests retain custom routes/ports, refusal exclusions, cancellation, existing delay, connection ownership and native resume gates. Live repair and affected-client recovery are verified separately; publication alone does not establish recovery.

## 2026-10-25 12:37 UTC

- **PR #324: Keep a verified partial-stock result across matching owned-order rows.** Native inspection on the third local client showed 54 golden apples left untouched by the existing whole-stack rule. Another matching order offered Collect but did not open a stock page. Previously that final check discarded the valid partial-stock result and incorrectly held the entire own-order desk in navigation recovery. Bounded zero-click traversal now preserves the physical result, applies the existing per-order wait and releases navigation recovery for other eligible work.
- Missing menus without an observed physical partial-stock result still fail normally. No items are transferred or collection credits invented. Ownership, settings, spending, inventory proofs, whole-stack policy and safety gates remain enforced. Explicit local collection inspection is limited to five minutes and 16 distinct sanitized frames, with no raw tooltip data, uploads or inspection clicks.
- Client artifact published **2026-10-25T12:37:59Z**. Build `1792931601563`; client SHA256 `c88b19e37a991fe589be7e0cb243375cb4f4ebebdfc31392ecba0c737d8d0c40`. Client, source and checksum manifest independently downloaded and verified. Source ZIP SHA256 `7cd7cab736b566f61fc76c127f0196b63cdda83a922fa08ca3a99566d47b44d2`, published 2026-10-25T12:38:01Z. PR324 merge `25764e7dd7f8c2291234684582865eead2146209`; tested source `2b3f4006d643cbbd1a59dccfe04076b69fe9a32a`. This dated publication entry is documentation only.
- Full test/build: **1,660 tests, zero failures/errors, two skipped**. The native-menu/controller regression fails on previous behavior with Collect Items did not open and passes with this repair, leaving the 54 items and all collection credits unchanged. Overall utilization, profitability and remote reporter recovery remain unproven. The necessary repair installation of only the third local client is verified separately; healthy peers and the first client's intentional pause are preserved.

## 2026-10-25 08:56 UTC

- **PR #322: Preserve recovery backoff when the book opens but the collection menu still fails.** A successful Your Orders read previously erased the preceding non-read navigation failure count. Repeated missing Edit Order/Collect Items menus could therefore stay at the initial5-second recovery delay and never reach the existing15/60/120/240-second backoff and auction-side step-aside. A book read now releases the next owned action while retaining that history; successful collection/cancellation clears it. Read-only failures still recover on a successful book read. A full-book placement result cannot falsely clear downstream recovery.
- Existing retry timings, physical-stock attempt limits, inventory proofs, credits, ownership, spending, whole-stack rules and safety gates remain unchanged. This fixes a reproduced retry-loop amplifier, not the underlying native menu failure or the historical reporter incident. No live installation, restart or settings change for this publication; local builds remain PR311/PR317/PR319. The one-time native-menu evidence request and external reports remain unresolved.
- Client artifact published **2026-10-25T08:56:51Z**. Build `1792918433152`; client SHA256 `99f4f9cce132b33774b6a6195e4ed433f1780d11b8e5b2939582774f80385f0d`. Client, source ZIP and checksum manifest independently downloaded and verified. Source ZIP SHA256 `50fa5608022583a3ec592be3632699bf97206357835a03fb7368f7baa278a140`, published 2026-10-25T08:56:54Z. PR322 merge `11288677bb387a9197d482c289ec7e5ff1769d72`; tested source head `dff9bfd339e6ede6c65657d38918535750396df7`. This publication entry is documentation only.
- Full production test/build: **1,657 tests, zero failures/errors, two skipped**. The actual native-menu/controller regression fails on previous public code: after an intervening successful book read, the second collection-menu failure waits5seconds instead of15. Fixed coverage reaches the existing60-second third-failure step-aside, preserves physical attempts/credits and retains normal read-only recovery.

## 2026-10-25 07:23 UTC

- **PR #319: Reach the later matching order when an earlier Collect menu never opens.** A live native book had two same-key384-unit rows, showing0 and374 delivered. A native-menu regression using that layout demonstrates that a missing first stock menu can repeatedly select the earlier row and starve the later delivery. With no collection click and unchanged inventory, the driver now uses its existing bounded traversal to inspect the next matching owned row before returning navigation failure.
- Ownership, item/price/requested matching, physical credits, whole-stack rules, pacing, spending, exposure and safety gates remain enforced. Rows cannot switch after a stock click or inventory change. This repairs a reproduced duplicate-row path, not a proven full live root cause. After publication, the confirmed malfunctioning third local client received one necessary safe-idle/graceful repair restart onto this exact build; its configuration and prior position records were retained and connection verified, but golden-apple collection-menu failures persisted. No live recovery is claimed, no additional restart attempt is planned for that incident, and external reports remain open. The two peers were untouched.
- Client artifact published **2026-10-25T07:23:15Z**. Build `1792912808172`; client SHA256 `7cf71faa58bea3ff384937cf0ae71d7f041a4b7569ebac1eb373b08b5e9ff010`. Public client, source ZIP and checksum manifest independently downloaded and verified. Source ZIP SHA256 `1c8c6086774224ef28ff451ef0e746d0884995937d63e3a15c3459eadcdc4b14`, published 2026-10-25T07:23:17Z. PR319 merge `5199e05f468c544d8177b4e280be37699fdcceed`; tested source head `2c292f76569d7b095dd2b6865dc1ccc4a8606ae4`. This entry is publication documentation, not another binary.
- Full production test/build: **1,654 tests, zero failures/errors, two skipped**. The actual native-menu regression fails on previous public source; after repair it reaches the second duplicate, physically collects320units and leaves the unfinished54-unit tail.

## 2026-10-25 03:48 UTC

- **PR #313: Distinguish a missing collection menu from an empty order.** After choosing Collect, failure to reach Collect Items no longer spends the physical-stock retry budget. Recovery uses the existing bounded navigation backoff only when that menu was never observed, no collection click occurred, and inventory is unchanged. Previously four such navigation timeouts could permanently suppress an already-completed order.
- An actually reached empty stock page still uses its bounded physical retry budget. Clicked failures, unknown faults, server-strain history, inventory proofs, credits, ownership, spending and exposure guards remain enforced. Menu observation resets for each operation and each no-click transition between matching orders.
- Client artifact published **2026-10-25T03:48:45Z**. Build `1792899929347`; client SHA256 `03c9d18817fb6331685a95f28b80a0c9e2542a9539400912e41dc9e8bb49e93b`. Client, source ZIP and checksum manifest independently downloaded and hash-verified. Source ZIP SHA256 `501cb7f7f30c772a6b753495bed4382dc42acbc9cc5a5a0a889c6c3cfae03329`, published 2026-10-25T03:48:47Z. PR313 merge `df3040eacb256331a3ee19ae060bd55bae72b75e`; tested source head `1452189256c91a31404055ec79b52a381b50c6b2`. This publication entry is documentation only.
- Full production test/build: **1,648 tests, zero failures/errors, two skipped**. Actual native-menu/controller coverage fails on the previous public source after four missing-stock-menu timeouts, and passes after repair. A negative case keeps actual empty-stock failures bounded. The zero-click/wrong-page trigger was observed locally on PR311, but physical stock on that order and external reporter recovery remain unproven.
- No live client installation, restart or settings change for this publication. The three clients remain on their separately authorized PR311 rollout. Existing backlog reports stay open. Delayed private diagnostic receipts do not establish current build adoption or post-publication recurrence.

## 2026-10-25 03:25 UTC

- **PR #311: Keep lost Edit Order navigation from permanently suppressing filled-order collection.** A failure to reach the Edit Order menu now uses the existing bounded navigation recovery and backoff instead of spending the physical-stock collection attempt budget. Previously four such failures could leave an already-filled order ineligible forever because its delivered total could not increase again.
- Genuine empty-stock failures, unknown stage failures, failures after collection clicks, timeout history, credits, ownership, inventory proofs, spending and exposure gates retain their existing behavior. No settings, live client installation or restart for this publication. Affected-report recovery remains unverified.
- Client artifact published **2026-10-25T03:25:08Z**. Build `1792898511651`; SHA256 `046582962e06dd2fe322431c5aa7f18ed1d76c92d5e7ec81f737b0759387e308`. Client, source ZIP and checksum manifest independently downloaded and hash-verified. Source ZIP SHA256 `d024f5f25d262f6fb3f0c61f718a3f4a8607a7b2f707dac25d0e430be67e9b30`, published 2026-10-25T03:25:10Z. PR311 merge `dcad29f2e1899a7489f14d5ffc5949c04fd80d09`; tested source head `13d6c2ad0b5600a85411f7b4332388c8f0dd3d5e`. This entry documents publication and is not another binary update.
- Full production test/build: **1,646 tests, zero failures/errors, two skipped**. A native-menu/controller regression reproduces four pre-collection menu failures suppressing an unchanged completed 192-item order on the prior source; fixed source retains eligibility without fabricating stock or credits. Added physical coverage includes three collected stacks, a completed partial tail with preexisting inventory, and an unfinished partial stack left waiting.

## 2026-10-25 02:34 UTC

- **PR #308: Read and collect through full own-order pages in every order mode.** Native page traversal no longer depends on allowing multiple new buy orders per item. A full first page follows Next, a settled native end establishes a complete book, and collection can find its matching row on later pages. A final page is not reused as the starting page for another order. This also lets existing new-delivery retry rules receive complete evidence in the default order mode.
- Existing page verification, requested-count limits, physical inventory collection proofs, spending, exposure and ownership gates remain enforced. This repairs reproduced full-page paths associated with the backlog investigation; it does not establish that every historical backlog or capacity wait is resolved.
- Client artifact published **2026-10-25T02:34:44Z**. Build `1792895472068`; SHA256 `24e9e13cdd16b84b5697052767c943344f4545289ad34282068e2d457bbf0b40`. Client, source ZIP and checksum manifest independently downloaded and hash-verified. Source ZIP SHA256 `670df6b08796f1bf7f72ea3d352736fd57132310ff3c283b1bbb2a30975c22a3`, published 2026-10-25T02:34:44Z. PR308 merge `09f4e2a95e232821677edd69631503a9d2bd5b5b`; tested source head `8354da3a42dead90ffb38035f69216f4e89c59f3`. This publication record is documentation only.
- Full multi-module production test/build: **1,642 tests, zero failures/errors, two skipped**. Five actual native-menu/controller regressions pass; four fail on the previous published source. Coverage includes following Next in default mode, complete full end pages, later-page collection, full-book accumulation, final-page reuse prevention and reopening an exhausted collection after newer delivered stock is read.
- Reports284/285/295/305 remain open pending reporter recovery. No live client installation, restart or settings change was performed for this publication.

## 2026-10-25 02:01 UTC

- **PR #306: Keep normal partial-stack collection waits from slowing all trading.** A native collection page with only partial stock and no collect click no longer adds global server-strain pacing or pauses. Its existing terminal result, controller rest and whole-stack policy stay unchanged. Real faults and previously clicked actions still count; this guard does not clear existing timeout history or holds.
- Client artifact published October 24 at **19:01:56 Los Angeles time (October 25 02:01:56 UTC)**. Build `1792893662722`; SHA256 `6c9e7aa651337398671b9e12f5d755e9539f28b0247932b4aa435e55c1df7ebf`. Client, source ZIP and checksum manifest independently downloaded and hash-verified. Source ZIP SHA256 `09d06eda9c846d5a4d42d5860fbdb3bba178961a29a4d285cd3d1a224878b7e3`, published 02:02:04 UTC. PR306 merge `c9cfe2397cc5cf8c8d04e529cf69529e2e5c673c`; tested source head `ba1ab870dc19edcc90a3a5c64ad657be5a590ec9`. This publication record is documentation only.
- Full multi-module production test/build: **1,637 tests, zero failures/errors, two skipped**. Regressions cover repeated native partial-stock waits, unchanged terminal/collection handling, retained prior timeout history and existing holds, and genuine collection faults. The closed `STACK_BATCH` reason distinguishes this no-click guard. The receiver/worker retain raw evidence and exclude only the exact normal guard from failure summaries; unknown and other faults remain.
- Order sizing, settings, spending, ownership, collection credits and safety gates are unchanged. This does not complete draft PR304's fixed 1,024-item order integration or establish resolution of reports285/305. No live client installation or restart was performed for this publication.

## 2026-10-24 22:42 UTC

- **PR #302: Reopen collection for new deliveries and report native auction occupancy.** An exhausted collection retry budget no longer blocks newly delivered goods under the same item/price/quantity key. A newer complete native order book must show increased deliveries; unchanged or incomplete pages do not reset attempts. A key absent from a complete book retires only its old transient attempt state. Inventory movement must still prove every collection.
- Client artifact published October 24 at **15:42:25 Los Angeles time (22:42:25 UTC)**. Build `1792881485823`; SHA256 `dbc23e2114a70ccd6b214f4162d1a74189c9f0dc4d7fdb94deec51cc0d8b7d81`. Client, source ZIP and checksum manifest independently downloaded and hash-verified. Source ZIP SHA256 `89e999b981ebf45dd647dc8addf11ce504238b3473f500af486a8e52485f99c9`, published 22:42:24 UTC. PR302 merge `f2544f9280eb8bc41f40aadfa4a35331d24e981e`; tested source head `abc3a00e3e6ea8261f58bc187014c285dc4c044d`. This publication record is documentation only.
- The controller regression failed before repair and passes afterward. Clean production suite: **1,634 tests, zero failures/errors, two skipped**. Native auction-count, missing/stale/future source, account-scope and image fixtures also pass. The Money grid uses each account's native auction audit plus confirmed movements, with coverage and audit age; ledger tracked listings are separate. Missing occupancy remains unknown rather than appearing free. Auction capacity no longer depends on an unrelated own-order read being fresh.
- These fixes do not change order-sizing defaults, spending, depth, price, capacity, ownership, exposure or safety gates. A stack ceiling is not a guaranteed batch size. Sustained utilization and the full historical backlog reports remain unproven and open; publication alone is not evidence that any particular installation recovered.

## 2026-10-24 19:39 UTC

- **PR #300: Drain completed order tails so collection can release order capacity.** A fully delivered or cancelled order can collect its last partial stack within the existing shelf/inventory budget. Active bounded orders still wait for whole stacks, and inventory movement must prove collection before credit is recorded.
- Client artifact published October 24 at **12:39:06 Los Angeles time (19:39:06 UTC)**. Build 1792870455788; SHA256 59a81d4e9ce210ba14b686a740f1f23f58c25bfe8d5f7211b08f6183063eea3d. Client, source ZIP and checksum manifest independently downloaded and hash-verified. Source ZIP SHA256 1f0729f0e02559dbc748393072c6350a81a5d777f8d55b7764efad733e360cec, published 19:39:09 UTC. PR300 merge 50d6647d22ff7c4fc63d44272e414f4dfbd46086; tested source head 7254fcf852d3f36ddad969c40910f9a5fabda25b. This publication record is documentation only.
- The completed-tail regression failed before the repair. Clean production suite: **1,630 tests, zero failures/errors, two skipped**. Spending, ROI, ownership, persistence and exposure caps remain enforced. This fixes a reproduced replenishment path; sustained profit improvement and the full cause of report #295 remain unproven. Reports stay open pending their own evidence.

## 2026-10-24 15:24 UTC

- **PR #296: Let a sent listing finish verification when inventory changes before its response.** If the first sell command escrows/removes its stack before a receipt or confirmation arrives, withdraw its queued retry and keep verifying the original response. A changed hand before the first send still refuses the sale; movement alone never proves a listing or clears exposure.
- Client artifact published October 24 at **08:24:05 Los Angeles time (15:24:05 UTC)**. Build `1792855316409`; SHA256 `c7093a9f98ed0f431fa8ff178d24c5e520bcb2a1d6a81e54ba3f0d55ef017b06`. Public client, source ZIP and checksum manifest independently downloaded and hash-verified. Source ZIP SHA256 `b04d6782edfd2543d7a147af0b4ad66a85b3674b6617b5a8c8f6c5fed093c69a`, published 15:24:08 UTC. PR296 merge `13479587848adbcb696947ba48ab08d5be543306`; tested source head `438f213c694000a93dd18c136e701a86bf2fa955`. This publication record is documentation only.
- Two actual queued-dispatch regressions failed on the old path and pass after the repair. Full clean production suite: **1,628 tests, zero failures/errors, two skipped**. Initial-hand, late-confirmation, server-refusal, pacing, ownership and inventory verification remain enforced.
- Related report #295 remains open: cloud history lacks the exact hand/dispatch sequence, so the independently reproduced timing repair does not establish the whole backlog's cause. No live bot upgrade, restart, setting change or protective-hold bypass.

## 2026-10-24 08:23 UTC

- **Release PR #292: Keep schema upgrades and their version markers atomic.**
- Client artifact published October 24 at **01:23:23 Los Angeles time (08:23:23 UTC)**. Build `1792829965952`; SHA256 `c2fa08b0d5c08899ca77178ac0861462f32ee0f998416b3a8245d244849421e2`. Clean production build: **1,626 tests, zero failures/errors, two skipped**. Sole production client entrypoint; no Smoke classes. Public client, source and checksum manifest independently downloaded and hash-verified. Separate binary publication after PR290.
- PR292 merge `ca4c65de31f24bcf60ff54b9de13ef954dc6b9db`; tested source head `4d8b60ac62dcf767fffe3c252ab2b8c147ab7e65`. Source ZIP SHA256 `af8beada11d741fe63470b7e56b27f84bf09812ed60348259f61e46cb3073df7`, published 08:23:25 UTC. This publication record is documentation only.
- If a schema-version write failed after V14 rebuilt its checkpoint table, the old client left that rebuild committed while still reporting V13. A retry could attempt the migration again against the changed schema. Schema steps and version markers now commit together under a shared-ledger writer lock acquired before reading the version; failed upgrades roll back. Failed database opens close their connection and release Windows WAL handles. The existing busy timeout also applies before journal-mode setup.
- The synthetic version-write failure reproduced the old partial-upgrade defect. The repair preserves the original checkpoint and uncertain intent on failure, supports a normal retry, and passes four concurrent clients opening one legacy ledger. No manual schema repair, position reset, persistence-hold bypass, or trading/settings change is included. Healthy live bots were not upgraded or restarted.
- Private diagnostic migration failures prompted the investigation but retained no exact SQLite code/message. This independently reproduced defect does not establish that installation's exact cause, repair an already damaged schema, or explain its later PERSISTENCE_WAIT. Existing reports and that investigation remain unresolved.

## 2026-10-24 05:21 UTC

- **Release PR #290: Recognize a full order page with repeated item labels.**
- Client artifact published October 23 at **22:21:40 Los Angeles time (October 24 05:21:40 UTC)**. Build `1792819067356`; SHA256 `ae0c9a85cffe798d6937dc25b217dc32b72feeb213c49d2a7ac75e9b693b6c07`. Production build: **1,624 tests, zero failures/errors, two skipped**. Sole production client entrypoint; no Smoke classes. Public client, source and checksum manifest independently downloaded and verified. Separate binary publication after PR286.
- PR290 merge `21d878aad097c7d2cef8f2a7259a35de488a72a2`; tested source head `7e15e82ac81b61f155815cff0371b15ccd7973fc`. Source ZIP SHA256 `6badc68870a3f91424ca08ca08258bae5357dc4a8b9021d9c5be8961de3b45a6`, published 05:21:45 UTC. This publication record is documentation only.
- When all 45 cells on the visible Your Orders page are occupied, repeated item names were deduplicated before counting. A full page could therefore wait for a nonexistent New Order control and abort, adding avoidable server-strain backoff. Each occupied cell now counts once, including repeated or blank hover names, and takes the existing no-charge full-page completion. Native next-page traversal still runs first where enabled; this does not change rank capacity or create an order on a full page.
- Both repeated/blank-label regressions fail with the old distinct-label count and pass with this repair; incomplete and empty pages remain incomplete. No quantity, order-count setting, budget, exposure, ownership or safety-gate change. Healthy live clients were not updated or restarted for this release.
- Retained diagnostics show a consistent full-page/fixed-wait pattern, including before report #285's anchor, but cannot prove the exact native label/control state or that this defect explains the entire reported auction backlog. The independently reproduced code defect is repaired; report #285 remains open for verification and unresolved symptoms.

## 2026-10-24 04:37 UTC

- **Release PR #286: Configure separate automatic buy orders per item.**
- Client artifact published October 23 at **21:37:33 Los Angeles time (October 24 04:37:33 UTC)**. Build 1792815998964; SHA256 7b40e6398f2e79ac98f706301bd9ab9b74ac644588b112bf6703ef598fd77ac0. Production build: **1,622 tests, zero failures/errors, one skipped**, including a passing live diagnostics upload, deduplication and erase check with the new setting. Sole production client entrypoint; no Smoke classes. Public client, source and checksum manifest independently downloaded and verified. Separate binary publication after PR287.
- PR286 merge 7f257be7dfe0d65ef82ede1db0a053170825ff5d; packaged source head e31177b3fc617f3df7b3295f49d43d88ce779333. Source ZIP SHA256 f921a52616a59627bf5ecae34ee70c9482a9f5bc81068a626952b08dd9ce2bb6, published 04:37:36 UTC. This publication record is documentation only.
- Under **Settings → Advanced view → Orders**, **Buy orders per item** allows more than one separate automatic buy order with Reserve Stock off. Set 2 to allow two per item; 0 preserves the existing mode behavior. Public upgrades preserve existing settings. Lowering the limit retains orders and stock. A fresh complete native order-book read separates placements; bounded page traversal fails closed when coverage is uncertain. Additional orders reuse a competitive owned bid rather than outbidding it.
- Outstanding orders, delivered stock and held inventory share depth and spending checks. Collection waits for room on the selling shelf. Native quantity sizing, price, ROI, exposure, ownership and recovery checks remain authoritative. This changes the number of orders, not exact stack quantities: existing 999-unit protection remains, so exact 1,024-item orders are not supported. More permitted orders do not guarantee fills, occupancy or profit. Report #285 remains open; its exact incident cause is not established.
- The trading guide documents normal-mode multiple orders and the separate Reserve Stock cycle behavior. The private diagnostics and dashboard services accept the new numeric setting and closed setting token while retaining strict validation and old-client compatibility.

## 2026-10-24 02:37 UTC

- **Release PR #287: Collect the live row among identical historical orders.**
- Client artifact published October 23 at **19:37:17 Los Angeles time (October 24 02:37:17 UTC)**. Build `1792809198792`; SHA256 `97c855df1b10a0b4f9f341b4ebb23dc755310c409f9c698de22fce46cd554e92`. Clean production build: **1,600 tests, zero failures/errors, two skipped**. Sole production client entrypoint; no Smoke classes. Public client, source and checksum manifest independently downloaded and hash-verified. Separate binary publication after PR282.
- PR287 merge `d781abf6bb9df97a1fece5956490f4fa9e355f66`; tested source head `1ae23770f8f1eb451b2496c800b24343aaed62c8`. Source ZIP SHA256 `a0aae916ab0494714791a6d89b8ebb2dab877aa0983cde02dc9f7bb05a43eb8b`, published 02:37:19 UTC. This publication record is documentation only.
- Ordinary collection now uses durable collected quantities to start at the matching row still owing goods instead of always opening the first identical historical row. If those rows reordered, the native driver checks other exact item/price/quantity matches in a bounded pass before any item click or inventory change. An exhausted native search is counted once; controller fallback remains available when it has not already been performed. Credits are still booked only from verified physical collection. Whole-stack, free-inventory and collection-quantity limits remain authoritative.
- The old planner failed the duplicate-row regression; the repaired planner and bounded-search regressions pass. This repairs a supported collection defect related to the path investigated for report #285, not proof of that report's original cause or that every backlog is solved. The report remains open. Configurable order-batch counts remain separate in draft PR286; no order sizing, settings, budgets, safety gates or healthy live-client builds changed in this release.

## 2026-10-24 01:11 UTC

- **Release PR #282: Support sixteen large-ticket listings per market.**
- Client artifact published October 23 at **18:11:53 Los Angeles time (October 24 01:11:53 UTC)**. Build `1792804021824`; SHA256 `93f7661b70355e57e38f0db8f57d9082850ea009416d8e818a616968dc7bf9cf`. Clean production build: **1,597 tests, zero failures/errors, two skipped**. Sole production client entrypoint; no Smoke classes. Public client, source and checksum manifest independently downloaded and hash-verified. Separate binary publication after PR280.
- PR282 merge `45d44cdebdca00667cc6d7496d4e51c13481e91f`. Source ZIP is tested head `44fbec530f9e252b2c3ee9588156a2693243c64a`; SHA256 `cf7039f955e49a126a162ab0d4a63937c1881b5f946612c40ebfdfb55c0a0c2d`, published 01:11:55 UTC. This publication record is documentation only.
- The general listing limit already accepts sixteen, but the separate **Large listings per market** setting previously clamped requests to ten. Its supported maximum is now sixteen across the in-game GUI, local panel and hosted panel. Default remains two, minimum remains one; existing installations are not automatically reconfigured.
- The range regression fails on the prior client and passes with this change. No purchase sizing, tier-pool allocation, spending/exposure/ROI guard, ownership or Reserve Stock behavior changes in the public client. Customer-requested local sizing/depth/tier settings are a separate explicit configuration change, not public defaults. Larger limits do not guarantee filled order books or occupied auction slots; live prices, fills, affordability and demand still apply.

## 2026-10-24 00:55 UTC

- **Release PR #280: Distinguish normal auction-watch completion in diagnostics.**
- Client artifact published October 23 at **17:55:47 Los Angeles time (October 24 00:55:47 UTC)**. Build `1792803135397`; SHA256 `16d7b42ae96a77988ae96e7561edfd4da923e9810d9f1b8c99fdcc19db25e53e`. Clean production build: **1,596 tests, zero failures/errors, two skipped**. Sole production client entrypoint; no Smoke classes. Public client, source and checksum manifest independently downloaded and hash-verified. Separate binary publication after PR278.
- PR280 merge `08c53dc3448d5a7e6396c7bade402824603ec4f5`. Source ZIP is tested head `9f39e56b3ffc94e6ae2dea6bec3ba16ae62c8895`; SHA256 `447589f38ceba27c84a4a5176f82becfa46d0f886acc24597dd5a25008cfc7b1`, published 00:55:48 UTC. This publication record is documentation only.
- A guarded auction watch ending without selecting a purchase now emits the fixed `WATCH_NO_PURCHASE` diagnostic reason instead of `UNCLASSIFIED`. Its existing terminal outcome and controller behavior remain unchanged. The compatible diagnostics receiver stores the event but excludes only `DRIVER / ABORTED / BUY / WATCH_NO_PURCHASE` from failure signatures. Real faults, unknown aborts and other operations remain visible; historical records are not relabeled.
- The real watch-budget regression failed before the repair and passes afterward; tests preserve selected-purchase verification, real confirmation-timeout reasons and strict privacy sanitization. Receiver owner independently reproduced rejection/misclassification, then passed 99 receiver and 104 worker tests, including an isolated Postgres storage/grouping check. No unresolved trading report is declared fixed from this diagnostic improvement. Existing healthy live clients remain on PR278; no restart, live upgrade, settings or trading-policy change was performed for this release.

## 2026-10-24 00:40 UTC

- **Release PR #278: Keep cached summary images at their original resolution.**
- Client artifact published October 23 at **17:40:22 Los Angeles time (October 24 00:40:22 UTC)**. Build `1792802182599`; SHA256 `234ccf47cdc4acbc52a3480285cb65a57ba7b7deda7f89c0997933596325eb8e`. Clean production build: **1,595 tests, zero failures/errors, two skipped**. Sole production client entrypoint; no Smoke classes. Public client, source and checksum manifest independently downloaded and hash-verified. Separate binary publication after PR276.
- PR278 merge `fba2198eff32363e2d87a9575e414519d83afda6`. Source ZIP is tested head `b403d3995c6394fd3f8fb538b0f9828b2eca7468`; SHA256 `fbd5a96c0e0ddc6f476514c0b4672d366ca180b4ada4d8c2e54cbee21570f07a`, published 00:40:31 UTC. This publication record is documentation only.
- Fixes PR276's preview quality regression: dense 1920px PNG summaries were repeatedly shrunk to fit one relay response. Cached previews now preserve the original PNG byte-for-byte. Large images transfer in sequential bounded chunks on the existing authenticated reporting endpoint; the browser assembles and decodes the complete image before replacing the last good frame. A pinned account/tab/frame prevents mixed versions; failed or expired transfers retain the prior image and its honest age. Caches and transfer retention are bounded.
- Money's complete shareable image remains first with readable text underneath. Served 390px phone fixture confirmed 1920px source images, no horizontal overflow and immediate cached return. Installed hosted relay passed exact-fixture transform/CSP parity at 390/1280px without a backend change; shell parity alone is not a live phone screenshot. No trading settings, positions, guards or diagnostic policy changed. This repair does not claim the exact earlier intermittent timeout cause was proven.

## 2026-10-24 00:27 UTC

- **Release PR #276: Fast cached reporting tabs and background image rendering.**
- Client artifact published October 23 at **17:27:47 Los Angeles time (October 24 00:27:47 UTC)**. Build `1792801410334`; SHA256 `aef6aca52989c3160110f00d5473a7b1ab7052cbdb8eb2d4fc3feaf76cda4061`. Clean production build: **1,594 tests, zero failures/errors, two skipped**. Production entrypoint only; no Smoke classes. Public client, source and checksum manifest independently downloaded and hash-verified. Separate binary publication after PR274.
- PR276 merge `1d263cdc719b7418c242742ebec48a64bbc0ae2c`. Source ZIP is tested PR head `ccba08ed99a51c80b85cc5f870c49ba28c403b06`; SHA256 `f8c3e9d0be297f2c5f8f9a5688594e4af08dfe4058b61c578a94033f6930cb89`, published 00:27:50 UTC. This publication-record update is documentation only.
- Reporting tabs keep separate images and readable summaries per selected account in memory. Returning to a cached tab shows its last successful frame immediately, with an update age; current controls load from fresh state. Common reporting views warm one at a time in the background. Cold/evicted views still need their initial render; inactive tabs are staggered rather than promising every image is two seconds old.
- Native status reads no longer compute the full financial report inline. A bounded background renderer snapshots reporting views without selecting another page/account or holding the live panel navigation monitor during image encoding. Existing authenticated data routes carry bounded data-URI PNG previews. Status polling is single-flight, obsolete navigation is skipped and late replies cannot replace a newer account or tab. Failed rendering retains its last frame and original age; memory use is bounded.
- The complete Money summary image remains first with readable text below. Settings forms and command controls are never restored from cached reporting frames. No trading settings, sizing, guards, positions or diagnostic policy changed. New regressions verify slow-render behavior, isolation, failure retention, eviction, preserved selection and concurrent polling; the 390px served phone preview showed cached Orders/Money before their controls refreshed. This improves request isolation but does not claim the exact earlier intermittent timeout cause was proven.

## 2026-10-23 23:51 UTC

- **Release PR #274: Complete Money summary image first, readable details below.**
- Client artifact published October 23 at **16:51:21 Los Angeles time (23:51:21 UTC)**. Build `1792799271326`; SHA256 `7716799baccc40decf27b20dd0788a4de85d8a0879767ecca182b4acc476e8ea`. Clean production build: **1,590 tests, zero failures/errors, two skipped**. Normal production client entrypoint; no Smoke classes. Public client, source and checksum manifest independently downloaded and verified. Separate binary publication after PR272.
- PR274 merge `afec20c88dcc0567831c21f0a478739af3251e91`. Source ZIP is tested PR head `4dd828af4bdaf5511726e32748fd58e58360a24b`; SHA256 `4814174cd06228433835031a6580306c0670134cdac321face79c3cf8874d0f8`, published 23:51:24 UTC. This publication-record update is documentation only.
- On the mobile Money page, the complete copyable/shareable summary image is always visible first and fits the phone width. Readable text cards and the occupied/free slot grid stay underneath. No extra toggle, and neither representation hides the other. Desktop and Discord summaries remain unchanged. Existing decoded refresh buffering, account scoping and trading settings are preserved. Mobile guide updated; actual served 390px layout verified with synthetic data.

## 2026-10-23 23:36 UTC

- **Release PR #272: Mobile app layout, restored auction slot grids and stable Money refresh.**
- Client artifact published October 23 at **16:36:20 Los Angeles time (23:36:20 UTC)**. Build `1792798332337`; SHA256 `541618d74e38b70e9580678c9994041baa7b3a4aeacf1d329d6ec3fb5c08840f`. Clean production build: **1,590 tests, zero failures/errors, two skipped**. Normal client entrypoint only; no preview/smoke classes. Public client, source and manifest independently downloaded and hash-verified. This is a separate client binary after PR270.
- PR272 merge `feed900e738009b521172f5583efb4bb184dde08`. Source ZIP is tested PR head `ad2e648ac98f760af92859fb6ce1b238e306832d`; SHA256 `afd207fabdad041a7a9b292c26e9290f55ead846a248d4bc34fa69d2b92870d2`, published 23:36:22 UTC. This publication-record update is documentation only.
- On phones, a compact account chooser, fixed bottom work areas and a single scrolling content area replace the tall desktop navigation. Money presents the same financial/order summary in readable cards, with recent sales and the full occupied/free slot grid. The original summary image remains available. Customize this view changes presentation only.
- All accounts uses current Hive members for capacity, occupied slots, activity, faces and order coverage. Three current 90-slot bots report 270 slots, without a historical fourth account making coverage unknown. Historical accounts remain explicitly selectable and keep their records; missing current observations remain unavailable instead of borrowing the host's data.
- Each current bot has its own current-action card. Money fields retain stable order and image positions when values change or observations are temporarily unavailable. Buffered image refresh waits for a decoded replacement, retains the last valid same-account image on refresh failure and rejects late images from the previous account.
- Phone/tablet/desktop layouts checked at 320, 390, 768 and 1280 pixels, with synthetic examples clearly labeled. New [mobile guide](guide/mobile.html) and UI preview images explain navigation and account scope. Existing desktop tutorials and required private diagnostics remain included. No trading sizing, Reserve Stock, spending/exposure/ROI cap or safety-gate change.

## 2026-10-23 22:35 UTC

- **Release PR #270: Simple / Advanced settings, clearer navigation and account-specific order reporting.**
- Client artifact published October 23 at **15:35:51 Los Angeles time (22:35:51 UTC)**. Build `1792794472237`; SHA256 `3f6a43699b6141e2d4d54666e3c735e44fe4ae7c66c58c3fcaa9152233166570`. Full production build: **1,585 tests, zero failures/errors, two skipped**. Production entrypoint only; no preview/smoke classes. Public client, source and checksum manifest independently downloaded and hash-verified. Separate client binary after PR268.
- PR270 merge `002e42ab3fa76fa3b79a9952ea62316537736669`. Source ZIP is the exact tested PR head `57bd4b32d3bf9d748ba05f2f4e0ce694fdc114ff`, whose tree matches that merge; SHA256 `419bfc5e45b8f6053ac532ac9155847da589539b3fae005677d437008b0805da`, published 22:35:54 UTC. This publication-record update is documentation only.
- Five aligned workspaces replace the tab wall. Simple settings groups four core controls with exact before/after previews; unmatched custom values stay preserved. Switching views changes no settings. Advanced keeps individual controls in searchable categories. Shared explanations show consequences in native, local and hosted panels. Independent in-game scaling and compact-window controls were visually checked without joining a world or arming trading.
- Money keeps its existing auction/profit layout and adds unfilled orders, committed order funds, delivered goods awaiting collection and observed filled orders per hour/day. Orders and summary figures follow the selected account; All accounts combines the Hive. Capacity sums each account's own limit, so three 90-slot accounts show 270. Missing/stale observations remain unknown or partial. Completion history starts with this update, deduplicates reads/restarts and does not reconstruct offline activity or count filled orders as sales/profit.
- The supported Listings per market maximum expands from 10 to 16; its default remains 8. Existing configurations, spending/exposure limits, tiers, native ownership checks, order batch sizing and Reserve Stock behavior are preserved. Live bots were not upgraded/restarted or assigned a new limit by publishing this update; old clients still clamp this setting at 10.
- Refreshed in-game tutorials, setup entry point (**Settings → Help & setup**), handbook, GitHub guides, screenshots, install video, Hive video and Discord video match the current UI. The hosted relay compatibility update preserves authentication/CSRF and passed desktop/phone fixture checks. Required private diagnostics from PR268 remain included.

## 2026-10-23 21:40 UTC

- **Release PR #268: Required private diagnostic history across clients and restarts.**
- Client artifact published October 23 at 14:40:07 Los Angeles time (21:40:07 UTC). Build `1792791320879`; SHA256 `12e0f4a24e96e4b04854f1bbb4730d1d03575cba66a620a64c9dc279cb46a027`. Full production build: 1,554 tests, zero failures/errors, two skipped, plus a separately enabled passing live receiver smoke test. Public client, source and checksum manifest independently downloaded and verified. Separate binary publication after PR263.

- All installations, including headless clients outside Swarm, upload typed,
  sanitized action/decision/fault evidence and numeric settings for bug diagnosis
  and recurring-failure investigation. History is private and bounded to up to
  24 hours; chat, names, locations, credentials and raw log/settings files are excluded.
- A durable diagnostics-only outbox survives restarts, retries outages and
  declares coverage gaps. Reports can privately link available history across
  sessions. Network failures never stop trading. About and report screens disclose
  the collection and show its availability.
- Preserve specific GUI/command timeout and listing-failure reasons rather than
  replacing them with UNCLASSIFIED. The VPS also repairs dotted settings parsing
  and reserves order decisions in selected history. Cross-client signatures are
  investigation leads, not automatic claims that a bug is proven.
- The separate Simple/Advanced UI overhaul remains a local preview.

## 2026-10-23 18:08 UTC

- **Release PR #263: Validate actual order offers before rejecting bulk bids.**
- Client artifact published October 23 at 11:08:14 Los Angeles time (18:08:14 UTC). Build `1792778694619`; SHA256 `aad91c0be149dbd78cb2d57104f0d36ca10a28a240c302c9fd21c2f113a3da2d`. Full clean production build: 1,541 tests, zero failures/errors, one skipped. Public client, source and checksum manifest independently downloaded and verified. Separate binary publication after PR261.
- The ordinary order planner checked its unused maximum resale ceiling against actual-trade and auction prices before calculating its lower offer. That could reject a safe whole-stack order and leave capacity unused. The existing offer calculation now runs first, and the same market-price gates validate the amount the order would actually pay. Previously eligible offer amounts, quantity sizing, spending limits, outbid checks and polluted-price checks remain in force.
- The real-planner regression reproduces the price-sanity refusal before the fix and queues a whole-stack order after it. Offers actually above the trade-price reference remain refused, with both sale-based sizing modes covered. This is a trading-logic fix, not a reporting-only change. It matches a rejection category in report #260, but the missing historical comparison values prevent claiming every symptom had this cause. The report remains open for verification; no live client was automatically upgraded or restarted.

## 2026-10-23 17:42 UTC

- **Release PR #261: Preserve order-sizing evidence through diagnostic floods.**
- Client artifact published October 23 at 10:42:22 Los Angeles time (17:42:22 UTC). Build `1792777097722`; SHA256 `ce0191f774478fa9fd87bce29dcafc243bd55ef7ea229b65f318075ec2ec04d6`. Full clean production build: 1,537 tests, zero failures/errors, one skipped. Public client, source and checksum manifest independently downloaded and verified. Separate binary publication after PR258.
- Report #260 exposed a coverage gap: recent driver phases and rejected candidates could displace the accepted order's sizing explanation. A separate bounded latest-per-market projection now reserves recent order plans and sizing inputs after baseline subsystem evidence. Original timestamps remain visible, pinned evidence expires after 15 minutes, and attachment limits, privacy filtering and omission counters remain enforced. The 6,000-event flood regression failed before the fix and passes afterward.
- This fixes report retention, not the underlying trading complaint in #260. Trading choices, quantities, price checks and safety gates are unchanged. Older missing decisions are not reconstructed. Continuous cloud-log collection remains separate; existing live bots are not automatically upgraded or restarted.

## 2026-10-23 16:15 UTC

- **Release PR #258:** Preserve system-wide decision evidence in current bug reports.

- Client artifact published October 23 at 09:15:27 Los Angeles time (16:15:27 UTC). Build 1792771157556; SHA256 `4ec2104559686e90100068e81c4ce646f644e0d1a0eb9664f755a0f0ea968887`. Full production build: 1,536 tests, zero failures/errors, one skipped. Public client, source and checksum manifest independently downloaded and verified. This is a separate binary publication after PR254.

- Reports now preserve a bounded timeline across the mod's subsystem traces, order-sizing inputs and final quantities, 37 closed planner rejection reasons, selected auction-watch markets, and observed numeric settings changes. Both description fields guide priority while baseline evidence remains available across subsystems. Capture-window, omitted-record and eviction counts make missing coverage explicit. Private identities, chat, commands and secrets remain excluded.

- Current attachments support 24 KiB with a matching receiver and worker deployment. The worker now distinguishes progress from correctness: active cycles alone do not explain a quantity, scheduling or capacity complaint, and an intended protective hold does not resolve the original refused write. A generated manual/automatic fixture was checked against the real receiver and worker; no valid decision line was dropped.

- This improves report evidence; it does not claim the trading behavior in reports #236/#257 or the original refusal in #256 fixed. Older reports cannot gain decisions their client never recorded. The separate continuous 24-hour cloud-log feature remains staged, not enabled by this release. Existing live bots are not automatically restarted or upgraded.

## 2026-10-23 02:29 UTC

- **Release PR #254:** Preserve diagnostic samples across game-thread and upload-worker timing.

- Client artifact published October 22 at 19:29:50 Los Angeles time (02:29:50 UTC). Build 1792722426072; SHA256 `ecee13a49e7e05dbc971ced8cda41f96ec12502a2a6714669d7c89696fa4cb01`. Full production build: 1,531 tests, zero failures/errors, one skipped. Public client, source and checksum manifest independently downloaded and verified. Separate client publication after PR251.

- A diagnostic capture queued just after the upload worker read its clock could be incorrectly discarded as future data. The outbox now retains that sample and waits for the next batch timestamp. The regression reproduces the interleaving and failed before the fix. Size and 24-hour retention limits, typed sanitization, acknowledgments, trading settings and safety gates are unchanged. Previously missing samples remain explicit gaps; there is no invented backfill or claim that every historical gap has this cause.

## 2026-10-23 02:19 UTC

- **Release PR #251:** Include the Focused toggle in private numeric diagnostic snapshots.

- Client artifact published October 22 at 19:19:39 Los Angeles time (02:19:39 UTC). Build 1792721834265; SHA256 `497fdd1878e38bebb397a9d2194d6e40ee608759ee541f7a34a909bc4dd55178`. Full production build: 1,530 tests, zero failures/errors, one skipped. Public client, source and checksum manifest independently downloaded and verified. Separate client publication after PR249.

- PR249 introduced Focused trading, but its numeric on/off toggle was missing from the explicit report export allowlist. This follow-up lets diagnostic history, bug reports and hourly analysis observe that toggle. The regression failed before the fix and now passes; text item lists remain excluded. Trading behavior, saved item selections, spending limits and safety checks are unchanged.

## 2026-10-23 02:12 UTC

- **Release PR #249:** Optional Focused trading and whole-stack quote refresh repair.

- Client artifact published October 22 at 19:12:38 Los Angeles time (02:12:38 UTC). Build 1792721417585; SHA256 `52311e25e3aff819075d4266e0cc556b7acda69578f3413e81c33e62a05dd904`. Full production build: 1,529 tests, zero failures/errors, one skipped. Public client, source and checksum manifest independently downloaded and verified. This is a separate client publication after PR247.

- In Settings → Buying, Use trading focus narrows new purchases and bids to Trading focus items. It is off by default; an enabled empty list buys nothing. Broader observations, existing-stock and order management, Items exclusions, ownership, caps and safety gates remain in force. Saved presets allow manual Original, Focused and Overnight selections. This is not automatic profit ranking or an overnight scheduler, and no profit uplift is promised.
- A rejected single-item bid candidate no longer prevents its market’s whole-stack quote refresh; insufficient price evidence still refuses the bid. Latest private Swarm diagnostics are retained, with backend validation updated for the numeric Focused toggle. Text item lists remain excluded from shared settings snapshots.

## 2026-10-23 01:50 UTC

- **Release PR #247:** Private 24-hour Swarm diagnostic history linked to reports and observed settings.

- Client artifact published October 22 at 18:50:41 Los Angeles time (01:50:41 UTC). Build 1792719202825; SHA256 `ab24e58f64e61c058fd188e1d21e0721064b120911f6f66a3f2d5e94a7302533`. Full production build: 1,525 tests, zero failures/errors, one skipped. Public client, source and checksum manifest independently downloaded and verified. This is a separate client publication after PR245.

- Swarm sharing now includes a rolling 24-hour private timeline of typed mod diagnostics and recognized numeric settings. This history excludes usernames, chat, credentials, home names, paths, text settings and raw Minecraft logs. Paused trading remains part of an enabled Swarm; disabling Swarm or unlinking stops new uploads.
- A server-issued Swarm client ID privately connects the timeline and settings versions to submitted reports. The support worker can inspect earlier failures and configuration changes even when the client has since closed. Sampling gaps and capture times are labelled; settings chronology is evidence to investigate, not proof of cause. Reports remain available without optional history linkage, and private tokens/timelines never appear in public issues.
- The allowlisted admin has a per-client timeline with observed settings changes. Uploaded timeline data expires after 24 hours; filed reports retain only a bounded selected diagnostic excerpt and relevant settings under the existing report policy. Old clients need this update before sending history; no historical data is invented or backfilled.

## 2026-10-22 22:39 UTC

- **Release PR #245:** Hourly numeric settings snapshots for private Swarm analysis.

- Client artifact published October 22 at 15:39:41 Los Angeles time (22:39:41 UTC). Build 1792708705184; SHA256 `c791e129bd4afddbe6f59be2c52816056dd1d565a36f4d3b06997c8790d037d6`. Full production build: 1,512 tests, zero failures/errors, one skipped. Public client, source and checksum manifest independently downloaded and verified. This is a separate client publication after PR243; the running bots were not upgraded.

- Linked Swarm clients share hourly snapshots of recognized numeric trading settings with diagnostic and performance metrics for private service analysis, feature/default refinement and bug investigation. Text/list settings, credentials, webhook URLs, player names and local paths are excluded from these snapshots.
- Snapshots use the existing authenticated dashboard connection only after server support is negotiated. Unlinking stops future sharing. The admin analysis view compares configurations and observed outcomes without automatically changing anyone's settings; snapshot times are observation times and do not establish causation.

## 2026-10-22 22:05 UTC

- **Release PR #243:** Refuse illegal characters before sending game commands.

- Client artifact published October 22 at 15:05:28 Los Angeles time (22:05:28 UTC). Build 1792706523474; SHA256 `9f9e5d70511b0bc4fcf926c8a1160429f53cfd1cca5eddf2c5db6943992d32d3`. Full production build: 1,507 tests, zero failures/errors, one skipped. Public client, source and checksum manifest independently downloaded and verified.

- Custom auction searches and manual messages could contain formatting codes or control characters that Minecraft rejects with an illegal-characters disconnect. Commands now use Minecraft's own character validator before queuing or sending. Invalid text is refused without rewriting commands; valid Unicode, payment amounts, pacing and safety gates are preserved.

- Report #242 remains open: its diagnostics record disconnect notices but do not identify the offending command or exact disconnect packet. This repairs a reproduced unsafe text path; the cause of that reporter's kick remains unconfirmed. Running bots were not upgraded for this publication.

## 2026-10-22 20:54 UTC

- **Release PR #238:** Restore existing Hive accounts safely and repair shared Discord host configuration.

- Client artifact published October 22 at 13:54:50 Los Angeles time (20:54:50 UTC). Build 1792702297322; SHA256 `b7ea6f0e9e80c32e56d7fd09e811ab56905a7f60cd2784c75a659feb8a4c657d`. Full production build: 1,500 tests, zero failures/errors, one skipped. Public client, source and checksum manifest independently downloaded and verified. Offline GUI preview verified the restore instructions, separate action buttons and Back without changing data.

- Added a reviewed Restore existing Hive account GUI flow for replacement Minecraft instances. It reuses the signed-in account’s saved profile and records, refuses active duplicate instances or conflicting local history, and preserves owned stock, orders, settings, limits and native recovery gates without deleting or resetting data.

- Fixed Discord panel updates stopping when a joined Hive profile wins host election. All hosts read and save the shared Hive Discord configuration and message identity without copying credentials into per-account settings. Trading ownership, recovery and safety gates are unchanged.

## 2026-10-22 04:51 UTC

- **Release PR #234:** Manual network reconnect controls and visible disconnect reasons.

- Reconnect screen: added **Reconnect now** for an ordinary connection-loss countdown and an explicit **Cancel / server list** route. Manual retries connect once and do not grant permission to resume trading; active staff-alert cooldowns and intentional restart ownership remain authoritative.
- Ordinary network retries now say **Connection lost** and show the latest available disconnect reason, instead of labelling every lost connection as Staff Watch. This implements the manual retry control requested in report #233; it does not establish or fix the underlying server rejection in that older-build report.

- **Validation/publication:** 1,495 tests, zero failures/errors, one skipped. Release PR #234 merge `96473ff29aa83865a634a9ee3a5625df0e1f4323`; build `1792644520377`. Public JAR upload completed **2026-10-22 04:51:12 UTC** (Oct 21, 21:51:12 America/Los_Angeles). JAR SHA-256 `8a7aac703c3d86beb2a37a4f21d3d65cc79eb4de5f6434169d7c93eb14957091`; source ZIP SHA-256 `894e7bd3c5c16623e92f20144421f7242ac1347620b676f5ca124e29a43acd8c`. Public JAR/source/checksums independently downloaded and matched. Separate binary after PR230; this publication-record commit is documentation only. Running bots were not upgraded.

## 2026-10-22 02:22 UTC

- **Release PR #230:** Staff Watch packet intake and shared Hive history.

- Staff Watch history now uses the validated shared Hive directory even when an added member has separate account settings. New observations from added members join the shared history; existing profile-local history files are retained without an automatic historical merge. Solo histories keep their existing location.
- Staff Watch: Anti Vanish now processes visibility and game-mode flags independently and inspects updates inside packet bundles. Full player-list additions remain excluded from local game-mode evidence; detection thresholds and response settings are unchanged.
- Added anonymous Anti Vanish counters to the existing minute detector log: bundled updates, queued versus initial mode updates, completion attempts/replies, recognized staff candidates, and alert requests. No additional game requests or background workers. These repairs address reproduced packet-handling defects; the reported multi-day loss of staff alerts remains under investigation.

- **Validation/publication:** 1,487 tests, zero failures/errors, one skipped. Release PR #230 merge `97dcf9cea7d042f0eb1967cf33c8c4d7896f1292`; build `1792635431186`. Public JAR upload completed **2026-10-22 02:22:50 UTC** (Oct 21, 19:22:50 America/Los_Angeles). JAR SHA-256 `5d50c72ad9f98b22ec2e446f577d0e806876dc6aba21e4c88ff99d3ff90e02c6`; source ZIP SHA-256 `9c7164bdf411c91edaf3365059d8e086d93ea2e353689d2bebb2cb181ae28228`. Public JAR/source/checksums independently downloaded and matched. Separate binary after PR228; this publication-record commit is documentation only.

## 2026-10-22 01:21 UTC

- **Release PR #228:** Bounded recovery history reads.

- Recovery/panel reads now load only REAL positions bought at or after the saved session start instead of deserializing lifetime history and discarding older positions in Java. With no known session start, reconstruction reuses the already-read open positions. The inclusive boundary, owner/legacy scope, durable counter floors, retail children, uncertainty and spending reconstruction remain unchanged.
- A bounded local CPU profile identified recovery reads as one source of client work. An owner-scoped, read-only SQL benchmark on one ledger returned 39,868 instead of 142,864 rows, with median row-read time 94.66 ms instead of 258.13 ms across five runs. This is about 2.7 times faster for that query, not an end-to-end Java/client CPU or FPS result; other machines and ledgers can differ.

- **Validation/publication:** 1,478 tests, zero failures/errors, one skipped. Release PR #228 merge `bf55b0cff2a29df6bf4fa3b1722d8be6e5105137`; build `1792631929192`. Public JAR upload completed **2026-10-22 01:21:54 UTC** (Oct 21, 18:21:54 America/Los_Angeles). JAR SHA-256 `d996cd1a78a9b17122e96da5ed3e2a44c513239d583cf4ade57c1c7fa3106210`; source ZIP SHA-256 `bc8385a097166ad73c5e6b13a766acd4ccee48e456460c7b449ce4b72adf9ac7`. Public client/source/checksums independently downloaded and matched. Separate binary after PR226; this publication-record commit is documentation only.

## 2026-10-22 00:26 UTC

- **Release PR #226:** Reserve Stock shelf restocking.

- Reserve Stock now refills its selling shelf as whole stacks sell. Collection is limited to the missing shelf quantity and available auction/inventory slots; remaining delivered stock stays on the order house. Already queued, held and repricing stock count toward the shelf.
- Delivered whole stacks can be taken from partially filled orders. Collection keeps the exact order key and duplicate-row ordinal, measures the actual inventory change, and requires a fresh native order read before another top-up.
- Each market buys at most two batches per cycle. After those purchases, both the shelf and outstanding/uncollected orders must drain before the next pair. The cycle record is saved before placement and restored across restarts; upgrades adopt existing exposure conservatively. Spending, market-depth, ownership, persistence and recovery guards are unchanged.

- **Production validation:** 1,476 tests, zero failures/errors, one skipped.
- **Publication:** Release PR #226 merge `aac2ab81a620db77930e4567ca642f7f4a2d0b15`; build `1792628580525`; public JAR upload completed **2026-10-22 00:26:02 UTC** (Oct 21, 17:26:02 America/Los_Angeles). JAR SHA-256 `7c9b46be899b2fd26ca067f7338b1003d8c41ac3e861fb4d22534f6900c14961`; source ZIP SHA-256 `7f5d6563719982ed1526bae05a948de1f34076e1cef59ec08fda07c96d1433ec`. Public client, source and checksums independently downloaded and matched. This is a separate client update after PR224; the following publication record is documentation only.

## 2026-10-21 23:51 UTC

- **Release PR #224:** Added **Reserve Stock** under Settings → Orders, off by default. Automatic stack markets can keep one batch selling and one buy order filling or waiting uncollected. A completed reserve is collected only after owned stock of that item is gone from listings, queued purchases and repricing. Existing smaller orders and histories are retained.
- **Reserve batch stacks** defaults to 12, bounded by **Stacks per bid**. Full batches must fit the existing per-purchase/session caps, order capacity and market-depth limits; the mode waits rather than silently reducing a reserve batch. Two batches maximum per item, including held and ordered goods. Delivered but uncollected goods remain counted at purchase cost against the spending cap.
- The mode uses whole-stack order-house buying for automatic markets and leaves manual Flips rules intact. It does not outbid its own reserve order, rereads native orders between placements, ignores old completed/collected rows when selecting collection, and rechecks stock before dispatching queued work. Stop, winding-down cleanup, recovery, ownership and persistence gates remain authoritative. Switching the feature off deletes no orders.

- Production clean/build: **1,471 tests, zero failures/errors, one skipped**. Regressions cover batch coverage, the last unsold stack, restart reconstruction, identical completed rows, budgets/depth, delivered reserve cost, the actual controller's recovered/repricing stock, legacy retail intent and native duplicate-order confirmation.
- **Publication:** PR224 merge `5b21377b32e3900d6c84e4c36d8b626ba6af3724`; build `1792626499322`; public JAR upload completed **2026-10-21 23:51:08 UTC** (Oct 21, 16:51:08 America/Los_Angeles). JAR SHA-256 `a5aff3115d6fbfa605e736afc92f1f70dbdcf6d5693a96f4fadaa6d45392c498`; source ZIP SHA-256 `deeb8bfbe249aaf2a82b76f0bcc144b35e1c819ec3f1b4e58efba6d165e9fcf6`. Public client/source/checksums independently downloaded and matched. Separate client publication after PR219; PR221 was tutorial media only. This following publication record is documentation only.

## 2026-10-21 16:52 UTC

- **Release PR #219:** Added a gold **START HERE** button at the beginning of navigation on every in-game tab. New installs open a beginner landing page; existing installations keep their normal entry, with help always available. The main path is **B → START HERE → Set up trading → choose limits → Review & Start**. Existing trading, pause and recovery controls remain on Trade.
- The landing page has direct links to Trade, the setup video and further tutorials. Setup returns to Start here instead of losing the beginner in the full dashboard. An explicit **Show menus + hotbar** action changes only those two display preferences for that account; name privacy, trading settings, positions, spending limits and other Hive members stay intact. Opening help does not enable or start trading.
- Updated the website handbook and install-video instructions with a real screenshot of the landing page. The existing video covers the limits/review steps; the new gold setup button comes first. Offline GUI verification covered desktop/compact layout, setup/back, Trade and the persistent entry without joining a server or enabling trading. Production clean/build: **1,459 tests, zero failures/errors, one skipped**; normal production entrypoint only, no smoke/preview classes.
- **Publication:** PR219 merge `a202d6d2ed51e410b0a0f1341c241bff40d03ce4`; build `1792601358696`; public JAR upload completed **2026-10-21 16:52:42 UTC** (Oct 21, 09:52:42 America/Los_Angeles). JAR SHA-256 `5dd422a4a0ba4d8c226fbb6869e9f393341fc33583f9a2647c876436919874d3`. Public JAR/source/checksums independently downloaded and matched. Separate client publication after PR217; no running bots were upgraded. The subsequent changelog commit is documentation only.

## 2026-10-21 11:58 UTC

- **Release PR #217:** Automatic account slot detection now reconciles the own-listings cells and navigation total before changing capacity. A locked second page alone cannot prove 45 slots: an 18-slot account can have the same page. A usable first page plus its locked second page can still establish 18/45 slots within the same scan; that evidence is discarded when the scan resets.
- Conflicting totals, unloaded/unrecognized cells and pending page turns keep the current limit. Compatible 18/45/90 evidence remains supported, and PR215's per-account manual override remains in effect. No trade records, positions, orders, caps or live clients are reset or upgraded by this publication.
- **Related to #211:** These inference paths were established in code and a locked-page regression fails against the prior implementation. The exact historical menu that produced the reported 90 is still unavailable; the report remains open and is not claimed fully resolved. Full build: 1,458 tests, zero failures/errors, one skipped.
- **Publication:** PR217 merge `57d7000bf1d7cc9ca8ef94d687881fcb27835bab`; build `1792583756123`; public JAR upload completed **2026-10-21 11:58:39 UTC** (Oct 21, 04:58:39 America/Los_Angeles). JAR SHA-256 `66c39a30e92da38f9059ec1134acaf32e3078935b146609781cca124f7265389`. Public JAR, source archive and checksums independently downloaded and verified. This is a separate client binary after PR215; the following changelog commit is documentation only.

## 2026-10-21 10:15 UTC

- Release PR #215: Fixed automatic capacity observations overwriting auction/order slot limits the player had just chosen manually. Editing either capacity now keeps both configured values under manual control for that account, including across reloads. Confirmed menu and API rank learning honor this choice; other Hive accounts keep their own detection mode. No positions, existing orders, limits or safety gates are reset.
- Automatic detection remains on by default until a manual slot edit is made. In the GUI, set Auction slots and Order slots to 18 if both should be 18; this switches detection off for that account. To resume detection after a rank upgrade, enable Detect account slots automatically under Settings/Tiers (Auction settings in the panels). Existing values are not guessed to be manual during migration. Malformed local settings cannot silently re-enable detection, and the choice is excluded from shared Hive presets.
- Related to #211: the manual-override defect was independently reproduced, and its regression fails on the prior code. The reason the reported menu was interpreted as 90 remains unconfirmed; the entire incident is not claimed fixed or closed. Full suite/build: 1,453 tests, zero failures/errors, one skip. Regressions cover menu/API learning, saved manual limits, per-account isolation, explicit re-enable, legacy values and malformed files. Normal production entrypoint; no smoke/preview classes.
- Client artifact published October 21 at 03:15:03 Los Angeles time (10:15:03 UTC). Build 1792577566996; SHA256: df754ca56e730d32f49f3a755d98cc44ba83360f344b2f836f414d5e9a3716be. Public JAR, source ZIP and checksum manifest independently downloaded and hash-matched. This is a separate publication from PR212; no running bot was upgraded or restarted for PR215.

## 2026-10-21 09:48 UTC

- Release PR #212: An unexpected connection loss during a running session could reconnect to the server successfully while trading remained paused. Ordinary connection retries did not retain the trading recovery ownership used by staff-triggered reconnects. Only a recent unexpected disconnect from an already running or internally resumable session now transfers that intent to the existing reconnect flow. A failed manual connection cannot create trading intent.
- Recovery still waits for a fresh connected world and balance, 15 seconds of settling, retained session ownership, and the native persistence, exposure, inventory and authorization gates. Stop, deliberate departures, cancellation, disabled reconnect and stale departure evidence remain protected. Native refusals keep the original intent and the existing 30-second retry spacing. No limits or settings are changed.
- Reproduced after a live connection reset during an order placement: the client reconnected but stayed paused without exposure. A deterministic ordinary-retry regression fails against the old flow. Full suite/build: 1,449 tests, zero failures/errors, one skip. Normal production client entrypoint only; no smoke/preview classes.
- Client artifact published October 21 at 02:48:20 Los Angeles time (09:48:20 UTC). Build 1792575951343; SHA256: `f3591813b4f1aac791a9446c0a9e92e23b8bdcf903ebae570e8447dcdc28af84`. Public JAR, source and checksum manifest independently downloaded and matched. This is separate from PR206. Reports #210 (repricing) and #211 (slot detection) are not claimed fixed by this release.

What each published update changed, newest first. Times are UTC publication times, not development start times. Each release keeps its own entry. The mod shows these in
the update notice, after an update installs, and under What's new on the
About tab.

## 2026-10-21 06:31 UTC

- Release PR #206: Corrected the scope of shared-data measurement. The Hive view now separates validated cloud responses, hints actually used in bid plans/native-check choices, follow/price-level provenance, history imports and local stored-book use from genuinely avoided broad scans. Follow and price-level source subsets can overlap; a lookup or hint is never converted into saved time. Older clients remain unmeasured. Local counts describe existing stored-book loads/planning, potentially from the same account's saved book; this release does not claim live Hive peer-refresh is implemented.
- Added separately negotiated data_use schema 1 over the existing authenticated connection, with bounded per-game counts, matching read-savings session/fallback totals, explicit rejection reasons, unchanged clock-only telemetry cadence and existing consent/ownership gates. Existing readSavings schema 1 remains unchanged. Production discovery requests the newest eligible same-owner non-self 200-page peer snapshot from the last 90 seconds, rather than allowing a newer ineligible read to mask it; legacy shadow/validation behavior and native price, balance, inventory, own-order and trade-safety checks stay authoritative. The existing VPS owner independently tested actual client wire output against the server parser/ledger offline and deployed the matching services.
- Client artifact published October 20 at 23:31:24 Los Angeles time (October 21 06:31:24 UTC). Build 1792563406286; SHA256: `aeeb42a53dff7f9bee4e5db91390a4c5c1ba2564e8f87be51cb078c391ab9235`. Full build: 1,445 tests, zero failures/errors, one skipped; backend community 26 tests and dashboard 75 tests passed. Public JAR, source and checksum manifest independently downloaded and matched; normal production entrypoint retained, no smoke classes. Live measuring-client rollout/ingestion remains the activation check.

## 2026-10-21 05:28 UTC

- Release PR #204: Made read savings a master Hive total in the in-game Swarm tab and both panels. The combined total is shown first, with per-account details collapsed below it. Each fresh account is counted once; stale, offline and older clients are explicitly unmeasured. Local totals sum current reporting sessions and are labelled bot-time, not elapsed wall-clock. No per-account speed ratios are summed or averaged.
- Added the confirmed schema 1 read-savings capability and bounded per-client cumulative snapshots over the existing authenticated dashboard connection. The server must explicitly advertise support; sharing and ownership gates remain in place. Repeated timestamps do not increase the existing telemetry cadence. The separately deployed dashboard (commit 0da06dd, 70 passing tests) persists/de-duplicates session snapshots and shows owner-only Hive and anonymous Whole Swarm master groups in the existing Swarm stats card, including measured skips/wait, estimated net bot-time, calibration, coverage and window start. Older clients are unmeasured; estimates are not proof of trading speed or profit.
- Client artifact published October 20 at 22:28:52 Los Angeles time (October 21 05:28:52 UTC). Build 1792560364228; SHA256: `8dec5e5d68473b7f301c5ce3eb3ce12bf1cdf7430da8d9555a153236528a5c07`. Full build: 1,437 tests, zero failures/errors, one skipped. Existing VPS owner independently compiled the actual client wire classes and verified their output against the deployed server parser/ledger offline. Authenticated live website master cells verified; real measuring-client ingestion remains the post-installation check. Public JAR, source and checksum manifest independently downloaded and matched. Production entrypoint retained with no smoke classes.

## 2026-10-21 04:58 UTC

- Release PR #202: Added passive Swarm read-savings measurements in the in-game Swarm tab and a new Swarm savings page in both local and hosted panels. Select each Hive account to see actual broad discovery scans skipped, shared lookups, fallbacks and measured lookup wait for its current session. Existing bounded local session history retains the measurements; no extra collector, experiment or scan is started.
- Estimated net time saved subtracts all lookup waits, including fallbacks, from estimated omitted scan time. Calibration needs at least three successful natural scans in the same observed region with the omitted scan's scheduled page count in the preceding 30 minutes. Stale, changed-region, different-depth and failed scans do not calibrate it; unknown baselines remain visible and estimates are never filled retrospectively. Negative savings and a matched discovery ratio below 1x show when shared lookups cost more time. Estimates are labelled and are not proof of total bot speed or profit gains; live execution checks remain unchanged.
- Client artifact published October 20 at 21:58:50 Los Angeles time (October 21 04:58:50 UTC). Build 1792558586147; SHA256: `12808b1a16cabeb80cf69d2a4607350217d9fb2ce7304db29c376f9f25d3a172`. Full suite/build: 1,429 tests, zero failures/errors, one skipped. Actual local/hosted panel routes, account switching and uncalibrated states verified with clearly labelled synthetic data. Public JAR, source and checksum manifest independently downloaded and matched. Running clients were left unchanged pending rollout authorization.

## 2026-10-21 03:44 UTC

- Release PR #200: Fixed Hive notification aliases putting the sending account first on every client, which could make every bot's alerts say Account 1. All senders now use the same sorted Hive roster, and immediate webhook alerts have an explicit source-account field. Privacy masking and suppressed mentions remain in place.
- A native joined account with no local webhook URL can reuse its existing Hive owner's enabled destination without copying credentials or changing trading settings. Explicit account destinations and local notification switches remain authoritative; a disabled owner destination or immediate-alert master switch is not bypassed. Missing or malformed owner settings fail closed. Existing staff, disconnect, evasion, death and jackpot alert call paths use this routing. No test notification was sent to Discord.
- The hourly report already read the shared ledger. It now says Hive/all accounts or ledger totals rather than showing only the posting account as its title, and includes recognized members' booked 1h/24h profit separately in image and text output. One-report-per-Hive election now recognizes native membership even with the older multi-client toggle off. This does not start a private experiment, schedule extra reports or change the report interval. Runtime activation and end-to-end delivery from updated live clients remain separate verification steps.
- Client artifact published October 20 at 20:44:15 Los Angeles time (October 21 03:44:15 UTC). Build 1792554062405; SHA256: `3e5b20753a7d8c00670744e5824eb98abbab4d638e98b68851c99c388f343f6b`. Full clean suite/build: 1,421 tests, zero failures/errors, one skip. Regressions cover three-sender identity consistency, inherited/explicit/disabled/malformed routing, owner alert opt-out, native report election and shared-ledger per-owner/time/mode accounting. Public JAR, source and checksum manifest independently downloaded and hash-matched. Normal client entrypoint retained; no smoke/test entrypoint packaged.

## 2026-10-21 03:13 UTC

- Release PR #197: The Hive page remains visible even before a second account is recognized. Previously it disappeared when the panel saw fewer than two accounts, making incomplete setup look like missing Hive support. Both the local panel and hosted Open panel now explicitly distinguish bots sharing this panel's ledger, separate running instances, only one detected bot and discovery still waiting. Each roster identifies the hosting bot. Swarm linking alone does not combine local Hives.
- Joining an already-shared Hive is disabled with a direct explanation that no linking or deletion is needed. Hive account totals say recorded accounts rather than claiming all are trading. Membership is reported from fresh same-computer discovery; no automatic linking, data deletion, history merge, guard bypass or live upgrade is performed. Existing trading histories still require the supported combination flow. These fix reproduced UI paths; the affected player's complete setup and earlier panel incident remain unconfirmed.
- Client artifact published October 20 at 20:13:59 Los Angeles time (October 21 03:13:59 UTC). Build 1792552282918; SHA256: `ca7207f5804a6741297039262b8a5a69edec98e0fedd06b856b1bf477ffb56d7`. Full clean suite/build: 1,414 tests, zero failures/errors, one skip. Real panel-picker regression failed before the fix; shipped JavaScript covers five membership and command scenarios. Offline browser preview checked the membership explanation and disabled redundant join. Public JAR, source and checksum manifest independently downloaded and matched; CLI/server checksum filenames match their existing release assets. Healthy running bots were left on their installed builds.

## 2026-10-21 02:56 UTC

- Release PR #195: Fixed the checkpoint save path assigning an old unowned trade to the account inspecting it. Existing position ownership now stays unchanged, and another named account cannot overwrite the position. Together with PR #193's account-scoped recovery, this prevents a fresh Hive member becoming stuck on another session's trade after closing an older client. Genuine recovery holds remain enforced.
- Already affected saved state requires a separate, explicitly approved offline correction. The included repair accepts only one paused legacy-import case with a preserved pre-join database, identical trade fields and matching legacy checkpoint. It archives the prior state atomically, restores the original ownership and retains the original trade and legacy recovery. Changed evidence, stale approval and replay attempts are refused. It never runs automatically, fabricates an inventory acknowledgement or enables trading. One verified third-account correction was explicitly approved and applied; post-relaunch trading has not yet been verified.
- Client artifact published October 20 at 19:56:15 Los Angeles time (October 21 02:56:15 UTC). Build 1792551115042; SHA256: `e209c683df4e457b5dfd80aae419b3f6ce16d0e1d53b207209165ed995caeb85`. Full clean suite/build: 1,413 tests, zero failures/errors, one skip. Public JAR, source and checksum manifest independently downloaded and matched. Normal client entrypoint retained; no GUI smoke entrypoint is packaged. Healthy peers do not require a routine upgrade or restart.

## 2026-10-21 02:35 UTC

- Release PR #193: Fixed a fresh Hive account displaying an interrupted trade it never made. Native joined-account profiles now load only their own positions, checkpoint, runtime and cancelled-stock candidates before recovery starts. Old unowned records stay in the shared ledger for legacy reconciliation; they are not assigned to the new account, removed or marked resolved. Genuine owned exposure still requires the normal native recovery process. Recovery spend/trade reconstruction excludes other named accounts while retaining the account's stored caps.
- Reproduced with a shared ledger containing an old unowned Bundle and other-account history: the legacy read included it in the new account's recovery. Tests now show empty recovery for a fresh joined member, retained recovery/caps for its genuine owned purchase, unchanged legacy rows, rejection when the owner is missing, the actual asynchronous persistence-worker path and native joined-profile classification. Existing legacy clients retain their supported recovery access. This does not automatically enable trading or require healthy peer restarts.
- Client artifact published October 20 at 19:35:16 Los Angeles time (October 21 02:35:16 UTC). Build 1792549976740; SHA256: `3a2eb467ed93a5b12edbb8d59fce094ffdc88e75d2a738fad8d37df652e4ed41`. Full clean suite/build: 1,409 tests, zero failures/errors, one skip. Production JAR has only the normal client entrypoint and no test/Smoke classes. Public JAR, source and SHA manifest independently downloaded and hash-verified. No manual audit acknowledgement or position/ownership rewrite is part of this repair.

## 2026-10-21 02:12 UTC

- Release PR #191: Fixed a fresh-account Hive join that waited for a checkpoint which had never existed. A never-started account reports generation zero; the shared safe-to-leave gate now recognizes a fully loaded STOPPED state with no open session, armed work, in-flight operation or recovered exposure. It does not invent a durable acknowledgement or start trading. Real accepted checkpoints still require their actual durable acknowledgement; unavailable recovery, refused writes and pending controller checkpoints remain blocked.
- Reproduced against the real controller: the never-started-state regression failed before the fix. This repairs the checkpoint wait observed while adding a third account through PR189's panel flow. No existing positions, account records, settings, limits or recovery holds are cleared. Live third-account joining has not yet been completed; existing healthy peers do not need a restart for this fresh-account repair.
- Client artifact published October 20 at 19:12:41 Los Angeles time (October 21 02:12:41 UTC). Build 1792548618302; SHA256: `66da830d606f8ce6af9d22254a30c83d76f6f796926d7e7a0048405984c71f40`. Full clean suite/build: 1,404 tests, zero failures/errors, one skip. Tests cover initial recovery loading, empty generation zero, open-session/refused-write rejection and a real accepted write awaiting durable acknowledgement. Production JAR has only the normal client entrypoint and no test/Smoke classes. Public JAR, source and SHA manifest independently downloaded and hash-verified.

## 2026-10-21 01:56 UTC

- Release PR #189: **Hive & commands** is available in both the local panel and the hosted Open panel. Running updated clients on this computer appear automatically. Choose a fresh bot and an existing Hive, then **Add selected bot to Hive**; no headless game window or copied code is needed. The existing member registers its Hive metadata at a safe checkpoint and keeps running. The new bot rechecks its empty history/account records, pauses safely if needed, leaves only its own world and creates its native link. Restart only that new instance normally. Its own settings and Swarm link are preserved; no trading is started or existing histories merged.
- Choose **Send as**, review a supported utility command and **Confirm and queue once**. Only the selected bot receives the work through its native paced FIFO. Exposure, persistence, cursor, connection, staff-transfer and teleport guards remain authoritative. Queued commands can be cancelled before send, expire after two minutes, and never replay onto another game connection or after a restart. Payments are not retried automatically. **SENT means handed to the connection, not confirmed paid or teleported.** A local request that times out before the game thread claims it cannot execute later.
- Detection requires each client to load this update once. Third/later membership is not limited to two accounts; joining existing trading histories still uses the separate supported flow. This release does not claim the user's third account is already joined or the affected #183 hosted timeout is resolved. No live client was upgraded, linked or restarted during publication.
- Client artifact published October 20 at 18:56:46 Los Angeles time (October 21 01:56:46 UTC). Build 1792547461109; SHA256: `008ade853fdb2244487b011794f21061aa6b90e592bd91fc768cce4e8bb8154d`. Full clean suite/build: 1,401 tests, zero failures/errors, one skip. Regressions cover fresh-Hive database/settings preservation, native pacing and no replay, connection changes, local authentication/CSRF, hosted expiry/duplicate handling and the shipped page JavaScript. Offline panel preview inspected using sample bots. Production JAR has only the normal client entrypoint and no test/Smoke classes. Public JAR, source and SHA manifest independently downloaded and hash-verified.

## 2026-10-21 01:04 UTC

- Release PR #187: A Hive can have many accounts. Hive setup now offers **Join existing hive (new account)** separately from the two-single-account history importer. A fresh third, fourth or later account can join an existing multi-account or combined Hive without merging or copying its trading database. Only the joining instance needs a restart; existing members can keep running.
- The joining account keeps its own settings, limits, execution gates and instance-scoped Swarm credentials. Its profile points to the existing shared ledger; it never inherits another member's order or balance records. Local history, saved account records, an existing destination identity, replacement links and pending combinations block unsafe joins. Setup leases and import-time checks prevent a history combination from dropping a newly added member. No existing live instance was upgraded, linked or restarted by this release.
- Hive setup and the guide explain the fresh-account path, its restart requirement and how older shared-folder Hives register once through Share this hive. The separate history importer still supports only two single-account histories; that is not a Hive membership limit. The earlier video is labelled as the history-combination walkthrough.
- Client artifact published October 20 at 18:04:32 Los Angeles time (October 21 01:04:32 UTC). Build 1792544543099; SHA256: `015575f83d330e3cb7b881cc558292d8cfd924c7ef7e16f59a22aec1c8b7d848`. Full clean suite/build: 1,385 tests, zero failures/errors, one skip. Added regressions cover third/fourth membership, adding a member to a combined Hive, unsafe joins and a prepared combination after another member joins. Offline GUI review was captured and inspected. Production JAR has only the normal client entrypoint and no test/Smoke classes. Public JAR, source and SHA manifest independently downloaded and hash-verified. This does not claim the user's third account has already joined.

## 2026-10-20 23:15 UTC

- Release PR #185: A stalled panel picture render can no longer occupy every reader used for status and data. Pictures now use a separate single worker with at most one outstanding render; repeated picture polls return promptly while status requests remain available. Picture capacity is released after completion or a rendering error. Existing authentication, overall request limits, expiry and game-thread control checks remain enforced.
- The panel now explains busy, unavailable, timed-out and oversized responses, retries automatically, and directs persistent failures to About → Report bug in game. It no longer treats these HTTP responses as proof that Minecraft stopped. Separately, a valid shared-history receipt at the frozen 24-hour boundary could expire during network transit and reject its whole page. Fully validated expired boundary rows are now skipped while fresh sales remain; scope, provenance, quantity, price and future-time checks stay enforced. No positions or ownership records are changed.
- Related to #183's web-panel connection failure and captured history fault. Three regressions failed before their respective fixes: actual panel executor starvation with a blocked renderer, actual page JavaScript handling relay failures, and the actual history decoder with a 250 ms transport delay. These repair reproduced paths; the affected player's complete incident cause remains unconfirmed and the report stays open. Separate VPS diagnostics measure anonymous operation/outcome/latency counts. No running bot was upgraded or restarted for this release.
- Client artifact published October 20 at 16:15:44 Los Angeles time (23:15:44 UTC), separately from PR #182. Build 1792537983146; SHA256: `f4be0cd37af064d3d4eb21308bb648a4e49299e7e1cdd3867a69566f199a7515`. Full clean suite/build: 1,381 tests, zero failures/errors, one skip. Production JAR has only the normal client entrypoint and no test or Smoke classes. Public JAR, source ZIP and checksum manifest independently downloaded and verified.

## 2026-10-20 22:52 UTC

- Release PR #182: Fixed inventory sweeps and fallback fill adoption booking stock a second time while its original purchase was still queued. That duplicate could list the physical stock first, leaving the original purchase to pause because it was no longer in inventory. Adoption now defers to queued purchases, preserving their original IDs, quantities and cost basis. Split batches, partial stacks and descriptor item keys are covered; actual new collection receipts retain their normal booking flow.
- This prevents the reproduced duplicate-booking path going forward. It does not clear existing ambiguous records, bypass inventory/ownership checks, change nearby-player evasion, or prove every report in #170/#171/#174 resolved. Existing positions still require native reconciliation. No live bots were upgraded or restarted for this release.
- Client artifact published October 20 at 15:52:27 Los Angeles time (22:52:27 UTC), separately from PR #180. Build 1792536604165; SHA256: `fb0d99e8f50df3c505819a3faf9129f0b27fbc7393061bc230baaf1c33253eae`. Two ownership regressions failed before the fix; all four new regressions pass. Full clean suite/build: 1,377 tests, zero failures/errors, one skip. Production JAR has only the normal client entrypoint and no test or Smoke classes. Public JAR, source ZIP and checksum manifest independently downloaded and verified.

## 2026-10-20 22:29 UTC

- Release PR #180: Restored normal Minecraft command handoff for auction sells. An October 17 change prepended a raw hotbar-selection packet before every sell and repeated it on retries, even after the selected exact stack had settled. Sells now retain their dispatch-time item/components/count/slot/inventory validation without that extra selection packet. Command pacing, connection-handoff verification, spending caps, durable intent and ownership guards stay enforced.
- Controlled verification on an affected local bot restored native listing confirmation, exact stack-removal checks and durable LISTED handling. The owner independently observed new listings; a read-only ledger check found eight positions listed since the trial boot, including three subsequently sold. The peer on PR #177 continued timing out. This supports the local regression repair related to #170/#171/#174; it does not prove every reporter incident or older inventory/recovery hold resolved. No server-side filtering mechanism or developer intent is claimed. Draft #179 was superseded by #180 at the identical tested commit; it was not another binary release.
- Client artifact published October 20 at 15:29:57 Los Angeles time (22:29:57 UTC), separately from PR #177. Build 1792535007250; SHA256: `79e8135ecb702e81bfa6cc26481a35e6bc23cb202069dae333c042358f44874b`. Two real command-transport regressions failed before the change and pass now. Full clean suite/build: 1,373 tests, zero failures/errors, one skip. Production JAR has only the normal client entrypoint and no test or Smoke classes. Public JAR, source ZIP and checksum manifest independently downloaded and verified. Configuration and tuning hashes were unchanged in the controlled trial.

## 2026-10-20 22:02 UTC

- Release PR #177: Native action-bar auction messages now reach the execution driver as well as HUD alerts. Previously this packet path could hide a server listing refusal or receipt from the active listing operation. A regression through the actual client callback failed before the fix and passes now. Receipt handling still requires the existing inventory evidence; idle messages do not create trades.
- Added privacy-safe local listing handoff diagnostics: packet handoff, screen/menu context, selected/expected slot and held count. No raw server message, player identity or settings are included. This repairs a demonstrated message-routing gap related to #171; the ongoing no-confirmation incident on PR #175 is still under live investigation and is not claimed resolved.
- Client artifact published October 20 at 15:02:06 Los Angeles time (22:02:06 UTC), separately from PR #175. Build 1792533709093; SHA256: `fcd64397621748e19924eb0025a20addb504cec1e68f97aeedd7082f3477fbcc`. Full clean suite/build: 1,371 tests, zero failures/errors, one skip. Production JAR has only the normal client entrypoint and no Smoke classes; public JAR, source ZIP and checksum manifest independently downloaded and verified. Existing ownership, retained positions and trade limits remain enforced.

## 2026-10-20 20:22 UTC

- Release PR #175: **Restart queue bypass** is enabled by default. Confirmed restart waiting rooms trigger a safe disconnect and direct reconnection through East, West, Asia, Oceania, EU Central or EU West. The current connection address is skipped; custom same-network lists support up to six addresses. Failed or limbo joins move to the next region with a delay; the first verified working region is kept. Explicit saved off settings remain off. A stable world and fresh balance sidebar are required before normal gated resume. Positions, owned orders, inventory checks, manual stops, recovery holds and spending limits are preserved. Implements #172's alternate-region restart flow. No running bots were installed, restarted or controlled.
- Fixed a reproduced queued-sell retry race: delayed confirmation menus, listing receipts and server refusals are processed before a retry can close the menu, stall behind backoff or resend the sell. Withdrawing the retry does not prove a sale; the existing receipt/confirmation and inventory checks still decide the result. Changed-hand guards stay enforced. Three real dispatch regressions failed before the fix and now pass. This repairs supported paths related to #170/#171/#174; their entire reported incidents are not claimed resolved and remain under investigation.
- Bug reports now distinguish confirmation GUI timeouts, blocked command dispatch, changed listing stacks, unavailable worlds and server listing refusals using fixed categories without private message text. The web guide and safety handbook explain restart recovery and confirmation delays.
- Client artifact published October 20 at 13:22:53 Los Angeles time (20:22:53 UTC), separately from PR #168. Build 1792527745336; SHA256: `03631c97eb0a22b7e2058b693ac2c9803ae1f26fbed643efc4a067d6fc9c9a3a`. Full clean suite/build: 1,368 tests, zero failures/errors, one skip. Six-region fallback/stable-world and default/host-exclusion regressions passed; production JAR has only the normal client entrypoint and no Smoke classes. Public JAR, source ZIP and checksum manifest independently downloaded and verified. Endpoint status pings answered; ping alone does not establish regional routing because wildcard DNS also answers.

## 2026-10-20 16:00 UTC

- Release PR #168: Replaced the alarming unresolved-item banner with an interrupted-trade recovery guide. Trade identifies the item and count, explains the next step for each recovery stage, and keeps refused-action feedback visible. A focused scrollable dialog makes the checklist readable at larger GUI scales; the global recovery button opens Review trade instead of taking a generic Resume action.
- Recovery help remains available while confirmations are blocked. Confirmation requires all instructions to be visible/read through, the same current recovery stage and one identified exposure; the controller still requires the existing two confirmations, fresh evidence and durable acknowledgement. Verified stock uses its normal listing path. Existing ownership, safety holds, saved positions and spending limits are preserved. Persistent waits or unclear records direct players to report the problem. This improves #167's recovery UX; its underlying interruption is not claimed resolved. No live bot was upgraded or restarted.
- Client artifact published October 20 at 09:00:43 Los Angeles time (16:00:43 UTC), separately from PR #164. Build 1792512001296; SHA256: `29332d9e72e49ba0686dd2764d3c81fad094de5f397e752cd57e6033ef24a255`. Full suite: 1,360 tests, zero failures/errors, one skip. Isolated offline synthetic recovery screenshots verified at two GUI scales, including the focused help dialog; no trade or recovery confirmation was executed. Production JAR excludes preview code; public JAR/source/checksum bytes independently verified.

## 2026-10-20 03:26 UTC

- Release PR #164: Fixed automatic buy-order sizing that described 7 individual diamond blocks as 7 of up to 12 stacks. A sampled single-item price bucket no longer becomes the physical stack size when singles selling is off. New stack orders require supported whole-stack resale pricing; a purchase cap cannot silently enable single-item orders.
- Whole-stack bids reduce to affordable whole stacks within existing purchase/session spending limits, listing room and the exact order-count bound. Twelve 64-item stacks means up to 768 items, not a promise to always order twelve. Explicit singles selling and unstackable items retain their supported paths; diagnostics identify items versus stacks. Existing orders, inventory, positions and collection/recovery remain intact, so an older small order can still complete. No caps were raised and no live bots were installed, restarted or controlled.
- Client artifact published October 19 at 20:26:53 Los Angeles time (October 20, 03:26:53 UTC), separately from PR #162. Build 1792466786792; SHA256: `040285bbbaeb3f3df9164d0cdba05fb08beb2ebebb6186a778dcd9457cb7ff85`. Two regressions failed against the extracted legacy selection/fallback decisions. Full suite: 1,360 tests, zero failures/errors, one skip, including true 64/16/1-item stack sizes, 12-stack quantities, affordability, listing room, exact-count bounds and overflow-safe sizing. Production package and public JAR/source/checksum downloads verified.

## 2026-10-19 23:30 UTC

- Release PR #162: Updated setup guides and tutorials for the current **Trade** tab (formerly **AutoPilot** or **Automation**). The homepage walkthrough, player handbook, GUI tour, API-key configuration and Discord instructions now use current navigation and distinguish guided Choose limits / Review & Start from existing-session controls. Earlier video and screenshot labels are identified explicitly, with links to current steps.
- Added an optional **Find AutoPilot / Trade** in-game lesson and renamed the Dashboard shortcut to **Manage trading**. API tools, Discord command centre and web-panel setup are explained separately. Corrected the safety guide: **End** is the default emergency-stop key; **B** opens GoNuts. Existing tutorial progress, trading behavior, limits, settings and recovery checks are unchanged.
- Client artifact published October 19 at 16:30:30 Los Angeles time (23:30:30 UTC), separately from PR #160. Build 1792452605667; SHA256: `c0c139d7989d8c0460e87015ec1231031465d4d9f39fe41d5d374e87467b5546`. Full suite: 1,355 tests, zero failures/errors, one skip. Client package, lesson content and public JAR/source/checksum downloads verified. Documentation anchors and rendered handbook checked. No live bots were updated or controlled.

## 2026-10-19 21:40 UTC

- Release PR #160: The Discord control-panel channel is reserved for its editable embed. Staff Watch and connection alerts no longer fall back there when the optional activity channel is blank; an explicitly configured activity channel must be separate. Unsent durable records remain pending. Existing connection webhook delivery stays on the configured webhook route.
- Staff Watch region-escape and watcher-disconnect events now also reach the configured webhook without requiring an activity-feed channel or a loaded world. Previously they only entered the bridge notification queue. All webhook alert switches remain authoritative, titles distinguish a requested region escape from a disconnect, and a reporting failure cannot prevent protection or local history.
- Staff Watch's existing passive minute diagnostics now count known-staff entries, spectator entries and observed mode changes inside combined player-add updates. Initial presence and reconnect/region boundaries do not invent changes; no identities or packet bodies are logged. These observations never trigger alerts or escapes. The combined-update exclusion is unchanged since October 13; the separate question of changed game-server signals versus missing client detections remains unconfirmed.
- Client artifact published October 19 at 14:40:53 Los Angeles time (21:40:53 UTC), separately from PR #158. Build 1792446021710; SHA256: `1e2e3a1c748a923a428bbfacb9b404a75dc55f793b858bfbe00fac5e29341011`. Three notification/routing regressions failed before their fixes. Full suite: 1,355 tests, zero failures/errors, one skip, including real durable queues with fake senders and actual combined packet handling. Production package and public JAR/source/checksum downloads verified. No live bots were updated or controlled, settings/guards were unchanged, and no Discord test messages were sent.

## 2026-10-19 21:17 UTC

- Release PR #158: A protective pause during a read-only auction watch now captures the driver's proof that no row was clicked before stopping it. Previously that proof was lost, leaving a false uncertain BUY intent that could block unattended recovery. A release is queued durably, and automatic scanning waits until the writer and controller apply the receipt.
- Selected rows, possible purchases, held positions and rejected releases retain their exposure. Explicit Stop, protective timing, native authorization and all spend/trade caps remain enforced. This is a reproduced related path from the #157 investigation; the report's original uncertain phase and Stop initiator are unknown, so the full incident is not claimed resolved and existing retained exposure is not cleared.
- Client artifact published October 19 at 14:17:37 Los Angeles time (21:17:37 UTC), separately from PR #154. Build 1792444635940; SHA256: `fbeb960b7d93466ed0cc8fc23789b8b4baa4d1da0c4e54462faa5274d19d490e`. Two regressions failed against the old code; full suite: 1,344 tests, zero failures/errors, one skip. Production package has no smoke entrypoints/classes; public JAR, source and checksum downloads independently verified. No live user bots were updated or controlled.

## 2026-10-19 20:24 UTC

- Release PR #154: Web-panel navigation now distinguishes Auction listings and Order book data views from Auction settings and Order settings. Previously the generic Auction and Orders shortcuts opened configuration sections, making them look like broken data links. Each settings page now explains which sidebar entry shows the data.
- Related to report #151. Existing page-selection actions, permissions, hashes, tracked listing/order-book data and settings remain unchanged. This fixes reproduced naming ambiguity; a different reporter-specific navigation failure is not claimed resolved, and the report remains open.
- Client artifact published October 19 at 13:24:53 Los Angeles time (20:24:53 UTC), separately from PR #152. Build 1792441467488; SHA256: `0b3dd1da17c30e8b2e8c7fa256093359bab9eba1018d75806e1a6b55d447ae95`. Verified actual production navigation JavaScript, isolated browser configuration/data routes at desktop and 320px width with no page overflow, and 1,339 tests with zero failures/errors and one skip. Production package contains no smoke entrypoints/classes. No live user bots were updated or controlled.

## 2026-10-19 19:05 UTC

- Release PR #152: Staff Watch now queues a durable local staff alert when it starts a watcher-triggered region escape. Previously successful region switches only appeared in the client log, while the Discord staff notification queue was used only for disconnects. This works independently of Swarm through the existing configured Discord feed.
- Alerts and Evasion history label the response as a requested region escape, without claiming the transfer completed or the client disconnected. Duplicate in-flight detector calls do not queue another first-hop notice; normal follow/timeout disconnect reporting remains intact. A reporting failure cannot block the protective escape.
- Passive once-a-minute, count-only client diagnostics distinguish received player-info packets, standalone versus combined-add game-mode updates and recognized staff mode changes. No names, UUIDs, coordinates or packet bodies are added, and detection thresholds, local-region proof, trade safety, settings and network requests are unchanged. The separate recent absence of spectator evidence is still under investigation; it is not claimed fixed by notification repair.
- Client artifact published October 19 at 12:05:06 Los Angeles time (19:05:06 UTC), separately from PR #149. Build 1792436682105; SHA256: `6150fab8fdac31606abca676b73c4e97765198b7f4918680dd374cfb35e0f49b`. Full suite: 1,339 tests, zero failures/errors, one skip. The missed-notification regression fails against the old path. Production package contains no smoke entrypoints/classes. No live user bots were updated or controlled, and no synthetic Discord alerts were sent.

## 2026-10-19 16:30 UTC

- Release PR #149: Analyzer now has a guided overview, compact baseline cards and separate evidence, next-step and estimate sections, with matching browser guidance. Suggested presets remain a separate save/review/apply decision.
- Hive setup separates Link accounts from Share this hive, lets you cycle through detected hives, and provides clear combination steps and scrollable help. Both original histories and separate settings are preserved.
- Combination startup failures now explain supported failure categories rather than always blaming restart order. A malformed queued request no longer throws again inside its error handler; original ledgers and incomplete-publication safety checks remain intact.
- Related to report #148. The UI and demonstrated failure-reporting defects are addressed; the cause of the reporter's original failed combination is unconfirmed. Verified with 1,333 tests, zero failures/errors, one skip, and isolated offline native previews. No live user bots were updated or controlled.
- Client artifact published October 19 at 09:30:53 Los Angeles time (16:30:53 UTC). Build 1792427444712; SHA256: `5568ee9e180765f6cbee4296d703c8d5d8ece5953a26e15fe1b64d4ef92fd085`. This is a separate client update from PR #146; the production package has no smoke entrypoints/classes.


## 2026-10-19 14:59 UTC

- Release PR #146: A refused Resume request now preserves the session's existing automatic recovery authorization. Previously, pressing Resume during an ineligible pause or while disconnected could silently disarm the session and prevent evasion/restart recovery from continuing later.
- Ordinary Resume waits for durable checkpoint acknowledgement. Operator stops stay stopped; world authorization, unresolved exposure, existing caps and trade verification remain enforced.
- Normal running/stopped ticks no longer overwrite actual recovery blockers with NOT_PAUSED refusal noise. Recovery results are distinguished from refusals. Reproduced during investigation of #144/#145; their complete historical symptoms are not claimed resolved, and #143 remains a separate investigation.
- Client artifact published October 19 at 07:59:51 Los Angeles time (14:59:51 UTC), separately from PR #141. Build 1792421850658; SHA256: `d999e0e653e4c1df5555c0704a582b9640ca758349f0c31221eba1c291cdac36`. Full suite: 1,330 tests, zero failures/errors and one skip. Production package contains no smoke entrypoints/classes. No running user bots were updated or controlled.

## 2026-10-19 03:41 UTC

- Release PR #141: After a watched auction listing has already sold, reopening the auction now starts with fresh page navigation. Previously a retry from page two or later retained the old page number and pages-read count, mistook fresh page one for an unchanged response, and could waste eight seconds plus lose further page coverage.
- Only page-local progress and opening age reset. The original watch deadline, remaining looks, sold-row exclusions, price ceilings and normal purchase-confirmation checks remain enforced. A retry does not record a purchase or clear exposure.
- Reproduced during investigation of #48 with a failing-before offline retry regression. This fixes a demonstrated no-purchase path defect; the original report does not establish whether it caused the entire three-hour symptom, so #48 remains under investigation.
- Client artifact published October 18 at 20:41:00 Los Angeles time (October 19 03:41:00 UTC), separately from PR #139. Build 1792381116075; SHA256: `fae676df489babe025b4875c8a36d81e50e287a8b65409f26ab842a14fda810b`. Full suite: 1,326 tests, zero failures/errors and one skip. Production package contains no smoke entrypoints/classes. No running user bots were updated or controlled.

## 2026-10-19 03:18 UTC

- Release PR #139: Structured action evidence now records requests, phases, actual outcomes, refusal categories, ages/counts and relevant quantity/price/ceiling/item values across the controller, auction driver, Flips/order desk, market feeds, recovery, Hive, dashboard, community, evasion, reconnect and restart paths.
- Reports prioritize relevant subsystem events from their description before trimming the fixed 8 KiB attachment. Shared controller/recovery context stays present; repeated events coalesce and the latest problem per subsystem is retained. Historical scanning remains bounded to the last 256 KiB of the current log.
- Fixed a reproduced automatic-report delivery defect: the disk queue rejected reports containing real controller context. Frozen diagnostics now survive save/reload and reach intake and the fix worker. This improves future investigation evidence; unresolved historical trade symptoms are not declared fixed.
- Client artifact published October 18 at 20:18:45 Los Angeles time (October 19 03:18:45 UTC), separately from PR #137. Build 1792379689005; SHA256: `298823a68f3e482fb9a0300a32887a2a981fa3f22ae1c9de511b727a5ea26028`. Verified with 1,325 tests, zero failures/errors and one skip; deployed receiver/worker passed 64 + 72 tests and isolated issue-to-prompt propagation checks.
- Diagnostic capture excludes identities, credentials and arbitrary messages. No new automatic submission consent, runtime bot updates, trading settings, positions or safety gates changed.

## 2026-10-19 02:20 UTC

- Release PR #137: Enabling Trade only these now discards a queued general auction watch and stops an already-running watch before it selects a listing. Previously selected watches could continue scanning unrelated markets after the toggle changed.
- Undispatched BUY intents release through a durable checkpoint. Reading watches end through their normal no-charge terminal event; a selected purchase continues normal verification and reconciliation. No trading limits, held stock or existing exposure are reset.
- Related to report #45; its historical toggle state and reporter outcome remain unconfirmed. The investigation also reviewed #48/#58/#118/#120 without claiming their separate symptoms fixed.
- Client artifact published October 18 at 19:20:36 Los Angeles time (October 19 02:20:36 UTC), separately from PR #135. Verified with 1,316 tests, zero failures/errors and one skip. Build 1792376334232; SHA256: `c5158ecebac16d9c8a943abf7002882725979672af8fb891f260af7ebbf737c6`.

## 2026-10-19 02:01 UTC

- Release PR #135: Explicit item selections now match that item only. Including golden apple no longer also permits enchanted golden apples; excluding plain glass no longer also excludes stained glass. Applies to Items in game and on the web panel, plus seller/rival Include and Exclude actions.
- Opposite actions and Clear remove exact and legacy entries for the selected name. Existing unrelated category rules, tracked markets, positions and trading limits are preserved. This fixes a reproduced selection defect; it does not establish the cause of historical reports #45/#58.
- Client artifact published October 18 at 19:01:01 Los Angeles time (October 19 02:01:01 UTC), separately from PR #133. Verified with 1,312 tests, zero failures/errors and one skip. SHA256: `f5815a2bc154b887820bd14425b4f7432b39cfe3f3a141e88629513c00905151`.

## 2026-10-18 19:56 UTC

- Release PR #133: Combine two accidentally separate single-account hives on the same computer through an explicit review and queued offline import. Select Use existing hive or paste the other hive’s code; creating another separate hive now warns when one is already known.
- Both original ledgers and settings folders are kept. A new validated shared ledger imports supported histories, remaps position references and deduplicates matching sale receipts; each member keeps separate settings, presets, safety preferences, account records, session caps and recovery state.
- Update both instances, queue from the second account, close BOTH instances completely, then start the requesting instance first. Start the other after import completes and refresh the website dashboard. Nothing is traded, started, cleared or reset by this setup.
- Running clients, conflicting receipts, payroll/loan records, unsupported schemas and existing multi-member groups block the import. Interrupted switching cannot silently create divergent ledgers; a queued import can be cancelled before switching begins.
- Verified with 1,310 tests, zero failures/errors and one skip, plus an offline rendered review/navigation check. No new Swarm security policy or backend changes are included.

## 2026-10-18 19:11 UTC

- Release PR #131: GoNuts now offers Hive setup when it detects a different running GoNuts account in another instance on the same computer. Both instances need this update for local detection.
- The notice appears during deliberate panel opening, never over an active trade. Choose Review setup, Not now, or Don't ask again; dismissed accounts stay dismissed across restarts.
- Discovery stays on this computer and excludes stale records, duplicate windows for the same account, and accounts already sharing the same hive. It does not send account names or instance paths to a server.
- Review opens the existing offline setup screen. No automatic linking, disconnects, restarts, database merges or settings imports are included in this update; existing-history merging remains separate work.

## 2026-10-18 19:00 UTC

- Release PR #130: Explain Hive setup requirements for report #129. Create/Join now respond with the offline setup instructions instead of appearing unresponsive while connected.
- The Hive tab shows the Multiplayer > GoNuts hive route, source-history guidance and hover/keyboard help. Hive links instances on the same PC; joining the community Swarm does not need a hive code.
- Setup still refuses writes while connected. Existing hive links, history protection and restart-only activation are unchanged. No automatic disconnect, restart or trading action is added.

## 2026-10-18 17:21 UTC

- Release PR #127: Fix API-free recovery waiting indefinitely for unavailable API auction-book scans.
- Recovery now checks the player's own in-game auction listings after the first explicit inspection confirmation. Two complete clean checks and a later fresh recheck are required before the separate final confirmation; interrupted, unreadable or matching-stock evidence retains the hold.
- Recovery checks do not buy, list, cancel or collect items. Recorded stock, session caps and the durable audit requirement remain intact. The header now opens Resolve recovery instead of repeatedly refusing Resume.
- Reports #120/#123 remain open for reporter confirmation; this release fixes the demonstrated recovery deadlock without claiming every recovery pause is resolved.

## 2026-10-18 17:02 UTC

- Release PR #124: Included preset hover explanations. Client artifact published October 18 at 10:02:58 Los Angeles time (17:02:58 UTC); separate from PR #119.
- Hover the preset selector or Apply button in Settings, Auction or Orders to see what Careful, Aggressive, Overnight and Default change before applying them. Keyboard focus also exposes the help.
- Explanations cover purchase/exposure limits, pacing and bundled feature changes; Overnight explicitly calls out its 32-block Evasion radius and unchanged purchase cap, while Default warns that it resets all numeric settings. Browsing does not apply changes; presets do not start trading or guarantee profit.
- Preset values and trading behavior are unchanged. Public client SHA256: `613f6e4c9aa8a103e6e80f0813e7c15a98aa9f5f18e60521e61a190261622d99`.

## 2026-10-18 16:18 UTC

- Release PR #119: Startup reliability and attached fault diagnostics. Published October 18 at 09:18 Los Angeles time. This is a separate update from PR #117 below; the former 09:07 label was an incorrect draft timestamp, not another published release.
- Fixed shared-history startup reading item stack components before Minecraft had bound them. An unavailable item now waits for a later normal feed pass; it cannot restart local keyless analysis or substitute a guessed stack size. Ready supported markets can still seed normally.
- The order desk now shows the screen/container/cursor reason when waiting for safe inventory, instead of leaving an unrelated scanning message visible. The safety gate remains enforced and player screens are not closed automatically.
- Bug report attachments now include session elapsed time, queued action, current UI blocker and the last non-panel blocker, plus a bounded latest-fault stage/type/count/age and verified GoNuts frames. The same capture feeds in-game, local-web and already-opted-in automatic reports; fault evidence survives a missing/rotated log during this process. Exception messages, names, paths, credentials and raw settings are excluded. Existing consent and send controls remain.
- Investigated report #118: the startup defect is reproduced and corrected, but the report's overall no-trading symptom is not yet established as resolved. Its captured INVENTORY_SAFETY category may reflect the report screen itself; a current status after closing the panel is still needed.

## 2026-10-18 15:45 UTC

- Release PR #117: Two-step setup, Auction/Orders tabs, tutorials and Swarm history warm-up.
- Added a two-step in-game setup: Choose limits, then Review & Start. It preserves existing installs' normal entry screen, offers optional Swarm/protection pages, and starts only after an explicit final action through existing recovery and trade gates.
- Added main Auction and Orders tabs, retaining the same settings in Settings and the order book inside Orders. Settings now has Auction, Orders, General and All categories in game and in the local web panel.
- Added optional tutorials for Swarm, Evasion/Staff Watch, items, Flips, orders, updates and bug reports. Staff Watch explains that a same-server spectator detection does not prove staff are watching you.
- Added bounded shared completed-sale history bootstrap for thin keyless databases with contribution and guidance enabled. Imports retain original quantities, times and prices, require sufficient samples and recent evidence, and never create owned trades, profit or live orders. Unsupported, stale or unavailable coverage falls back to local collection. Native live reads and trade gates remain enforced.
- Fixed local web Swarm joining when a valid dashboard IPC status exceeded the old 4 KiB reader limit. Status reads now accept the existing 32 KiB protocol bound and publish a compact safe summary; action requests retain their 4 KiB limit and freshness checks.
- Website companion changes: ranking updates retain stats, unconnected owners see an explanation, and both bot cards and Swarm rank/statistics use two columns on desktop and one column on narrow screens.

## 2026-10-18 14:45 UTC

- Fixed an escape teleport remaining unsent behind an idle Auction House or Orders page. Evasion can now close a recognized trading page with an empty cursor, then dispatch through the existing command pacing and safety gates. Active operations, confirmations, unknown containers and server holds remain protected.
- Cancelled or expired escape requests are removed from the queue so they cannot teleport later. Escape diagnostics distinguish a command that was never sent from a dispatched teleport that did not complete, and notifications no longer claim an escape succeeded at request time.

## 2026-10-18 13:56 UTC

- Added a top-level Swarm tab and a Dashboard shortcut for linking a bot to the website. The in-game flow explains sign-in, the five-minute code, optional bot label and the single Link dashboard & join Swarm action; linked bots show their dashboard and reconnection controls.
- Swarm now explains the shared database benefits, live-check limits and contribution details in the GUI. It shows current contribution status, accepted uploads, estimated Hive rank, reported booked profit, active Hive/bot counts and shared-market coverage where available. Expired or withheld figures stay unavailable, rather than becoming zero or remaining on screen.
- Fixed Swarm HTTP 401/403 recovery after unlinking and re-pairing. Retry renews a refused or legacy disabled membership through the currently linked dashboard instead of reusing the revoked credential. A new explicit pairing also renews its membership. Pending uploads are retained during authentication refusal; server admission checks and retry delays remain enforced.

## 2026-10-18 13:06 UTC

- Fixed listing and sale reconciliation using one snapshot's freshness limit as the deadline for an entire multi-scan operation. The bounded wait now allows an in-flight read followed by two consecutive exact scans; every scan still has to meet the original freshness, ownership and stability checks. Missing proof still pauses with the position and cost retained. Report #34.

## 2026-10-18 13:00 UTC

- Fixed order collections containing multiple inventory stacks being reduced to one booked stack. Every delivered stack and its acquisition cost is now retained and queued before listing; a partial fill still waiting on the order does not hide stock already collected. Refused persistence keeps the batch paused for reconciliation. Report #112.
- Accepted Start and Resume requests now clear the previous explicit-stop flag so queued stock can proceed through normal safety gates. Rejected Resume requests retain that stop; protective holds, inventory proof and spending limits remain enforced.

## 2026-10-18 12:05 UTC

- Fixed recovered stock getting stuck behind an idle auction or server dialog until the long liveness timer expired. Recovery now closes the known stray page promptly and identifies other blocking screens or cursor activity; player screens and safety holds remain protected. Reports #105 and #108.
- Fixed recovered-purchase listing checks becoming permanently disabled after one attempt or carrying a previous purchase's attempted flag into the next recovery. Interrupted reads retry at a bounded interval, already-listed stock is adopted after its checkpoint is accepted, and other recovered positions remain queued. Report #106.
- Staff Watch's display-only HUD no longer runs ImGui's desktop mouse/cursor updates while rendering. Minecraft keeps ownership of cursor behavior, including when trading in the background after Alt-Tab. Report #107.
- Swarm connection failures now back off the whole transport pump, including account registration, and display the failed request stage and safe failure category or HTTP status. Server retry delays are respected. This improves diagnosis of #109; its original connection failure is not yet established.

## 2026-10-18 02:11 UTC

- Removed the hidden purchase-focus list. Items Include/Exclude now controls item eligibility consistently for new purchases, orders and targeted price lookups. Existing purchase-focus selections migrate into Include and Track, alongside existing selections; explicit exclusions remain enforced. The migration keeps a backup and does not repeat after later Items edits. Older saved presets and Settings codes convert on import too. Price, profit, spending, inventory and safety checks remain enforced.

## 2026-10-18 00:26 UTC

- Flip entries blocked by their maximum buy price now recheck an old best-bid quote on a later desk pass instead of keeping the rejection for the normal 15-minute quote lifetime. Rechecks are rate-limited to once per minute per item; a fresh lower bid can allow the next order while the configured buy ceiling and all execution gates remain enforced.

## 2026-10-17 23:45 UTC

- Fixed the in-game Send report button staying disabled even when reporting is available. Manual and automatic report checks now share the same bounded 8 KiB capability reader; the receiver's expanded diagnostic metadata no longer trips an outdated 4 KiB limit. Unsupported responses and reporting cooldowns remain enforced.

## 2026-10-17 22:47 UTC

- Flip collections now match the original requested quantity as well as item and price. An old differently sized order at the same price no longer steals the collection target or credits the wrong order key. Refused requests leave the current row target unchanged.
- Cancelling an order skips completed/cancelled historical rows and targets an active order. If a delivery arrives during cancellation, the desk rereads Your Orders before collecting instead of creating a zero-quantity collection key.

## 2026-10-17 22:41 UTC

- Fixed Flip collection retries: a failed attempt no longer selects the next order row as though the retry count were a row index. New passes start at the first matching row and skip older collected rows without spending the failure budget.
- Confirmed collections clear previous failures for that exact order key, including inventory-confirmed collections. Repeated successful Flip lots no longer accumulate a permanent four-attempt collection block; genuinely unsuccessful passes remain bounded and uncollected goods are preserved.

## 2026-10-17 22:35 UTC

- Fixed a multi-Flip replanning bug: after placing one order, a quote for another item could reuse the older empty own-order book and queue the first order again. A verified native confirmation page now updates the controller's book, reserved funds and slots before replanning.
- Receipt-only placement success does not refresh the old page: it requires another native Your Orders read. Historical completed or cancelled rows cannot certify an active placed order. All earlier Flip replenishment and report fixes remain included.

## 2026-10-17 22:28 UTC

- Fixed a repeat-order stall: cancelled orders that remain visible on Your Orders no longer reserve an active bid slot, unfilled funds or the selected Flip market. Goods still owed must be collected before replenishment; active orders and purchase/spend limits remain respected.
- Cancelled-order collection now uses the original order's requested quantity for its accounting key, queues each owed collection once and respects the retry limit. Already collected goods are not collected again merely because the cancelled row still appears on the page.

## 2026-10-17 20:53 UTC

- Bug-report previews capture bounded, read-only controller evidence directly: current state and active action, fixed pause/desk/Flip/resume categories, evidence ages, persistence readiness and pending writes, retained exposure counts, caps and anonymous Flip allowances. Missing logs no longer prevent a report; unavailable historical counters are explicitly marked unknown. Names, identifiers, raw messages and settings text remain excluded.
- The report panel shows compact known-bug rows and 24-hour report/patch/confirmed-resolution/unresolved counts from the shared cached tracker. Hover, keyboard focus or tap reveals a brief synopsis. “I'm affected too” prepares reviewed diagnostics for the existing bug; attachments use an advertised fixed receiver route and never silently fall back to creating another issue.
- Mobile panels now scale the complete activity picture to the viewport, wrap navigation and controls, and reflow narrow settings cards in portrait and landscape. The website navigation links to the shared bug tracker. Existing running clients are not automatically restarted or upgraded.

## 2026-10-17 20:26 UTC

- A prolonged recovery hold no longer automatically force-resets unresolved exposure outside Swarm mode. Stock records, spend accounting and pending checkpoints stay intact; the existing recovery timer now warns that audited recovery is required. Native verified recovery can continue, and elapsed time never starts trading or writes off a position.
- Filtered bug reports now include fixed reason codes for recognized reconciliation/recovery pauses, a completed-order marker, and a retained-exposure warning. Repeated unchanged state ticks are collapsed so they do not crowd out useful failure evidence. Raw pause messages, identities, locations and credentials remain excluded.
- Reviewed all 15 open bug reports in the linked report-triage document. Related published patches remain awaiting confirmation; missing evidence is explicitly identified rather than treating generic exceptions or zero counters as proof of an upstream outage or a resolved bug.

## 2026-10-17 20:02 UTC

- Fixed a bid-planning loop where a fresh Swarm or selected-item order quote triggered another full Your Orders read, allowing the quote to expire before placement. The desk now replans against a still-fresh native own-order book. Missing or stale own-order evidence still requires a read, and native prices, sizing, inventory, spend limits and protective guards remain authoritative.

## 2026-10-17 19:47 UTC

- Manual bug reports can include an anonymous installation key when the reporting service advertises support. The key is a hash of a random local secret, contains no account or machine identifier, and is disclosed in the in-game and web-panel preview. Repeated submissions can now be grouped and throttled without forwarding market credentials. Rate-limit messages ask users to consolidate reports and warn that repeated spam reports will be ignored.

## 2026-10-17 19:41 UTC

- Single-account clients now stamp new automation ledger records with their account, so booked dashboard and Discord metrics can find them. Hosted metrics accept case-only changes in the same account name and continue excluding other accounts and unowned historical records.
- Evasion can send a direct RTP command for the current region when a fresh server label and current server command suggestions agree. This avoids relying on bare RTP opening a region-selection menu. Unknown or stale region evidence keeps the existing route; configured homes, movement verification and protective disconnect fallback remain unchanged.

## 2026-10-17 19:22 UTC

- Delayed auction sell commands now recheck the selected slot, exact stack and inventory before sending, and synchronize the selected slot with the server. Changed or empty hands retain stock for verification instead of sending a stale sell command.
- Recovered purchases wait while inventory is unsettled, an operation is busy, a protective escape or transfer is active, or persistence refuses a write. Recovery messages now explain why stock is waiting. New live execution also waits while the player is dead.

## 2026-10-17 18:41 UTC

- Participating clients can share new Staff Watch detections with same-region evidence for the Swarm dashboard sidebar. Reports contain only the public staff name, broad explicitly reported region, detection type and time; they expire after 15 minutes. Global staff-online entries and disappearances alone are excluded. Detections are handed to the existing transport immediately, so a protective game disconnect does not lose an already-observed report. Unlinking or losing participation clears the temporary local feed. Staff Watch controls and alert behavior are unchanged.

## 2026-10-17 18:18 UTC

- Website Swarm cards now show six estimated standings (sale records per hour, profit per hour/24 hours, revenue per hour/24 hours and 24-hour profit margin), plus an equal-weight overall score and position using available metrics. Collective profit/revenue and your Hive profit share give context. Figures use compact K/M/B notation and rounded-display labels; missing revenue remains unknown or partial until clients report it. No 10-Hive or ledger-coverage wait is required.

## 2026-10-17 18:07 UTC

- Linked clients now report optional gross booked sales revenue for rolling one-hour and 24-hour windows to support anonymous collective Swarm estimates. Revenue uses completed real sales, stays separate from profit and sale-record counts, and remains unknown when ledger sale prices are incomplete. Reading is local and read-only; no trades or settings changes are dispatched.

## 2026-10-17 17:56 UTC

- Swarm rankings now give an estimated position among active contributing Hives with available reported 24-hour profit. Removed the 10-Hive minimum and 90% coverage wait; two Hives are enough. A Hive uses available bot data and may be partial. Ties share a rank; other users identities and figures stay private.

## 2026-10-17 12:16 UTC

- Replaced the sampled “all sellers” rank with an anonymous Swarm Hive percentile based on client-reported booked realized profit over 24 hours. Active bots belonging to the same owner form one Hive; duplicate accounts count once. Only your own result and the eligible Hive count are shown.
- Rankings require at least 10 qualified active contributing Hives, fresh valid ledger snapshots and 90% recorded ledger-observation coverage. New clients begin collecting coverage from this update; offline gaps and missing profit remain unknown. Ties do not count as lower profit. Stale or unqualified results show an explanation instead of an invented rank.

## 2026-10-17 11:58 UTC

- Added Feature request beside Report bug in the local and hosted web panel. It opens the same public GitHub feature-request form as the in-game button, without attaching bug-report diagnostics.

## 2026-10-17 10:54 UTC

- Evasion now shows one activity view: the native picture with recent events, followed by the original guard, route, homes and tuning controls. The duplicate text activity table appears only if the picture cannot load, and returns to hidden after a successful refresh.

## 2026-10-17 10:50 UTC

- Fixed the Evasion event image overflowing the local and hosted web panel. It now scales to the content width while preserving its aspect ratio, leaving the complete image, settings and history accessible.

## 2026-10-17 09:46 UTC

- Added the Discord-linked dashboard and per-installation Swarm API opt-in. Participating clients contribute observed markets and passive follow activity and receive shared discovery guidance in the background. Linking a bot to the dashboard includes Swarm contribution and discovery; no second enrollment step is needed. Unlinking leaves the Swarm.
- Normal trading now uses fresh shared observations to prioritize discovery. Current native price quotes, own-order evidence, sizing, profit/ROI, inventory, capacity and guard checks remain authoritative. Missing or stale service data falls back to normal reads. Duplicate broad discovery is reused only with accepted, recent, complete same-owner peer coverage and fresh owned-state evidence; observed history is never an executable price or an ownership ledger.
- Dashboard in a hosted bot panel now returns to the website dashboard/sign-in page; the local panel retains account linking. Fixed Report bug remaining on Reading after a removed form control caused a JavaScript error, and made failed submission/preview requests retryable.
- Normal recovery no longer implicitly writes off missing retained purchases. Real sidebar balance feeds the controller as well as the display, including isolated validation paths.
- Evasion emergency and optional movement paths respect Disconnect and the selected home/RTP route; an empty home list no longer silently falls back to RTP. Staff Watch's explicitly selected region-switch response remains separate.
- Retained the current public Analyzer, saved presets, capital charts, report privacy and Evasion-history patches. Added a MultiMC + HeadlessMC guide and per-client mascot telemetry with independent native activity animation and transport-driven antenna send/receive signals. Background request time is not reported as trading downtime; no guaranteed speed or profit uplift is claimed.

## 2026-10-16 23:42 UTC

- Added named saved presets in Settings: save the current setup, apply an existing copy, rename, copy its Settings code, or explicitly replace/delete it. Saved copies remain independent and survive restarts; empty item lists restore correctly, while account slot counts and follow settings keep their existing import protections.
- Added a local Analyzer tab in the GUI and web panel. It reviews the 24-hour ledger, bankroll, purchase limits, active hive client count, this account's slot capacity and recorded uptime, plus observed opportunities and follow notices. Findings separate measured performance from conditional estimates and can be saved as a suggested preset without applying changes.
- Analyzer now separates bank, funds committed to observed orders, goods awaiting collection, unlisted stock, auction cost basis and unresolved exposure. A numbered capital chart and sampled timeline include hover details and 1-hour/24-hour/7-day ranges. Asking prices stay separate from realized profit; stale or unverified categories stay unknown. History records locally from installation onward, without reconstructing past balances.
- Analysis runs locally, dispatches no trades and sends no analytics or settings to a backend. Market suggestions require fresh non-demo observations and sufficient sales evidence. Historical results are not attributed to the current configuration.

## 2026-10-16 23:00 UTC

- Routine auction-watch handoffs now show a neutral "Auction watch finished" notice instead of a red "Execution aborted" warning. The notice explains that no purchase was made and trading continues with orders and existing stock. Actual execution failures still show red error alerts.

## 2026-10-16 20:49 UTC

- Bug reports now include a sanitized Settings code alongside the diagnostic log. It contains approved numeric settings and toggles, including account slot limits, so support can distinguish configuration problems from defects. Home names, player lists, webhooks, Discord identifiers, tokens and all other text settings are excluded. The preview shows the attached code, and retries retain the prepared snapshot.

- Restored the Evasion activity summary and recent hive event history in the web panel alongside its editable settings. History refreshes without discarding unsaved settings and uses the existing name privacy controls.

- Fixed Evasion showing on in Settings while the guard was off. The GUI, web panel and runtime now use one per-client toggle, including after presets, settings-code imports and restarts. Migration preserves an existing off choice and keeps hive accounts independent.

## 2026-10-16 03:35 UTC

- Every in-game, web-panel and automatic bug report now captures sanitized diagnostic logs automatically. Removed the Include log toggle. An unreadable log produces a fixed availability notice, without exposing paths or raw errors.

- Made Flips only prominent on the Flips pages in the GUI, web panel and Discord, with an explanation of normal trading when it is off.
- Flips only now stays restricted even with an empty list. General market scans stop; a general read already in progress is abandoned safely, and queued auction price sweeps are dropped. Targeted order quotes and existing stock maintenance remain available. The background feed keeps recorded sales and hive presence alive without the broad API scanner.

## 2026-10-16 02:42 UTC

- Added manual region switching to Region Map in the GUI, web panel and Discord. Choose one hive account and a destination; moves wait for a safe, durable checkpoint, use the normal command pacing, and resume only the session paused by this transfer after fresh server confirmation. Failed or cancelled transfers never start trading.

- Added an explicit Staff Watch response choice in the GUI, web panel and Discord: switch region first or disconnect immediately. In switch mode, a fresh alert for the same staff member within two minutes of confirmed arrival disconnects for the existing 5–10 minute cooldown, without a second hop. In-flight duplicates are ignored and other staff alerts do not erase pending follow checks.
- Added a Region Map tab in the GoNuts GUI and Region Map pages in the web and Discord panels. The numbered 9×9 Overworld layout identifies this client's mapped server area when Donut Randomized Coords is off; the map highlights the whole area rather than an exact position.
- The existing name privacy toggle also scrambles GUI XYZ using fixed dummy characters. Web and Discord maps always omit exact coordinates, regardless of that toggle. Disconnected clients and unsupported dimensions show an unavailable region rather than a stale location.

## 2026-10-16 01:21 UTC

- Added a dedicated Evasion tab in game and editable Evasion controls in the local web panel. Choose /rtp or /home, enter existing home names such as base, vault, farm, and cycle through them in entered order. The selected route also applies to periodic moves and underwater retries; an empty home list falls back to /rtp.
- Automatically learn auction and order capacity together from confirmed own-menu evidence, including rank upgrades. Each account keeps its own limits; a partial first page cannot downgrade a known larger capacity.

## 2026-10-16 00:48 UTC

- Fixed automatic purchase recovery and own-listing adoption returning to trading without retaining recovery ownership. A later server restart or Staff Watch pause can now resume the already-running session instead of leaving it paused indefinitely.
- Staff Watch reconnects now retain an explicit safe resume retry after cooldown and fresh world verification. Temporary blockers keep retrying; manual stops/cancellation, unresolved stock, rejected writes and session caps remain enforced.

## 2026-10-15 22:26 UTC

- Fixed a paused recovery loop after an interrupted buy or listing was verified resolved: the controller now consumes its leftover intent receipt once the writer has durably cleared the uncertainty. This lets the existing recovery retry proceed instead of waiting forever for an already completed checkpoint (#35).
- Unresolved stock, refused writes, pending actions and operator pauses retain their safeguards. Session spending and trade counts are preserved.

## 2026-10-15 21:53 UTC

- Fixed hive auction/order slot limits overwriting each other (#36). Both values now always save in each instance's doughbay-local.json, including fresh installs and automatic rank detection. Existing local limits win; legacy shared limits are captured locally once, and subsequent shared edits cannot replace them.
- Local settings load even when the shared tuning file is missing. Live reload preserves account slot limits if a local slot entry is removed or its file becomes unreadable, without overwriting malformed local files.

## 2026-10-15 20:05 UTC

- Added guided hive setup: create and copy a code in the account whose data you want to keep, paste and join in a second instance on the same PC, then restart each once. No junction commands, launcher changes or live database copies are needed. Existing history is never silently merged or overwritten; missing shared folders stop startup instead of creating an empty ledger.
- New hives automatically use account-specific order records. Only the creating account inherits its old single-account records, so an alt never starts with the owner's saved bids or balance.
- Clients sharing a configuration folder now host one web panel, with automatic takeover when its host closes, instead of competing for the same port.
- Added a Guides section to the website, an 80-second captioned hive walkthrough, and organized instructions for installation, the web panel, updates, bug reports, Discord and optional headless use.

## 2026-10-15 19:20 UTC

- Enabling auto-update from any settings surface now immediately checks the latest already-published release. Auto-update performs its own checks even if notification-only checks are off.
- Fixed unchanged-release caching skipping a needed download or retry. A known newer release can be fetched on subsequent checks without another publication or repeated GitHub API requests. Verified downloads still install only after the game closes; current and newer local builds are not downgraded.

## 2026-10-15 18:03 UTC

- Fixed large retail lots monopolizing the replenishment loop: after four verified retail-single listings, allow one normal order-desk action before continuing the remaining batch. This can refresh orders, collect fills or place an already eligible order for another market.
- Only known, purchased retail remainders yield; unresolved recovery stock keeps its previous priority. Existing desk checks, spending limits, stock accounting, stale repricing and click pacing remain enforced. If the desk cannot start within 15 seconds, resume listing rather than stranding stock.

## 2026-10-15 17:20 UTC

- Added per-client SOCKS5/SOCKS4 proxy profiles, a Multiplayer-screen button, and Proxies tabs in the in-game GUI and authenticated web panel. Profiles apply to new Minecraft connections, pings, transfers and reconnects; existing sessions are not interrupted. Local/LAN connections stay direct.
- Proxy routing defaults off. Enabled proxy failures do not fall back to direct connections; unreadable settings block remote connections until explicitly repaired or disabled. Credentials are saved in a separate local file with restricted access, omitted from web responses and excluded from diagnostic reports.
- Simplified manual bug reports to one Send report action: prepare and sanitize the selected diagnostics automatically, with an optional preview. Public-report disclosure, attachment choice, bounded transport and stable retry identifiers remain in place.

## 2026-10-15 16:39 UTC

- Fixed replenishment scheduling counting order-screen cleanup and settle waits as completed desk turns. Only dispatched desk actions count now. Below half of usable auction capacity, the desk gets four actions between due reprices instead of two; a due reprice still gets priority after a minute of scheduling deferral. Existing stale thresholds, trading limits and click pacing are unchanged.
- Added a Report bug page to the authenticated web panel: prepare a sanitized report and optional diagnostic excerpt, review the public preview, and send directly without opening the game GUI. Uploads run in the background, retain retry identifiers, and require explicit review.
- Added dismissible web-panel notices for detected critical persistence failures and eligible stalled evasion recovery, including clients that have not opted into automatic uploads. Notices never pause trading or open a screen.
- Combined current Staff Watch sightings into one row per staff member across the hive. Observers are merged, with differing presence or region evidence attributed within the same row. Matching recent sightings across different hive accounts within ten seconds also appear once; separate events remain distinct.

## 2026-10-15 16:03 UTC

- Fixed trading remaining paused after an evasion reconnect: an interrupted cooldown was refused by ordinary Resume, then the fallback mistook the bot's own escape for a manual leave when resume-after-leave was off. Evasion now uses guarded internal recovery after its all-clear wait.
- Evasion retains ownership and retries while safe reconciliation is pending instead of abandoning recovery after four refusals. Manual stops, unresolved exposure, persistence acknowledgements, server checks and existing spending limits remain enforced; no operator force-reset path is used.

## 2026-10-15 06:07 UTC

- Fixed a recovery watchdog reset resuming without its trading policy, which caused a null-policy error in spending reconciliation and left the bot paused. Recovery now restores validated limits before clearing exposure or arming; unavailable limits retain the recovery lock and records.
- Added a fail-closed active-tick policy check and preserved explicitly supplied recovered-session policies. Trading limits and existing reset semantics are unchanged when a valid policy is present.

## 2026-10-15 04:54 UTC

- Added Staff Watch activity history to the in-game tab, authenticated web panel and Discord panel: current staff observations, spectator sightings, observing accounts, first/last sightings, detected duration and recent events.
- History is retained for 30 days in a separate shared sidecar database. Hive overlaps are merged; disconnects, observation gaps and stale data are excluded. Activity hours require enough observed coverage and use the operator's timezone, without inferring staff timezones or account ownership.
- History collection uses existing detector evidence only, with background persistence and cached UI snapshots. Existing staff escape/reconnect behavior and trading settings are unchanged.
- The website now shows lifetime mod, CLI and server downloads from the public VPS counter, hiding the count if it cannot be loaded.
## 2026-10-15 01:57 UTC

- Added opt-in automatic critical bug reports under Settings → Support. Persistent rejected checkpoints or a failed persistence writer are reported only after two minutes without recovery following healthy operation. Slow sales, normal scans, manual stops and intentional restart/escape handling do not trigger reports.
- Reports use the public schema 3 receiver with sanitized log excerpts and anonymous incident fingerprints. Per-install consent defaults off and cannot be inherited through shared configuration or copied settings codes; headless installs can opt in through doughbay-local.json.
- Delivery runs off the game thread. Frozen requests and retry identifiers survive restarts, retries are bounded, and duplicate suppression plus a three-new-report daily limit prevent repeated failures flooding GitHub. Cached evidence is validated before upload, and older or disabled receivers block automatic submission while manual reports remain available.

## 2026-10-15 00:08 UTC

- Bug reports now send directly without obtaining an API token. Public capabilities enable the sanitized log excerpt, and the GUI shows Sent only after a valid issue link. Retries keep their identifier; a 409 conflict asks the user to prepare again.
- Staff Watch now queues a safe regional RTP on the first qualifying alert. A fresh sighting of the same staff after confirmed arrival triggers another hop, then the existing five-to-ten-minute disconnect cooldown. Late autocomplete replies and duplicate alerts cannot escalate a transfer.
- Transfer commands respect the teleport safety setting and durable transaction boundaries. Blocked, failed or unconfirmed hops fall back to disconnecting. Manual stops/pauses and recovery locks are preserved; coordinate labels are not accepted as transfer proof.

## 2026-10-14 21:55 UTC
- Fixed: with two clients sharing one ledger, retail singles could be prepared with a position id the other client had already used, which refused the save ("Retail child position id is already in use"), stopped all further record-keeping and left the bid desk pausing and auto-resuming for hours without being able to stop; position ID ranges are now reserved durably by the shared ledger, and rejected writes stay rejected even after later saves. The writer remains available for safe stop/reconciliation; automatic retries retain exposure and pause until explicit reconciliation or restart. Existing transaction history is preserved. (#5)

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
