# PvP Danger Encounters

Optional feature of the fake-player (phantom) system. While you play in the open field, phantoms occasionally walk
up and fight you **once**. Every actor exists only to PvP the player: it fights until you die or it dies, then it
leaves (or lies there a few seconds and disappears). Nothing is left roaming.

Off by default. Enable with `PhantomEncounters = True` in `dist/game/config/Custom/FakePlayers.ini`
(also requires `FakePlayers` enabled and `PhantomPvpEnabled = True`).

## The five kinds

| Kind | Level vs. you | Gear | Group size | Behaviour |
|---|---|---|---|---|
| Wimp | 2-3 lower | +0 | your party size | Walks up, asks "are you a bot?", then attacks. |
| Normie | same | +0 to +3 | your party size | Attacks while you stand still or fight a monster. |
| Hard | +3 | +3 to +4 | your party size | Same trigger as Normie. |
| 4Horsemen | +5 | +5 to +7 | your party size, minimum 4 | Attacks the moment it reaches you. |
| AssMuncher | +11 | full +16 | always 1 | The extinction event: one named phantom, no flee. |

Group size is capped by `PhantomEncounterMaxActors` (default 9). Roles (warrior, archer, dagger, nuker, monk) are random.

## When they happen

Each player has an independent timer per kind (a random wait between `MinMinutes` and `MaxMinutes` after the last
one of that kind). Defaults:

| Kind | Wait | Unlocks at your level |
|---|---|---|
| Wimp | 30-40 min | 10 |
| Normie | 30-40 min | 10 |
| Hard | 2-3 h | 20 |
| 4Horsemen | 4.5-5.5 h | 20 |
| AssMuncher | 7-9 h | 20 |

- Wimp + Normie together land roughly every 17 minutes.
- If two kinds are due together, the rarer one goes first.
- After any encounter starts, nothing else starts for `PhantomEncounterGapMinutes` (default 5).
- At most `PhantomEncounterMaxActive` encounters (default 2) run server-wide; a whole group counts as one.
- The clock starts at your first eligible sighting, so there is no ambush on login.
- You are skipped in towns/peace zones, no-PvP and siege zones, duels, stores, instances, the Olympiad, and while dead.

## How an encounter plays

1. **Spawn:** each actor appears 650-900 units away from one general direction, on ground it can walk up (geodata checked).
   An actor that lands in a peace zone is despawned.
2. **Approach:** it walks to you (up to `PhantomEncounterApproachSeconds`). Wimps stop and ask first; Normie/Hard wait for you
   to stand still (`PhantomEncounterStillSeconds`) or be mid-fight; Horsemen/AssMuncher strike on arrival.
3. **Fight:** a single fight, up to `PhantomEncounterFightSeconds`. Actors never flee.
4. **End:** if they all die you are paid; if you die the survivors say a win line and leave; if you escape or time runs out they leave.
   You never get a second round from the same actor.

Actors are flagged hostile to you internally (`PhantomEncounterRules.HOSTILE`, hooked into `Player.isAutoAttackable`)
so skills work on you without giving you or them a karma/PK flag. They are deliberately **not** red-named, so
nobody drops items on death: phantoms killed by players never drop, and you only drop gear under the normal PK/karma rules.

## Rewards

Adena only, paid once when the whole group is down (`PhantomEncounter<Kind>AdenaReward`):

| Wimp | Normie | Hard | 4Horsemen | AssMuncher |
|---|---|---|---|---|
| 50,000 | 100,000 | 200,000 | 350,000 | 1,000,000 |

Gear drops exist but are off (`PhantomEncounter<Kind>LootPercent = 0`). If enabled, a wiped group drops at most ONE random
equipped weapon/armor piece of the last actor to die, protected for you.

## Config reference (`FakePlayers.ini`)

Global: `PhantomEncounters`, `PhantomEncounterMinPlayerLevel`, `PhantomEncounterGapMinutes`, `PhantomEncounterMaxActive`,
`PhantomEncounterMaxActors`, `PhantomEncounterHorsemenMinSize`, `PhantomEncounterPkerName` (default `AssMuncher`),
`PhantomEncounterApproachSeconds`, `PhantomEncounterFightSeconds`, `PhantomEncounterWarnSeconds`, `PhantomEncounterStillSeconds`.

Per kind (`PhantomEncounter<Wimp|Normie|Hard|Horsemen|Pker>`...): `MinMinutes`, `MaxMinutes` (0 = off), `MinPlayerLevel`,
`LevelMin`, `LevelMax`, `EnchantMin`, `EnchantMax`, `AdenaReward`, `LootPercent`.

## Code map

- `managers/PhantomEncounterRules.java` - pure rules (tiers, group size, level/enchant math, hostile map, group state). Unit tested.
- `managers/PhantomEncounterManager.java` - the director: per-player/per-kind timers, eligibility, spawn points, group creation.
- `managers/PhantomManager.java` - actor script (`serviceEncounter`: approach, warn, fight, win/die, leave), spawning, adena payout, optional gear drop.
- `model/actor/Player.java` - one-line `isAutoAttackable` hook for hostile encounter actors.
- `config/custom/FakePlayersConfig.java` - settings.
- `tests/java/PhantomEncounterRulesTest.java` - rules tests (`tests/run_all.sh`).

Because `Player.java` changed, the server jar must be rebuilt.

## Known limits / to verify in game

Unit tests cover the rules only. Not yet verified in a live server: skills landing on an unflagged player via the hostile hook,
gear/enchant on spawned actors, spawn visibility at 650-900 units, and the AssMuncher name colliding if that name is already taken.
Difficulty numbers (rough time-to-kill edge: Hard ~15-25%, Horsemen ~1.3-1.5x, AssMuncher ~2.5-3x) are estimates, not measurements.
