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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.skill.Skill;

/**
 * The damage extension point, handed to a module through {@link ModuleContext#damage()}. Every hit that lowers a
 * creature's HP (an auto attack, a skill, damage over time, a reflect) is reported once with the damage it did, who
 * did it, and the skill if there was one. The stock {@code OnCreatureDamageDealt} event only fires for auto attacks,
 * which is why a damage meter needs this.
 * <p>
 * Damage is the HP and CP actually lost during native damage handling. Redirected damage is reported on its recipient,
 * rejected hits and MP absorption contribute nothing, and overkill does not count. Healing covers successful instant
 * HEAL effects of non-static skills, including SELF; static/item heals and recovery herbs are excluded.
 * Listeners run after committed changes on the native thread and must be quick. Lethal damage is published before
 * the native death event, so a module's death recap includes the finishing hit.
 */
public class ModuleDamage
{
	private static final Logger LOGGER = Logger.getLogger(ModuleDamage.class.getName());
	private record DamageRegistration(ModuleHandles owner, Listener listener) { }
	private record HealRegistration(ModuleHandles owner, HealListener listener) { }
	private static final List<DamageRegistration> LISTENERS = new CopyOnWriteArrayList<>();
	private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
	private final ModuleHandles _handles;

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

	private static final List<HealRegistration> HEAL_LISTENERS = new CopyOnWriteArrayList<>();

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

	ModuleDamage(ModuleHandles handles)
	{
		_handles = Objects.requireNonNull(handles);
	}

	/** @return {@code true} if anything is listening for heals */
	public static boolean healActive()
	{
		return !HEAL_LISTENERS.isEmpty();
	}

	/** Called by the core when a skill's instant effects restored HP. */
	public static void healed(Creature healer, Creature target, double amount, Skill skill)
	{
		for (HealRegistration registration : HEAL_LISTENERS)
		{
			try
			{
				registration.listener().onHeal(healer, target, amount, skill);
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, "Heal listener from module '" + registration.owner().getModuleId() + "' failed.", e);
			}
		}
	}

	/** Be told about every instant heal (a heal skill, not regeneration or heal-over-time). */
	public void addHealListener(HealListener listener)
	{
		HEAL_LISTENERS.add(new HealRegistration(_handles, Objects.requireNonNull(listener)));
		_handles.record("instant heal listener");
	}

	/** @return {@code true} if anything is listening, so the core can skip the work when no module is */
	public static boolean active()
	{
		return !LISTENERS.isEmpty();
	}

	/** Called by the core for each hit that lowers HP. */
	public static void dealt(Creature attacker, Creature target, double damage, Skill skill, boolean damageOverTime)
	{
		for (DamageRegistration registration : LISTENERS)
		{
			try
			{
				registration.listener().onDamage(attacker, target, damage, skill, damageOverTime);
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, "Damage listener from module '" + registration.owner().getModuleId() + "' failed.", e);
			}
		}
	}

	/** Be told about every hit that lowers HP. */
	public void addListener(Listener listener)
	{
		LISTENERS.add(new DamageRegistration(_handles, Objects.requireNonNull(listener)));
		_handles.record("damage listener");
	}

	/** Core-only: measure a damage operation without replacing any native status rule. */
	public static Scope captureDamage(Creature attacker, Creature target, Skill skill, boolean damageOverTime)
	{
		if (!active())
		{
			return null;
		}
		final Scope parent = CURRENT.get();
		final boolean reflection = (parent != null) && parent._reflection
			&& (parent._source == attacker) && (parent._target == target);
		// Native status transfers call reduceCurrentHp with a null skill; retain their originating hit's metadata.
		final boolean transfer = (skill == null) && (parent != null) && parent._recording && !parent._heal && !parent._reflection
			&& (parent._source == attacker) && (parent._target != target);
		return new Scope(attacker, target, reflection ? null : transfer ? parent._skill : skill,
			damageOverTime || (transfer && parent._damageOverTime), false, false);
	}

	/** Core-only: identify reflected damage for observers while retaining the original skill's native damage flags. */
	public static Scope captureReflection(Creature reflector, Creature target)
	{
		return active() ? new Scope(reflector, target, null, false, false, true) : null;
	}

	/** Core-only: isolate each instant effect; only regular HEAL effects receive healing credit. */
	public static Scope captureHeal(Creature healer, Creature target, Skill skill, boolean healEffect)
	{
		if (!healActive())
		{
			return null;
		}
		final boolean eligible = healEffect && !skill.isStatic() && !skill.isRecoveryHerb() && (skill.getReferenceItemId() == 0);
		return new Scope(eligible ? healer : null, target, skill, false, true, false);
	}

	/** Core-only: a synchronous death observer must see the finishing damage before it closes its score window. */
	public static void beforeDeath(Creature target)
	{
		if (active())
		{
			final Scope scope = CURRENT.get();
			if ((scope != null) && !scope._heal && (scope._target == target))
			{
				scope._recording = false;
				scope.publish();
			}
		}
	}

	/** Core-only: record the exact HP mutation under the native status monitor; never invoke listeners here. */
	public static void hpChanged(Creature target, double before, double after)
	{
		if (!active() && !healActive())
		{
			return;
		}
		final Scope scope = CURRENT.get();
		if (scope != null)
		{
			scope.record(target, scope._heal ? after - before : before - after);
		}
	}

	/** Core-only: CP lost to damage belongs to its actual recipient, including direct transfer reductions. */
	public static void cpChanged(Creature target, double before, double after)
	{
		if (!active())
		{
			return;
		}
		final Scope scope = CURRENT.get();
		if ((scope != null) && !scope._heal)
		{
			scope.record(target, before - after);
		}
	}

	/** A per-thread native operation. Nested transfers have their own attribution; callbacks run outside capture. */
	public static final class Scope implements AutoCloseable
	{
		private final Scope _parent;
		private final Creature _source;
		private final Creature _target;
		private final Skill _skill;
		private final boolean _damageOverTime;
		private final boolean _heal;
		private final boolean _reflection;
		private boolean _recording = true;
		private final Map<Creature, Double> _amounts = new LinkedHashMap<>();

		private Scope(Creature source, Creature target, Skill skill, boolean damageOverTime, boolean heal, boolean reflection)
		{
			_parent = CURRENT.get();
			_source = source;
			_target = target;
			_skill = skill;
			_damageOverTime = damageOverTime;
			_heal = heal;
			_reflection = reflection;
			CURRENT.set(this);
		}

		private void record(Creature target, double amount)
		{
			if (_recording && (_source != null) && (amount > 0) && Double.isFinite(amount) && (!_heal || (target == _target)))
			{
				_amounts.merge(target, amount, Double::sum);
			}
		}

		private void publish()
		{
			if (_amounts.isEmpty())
			{
				return;
			}
			final Map<Creature, Double> amounts = new LinkedHashMap<>(_amounts);
			_amounts.clear();
			// A listener can start another native operation. Its mutations must not leak into the parent hit.
			CURRENT.remove();
			try
			{
				for (Map.Entry<Creature, Double> entry : amounts.entrySet())
				{
					if (_heal)
					{
						healed(_source, entry.getKey(), entry.getValue(), _skill);
					}
					else
					{
						dealt(_source, entry.getKey(), entry.getValue(), _skill, _damageOverTime);
					}
				}
			}
			finally
			{
				CURRENT.set(this);
			}
		}

		@Override
		public void close()
		{
			try
			{
				publish();
			}
			finally
			{
				if (_parent != null)
				{
					CURRENT.set(_parent);
				}
				else
				{
					CURRENT.remove();
				}
			}
		}
	}
}
