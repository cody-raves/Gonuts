# GoNuts token bot — contract

For the agent running the VPS. A Discord bot, living on the VPS beside the
proxy, that lets a player mint their own GoNuts API token, tied to their
Discord account. One active token per person; minting a new one retires the
old one, so anyone can rotate on the spot without asking. Every token holder
can read the API and contributes to the shared order book the moment their
mod starts.

Nothing changes in the mod: a minted token goes into
`config/doughbay/secret.dat` exactly as the two existing tokens do, and the
uploader and reads use it as they do now.

---

## 1. What a player sees: one standing panel

The bot works the way the mod's own Discord panel does: **one message in one
channel, posted once and edited in place**, with the controls on it. Nobody
types a command; they press a button on the panel. The message id is stored
so a restart of the bot finds and keeps editing the same message rather than
posting a new one.

**The embed** (public, so it must never contain a token or anything personal):

- Title: `GoNuts API`
- A short line on what a token is for: read the API, and your mod contributes
  order sweeps under your name while it runs.
- Live figures, refreshed every few minutes: active token holders, sweeps
  received in the last hour, open orders in the book, book age.
- The setup hint: "Mint a token, then put it in
  `.minecraft/config/doughbay/secret.dat` and start the mod."

**The buttons** (one row; every press answers with an **ephemeral** reply that
only the presser sees, so a token never lands in the channel):

| Button | Style | What it does |
|---|---|---|
| **Mint token** | green | Creates a token for the presser and shows it **once**. If they already have one, the label reads **Rotate token** for them (the reply says: "This replaces your old token, which stops working now"). |
| **My status** | grey | Their active token's name, minted, last seen, requests today, sweeps contributed, limit; never the token itself. No token: "You have none - press Mint." |
| **Revoke** | red | Revokes their active token after a confirm step (a second ephemeral message with a Confirm button). Nothing replaces it until they mint again. |

Button presses from members without the Trader role get the same ephemeral
"ask an admin for the Trader role" reply; the panel itself is visible to
everyone in the channel.

Rotation is immediate because that is what a rotation is for - a leaked token
has to die at once. If a grace period is ever wanted for a running client,
make it a short one (≤ 5 min) and opt-in, not default.

Slash commands are **not** needed for players. Keep them for the admin
actions below, where a button on a public panel would be the wrong surface.

## 2. Who may mint

- Only members holding a configured role (`TRADER_ROLE_ID`) in the configured
  guild. Everyone else gets "ask an admin for the Trader role".
- One active token per Discord user id. The user id is the identity; the
  Discord username is only a label and can change.

## 3. Admin commands

Restricted to `ADMIN_ROLE_ID` (or the guild owner), also ephemeral:

| Command | What it does |
|---|---|
| `/token list` | Every active token: holder, minted, last seen, requests today, sweeps, limit. Never the token. |
| `/token revoke-user @user` | Revokes that user's token. |
| `/token ban @user` | Revokes and blocks further minting until unbanned. |
| `/token unban @user` | Lifts the block. |
| `/token limit @user <per_minute>` | Per-token rate limit override (default 300). |

## 4. Storage and the proxy

Extend the existing `tokens` table (whatever its current shape) so each row
carries:

```
token_hash      sha256 of the token; the token itself is never stored
name            label shown as "contributor" (Discord username at mint time)
discord_id      the holder's Discord user id
minecraft_name  filled in by the proxy from X-GoNuts-Client on first use
issued_at, revoked_at, last_seen_at
limit_per_min   default 300
banned          bool, per discord_id (or a separate table)
```

- **Token format:** `dbay_` + 32 random bytes, base64url, from a CSPRNG.
  Shown once at mint. The proxy looks it up by hash.
- The proxy's existing bearer check and per-token limiter read from this
  table unchanged; `contributor` on ingested rows becomes the token's `name`.
- **Audit table:** every mint, revoke, ban, limit change - who, when, on whom.
  Never the token.
- **Never log a token** anywhere: not in the bot's log, not in the proxy's,
  not in an error message. Log the hash prefix (8 chars) if a handle is needed.

The two tokens that exist today (the owner's two clients) are migrated into
this table with `discord_id` = the owner's id; they keep working unchanged.

## 5. Running it

- One process, systemd unit beside the proxy, same Postgres, restart on
  failure. Language is the VPS agent's choice; discord.py or discord.js are
  both fine.
- Config from the environment: `DISCORD_BOT_TOKEN`, `GUILD_ID`,
  `TRADER_ROLE_ID`, `ADMIN_ROLE_ID`, database URL. The bot token is entered on
  the VPS by the owner, never pasted into chat with anyone.
- Bot permissions needed: Send Messages and Embed Links in the panel channel
  (to post and edit the one message), plus application commands for the admin
  slash commands. No message content intent, no channel read. Register the
  slash commands to the one guild (instant), not globally.
- Config also carries `PANEL_CHANNEL_ID`; the bot stores the panel's message
  id once posted (in the database, not a file) and edits it from then on.
  Button `custom_id`s are stable strings (`tok:mint`, `tok:status`,
  `tok:revoke`, `tok:revoke-confirm`) so presses on the existing message keep
  working across bot restarts.
- Interactions must be acknowledged within Discord's 3-second window: defer
  ephemerally first, then do the database work, then edit the reply.
- The bot needs no HTTP exposure; it talks outbound to Discord and locally to
  Postgres.

## 6. Verify before anyone else uses it

0. The panel is posted once; restarting the bot edits the same message
   instead of posting another; its figures refresh on their own.
1. A member without the role presses **Mint** and is refused, ephemerally.
2. A member with the role presses **Mint**; the reply is visible only to them
   (check from a second account: nothing appears in the channel); a request
   to `/v1/orders` with that token returns 200; `contributor` on their ingested
   rows shows their name.
3. They press **Rotate**: the reply shows a new token; the previous token now
   gets 401 within a second; **My status** shows the new one only.
4. **Revoke** → Confirm, then `/v1/orders` with the revoked token: 401.
5. `/token ban @user` then `/token mint` as that user: refused.
6. The owner's two existing tokens still work throughout.
7. `grep` the bot log and the proxy log for `dbay_`: nothing.

## 7. Two things that are the owner's call, not the build's

- The docs' standing open question: DonutSMP's rules on an API that serves
  data to other players. Once the first outside token exists, this is a
  product. Ask staff before the role is handed out widely.
- Whether a token holder must also contribute (run the mod) or may read only.
  The build above treats them the same; a read-only tier is a `limit_per_min`
  and a flag away if wanted later.
