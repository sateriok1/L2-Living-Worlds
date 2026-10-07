package org.l2jmobius.gameserver.managers;

import java.util.HashMap;
import java.util.Map;

/**
 * The class self-buffs an encounter phantom casts on itself before it fights (a Titan's Rage and Zealot, an archer's
 * Hawk Eye and Soul of Sagittarius, and so on). Casters have none worth casting; summoners get their servitor instead.
 * A skill the phantom has not learned, or cannot cast right now, is simply skipped. Edit the lists below to taste.
 */
public final class PhantomEncounterBuffs
{
	private static final int[] NONE = new int[0];
	private static final Map<Integer, int[]> BY_CLASS = new HashMap<>();

	static
	{
		BY_CLASS.put(88, new int[] { 297, 287, 78 }); // Duelist: Duelist Spirit, Lionheart, War Cry
		BY_CLASS.put(113, new int[] { 94, 287 }); // Titan: Rage, Lionheart
		BY_CLASS.put(114, new int[] { 425, 420, 443 }); // Grand Khavatari: Hawk Spirit Totem, Zealot, Force Barrier
		BY_CLASS.put(89, new int[] { 130, 287, 121 }); // Dreadnought: Thrill Fight, Lionheart, Battle Roar
		BY_CLASS.put(90, new int[] { 72, 82, 438 }); // Phoenix Knight: Iron Will, Majesty, Soul of the Phoenix
		BY_CLASS.put(91, new int[] { 72, 82, 439, 86 }); // Hell Knight: Iron Will, Majesty, Shield of Revenge, Reflect Damage
		BY_CLASS.put(99, new int[] { 123 }); // Eva's Templar: Spirit Barrier
		BY_CLASS.put(106, new int[] { 91 }); // Shillien Templar: Defense Aura
		BY_CLASS.put(92, new int[] { 99, 131, 303, 415, 416 }); // Sagittarius: Rapid Shot, Hawk Eye, Soul/Spirit/Blessing of Sagittarius
		BY_CLASS.put(102, new int[] { 413, 131, 303, 415, 416 }); // Moonlight Sentinel: Rapid Fire, Hawk Eye, the Sagittarius buffs
		BY_CLASS.put(109, new int[] { 414, 131, 303, 415 }); // Ghost Sentinel: Dead Eye, Hawk Eye, Soul/Spirit of Sagittarius
		BY_CLASS.put(93, new int[] { 357, 356 }); // Adventurer: Focus Power, Focus Chance
		BY_CLASS.put(101, new int[] { 355, 356, 410, 446 }); // Wind Rider: Focus Death, Focus Chance, Mortal Strike, Dodge
		BY_CLASS.put(108, new int[] { 355, 357, 410, 447 }); // Ghost Hunter: Focus Death, Focus Power, Mortal Strike, Counterattack
		// Encounter spawning resolves lower levels to their second class; learned-skill checks filter this list.
		for (int[] lineage : new int[][] { { 2, 88 }, { 3, 89 }, { 5, 90 }, { 6, 91 }, { 8, 93 }, { 9, 92 }, { 20, 99 }, { 23, 101 }, { 24, 102 }, { 33, 106 }, { 36, 108 }, { 37, 109 }, { 46, 113 }, { 48, 114 } })
		{
			BY_CLASS.put(lineage[0], BY_CLASS.get(lineage[1]));
		}
		BY_CLASS.put(1, new int[] { 297, 287, 78, 130, 121 }); // Warrior, before its melee specialization
		BY_CLASS.put(4, new int[] { 72, 82, 438, 439, 86 }); // Human Knight
		BY_CLASS.put(7, new int[] { 99, 131, 303, 415, 416, 357, 356 }); // Rogue
		BY_CLASS.put(19, BY_CLASS.get(99)); // Elven Knight
		BY_CLASS.put(22, new int[] { 413, 131, 303, 415, 416, 355, 356, 410, 446 }); // Elven Scout
		BY_CLASS.put(32, BY_CLASS.get(106)); // Palus Knight
		BY_CLASS.put(35, new int[] { 414, 131, 303, 415, 355, 357, 410, 447 }); // Assassin
		BY_CLASS.put(45, BY_CLASS.get(113)); // Orc Raider
		BY_CLASS.put(47, BY_CLASS.get(114)); // Monk
	}

	private PhantomEncounterBuffs()
	{
	}

	/** @return the self-buff skill ids for a class, in the order to cast them (empty for none). */
	public static int[] forClass(int classId)
	{
		final int[] ids = BY_CLASS.get(classId);
		return (ids == null) ? NONE : ids.clone();
	}
}
