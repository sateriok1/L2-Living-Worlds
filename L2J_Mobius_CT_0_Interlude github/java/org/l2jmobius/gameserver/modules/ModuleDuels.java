/*
 * Copyright (c) 2013 L2jMobius
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR
 * IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package org.l2jmobius.gameserver.modules;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.managers.PhantomManager;
import org.l2jmobius.gameserver.managers.PhantomManager.PartyRole;
import org.l2jmobius.gameserver.managers.PhantomPvpManager;
import org.l2jmobius.gameserver.config.custom.FakePlayersConfig;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.player.DuelResult;
import org.l2jmobius.gameserver.model.zone.ZoneType;

/**
 * The phantom duel extension point, handed to a module through {@link ModuleContext#duels()}. A duelist is a fully
 * geared phantom that stays where it was put, takes duels from anyone who asks, and can challenge a player (or another
 * duelist) to the server's own duel system. The duel itself, with its countdown, no-death rule and HP restore, is
 * the stock one.
 * <p>
 * The platform owns the mechanics: spawning the duelist, walking it up, asking, and fighting. A module owns the
 * policy: where duelists stand, which ones, when they challenge, and what a win is worth. Nothing happens until a
 * module calls it.
 */
public class ModuleDuels
{
	private static final Logger LOGGER = Logger.getLogger(ModuleDuels.class.getName());
	private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
	private static final List<ZoneType> ARENAS = new CopyOnWriteArrayList<>();

	/** Told when a one-on-one duel ends. */
	public interface Listener
	{
		/**
		 * @param first the player who challenged
		 * @param second the player who was challenged
		 * @param result how it ended
		 */
		void onDuelEnd(Player first, Player second, DuelResult result);
	}

	ModuleDuels()
	{
	}

	/** Called by the duel system when a one-on-one duel has ended. */
	public static void duelEnded(Player first, Player second, DuelResult result)
	{
		for (Listener listener : LISTENERS)
		{
			try
			{
				listener.onDuelEnd(first, second, result);
			}
			catch (Exception e)
			{
				LOGGER.warning("Duel listener failed: " + e.getMessage());
			}
		}
	}

	/** @return {@code true} if the duel winner is {@code first}, {@code false} if {@code second}, or {@code null} for a tie or a cancelled duel */
	public static Boolean firstWon(DuelResult result)
	{
		switch (result)
		{
			case TEAM_1_WIN:
			case TEAM_2_SURRENDER:
			{
				return Boolean.TRUE;
			}
			case TEAM_2_WIN:
			case TEAM_1_SURRENDER:
			{
				return Boolean.FALSE;
			}
			default:
			{
				return null;
			}
		}
	}

	/** @return {@code true} if the player stands in a PvP zone that a module has opened to duels, where the stock rules would refuse them */
	public static boolean isDuelArena(Player player)
	{
		for (ZoneType arena : ARENAS)
		{
			if (arena.isCharacterInZone(player))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Opens a PvP zone to duels, which the stock rules refuse everywhere in a PvP zone. Players duel there under the
	 * same rules as anywhere else (countdown, nobody dies, HP restored). Applies for the whole run.
	 */
	public void openArena(ZoneType zone)
	{
		if ((zone != null) && !ARENAS.contains(zone))
		{
			ARENAS.add(zone);
		}
	}

	/** Be told when a one-on-one duel ends. */
	public void addListener(Listener listener)
	{
		LISTENERS.add(listener);
	}

	/** @return {@code true} if duelists can run on this server (fake players, phantom PvP and phantom duels are all on) */
	public boolean available()
	{
		return FakePlayersConfig.FAKE_PLAYERS_ENABLED && PhantomPvpManager.pvpEnabled() && PhantomPvpManager.duelsEnabled();
	}

	/**
	 * Puts a duelist at a spot.
	 * @param where where it stands
	 * @param level its level
	 * @param role the archetype it is geared as
	 * @param enchant the +level on its weapon and armor
	 * @param name its name, or {@code null} for a random one
	 * @param classId the exact class, or 0 or less for any in the role (resolved for its level, like a named recruit)
	 * @return the duelist, or {@code null} if it could not be made
	 */
	public Player spawn(Location where, int level, PartyRole role, int enchant, String name, int classId)
	{
		return PhantomManager.getInstance().spawnArenaDuelist(where, level, role, enchant, name, classId);
	}

	/**
	 * Has a duelist walk up to a player (or another duelist) and challenge them to a duel.
	 * @return {@code true} if it set off, {@code false} if either side is busy or may not duel right now
	 */
	public boolean challenge(Player duelist, Player target)
	{
		return PhantomManager.getInstance().challengeToDuel(duelist, target);
	}

	/** @return {@code true} if this duelist is free to be sent: alive, not in a duel, not on its way to one */
	public boolean isFree(Player duelist)
	{
		return PhantomManager.getInstance().isArenaDuelistFree(duelist);
	}

	/** @return {@code true} if this player is a duelist made through {@link #spawn} */
	public boolean isDuelist(Player player)
	{
		return PhantomManager.getInstance().isArenaDuelist(player);
	}

	/** Removes a duelist. */
	public void discard(Player duelist)
	{
		if (duelist != null)
		{
			PhantomManager.getInstance().despawnRecruit(duelist);
		}
	}

	/** @return {@code true} if the player is a fake player */
	public boolean isPhantom(Player player)
	{
		return PhantomManager.getInstance().isPhantom(player);
	}
}
