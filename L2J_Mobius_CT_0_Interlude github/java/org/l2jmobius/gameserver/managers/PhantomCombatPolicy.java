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
package org.l2jmobius.gameserver.managers;

/** Role-aware baseline preferences. Scores are policy hints, not estimates of retail damage. */
public final class PhantomCombatPolicy
{
	public enum Context
	{
		SOLO_PVE, PARTY_PVE_SAFE, PARTY_PVE_DANGER, RAID, PVP
	}

	public enum Availability
	{
		READY_NOW, APPROACHABLE, UNAVAILABLE
	}

	public static Context context(boolean pvp, boolean raid, boolean party, boolean danger)
	{
		return pvp ? Context.PVP : raid ? Context.RAID : !party ? Context.SOLO_PVE : danger ? Context.PARTY_PVE_DANGER : Context.PARTY_PVE_SAFE;
	}

	public static Availability availability(boolean feasible, boolean positioned, boolean movable)
	{
		return !feasible ? Availability.UNAVAILABLE : positioned ? Availability.READY_NOW : movable ? Availability.APPROACHABLE : Availability.UNAVAILABLE;
	}

	/** Authored rotations have class semantics; only a safe party archer deliberately conserves them. */
	public static boolean authoredRotation(String role, Context context, int range, boolean magic)
	{
		return !"ARCHER".equals(role) || ((range >= 400) && !magic && (context != Context.PARTY_PVE_SAFE));
	}

	public static boolean suppressSetup(boolean economic, Context context, boolean pressure, boolean durable)
	{
		return !economic && (context != Context.PVP) && (context != Context.PARTY_PVE_DANGER) && !pressure && !durable;
	}

	/** The native aura is permitted only for a healthy actor with a validated existing pack. */
	public static boolean provokePack(int hpPercent, int safePack)
	{
		return (hpPercent >= 70) && (safePack >= 3);
	}

	public static boolean protectedTankPack(int pack, int healthyTankHeld)
	{
		return (pack > 0) && (healthyTankHeld == pack);
	}

	/** Pack size alone does not make a healthy physical frontliner lose control. */
	public static boolean partyVictimDanger(boolean fragile, int hpPercent, boolean controlled)
	{
		return fragile || (hpPercent < 50) || !controlled;
	}

	/** Only obvious one-hit waste is predicted for authored physical attacks. */
	public static boolean obviousWaste(double targetHp, double normalHit, boolean inWeaponRange, boolean overhit, boolean tactical)
	{
		return !tactical && !overhit && inWeaponRange && (targetHp > 0) && (normalHit > 0) && (targetHp <= normalHit);
	}

	private PhantomCombatPolicy()
	{
	}

	public static int priority(String use)
	{
		return switch (use)
		{
			case "PANIC" -> 1000;
			case "LIMIT" -> 900;
			case "OPENER" -> 800;
			case "STANCE" -> 750;
			case "CONTROL" -> 700;
			case "DEBUFF" -> 650;
			case "AOE" -> 600;
			default -> 100;
		};
	}

	public static boolean affordable(boolean caster, double mp, double maxMp, double cost, int reservePercent)
	{
		return (cost <= mp) && (caster || (((mp - cost) * 100 / Math.max(1, maxMp)) >= reservePercent));
	}

	public static boolean pressured(boolean underAttack, int hpPercent, int attackers, boolean fragile)
	{
		return underAttack && ((hpPercent < 70) || (fragile && (attackers >= 2)));
	}

	public static boolean worthwhile(String role, boolean caster, boolean pvp, boolean pressure, boolean durable, double cost, double maxMp, int range, boolean magic)
	{
		if ("ARCHER".equals(role) && ((range < 400) || magic))
		{
			return false; // a bow user never chases a generic contact attack
		}
		if (caster)
		{
			return magic;
		}
		if (pvp || pressure || durable)
		{
			return true;
		}
		// Ordinary farming favors the weapon. Bow skills are held for danger or a durable target; melee spending is bounded.
		return !"ARCHER".equals(role) && (cost <= Math.max(1, maxMp) * 0.04);
	}

	/** Compare useful damage and casting time, cap overkill, and account for the weapon damage a physical cast interrupts. */
	public static double damageScore(double damage, double targetHp, double cost, int castMs, double lostWeaponDamage, boolean caster, boolean sustain)
	{
		final double useful = Math.min(Math.max(0, damage), Math.max(0, targetHp));
		if (useful <= 0)
		{
			return Double.NEGATIVE_INFINITY;
		}
		final double net = useful - (caster ? 0 : Math.max(0, lostWeaponDamage));
		if (net <= 0)
		{
			return Double.NEGATIVE_INFINITY;
		}
		return (net * 1000 / Math.max(550, castMs)) + (useful / Math.max(1, cost)) + (sustain ? useful * 0.25 : 0);
	}
}
