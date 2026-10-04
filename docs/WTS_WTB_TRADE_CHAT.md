# WTS / WTB Trade Chat and Meet Recall

Fake players ("bots") answer trade ads typed in public chat, haggle through the brain (`fpc_brain.py`), then meet you
and open a private store. This change makes that flow more reliable and adds an optional "meet recall". Every switch is
in `dist/game/config/Custom/FakePlayers.ini`; each can be set to `False` for the previous behaviour.

## Writing an ad

Say a trade marker as a whole word anywhere in the line, then the item(s):

- `wts ssd`, `ssd wtb`, `[WTS] Spirit Ore`, `S> bsoe`, `S>bsoe`, `B> sps`, `selling` / `buying`
- Up to 3 items per ad: `wts ssd, bsoe, spirit ore` (each gets its own bot).
- Price hint is optional: `wtb ssd 5k`, `wts ssd 300 adena`.
- Enchant: `wts +5 Sword of Revolution` (the bot buys only a copy at exactly +5) or `wtb +5 Sword of Revolution`
  (the bot sells a +5 piece, priced by grade and enchant).
- Linking an item from your inventory in the ad uses that exact item (id, enchant, stack) and skips the name search.
  Other items named in words in the same ad are still read. (Interlude has no chat item links, so this path is idle here.)

Words that merely contain a marker (e.g. "unselling") are not ads.

## What the bots do

1. **Parse** the ad (`TradeAdParserV2`), resolve linked items (`TradeAdLinkedItems`), and match names against the shop catalogue.
2. **Clarify** when several items match equally well (`TradeAdClarify`): the bot asks which one you meant. Answer with the
   name or its number, and add an amount or price if you like (`second one @150k`). Several unclear items are asked
   about one at a time.
3. **Reply** after a short delay (`TradeAdReplyMinMs`/`MaxMs`, default 3-7 s, was 5-15 s). If an item is not recognised
   a bot sends a format example (`TradeAdFormatHint`).
4. **Say why not** instead of staying silent (`TradeAdStatusReplies`): offer cap reached, nobody free, nothing stocked.
   A player posting more than twice the per-player offer cap in ads per minute (at least 6) is ignored until the minute
   passes, before any brain call.
5. **Haggle**: the brain receives the deal context headers, including the limit price (`X-Deal-Limit-Price`) and the
   enchant (`X-Deal-Enchant`). The limit is kept inside the economy price band, so a price the bot names is one it
   will accept.
6. **Hold the offer** for `TradeOfferTimeoutSeconds` (default 180 s, was 420 s).
7. **Meet and trade**: the bot goes to the meet spot and opens the private store.

## Caps and pricing

- `TradeAdOffersPerMinute` (world, default 60; was a fixed 20) and `TradeAdOffersPerPlayerPerMinute` (default 4).
- `TradeAdMaxItems` 1-3.
- `TradeAdEnchantPricing`: enchanted gear is priced up by grade and enchant level; deal stock and price bands are
  enchant-aware. Off = enchant ignored.

## Meet recall (optional)

Off by default (bots walk as before). When `FakePlayerMeetRecall = True` a bot that agreed to meet you "reads a scroll":
it stands still for `FakePlayerMeetRecallCastSeconds` (4) and lands on the meet spot.

- Recalls if farther than `FakePlayerMeetRecallMinDistance` (500) or if it made no progress for `FakePlayerMeetRecallStuckSeconds` (10). Close bots just walk.
- At most one recall per meet.
- `FakePlayerMeetNearPlayer = True` picks the landmark (gatekeeper, warehouse, shop) nearest YOU instead of the bot, so you and the bot mean the same place. Only has effect with recall on.

## Config reference

| Key | Default |
|---|---|
| `TradeAdParserV2` | True |
| `TradeAdLinkedItems` | True |
| `TradeAdStatusReplies` | True |
| `TradeAdFormatHint` | True |
| `TradeAdOffersPerMinute` | 60 |
| `TradeAdOffersPerPlayerPerMinute` | 4 |
| `TradeAdReplyMinMs` / `TradeAdReplyMaxMs` | 3000 / 7000 |
| `TradeAdClarify` | True |
| `TradeAdMaxItems` | 3 |
| `TradeAdEnchantPricing` | True |
| `TradeOfferTimeoutSeconds` | 180 |
| `FakePlayerMeetRecall` | False |
| `FakePlayerMeetRecallCastSeconds` | 4 |
| `FakePlayerMeetRecallMinDistance` | 500 |
| `FakePlayerMeetRecallStuckSeconds` | 10 |
| `FakePlayerMeetNearPlayer` | False |

## Code map

- `FakePlayerChatParsing.java` - ad parser, linked-item extraction, item splitting, clarify matching, counter limit, format hint.
- `FakePlayerChatManager.java` - trade flow, status replies, clarify state, per-player cap, brain headers.
- `FakePlayerStoreFactory.java` / `FakePlayerStorePricing.java` - close-match search, enchant-aware stock and pricing.
- `FakePlayerBehaviorManager.java` - specific-responder claim, offer timeout, meet recall.
- `FakePlayersConfig.java`, `FakePlayers.ini` - settings.
- `fpc_brain.py` - deal note now includes enchant and the limit price.
- Tests: `tests/java/TradeAdParserTest.java`, `tests/test_fpc_brain.py` (run via `tests/run_all.sh`).

## Verification status

Covered by unit tests only (parser, pricing, brain deal note). Not yet run in a live server. Open design questions:
whether the per-player offer cap default (4/min) is right, whether the format hint should stay fixed text or be generated,
and scope of any follow-up changes.
