# Phantom Hunting-Spot Defense

`PhantomPvpSpotDefense` defaults False and needs `PhantomPvpEnabled`. A field hunter accumulates annoyance
when an eligible real player damages an already engaged monster or hunts close by. Its rolled Hot, Normal or Patient temper
sets the limit. It complains, gives the same offender a three- or six-second ultimatum, then attempts a fight
or a stock duel challenge. Calming below the attack threshold cancels the ultimatum. A replacement offender
receives a new full warning period. After escalation, the hunter cools down for the configured interval.

Clean white farmers can receive warnings and consensual duel challenges, but the feature grants no forced
attack permission. For legally attackable offenders, FightPercent chooses PvP versus a duel before checking
challenge availability. A selected duel that is disabled, unavailable or rejected means stand-down, with no PvP
fallback. Ordinary PvP still requires
native attack eligibility. Both sides' peace/no-PvP zones, clan/alliance/party membership, newbie and level
protection, instances, events, Olympiad and duel state remain hard gates. Recruits, buddies and encounter actors
never initiate spot defense. Only consecutive observed crowding samples add time-based annoyance; pauses
are not charged retroactively. Theft recording and warning decisions share the hunter's state lock.

Theft needs an earlier observation of positive native hunter damage with no real player damage, followed by
positive damage from an eligible player. Monster targeting and internal reservations are not theft evidence.
Native damage totals have no timestamps, so ambiguous first-hit order is ignored. Dropping the claim, replacing
the native damage entry on respawn, or pausing observation invalidates the evidence. Historical offline-player
damage still prevents a later reconnect from being treated as theft. Scoring uses the same protections as
escalation, and protected/offline players' old scores are removed during observation. Disabling either spot
defense or the PvP master switch clears scores, warnings, evidence and cooldown state for all hunters.

Run `tests/run_phantom_spot.py --core <GameServer.jar>` for native manager regressions. Live verification:
enable the switch deliberately, damage a mob after observing the hunter hit it and crowd the hunter across several ticks;
check warning cancellation, a changed offender's grace period, consensual white-player duels, cooldown,
and both sides of a safe-zone boundary. Deploy the rebuilt `GameServer.jar`, merge the new keys into
`game/config/Custom/FakePlayers.ini` without replacing customized values, and restart the game server.
