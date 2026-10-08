# Gear Grimoire 1.0.0

A Community Board book of gear. Alt+B, press **Gear Grimoire**, or type `.gg <name>`.

- **Armor sets, best first.** Heavy tank, heavy damage, light, robes for casters, robes for healers, per grade. Order is the tier lists (tank = survivability, damage = damage gained, healers = cast speed then mana).
- **Weapons and jewelry** per grade: weapons strongest P.Atk first, jewelry highest M.Def first.
- **Open a set** for its bonus and pieces; **open a piece** for every way to get it: shop (real prices only), exchange (the NPC page, what it asks for, and where each ingredient comes from, so "Majestic Plate Armor" shows the Sealed piece and its drop or craft), craft (recipe and materials), drops, quests. A link jumps to the stock drop search.
- **Search** by name: sets first, then pieces.

## Install
Delete any old `gear-grimoire` folder from the server's modules folder, copy this whole folder in, set `Enabled = True` in `config/module.ini`, restart. Needs nothing else.

## The data
`data/gear_index.tsv` is generated from the datapack by `tools/combat_sim/gear_index.py` (rerun it if you change items, drops, shops, multisells, recipes or armor sets, then replace the file). The module only reads it; it never changes items.

## Known limits (v1)
- No NPC locations for shops: shop lines give the price only. Exchange lines name the NPC when its page can be found.
- Tier placement exists for armor sets only; weapons and jewelry are ordered by raw P.Atk / M.Def.
- Sets not on a tier list (e.g. unobtainable or off-role) show "not on a tier list".
- Gear score leaderboard is not in this version.
