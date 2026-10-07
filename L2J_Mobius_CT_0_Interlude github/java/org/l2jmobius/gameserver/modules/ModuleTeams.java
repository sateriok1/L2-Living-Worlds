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

import org.l2jmobius.gameserver.config.custom.FakePlayersConfig;
import org.l2jmobius.gameserver.managers.PhantomManager;
import org.l2jmobius.gameserver.managers.PhantomManager.PartyRole;
import org.l2jmobius.gameserver.managers.PhantomPvpManager;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.creature.Team;

/**
 * The team-event extension point, handed to a module through {@link ModuleContext#teams()}. It puts players and
 * phantoms on a blue or red team using the server's own event rules: team members cannot hurt each other, enemies
 * can, a team circle shows over every head, nobody pays a death penalty and parties cannot cross teams.
 * <p>
 * A team fighter is a geared phantom that hunts the nearest living enemy in sight and walks to a rally point when
 * none is. The platform owns that behavior; a module owns the rules: who plays, scoring, respawns and prizes.
 */
public class ModuleTeams
{
	/** Players held by {@link #lock}, so {@link #leave} only undoes a lock this surface applied (FPC-245). */
	private final java.util.Set<Integer> _locked = java.util.concurrent.ConcurrentHashMap.newKeySet();

	ModuleTeams()
	{
	}

	/** @return {@code true} if team fighters can run on this server (fake players and phantom PvP are on) */
	public boolean available()
	{
		return FakePlayersConfig.FAKE_PLAYERS_ENABLED && PhantomPvpManager.pvpEnabled();
	}

	/**
	 * Puts a team fighter at a spot.
	 * @param blue {@code true} for the blue team, {@code false} for red
	 * @param where where it appears
	 * @param rally where it heads when no enemy is in sight, or {@code null} to stay put
	 * @param level its level
	 * @param role the archetype it is geared as
	 * @param enchant the +level on its weapon and armor
	 * @param name its name, or {@code null} for a random one
	 * @param classId the exact class, or 0 or less for any in the role
	 * @return the fighter, or {@code null} if it could not be made
	 */
	public Player spawn(boolean blue, Location where, Location rally, int level, PartyRole role, int enchant, String name, int classId)
	{
		return PhantomManager.getInstance().spawnTeamFighter(blue ? Team.BLUE : Team.RED, where, rally, level, role, enchant, name, classId);
	}

	/** Puts a free-for-all fighter at a spot: on no team, an enemy of every other solo player. Same arguments as {@link #spawn}. */
	public Player spawnSolo(Location where, Location rally, int level, PartyRole role, int enchant, String name, int classId)
	{
		return PhantomManager.getInstance().spawnTeamFighter(Team.NONE, true, where, rally, level, role, enchant, name, classId);
	}

	/** Puts a real player into a free-for-all: on no team, an enemy of every other solo player. */
	public void joinSolo(Player player)
	{
		if (player != null)
		{
			player.setTeam(Team.NONE);
			player.setOnSoloEvent(true);
			player.setOnEvent(true);
		}
	}

	/** Strips a real player's buffs and gives the ones a spawned fighter arrives with (full buff set plus the class's own self-buffs). */
	public void buffLikeFighter(Player player)
	{
		PhantomManager.getInstance().buffLikeFighter(player);
	}

	/** Locks a real player in place and makes them untouchable (for a countdown), or frees them. */
	public void lock(Player player, boolean locked)
	{
		if (player != null)
		{
			player.setImmobilized(locked);
			player.setInvul(locked);
			if (locked)
			{
				_locked.add(player.getObjectId());
			}
			else
			{
				_locked.remove(player.getObjectId());
			}
		}
	}

	/** Brings a player or fighter, and its servitor, to full HP, MP and CP. */
	public void fullHeal(Player player)
	{
		PhantomManager.getInstance().fullHeal(player);
	}

	/**
	 * Puts these players in one party, the first as leader. Any party they were in is left first. A party holds nine at
	 * most; extras are ignored.
	 */
	public void formParty(java.util.List<Player> members)
	{
		if ((members == null) || members.isEmpty())
		{
			return;
		}
		try
		{
			final Player leader = members.get(0);
			for (Player p : members)
			{
				if ((p != null) && p.isInParty())
				{
					p.leaveParty();
				}
			}
			if (members.size() < 2)
			{
				return;
			}
			leader.setParty(new org.l2jmobius.gameserver.model.groups.Party(leader, org.l2jmobius.gameserver.model.groups.PartyDistributionType.FINDERS_KEEPERS));
			for (int i = 1; (i < members.size()) && (i < 9); i++)
			{
				final Player p = members.get(i);
				if ((p != null) && (p != leader))
				{
					p.joinParty(leader.getParty());
				}
			}
		}
		catch (Exception e)
		{
			java.util.logging.Logger.getLogger("ModuleTeams").warning("ModuleTeams.formParty failed: " + e);
		}
	}

	/** Breaks up the party this player is in. */
	public void disbandParty(Player member)
	{
		if ((member != null) && member.isInParty())
		{
			member.getParty().disbandParty();
		}
	}

	/** Puts a real player on a team. They keep their own gear and skills. */
	public void join(Player player, boolean blue)
	{
		if (player != null)
		{
			player.setTeam(blue ? Team.BLUE : Team.RED);
			player.setOnEvent(true);
		}
	}

	/** Takes a real player off their team, and frees them if {@link #lock} still holds them. */
	public void leave(Player player)
	{
		if (player != null)
		{
			if (_locked.remove(player.getObjectId()))
			{
				player.setImmobilized(false);
				player.setInvul(false);
			}
			player.setOnEvent(false);
			player.setOnSoloEvent(false);
			player.setTeam(Team.NONE);
		}
	}

	/** @return {@code true} for blue, {@code false} for red, or {@code null} if the player is on no team */
	public Boolean teamOf(Player player)
	{
		if ((player == null) || (player.getTeam() == Team.NONE))
		{
			return null;
		}
		return player.getTeam() == Team.BLUE;
	}

	/** @return {@code true} if this player is a team fighter made through {@link #spawn} */
	public boolean isFighter(Player player)
	{
		return PhantomManager.getInstance().isTeamFighter(player);
	}

	/**
	 * Holds or releases a fighter. A held fighter buffs but does not move, fight or take damage. A new fighter starts
	 * held, so a module can set a whole event up and release everyone at once.
	 */
	public void hold(Player fighter, boolean hold)
	{
		PhantomManager.getInstance().holdTeamFighter(fighter, hold);
	}

	/** Changes where a fighter heads when no enemy is in sight. */
	public void setRally(Player fighter, Location rally)
	{
		PhantomManager.getInstance().setTeamRally(fighter, rally);
	}

	/** Brings a fighter back at full strength at a spot, on the same team. */
	public void revive(Player fighter, Location where)
	{
		PhantomManager.getInstance().reviveTeamFighter(fighter, where);
	}

	/** Takes a fighter off its team and removes it. */
	public void discard(Player fighter)
	{
		PhantomManager.getInstance().discardTeamFighter(fighter);
	}

	/** @return {@code true} if the player is a fake player */
	public boolean isPhantom(Player player)
	{
		return PhantomManager.getInstance().isPhantom(player);
	}
}
