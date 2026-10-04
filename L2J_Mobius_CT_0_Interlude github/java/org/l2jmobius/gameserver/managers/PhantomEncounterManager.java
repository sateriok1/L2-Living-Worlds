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
 * timer is due, sends one encounter: a single phantom that walks up and fights once (see {@link Tier}). The actor's
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

	private final Map<Integer, Long> _nextAt = new ConcurrentHashMap<>();
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
				final Long due = _nextAt.get(oid);
				if (due == null)
				{
					_nextAt.put(oid, now + nextDelay()); // first sighting: start the clock, no instant ambush on login
					continue;
				}
				if ((now < due) || phantoms.hasEncounterFor(player))
				{
					continue;
				}
				if (active >= FakePlayersConfig.PHANTOM_ENCOUNTER_MAX_ACTIVE)
				{
					_nextAt.put(oid, now + RETRY_MS);
					continue;
				}
				if (trigger(player, phantoms))
				{
					active++;
					_nextAt.put(oid, now + nextDelay());
				}
				else
				{
					_nextAt.put(oid, now + RETRY_MS);
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.warning(getClass().getSimpleName() + ": tick error: " + e.getMessage());
		}
	}

	private static long nextDelay()
	{
		return PhantomEncounterRules.delayMs(FakePlayersConfig.PHANTOM_ENCOUNTER_MIN_MINUTES * 60_000L, FakePlayersConfig.PHANTOM_ENCOUNTER_MAX_MINUTES * 60_000L, Rnd.get(1000));
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
		return (player.getLevel() >= FakePlayersConfig.PHANTOM_ENCOUNTER_MIN_PLAYER_LEVEL) && !player.isNewbie();
	}

	private boolean trigger(Player player, PhantomManager phantoms)
	{
		final int partySize = (player.getParty() == null) ? 1 : player.getParty().getMemberCount();
		final int[] weights =
		{
			PhantomEncounterRules.weightFor(Tier.WIMP, FakePlayersConfig.PHANTOM_ENCOUNTER_WIMP_WEIGHT, player.getLevel(), partySize, FakePlayersConfig.PHANTOM_ENCOUNTER_MIN_PLAYER_LEVEL, FakePlayersConfig.PHANTOM_ENCOUNTER_MAX_PARTY_FOR_SOLO),
			PhantomEncounterRules.weightFor(Tier.NORMIE, FakePlayersConfig.PHANTOM_ENCOUNTER_NORMIE_WEIGHT, player.getLevel(), partySize, FakePlayersConfig.PHANTOM_ENCOUNTER_MIN_PLAYER_LEVEL, FakePlayersConfig.PHANTOM_ENCOUNTER_MAX_PARTY_FOR_SOLO),
			0, // PKER: stage 2
			0 // HORSEMEN: stage 2
		};
		int total = 0;
		for (int w : weights)
		{
			total += w;
		}
		final Tier tier = (total <= 0) ? null : PhantomEncounterRules.pickTier(weights, Rnd.get(total));
		if (tier == null)
		{
			return false;
		}
		final Location where = pickSpawn(player);
		if (where == null)
		{
			return false;
		}
		final int level = PhantomEncounterRules.actorLevel(tier, player.getLevel(), Rnd.get(1000));
		final PartyRole role = ROLES[Rnd.get(ROLES.length)];
		final Player actor = phantoms.spawnEncounterActor(player, where, level, role, tier, PhantomEncounterRules.gradeShift(tier), PhantomEncounterRules.enchantFor(tier, Rnd.get(1000)));
		if (actor == null)
		{
			return false;
		}
		if (actor.isInsideZone(ZoneId.PEACE))
		{
			phantoms.despawnRecruit(actor); // landed in a safe zone: not an encounter
			return false;
		}
		LOGGER.info(getClass().getSimpleName() + ": " + tier + " encounter for " + player.getName() + " (lvl " + player.getLevel() + "): " + actor.getName() + " lvl " + actor.getLevel() + " " + role + ".");
		return true;
	}

	/** A walkable point 650-900 units from the player, reachable on foot, or {@code null} if none was found. */
	private static Location pickSpawn(Player player)
	{
		for (int attempt = 0; attempt < 8; attempt++)
		{
			final double angle = Rnd.nextDouble() * Math.PI * 2;
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
