# GoNuts offline trading sandbox — first playable version

A separate Fabric 26.2 test mod. It creates a synthetic venue inside a single-player world: native menus and dialogs, exact per-order quantities, a fake balance, inventory transfers, auctions, a scoreboard and follow messages. No connection to the real server is needed. The module is excluded from normal GoNuts builds and public client artifacts.

## Run

Build with Java 25:

```powershell
.\gradlew.bat -PtradingSandbox :trader-sandbox:build
.\gradlew.bat -PtradingSandbox :trader-sandbox:runClient
```

The development client uses the separate directory `%LOCALAPPDATA%\GoNuts\offline-trading-sandbox-run` and the synthetic name `SandboxTester`. Create a new single-player world, then run `/sandbox start`. The scoreboard is labelled **GoNuts TEST — FAKE MONEY** and starts at one billion fake coins. This mod activates only in single player and only after that command.

For a normal launcher, create a **new test instance** for Minecraft 26.2 and Java 25, with Fabric API and `gonuts-trading-sandbox-0.1.0+26.2.jar`. Add the GoNuts client jar to that same test instance when testing the actual bot. Keep its configuration and records separate from live clients; do not copy live account links or credentials into it. The sandbox does not read or change another instance's files.

## Things to try

- `/orders`: open the order house, choose **Your Orders**, then **New Order**. Search for an item, enter an exact quantity and price, review and create it. Each order has its own durable ID. Three orders of 1024 items remain three separate orders.
- Click an order, then **Collect Items** to transfer its actual delivered stacks into your inventory. Only physically transferred units become collected credits. Cancel refunds only the undelivered escrow; already delivered stock stays collectable.
- `/ah`: browse synthetic traders' listings and confirm a purchase. `/ah SandboxTraderA` filters one trader. `/ah diamond` filters a market.
- Hold a collected or purchased stack and run `/ah sell 80000`. The fake venue removes it from your hand, lists it, and simulated buyers can buy it. Sales credit proceeds after a 2% test fee. `/ah me` shows your native auction slots; click an unsold listing to withdraw it.
- `/follow SandboxTraderA`: toggle a synthetic follow. The venue emits readable listing notices for followed actors, plus delivery and sale receipts. Actors are named market participants in this first version, not walking NPC entities.
- `/balance` or `/sandbox status`: inspect fake cash and venue state. The sidebar shows player name, balance, orders, auctions, follow count and simulation step.

## Repeatable scenarios

The clock advances once every 40 Minecraft server ticks (normally two seconds). `/sandbox pause` freezes automatic market events; `/sandbox step` executes one deterministic step; `/sandbox resume` resumes them. This is the native game simulation, not another scheduled monitor.

- `/sandbox scenario steady`: one order receives up to 64 items per step, one reasonably priced listing may sell, and traders periodically add listings.
- `/sandbox scenario partials`: deliveries arrive seven items at a time, exercising partial-stack waits.
- `/sandbox scenario stalled`: orders and sales stop progressing while market listings can still appear. Useful for distinguishing connectivity from actual trading.
- `/sandbox seed-full`: in a fresh sandbox world, create 48 completed 192-item orders. Three are on the second native page. This reproduces the shape of the default-mode collection regression without touching a real account.

Use separate worlds to keep scenario histories distinct. There is no reset command that silently clears positions. Fake venue state is saved atomically inside the current world's `gonuts-sandbox` folder and loaded again by `/sandbox start` after a graceful restart. Corrupt saves refuse to reset. Minecraft inventory/world saves and the venue JSON are separate stores; forced-crash atomicity across both is not yet implemented.

## Scope of this first version

The catalog currently contains eight ordinary markets. The venue provides real Minecraft chest menus, order-creation dialogs, scoreboard entries and follow/receipt formats, but is a controlled approximation of server behavior. It does not establish parity for every server plugin, timing pattern or formatted field. Delayed/rounded responses, interrupted transfers, richer rival activity, snapshots and automatic end-to-end scenario assertions are next extensions.

The included ledger tests check exact separate orders, escrow/refunds, partial collection, inventory and listing ownership, duplicate purchase/sale prevention, fees, full-page fixtures, deterministic progression and save/reload failure handling. A passing simulated scenario remains evidence about the modeled behavior, not a guarantee about a live server.
