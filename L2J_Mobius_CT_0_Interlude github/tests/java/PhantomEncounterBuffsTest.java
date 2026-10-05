import java.util.HashSet;
import java.util.Set;

import org.l2jmobius.gameserver.managers.PhantomEncounterBuffs;

public class PhantomEncounterBuffsTest
{
	private static int checks;

	private static void check(boolean condition, String message)
	{
		checks++;
		if (!condition)
		{
			throw new AssertionError(message);
		}
	}

	public static void main(String[] args)
	{
		final int[] melee = { 88, 113, 114, 89, 90, 91, 92, 102, 109, 93, 101, 108 };
		for (int classId : melee)
		{
			final int[] ids = PhantomEncounterBuffs.forClass(classId);
			check(ids.length > 0, "class " + classId + " has self-buffs");
			final Set<Integer> seen = new HashSet<>();
			for (int id : ids)
			{
				check(id > 0, "positive skill id");
				check(seen.add(id), "no duplicate skill in class " + classId);
			}
		}
		check(PhantomEncounterBuffs.forClass(94).length == 0, "an Archmage has no class self-buff to cast");
		check(PhantomEncounterBuffs.forClass(96).length == 0, "summoners get their servitor, not a buff list");
		check(PhantomEncounterBuffs.forClass(-1).length == 0, "unknown class");
		final int[] first = PhantomEncounterBuffs.forClass(113);
		first[0] = 0;
		check(PhantomEncounterBuffs.forClass(113)[0] != 0, "the list is a copy");
		System.out.println("Ran " + checks + " checks, 0 failure(s).");
	}
}
