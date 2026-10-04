/*
 * This file is part of the L2J Mobius project.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.l2jmobius.gameserver.managers;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.FakePlayersConfig;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.managers.PhantomEncounterRules.Tier;
import org.l2jmobius.gameserver.managers.PhantomManager.PartyRole;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.zone.ZoneId;

/**
 * The PvP danger director. On a slow tick it looks at each real player in the field and, once that player's random
 * timer is due, sends one encounter: a phantom (or a group) that walks up and fights once (see {@link Tier}). The actor's
 * script (approach, fight, leave) lives in {@link PhantomManager#serviceEncounter}; the rules are in
 * {@link PhantomEncounterRules}. Everything is gated by {@code PhantomEncounters} in FakePlayers.ini.
 */
public class PhantomEncounterManager
{
	private static final Logger LOGGER = Logger.getLogger(PhantomEncounterManager.class.getName());

	private static final long START_DELAY_MS = 60_000;
	private static final long TICK_MS = 15_000;
	private static final long RETRY_MS = 120_000; // a failed attempt (no room, busy) tries again after this
	private static final PartyRole[] ROLES =
	{
		PartyRole.WARRIOR,
		PartyRole.WARRIOR,
		PartyRole.ARCHER,
		PartyRole.DAGGER,
		PartyRole.NUKER,
		PartyRole.MONK
	};

	private final Map<Integer, long[]> _nextAt = new ConcurrentHashMap<>(); // per player, one due-time per kind (0 = kind not running yet)
	private boolean _started;

	protected PhantomEncounterManager()
	{
	}

	/** Starts the tick once. Called from {@link PhantomManager#load()}; it idles whenever the feature is off. */
	public synchronized void start()
	{
		if (_started)
		{
			return;
		}
		_started = true;
		ThreadPool.scheduleAtFixedRate(this::tick, START_DELAY_MS, TICK_MS);
	}

	public static boolean enabled()
	{
		return FakePlayersConfig.FAKE_PLAYERS_ENABLED && FakePlayersConfig.PHANTOM_ENCOUNTERS_ENABLED && PhantomPvpManager.pvpEnabled();
	}

