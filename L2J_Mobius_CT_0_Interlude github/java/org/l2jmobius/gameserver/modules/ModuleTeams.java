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

	/** Puts a real player on a team. They keep their own gear and skills. */
	public void join(Player player, boolean blue)
	{
		if (player != null)
		{
			player.setTeam(blue ? Team.BLUE : Team.RED);
			player.setOnEvent(true);
		}
	}

	/** Takes a real player off their team. */
	public void leave(Player player)
	{
		if (player != null)
		{
			player.setOnEvent(false);
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
