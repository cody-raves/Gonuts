# GUI tour

Open GoNuts in game with **B**. Wide windows use a grouped sidebar; smaller
windows use multiple rows of navigation buttons. The selected page, feed health,
and session control stay visible above the content.
In the default **Observe** mode every tab is view-only, so you can explore the
whole interface without a single trade being possible.

The Dashboard opens first, with realized profit, active listings, a profit
chart, the top opportunities, and recent activity. The ledger always shows real
records, even when market opportunities use explicitly labeled demo data.

The rounded cream-and-teal robot has an upright antenna and independently
animated eyes, eyelids and mouth: reading scans, rolling dollar reels, and a
bored idle with glances and yawns. It appears once on the Dashboard.
**Settings → HUD → Reduced motion** keeps its expression still and
removes HUD movement. Alerts have a visible lifetime bar and avoid other GoNuts
HUD panels; the key legend and nearby-player panel use the same visual theme.
The HUD separates state, slot usage, committed money, and current action.
Status and alert text wrap, with card height measured from the full text.
The capacity grid fills the card width, using larger, evenly spaced slots.
**Preview HUD** on the Dashboard displays labeled sample values for 30 seconds
and cycles through reading, sale, idle and working animations.

> Screenshots are captured in the mod's built-in demo mode (sample data, never a
> live account) and will be added per tab below.

---

## Opportunities

A ranked set of opportunity cards showing resale flips the engine currently likes, each
with its expected margin, ROI, and estimated hold time, all backed by completed
sales. Click one for a detail view with a 12-hour completed-sale chart and the
price bands behind the call. In Observe mode this is a shortlist to read, not a
buy button.

## Markets

The live scan roster: every market being watched right now, marked as pinned,
auto-selected, or held. Each row shows the price bands, liquidity (sales per
hour), and confidence, so you can see at a glance which markets are trustworthy
and which are thin.

## Search

Look up any item, even one that is not in the current scan set. Type a name and
GoNuts pulls its market: the price bands, the chart, and how fresh the data is.
Useful for checking a hunch before you pin something.

## Items

A creative-style item grid for editing the include and exclude lists. Click
items to pin markets you always want scanned, or to deny ones you never want
touched. This is how you shape what the bot pays attention to.

## Performance

How trading actually went, in game: realized profit, win rate, median ROI and
hold, best and worst flips, per-market profit and loss, and an equity-over-time
curve. The same performance picture the CLI `report` command prints, without
leaving Minecraft.

## Rivals

The competition, reconstructed from the sales feed: who else is flipping, the
hours they are active, what they trade, and how they price. It also surfaces the
**underdog's shadow markets**, markets a rival has proven profitable that you
can quietly shadow.

## Orders

The order house, a buy-order book no public API exposes. This tab shows your
standing bids, what has been collected and delivered, and the depth of the book,
so you can trade the demand side as well as the listings.

## Autopilot

The trading controls, and the only tab that can arm anything. It has three
sub-modes:

- **Continuous:** the live buy, relist, and track engine, with the session
  spend and trade caps, plus start and stop. Off by default and gated behind the
  full execution allowlist.
- **Tools:** setup and proving: the API-key card, and a one-shot test buy and
  sell for validating the live GUI format.
- **Discord:** the command-centre setup: bot token, channel, and operator.

See [Execution and safety](execution-and-safety.md) for exactly what has to be
true before a real trade can happen.

## Activity

The most recent 80 alerts from this game session, including their complete text
and timestamps. Popups can expire without losing the event here.

## Diagnostics

The health panel: feed freshness, request-budget usage, the liveness verdict,
window state, and which gates are open or closed. This is where you confirm the
bot is actually turning over rather than quietly stuck.

## Logs

The recent log lines, scrollable, right inside the panel, so you can watch what
GoNuts is doing without alt-tabbing to a log file.

## Payroll

Hive profit-sharing. Define rules that pay a share of profit out to accounts,
and see the payment history. For groups running several accounts together.

## Settings

Every tuning knob, grouped by feature: Adaptive market, Buying, Selling, Tiers,
Pacing, Rivals, Evasion, Orders, Boxes, Consignment, Stash, Slots, Multi client,
HUD, Session, and more. Each setting has a toggle or a value, with plus, minus,
reset, and preset groups. Changes take effect live.

## About

Version, credits, and links.

---

Back to the [README](../README.md).
