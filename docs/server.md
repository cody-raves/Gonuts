# Hive and clan server

Every client can talk directly to the Donut API with its own key, which is the
default and needs nothing here. But several accounts sharing one key split a
single 250-requests-per-minute budget between them, so each gets a slower,
staler feed.

The `trader-server` module fixes that. One Donut key polls the sales and listing
feeds around the clock into a shared database, and a small HTTP API serves that
history plus the derived market statistics to any number of clients **under
their own keys, with no Donut key of their own.** A whole hive or clan then runs
on a single key's budget: one poll, many readers. The shared history is a quiet
advantage too, since the more accounts feed it, the sharper everyone's
valuations get.

## Run it

On any box (a spare PC, a cheap VPS, wherever):

```bash
./gradlew :trader-server:build
java -jar trader-server/build/libs/doughbay-server.jar doughbay-server.json
```

The config file takes the same keys as the CLI's, plus `port` and `adminToken`.
The Donut key and admin token are read from the environment first, so a service
unit can pass `DONUT_API_KEY` and `DOUGHBAY_ADMIN_TOKEN` instead of writing them
to a file. See [`trader-server/README.md`](../trader-server/README.md) for the
full config.

## Issue and revoke member keys

```bash
# issue a key for a member
curl -H "X-Admin-Token: $DOUGHBAY_ADMIN_TOKEN" \
     "http://localhost:8787/v1/admin/keys?name=<member>"

# revoke it when they leave
curl -H "X-Admin-Token: $DOUGHBAY_ADMIN_TOKEN" \
     "http://localhost:8787/v1/admin/revoke?key=db_..."
```

A clan admin runs one server on one Donut key and hands each member a `db_...`
key. Members never see or need the Donut key, and a key can be revoked the
moment someone leaves.

## Point a client at it

Two ways:

- Set `apiBaseUrl` to the server's origin (for example `"http://your-server:8787"`)
  in `.minecraft/config/doughbay/config.json`.
- In game, switch the API-key card to **Key type: Server** and paste the issued
  key.

No server address is baked into the mod. You always point at your own server.
