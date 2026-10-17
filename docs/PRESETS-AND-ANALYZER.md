# Saved presets and local Analyzer

## Save a baseline before experimenting

Open Settings → Saved presets in the game, or Settings → Saved presets in the local web panel. Enter a name and save your current settings as a new preset. Change settings normally, then save the experiment under another name. Select your baseline and apply it to switch back without restarting.

Saving a new preset never overwrites another one. Names are unique without regard to capitalization. Rename keeps its settings, Copy code shares its existing Settings code, and Replace with current/Delete require an explicit confirmation. Applying a preset changes current tuning and keeps the stored copy intact. Presets use the existing shareable Settings-code rules: per-account rank slot limits and follow options are not imported. The complete preset snapshot includes empty item lists so switching back clears a list that was previously empty. The normal Copy settings code feature keeps its existing format and behavior.

The hive configuration directory contains saved-presets.json. Hive members can access the same preset library. Applying a preset updates shared trading settings for the hive where those settings are shared; per-client overrides and account-local import exclusions remain in effect. Saves use a process lock and atomic replacement. A corrupt or oversized file is preserved rather than silently overwritten. Up to 100 named presets can be kept.

## Review your local data

Open Analyzer on the left of the game GUI, or Analyzer in the local web panel, and press Analyze local data. In the GUI use Previous/Next to move between the baseline and findings; scroll to read long findings. The report is built from cached local ledger statistics and locally observed market information. It does not query the community database or transmit your settings.

The baseline shows the local real ledger for the last 24 hours, realized profit, sales, a 24-hour wall-clock average, current account bank and purchase cap, active hive client count, this account's slot capacity and recorded active time. Other accounts' slot capacities are not guessed, and one account's active time is not used to divide hive-wide profit. A new installation's 24-hour average is not a connected-hour earning rate.

The first release flags unknown/zero bank, fixed caps beyond current balance, unused account listing capacity, markets with at least 10 losing completed sales, proven profitable markets, and tracked markets without a local sale in the window. A lack of sales alone is not enough to recommend dropping an item. Pool size alone is not enough to claim wasted scan time.

A trial candidate must have a recent non-demo market snapshot, at least 10 observed sales, confidence of at least 0.6, positive estimated net profit and a quoted buy price inside both current bank and purchase cap. Candidates outside the current explicit watch pool may be paired with a losing market as a watch-list experiment. Existing item allow/deny filters are retained; the recommendation does not override them or automatically cancel existing stock. Follow notices provide context, not evidence of your profit. When no fresh affordable listing qualifies, Analyzer can still highlight an outside market with at least 20 observed sales and sufficient confidence for further investigation. Its market-wide sales rate is not your expected throughput, and without an acquisition quote it does not claim a margin or generate a replacement preset.

Profit per quoted trade is an estimate. A scenario for one additional occupied slot uses 50–100% of a proven market's observed profit per slot-hour and is explicitly conditional on demand and utilization. Neither is a promised profit/hour increase. Historical ledger results may span earlier configurations; this release does not infer causal effects or backtest settings changes.

Save suggested preset creates a named snapshot for review. It never applies suggestions or starts/resumes automation. If no supported numeric/watch-list change exists, the report remains useful but no suggested preset is generated. Copy report exports readable JSON including the current and suggested shareable settings codes.

## Scope

This is a standalone local-client feature. Hosted dashboard enrollment, community uploads, scan-reduction trials and backend rollout flags are separate and unchanged. No model API or external analysis service is required.

## Capital charts and money tied up

Run Analyze again to capture an up-to-date report and include the newest recorded history. The capital view belongs to the current account. It separates available bank, observed unfilled order funds, delivered order goods minus recorded collections, collected/unlisted stock at purchase cost, auction stock at purchase cost, and unresolved exposure at its recorded cost. Asking prices are shown separately as potential gross revenue and never included in the pie or booked profit. A lower bank balance alone is not evidence of a loss.

Identical orders share a collection counter, so delivered quantities are aggregated before collections are subtracted once. Cancelled orders have no remaining reserved funds. Collected stock is counted from its current ledger status; cancelled retail parent positions and other hive accounts are excluded. Unattributed legacy records are flagged rather than assigned to this account. Own-order coverage is only the rows actually observed: the current reader cannot certify that every own-order page was read. Amounts are observations, not a complete net-worth statement.

Open Capital charts in the GUI; Previous/Next changes the history category, and the range button chooses 1 hour, 24 hours or 7 days. Hover pie slices, numbered category labels or history marks for amount, snapshot time and source-read time. The web Analyzer has the same allocation chart, category/time selectors and hover details. Source notes and unknown amounts remain visible. The bank source is a fresh sidebar read (at its displayed resolution), or an actually timestamped API balance read, not the last general market refresh. Bank/order observations older than five minutes and ledger costs older than 30 seconds are excluded rather than drawn as zero.

The client records a local snapshot every five minutes while running and keeps up to seven days in gonuts-capital-history under the game directory. Files are separated by a hash of the account name so independent hive clients do not duplicate the same account's bank into a hive total. Nothing is sent to the backend. The timeline begins when recording starts; there is no invented past bank/order history. Bars represent individual snapshots, leaving offline and unknown periods blank instead of interpolating them. A corrupt history file is kept unchanged and recording is disabled for that file.
