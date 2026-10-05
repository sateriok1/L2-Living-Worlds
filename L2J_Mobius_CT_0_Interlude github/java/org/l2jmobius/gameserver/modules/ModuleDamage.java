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

import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.skill.Skill;

/**
 * The damage extension point, handed to a module through {@link ModuleContext#damage()}. Every hit that lowers a
 * creature's HP (an auto attack, a skill, damage over time, a reflect) is reported once with the damage it did, who
 * did it, and the skill if there was one. The stock {@code OnCreatureDamageDealt} event only fires for auto attacks,
 * which is why a damage meter needs this.
 * <p>
 * The reported damage is what the hit was worth after the platform's own rules, capped at what the target had left
 * (HP, plus CP for a player), so the last hit on a boss does not count overkill. Nothing is reported for a target that
 * is already dead or cannot be hurt. Listeners run on the thread that dealt the damage and must be quick.
 */
public class ModuleDamage
{
	private static final Logger LOGGER = Logger.getLogger(ModuleDamage.class.getName());
	private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

	/** Told about every hit that lowers HP. */
	public interface Listener
	{
		/**
		 * @param attacker who dealt it (a servitor or pet is reported as itself; ask it for its owner)
		 * @param target who took it
		 * @param damage the damage that counted, never more than the target had left
		 * @param skill the skill that did it, or {@code null} for an auto attack or a reflect
		 * @param damageOverTime {@code true} for a damage-over-time tick
		 */
		void onDamage(Creature attacker, Creature target, double damage, Skill skill, boolean damageOverTime);
	}

	private static final List<HealListener> HEAL_LISTENERS = new CopyOnWriteArrayList<>();

	/** Told about every instant heal that restored HP. */
	public interface HealListener
	{
		/**
		 * @param healer who cast it (may be the target itself)
		 * @param target who was healed
		 * @param amount the HP actually restored, never more than the target was missing
		 * @param skill the skill that did it
		 */
		void onHeal(Creature healer, Creature target, double amount, Skill skill);
	}

	ModuleDamage()
	{
	}

	/** @return {@code true} if anything is listening for heals */
	public static boolean healActive()
	{
		return !HEAL_LISTENERS.isEmpty();
	}

	/** Called by the core when a skill's instant effects restored HP. */
	public static void healed(Creature healer, Creature target, double amount, Skill skill)
	{
		for (HealListener listener : HEAL_LISTENERS)
		{
			try
			{
				listener.onHeal(healer, target, amount, skill);
			}
			catch (Exception e)
			{
				LOGGER.warning("Heal listener failed: " + e);
			}
		}
	}

	/** Be told about every instant heal (a heal skill, not regeneration or heal-over-time). */
	public void addHealListener(HealListener listener)
	{
		HEAL_LISTENERS.add(listener);
	}

	/** @return {@code true} if anything is listening, so the core can skip the work when no module is */
	public static boolean active()
	{
		return !LISTENERS.isEmpty();
	}

	/** Called by the core for each hit that lowers HP. */
	public static void dealt(Creature attacker, Creature target, double damage, Skill skill, boolean damageOverTime)
	{
		for (Listener listener : LISTENERS)
		{
			try
			{
				listener.onDamage(attacker, target, damage, skill, damageOverTime);
			}
			catch (Exception e)
			{
				LOGGER.warning("Damage listener failed: " + e);
			}
		}
	}

	/** Be told about every hit that lowers HP. */
	public void addListener(Listener listener)
	{
		LISTENERS.add(listener);
	}
}
