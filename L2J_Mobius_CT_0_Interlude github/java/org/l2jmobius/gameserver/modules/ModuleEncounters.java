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
import org.l2jmobius.gameserver.managers.PhantomEncounterRules;
import org.l2jmobius.gameserver.managers.PhantomEncounterRules.Approach;
import org.l2jmobius.gameserver.managers.PhantomEncounterRules.EncounterGroup;
import org.l2jmobius.gameserver.managers.PhantomEncounterRules.Style;
import org.l2jmobius.gameserver.managers.PhantomManager;
import org.l2jmobius.gameserver.managers.PhantomManager.PartyRole;
import org.l2jmobius.gameserver.managers.PhantomPvpManager;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Player;

/**
 * The phantom encounter extension point, handed to a module through {@link ModuleContext#encounters()}. An encounter
 * sends one phantom, or a group, after one player: the actors walk up, fight that player once, and then leave or die.
 * <p>
 * The platform owns the mechanics: spawning a fully geared actor, walking it to the player, the fight itself, the
 * hostile rule that lets it attack a player who is not flagged, and clearing everything up afterwards. A module owns the
 * policy: when to send one, how many, how strong, what they say, and what a win is worth. The platform has no timers,
 * kinds or rewards of its own.
 * <p>
 * It is inert until a module calls it, and the actors it makes come from the fake player system, so it needs fake
 * players and phantom PvP to be on ({@link #available()}).
 */
public class ModuleEncounters
{
	/** Told when the player wins. */
	public interface Listener
	{
		/**
		 * Every actor of the encounter is dead.
		 * @param victim the player the encounter came for
		 * @param lastActor the actor that died last
		 */
		void onGroupDefeated(Player victim, Player lastActor);
	}

	/** How an encounter behaves, see {@link Style}. */
	public static Style style(Approach approach, int approachSeconds, int fightSeconds, int warnSeconds, int stillSeconds, String[] askLines, String[] winLines)
	{
		return new Style(approach, approachSeconds, fightSeconds, warnSeconds, stillSeconds, askLines, winLines);
	}

	/** As above, with trash talk as the fight begins ({@code strikeLines}) and whining from the first to fall ({@code defeatLines}). */
	public static Style style(Approach approach, int approachSeconds, int fightSeconds, int warnSeconds, int stillSeconds, String[] askLines, String[] winLines, String[] strikeLines, String[] defeatLines)
	{
		return new Style(approach, approachSeconds, fightSeconds, warnSeconds, stillSeconds, askLines, winLines, strikeLines, defeatLines);
	}

	/** One encounter in progress: the actors share it. */
	public static final class Group
	{
		private final EncounterGroup _group;

		Group(EncounterGroup group)
		{
			_group = group;
		}

		/** The group turned out smaller than planned (some actors could not be placed). Call once, after spawning. */
		public void shrinkTo(int size)
		{
			_group.shrinkTo(size);
		}
	}

	ModuleEncounters()
	{
	}

	/** @return {@code true} if encounters can run on this server (fake players and phantom PvP are both on) */
	public boolean available()
	{
		return FakePlayersConfig.FAKE_PLAYERS_ENABLED && PhantomPvpManager.pvpEnabled();
	}

	/**
	 * Starts an encounter. Spawn its actors with {@link #spawn}.
	 * @param size how many actors are planned
	 * @param style how they behave
	 * @param listener told when the player wins (may be {@code null})
	 * @return the encounter
	 */
	public Group begin(int size, Style style, Listener listener)
	{
		final PhantomEncounterRules.Listener adapter = (listener == null) ? null : (victimId, actorId) ->
		{
			final Player victim = World.getInstance().getPlayer(victimId);
			final Player actor = World.getInstance().getPlayer(actorId);
			if (victim != null)
			{
				listener.onGroupDefeated(victim, actor);
			}
		};
		return new Group(new EncounterGroup(size, style, adapter));
	}

	/**
	 * Spawns one actor of an encounter, fully geared, already pointed at the player.
	 * @param victim the player it comes for
	 * @param group the encounter it belongs to
	 * @param where where it appears
	 * @param level the actor's level
	 * @param role its class role
	 * @param enchant the enchant on its weapon and armor
	 * @param fixedName its name, or {@code null} for a random one
	 * @return the actor, or {@code null} if it could not be spawned
	 */
	public Player spawn(Player victim, Group group, Location where, int level, PartyRole role, int enchant, String fixedName)
	{
		return spawn(victim, group, where, level, role, enchant, fixedName, 0);
	}

	/** As above, pinned to one class id (for example 113 for a Titan); 0 keeps the role's random class. */
	public Player spawn(Player victim, Group group, Location where, int level, PartyRole role, int enchant, String fixedName, int classId)
	{
		return spawn(victim, group, where, level, role, enchant, fixedName, classId, 0, false, false);
	}

	/** As above; {@code escapeChance} (percent, 0 = none) gives the actor a Blessed Scroll of Escape; at 10% HP (or, with {@code escapeOnRout}, once three quarters of its group is down and your side outnumbers what is left) it may curse you out; {@code cpPotions} also gives and uses CP potions (otherwise only healing and mana potions), read it and vanish as if defeated. */
	public Player spawn(Player victim, Group group, Location where, int level, PartyRole role, int enchant, String fixedName, int classId, int escapeChance, boolean escapeOnRout, boolean cpPotions)
	{
		if ((group == null) || !available())
		{
			return null;
		}
		return PhantomManager.getInstance().spawnEncounterActor(victim, where, level, role, enchant, group._group, fixedName, classId, escapeChance, escapeOnRout, cpPotions);
	}

	/** Removes an actor that was just spawned but turned out to be somewhere it must not be (for example a safe zone). */
	public void discard(Player actor)
	{
		if (actor != null)
		{
			PhantomManager.getInstance().despawnRecruit(actor);
		}
	}

	/** @return {@code true} if an actor is currently out for this player */
	public boolean isTargeted(Player victim)
	{
		return PhantomManager.getInstance().hasEncounterFor(victim);
	}

	/** @return how many encounters are running now (a group counts once) */
	public int activeCount()
	{
		return PhantomManager.getInstance().activeEncounterCount();
	}

	/** @return {@code true} if the player is a fake player (an encounter must never target one) */
	public boolean isPhantom(Player player)
	{
		return PhantomManager.getInstance().isPhantom(player);
	}
}
