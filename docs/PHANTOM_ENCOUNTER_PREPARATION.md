# Encounter preparation and summoner support

Encounter actors prepare before joining their group's fight. Summoners call their strongest learned, currently
legal servitor; other classes try their learned class self-buffs. Class families cover the lower classes selected
by encounter level normalization. Every cast checks native resource costs, including initial MP, HP and charges,
plus XML conditions against its intended target. A full-HP actor cannot use Zealot to bypass its HP requirement.

New preparation casts have a ten-second budget. A cast already running can finish after that deadline. The full
approach allowance starts when initial preparation finishes, so a fifteen-second summon is not cancelled by a
ten-second approach timeout. Protected or offline targets still end preparation immediately, and combat retains
its separate fight cap. The servitor receives the usual spawn buff kit once before fighting. Servitors follow
their owner's encounter target and periodically try native utility and damage skills. Force-use is restricted to
the currently authorized, exact actor/victim pair.
Native owner PvP and area-target rules still apply; unrelated unflagged players remain protected. Ordinary legal
area targets retain native behavior. The actor and pet stand down when the encounter ends or its protections change;
the encounter pet is removed immediately, and pending preparation casts are cancelled.

Recruited summoners call, buff, heal and recharge their servitor using native casting eligibility. Pet combat obeys
Stop, Hold, raid waiting and hold-fire gates. An actual defensive target can still be fought under the existing
party defense rules.

Queen and Seraphim use their party buffs and cure only matching native debuff slots. Buff alternatives share a
native slot, so an existing variant is preserved instead of replaced repeatedly. Seraphim prefers MP recovery
when nearby recipients are short of MP, otherwise magic reuse. Nightshade heals injured owners or nearby party
members and uses its targeted curse against an authorized hostile focus. Healing respects the pet's HP cost;
all utility uses native resource, condition and recipient range checks. Passive party ticks let beneficial casts
finish, while an explicit Stop still cancels them.

A party leader can ask a member that knows Summon Friend to "summon me", "port me", or "summon <name>".
Explicit names must match a real member of the caster's party exactly. Unknown names, the caster itself and phantom
targets are refused. A negated request cancels a pending order. Stop also cancels that order. The target must carry
a Summoning Crystal and be eligible under native skill conditions; a waiting order expires after one minute.
Before casting, target restrictions for stores, Olympiad registration/mode, festivals/events, observer mode,
blocked summon zones and jail are checked, together with the caster's instance and Seven Signs restrictions.
A blocked target waits without spending reuse. Native final-effect validation still handles changes during the
cast, and the target's summon confirmation remains effective. "No worries, summon me" is an affirmative request;
"don't ever summon me" and "stop trying to summon me" cancel the pending order.

The scoped feature is based on [public PR #45](https://github.com/Teravibes/L2-Living-Worlds/pull/45), including its
party summoner dependency. The contributor's original commits are retained in the public PR.

Validation uses standalone rule tests and `tests/run_phantom_preparation.py --core <GameServer.jar>` against the
JDK 25 + Ant core. The native harness checks skill resources/conditions, targeted pet selection, exact encounter
permission, revocation, full service timing, Summon Friend preflight, utility selection and immediate teardown.
It isolates packet delivery, AI scheduling, world discovery, registration state, skill lookup and pet removal
boundaries. Cast completion is controlled without wall-clock sleeps; live effects, summon confirmation and combat
feel still need in-game checks.

Merge public PR #45 first using a merge commit to retain the contributor history, then merge this follow-up.
Deploy the rebuilt `GameServer.jar` and restart the game server. This follow-up changes Java behavior only;
no new datapack files or configuration are required.
