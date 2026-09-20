# GoNuts data server

One Donut API key polls the sales and listing feeds around the clock into
the same database schema the mod uses, and a small HTTP API serves that
history plus the derived market statistics to any number of clients under
their own keys. Clients never need a Donut key.

## Run

```bash
./gradlew :trader-server:build
java -jar trader-server/build/libs/doughbay-server.jar doughbay-server.json
```

`doughbay-server.json` takes the same keys as the CLI's `doughbay.json`
plus `port` and `adminToken`:

```json
{
  "apiKey": "<your Donut API key, or set DONUT_API_KEY>",
  "databasePath": "data/doughbay-server.db",
  "port": 8787,
  "adminToken": "<a long random string, or set DOUGHBAY_ADMIN_TOKEN>",
  "targetRequestsPerMinute": 180,
  "salesPollSeconds": 10,
  "listingsPollSeconds": 300,
  "liveListingsSeconds": 5,
  "statsSeconds": 60
}
```

Never commit that file. The key is read from the environment first, so a
service unit can pass `DONUT_API_KEY` and `DOUGHBAY_ADMIN_TOKEN` instead.

## Keys

```bash
curl -H "X-Admin-Token: $DOUGHBAY_ADMIN_TOKEN" "http://localhost:8787/v1/admin/keys?name=cody"
curl -H "X-Admin-Token: $DOUGHBAY_ADMIN_TOKEN" "http://localhost:8787/v1/admin/keys"
curl -H "X-Admin-Token: $DOUGHBAY_ADMIN_TOKEN" "http://localhost:8787/v1/admin/revoke?key=db_..."
```

Each key gets 120 requests a minute.

## Endpoints

| Path | What |
|---|---|
| `GET /v1/health` | liveness, feed status, Donut budget use (no key) |
| `GET /v1/markets` | current statistics for every tracked market |
| `GET /v1/opportunities?cap=` | buy signals from those statistics |
| `GET /v1/sales?since=&limit=` | completed sales since a timestamp, up to 5000 |
| `GET /v1/listings?since=&limit=` | newest listings from the live lane, with the quick-sale price and discount beside each |
| `GET /v1/overview` | totals, revenue by market and ticket size, edge, biggest sales, movers |
| `GET /v1/market/{itemKey}?since=&limit=` | one item: value, 24 h change, price ranges, lowest asks, sales with what was on the listing |
| `GET /v1/trends?items=a,b` | 12 hourly buckets of count and median per item |
| `GET /v1/components/{itemId}` | enchantments, effects, trims and contents clients saw on the page |
| `GET /v1/seller/{name}?since=` | one seller's sales |
| `GET /v1/rivals` | sellers of the last 24 hours with the bot-likelihood score |
| `GET /` | dashboard |

Send the key as `X-Api-Key` or `?key=`.

## Enchantments

The Donut feed's `enchants.enchantments.levels` is null on every row it has
ever sent, for every item, although the API's own schema declares it a map.
Trims and container contents do come through. Enchantments therefore come
from clients reading the auction page in game (`component_listings`), and
`/v1/market` pairs each sale with the latest observation of the same item,
count and price within the previous 48 hours to say what was on the listing.
