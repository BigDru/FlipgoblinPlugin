# Flip Goblin plugin — compliance policy

The short version:

- **No automation, ever.** The plugin never touches GE offers and never sends input.
  Informational/tracking only.
- **Zero network by default.** A fresh install makes no network calls at all.
- **All networking is opt-in**, behind an account token the user pastes in themselves (the
  full disclosure is shown right there in the Settings tab). Traffic goes to our own API
  only. Removing the token stops everything.

## No automation

The plugin never:

- creates, modifies, aborts, or collects Grand Exchange offers;
- clicks, moves the camera, sends input, or queues any player action;
- reads game state beyond RuneLite's public GE-offer events (`GrandExchangeOfferChanged`)
  and its own config;
- offers any "one-click apply" of a recommendation.

This is permanent product policy, not a v1 gap.

## Data disclosure

Without a token, nothing is sent. Fill capture, session P/L, buy-limit tracking, and asset
snapshots all run locally in the client.

Linking is explicit: the user makes a free Flip Goblin account, mints a token on their own
dashboard, and pastes it into the plugin's Settings tab, which shows this disclosure at that
moment. The server URL is fixed in the client (`https://flipgoblin-api.druex.workers.dev`);
everything is HTTPS. Removing or revoking the token immediately stops all of the below.

While a token is set, the plugin exchanges:

- **Market data reads:** item ids + the token, to fetch live prices/candles for items the
  user views at the GE (or hovers in their inventory — toggleable), plus a bulk price list
  used to value the user's own assets. No character, session, or hardware identifiers.
  Batched, edge-cached, and rate-limited server-side.
- **Trade sync:** the user's own GE fills — item id, side, price, quantity, timestamp, GE
  slot, and a random dedup id — to their own account, so their dashboard shows their trades.
- **Price contributions:** those same fills, minus the GE slot, feed the shared price
  stream. The site only ever shows blended aggregates across many contributors, never an
  individual's fills; raw reports are kept at most 7 days and are tied to the account for
  abuse prevention only.
- **Asset snapshots:** the latest bank/inventory/GE-held (item id, quantity) pairs plus
  coins, to the user's own account for the dashboard's net-worth view. The server keeps an
  hourly value history; deleting the account deletes it along with everything else.
- **Targets read:** the user's own watchlist and alert thresholds, read-only, shown beside
  live prices.
- **Character name:** the current character's display name, as a cosmetic label so the
  dashboard can group fills and assets per character. The token is the identity, not the
  name.

**Never sent, linked or not:** Jagex account or login identity, other players' names,
hiscores, location, chat, hardware or client fingerprints.

The plugin never calls the OSRS Wiki API — all traffic goes to our backend.
