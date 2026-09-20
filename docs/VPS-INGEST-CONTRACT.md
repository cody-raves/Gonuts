# GoNuts API — order-book ingest contract

For the agent running the VPS API. The trading clients already read the whole
DonutSMP order house in the game (a page nobody's public API exposes) and now
post every sweep home. This is what arrives and what the API should do with it.

The mod side is already written (`dev.doughbay.fabric.FeedUploader`) and sits
behind `upload.enabled`, off until this route exists. Once it is switched on
the clients start posting within ten minutes; it is also tolerant of the route
going away (a 404 makes it wait half an hour and try again).

---

## 1. The request

```
POST /v1/ingest/orders
Authorization: Bearer <the client's GoNuts proxy token — same one it reads with>
Content-Type: application/json
Content-Encoding: gzip
X-GoNuts-Mod: 0.1.0
X-GoNuts-Client: your-username
```

Body (gzipped JSON, ~50–150 KB uncompressed for a 1,300-order book):

```json
{
  "client": "your-username",
  "mod": "0.1.0",
  "observed_at": 1788990123456,
  "pages": 27,
  "full_sweep": true,
  "orders": [
    {
      "item_id": "minecraft:rail",
      "item_key": "minecraft:rail|64",
      "descriptor": "{...}",
      "parts": ["Enchantments: ..."],
      "unit_price": 350,
      "delivered": 128,
      "total": 192,
      "remaining": 64,
      "page": 3,
      "observed_at": 1788990120000
    }
  ]
}
```

- `observed_at` are epoch milliseconds from the client's clock.
- `full_sweep` true means every page of the order house was walked; false means
  a partial read (first pages only) — a partial read must never be used to
  conclude an order is gone.
- `parts` are the tooltip's requirement lines (enchantments, contents); may be
  empty. `descriptor` is the item's component JSON when the row had one; may be
  absent.
- `item_key` is `item_id|stack_size` for plain items and carries a `#hash` for
  items with components.

## 2. What the API must do

1. **Authenticate** with the same bearer check as the read routes. The token
   identifies the contributor; store it against each row (`contributor`).
   Reject unknown/revoked tokens with 401.
2. **Merge, newest wins.** Identity of an order is
   `(item_key, parts joined by ';', unit_price, total)`. Upsert on that key;
   keep the row with the greatest `observed_at`. Never let an older
   `observed_at` overwrite a newer one, whichever contributor sent it.
3. **Expire.** After a `full_sweep: true` payload, any stored order last seen
   more than 60 minutes before that payload's `observed_at` and absent from it
   is marked `gone_at = observed_at`. Partial sweeps expire nothing.
4. **Answer** `202 Accepted` with `{"accepted": <rows>, "merged": <new or
   updated>, "expired": <n>}`. Do not make the client wait on the merge —
   queue it if the write is slow. Any 5xx makes the client retry with backoff
   for up to an hour; a 404/501 makes it back off 30 minutes (use that while
   the route is being built).
5. **Size.** Accept gzip bodies up to 2 MB. Two clients post every ~10
   minutes each; a full sweep every 30–45 minutes.

## 3. Serve it back

The point of the ingest is a book nobody else has. Expose it under the same
tokens:

- `GET /v1/orders` — every open order (not `gone`), newest observation first;
  filters `item_id`, `item_key`, `min_unit_price`, `since` (epoch ms).
- `GET /v1/orders/{item_id}` — the book for one item, highest `unit_price`
  first, with `remaining`, `unit_price`, `observed_at`, `contributor_count`.
- Include `observed_at` on every row so a reader can judge staleness itself,
  and `X-GoNuts-Book-Age: <seconds since newest full sweep>` on the response.

## 4. Verify

- One sweep from a client shows in the log as
  `GoNuts upload: the API is taking order sweeps (1 sent)`; the API's log
  shows the row count and the contributor.
- `GET /v1/orders/minecraft:rail` returns rows whose `observed_at` is within
  ~10 minutes of now while a client is trading.
- Send the same payload twice: the second returns `merged: 0`.
- Send a payload with an older `observed_at`: no row changes.

## 5. Later, same shape

The uploader is written so these can follow with a path each and no change to
the auth or merge rules: `/v1/ingest/listings` (live auction rows the client
sees on the page — minutes fresher than the public feed) and
`/v1/ingest/sales` (the exact chat receipts of the client's own fills).