	private void tick()
	{
		if (!enabled())
		{
			_nextAt.clear();
			return;
		}
		try
		{
			final long now = System.currentTimeMillis();
			final PhantomManager phantoms = PhantomManager.getInstance();
			int active = phantoms.activeEncounterCount();
			for (Player player : World.getInstance().getPlayers())
			{
				if (phantoms.isPhantom(player) || !eligible(player))
				{
					continue;
				}
				final int oid = player.getObjectId();
				final long[] due = _nextAt.computeIfAbsent(oid, k -> new long[Tier.values().length]);
				int pick = -1;
				for (int i = 0; i < due.length; i++)
				{
					if ((FakePlayersConfig.PHANTOM_ENCOUNTER_MAX_MINUTES[i] <= 0) || (player.getLevel() < FakePlayersConfig.PHANTOM_ENCOUNTER_MIN_LEVEL[i]))
					{
						due[i] = 0; // off, or not unlocked yet
						continue;
					}
					if (due[i] == 0)
					{
						due[i] = now + nextDelay(i); // first eligibility: start this kind's clock, no instant ambush
						continue;
					}
					if (now >= due[i])
					{
						pick = i; // the rarer kind wins when several are due
					}
				}
				if ((pick < 0) || phantoms.hasEncounterFor(player))
				{
					continue;
				}
				if (active >= FakePlayersConfig.PHANTOM_ENCOUNTER_MAX_ACTIVE)
				{
					due[pick] = now + RETRY_MS;
					continue;
				}
				if (trigger(player, phantoms, Tier.values()[pick]))
				{
					active++;
					due[pick] = now + nextDelay(pick);
					final long quiet = now + (FakePlayersConfig.PHANTOM_ENCOUNTER_GAP_MINUTES * 60_000L);
					for (int i = 0; i < due.length; i++)
					{
						if ((due[i] != 0) && (due[i] < quiet))
						{
							due[i] = quiet; // nothing else starts right behind it
						}
					}
				}
				else
				{
					due[pick] = now + RETRY_MS;
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.warning(getClass().getSimpleName() + ": tick error: " + e.getMessage());
		}
	}

	private static long nextDelay(int kind)
	{
		return PhantomEncounterRules.delayMs(FakePlayersConfig.PHANTOM_ENCOUNTER_MIN_MINUTES[kind] * 60_000L, FakePlayersConfig.PHANTOM_ENCOUNTER_MAX_MINUTES[kind] * 60_000L, Rnd.get(1000));
	}

	/** In the open field and free to be bothered: not in town, a duel, a store, an instance, the Olympiad, or a siege. */
	private static boolean eligible(Player player)
	{
		if (!player.isOnline() || player.isDead() || player.isInOlympiadMode() || player.isInDuel() || player.isInStoreMode() || (player.getInstanceId() != 0))
		{
			return false;
		}
		if (player.isInsideZone(ZoneId.PEACE) || player.isInsideZone(ZoneId.NO_PVP) || player.isInsideZone(ZoneId.SIEGE))
		{
			return false;
		}
		return (player.getLevel() >= FakePlayersConfig.PHANTOM_ENCOUNTER_MIN_PLAYER_LEVEL);
	}

	private boolean trigger(Player player, PhantomManager phantoms, Tier tier)
	{
		final int partySize = (player.getParty() == null) ? 1 : player.getParty().getMemberCount();
		final int size = PhantomEncounterRules.groupSize(tier, partySize, FakePlayersConfig.PHANTOM_ENCOUNTER_HORSEMEN_MIN_SIZE, FakePlayersConfig.PHANTOM_ENCOUNTER_MAX_ACTORS);
		final int index = tier.ordinal();
		final PhantomEncounterRules.EncounterGroup group = new PhantomEncounterRules.EncounterGroup(size);
		final double baseAngle = Rnd.nextDouble() * Math.PI * 2; // the group arrives from one general direction
		final String pkerName = FakePlayersConfig.PHANTOM_ENCOUNTER_PKER_NAME;
		int spawned = 0;
		Player first = null;
		for (int i = 0; i < size; i++)
		{
			final Location where = pickSpawn(player, baseAngle);
			if (where == null)
			{
				continue;
			}
			final int level = PhantomEncounterRules.levelFor(player.getLevel(), FakePlayersConfig.PHANTOM_ENCOUNTER_LEVEL_MIN[index], FakePlayersConfig.PHANTOM_ENCOUNTER_LEVEL_MAX[index], Rnd.get(1000));
			final int enchant = PhantomEncounterRules.enchantIn(FakePlayersConfig.PHANTOM_ENCOUNTER_ENCHANT_MIN[index], FakePlayersConfig.PHANTOM_ENCOUNTER_ENCHANT_MAX[index], Rnd.get(1000));
			final PartyRole role = ROLES[Rnd.get(ROLES.length)];
			final Player actor = phantoms.spawnEncounterActor(player, where, level, role, tier, enchant, group, (tier == Tier.PKER) && !pkerName.isEmpty() ? pkerName : null);
			if (actor == null)
			{
				continue;
			}
			if (actor.isInsideZone(ZoneId.PEACE))
			{
				phantoms.despawnRecruit(actor); // landed in a safe zone: not part of the encounter
				continue;
			}
			spawned++;
			if (first == null)
			{
				first = actor;
			}
		}
		if (spawned == 0)
		{
			return false;
		}
		if (spawned < size)
		{
			group.shrinkTo(spawned); // some did not fit: the group is as big as what actually spawned
		}
		LOGGER.info(getClass().getSimpleName() + ": " + tier + " encounter for " + player.getName() + " (lvl " + player.getLevel() + ", party of " + partySize + "): " + spawned + " phantom(s), first " + first.getName() + " lvl " + first.getLevel() + ".");
		return true;
	}

	/** A walkable point 650-900 units from the player, near {@code baseAngle}, reachable on foot, or {@code null}. */
	private static Location pickSpawn(Player player, double baseAngle)
	{
		for (int attempt = 0; attempt < 8; attempt++)
		{
			final double angle = baseAngle + ((Rnd.nextDouble() - 0.5) * 1.0);
			final int distance = Rnd.get(650, 901);
			final int x = player.getX() + (int) (Math.cos(angle) * distance);
			final int y = player.getY() + (int) (Math.sin(angle) * distance);
			final int z = GeoEngine.getInstance().getHeight(x, y, player.getZ());
			if (Math.abs(z - player.getZ()) > 250)
			{
				continue; // a cliff or another level: it could not walk up
			}
			if (GeoEngine.getInstance().canMoveToTarget(x, y, z, player.getX(), player.getY(), player.getZ(), player.getInstanceId()))
			{
				return new Location(x, y, z);
			}
		}
		return null;
	}

	public static PhantomEncounterManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PhantomEncounterManager INSTANCE = new PhantomEncounterManager();
	}
}
