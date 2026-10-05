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
