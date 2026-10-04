import org.l2jmobius.gameserver.managers.PhantomEncounterRules;
import org.l2jmobius.gameserver.managers.PhantomEncounterRules.Tier;

/** Standalone harness for {@link PhantomEncounterRules}. Exit code 0 when every check passes. */
public class PhantomEncounterRulesTest
{
	private static int checks = 0;
	private static int failures = 0;

	public static void main(String[] args)
	{
		testPickTier();
		testWeightFor();
		testActorLevel();
		testGear();
		testStrikeRules();
		testDelay();
		testActorCount();
		testHostileRegistry();
		testOver();
		System.out.println("Ran " + checks + " checks, " + failures + " failure(s).");
		System.out.println(failures == 0 ? "OK" : "FAILED");
		System.exit(failures == 0 ? 0 : 1);
	}

	private static void testPickTier()
	{
		final int[] w = { 55, 45, 0, 0 };
		eq(Tier.WIMP, PhantomEncounterRules.pickTier(w, 0), "roll 0 is wimp");
		eq(Tier.WIMP, PhantomEncounterRules.pickTier(w, 54), "roll 54 is wimp");
		eq(Tier.NORMIE, PhantomEncounterRules.pickTier(w, 55), "roll 55 is normie");
		eq(Tier.NORMIE, PhantomEncounterRules.pickTier(w, 99), "roll 99 is normie");
		eq(Tier.WIMP, PhantomEncounterRules.pickTier(w, 100), "roll wraps");
		eq(null, PhantomEncounterRules.pickTier(new int[] { 0, 0, 0, 0 }, 5), "no weights, no tier");
		eq(null, PhantomEncounterRules.pickTier(new int[] { -3, 0, 0, 0 }, 5), "negative weight counts as 0");
		eq(Tier.HORSEMEN, PhantomEncounterRules.pickTier(new int[] { 0, 0, 0, 5 }, 3), "only horsemen");
		// distribution over a full cycle matches the weights
		int wimp = 0;
		int normie = 0;
		for (int r = 0; r < 100; r++)
		{
			final Tier t = PhantomEncounterRules.pickTier(w, r);
			if (t == Tier.WIMP)
			{
				wimp++;
			}
			else if (t == Tier.NORMIE)
			{
				normie++;
			}
		}
		eq(55, wimp, "wimp share");
		eq(45, normie, "normie share");
	}

	private static void testWeightFor()
	{
		eq(55, PhantomEncounterRules.weightFor(Tier.WIMP, 55, 40, 1, 20, 4), "eligible");
		eq(0, PhantomEncounterRules.weightFor(Tier.WIMP, 55, 19, 1, 20, 4), "below min level");
		eq(0, PhantomEncounterRules.weightFor(Tier.WIMP, 0, 40, 1, 20, 4), "weight 0 is off");
		eq(0, PhantomEncounterRules.weightFor(Tier.PKER, 10, 40, 5, 20, 4), "big party locks out the lone PKer");
		eq(10, PhantomEncounterRules.weightFor(Tier.PKER, 10, 40, 4, 20, 4), "party at the limit is fine");
		eq(45, PhantomEncounterRules.weightFor(Tier.NORMIE, 45, 40, 9, 20, 4), "normie still comes for a big party");
	}

	private static void testActorLevel()
	{
		for (int roll = 0; roll < 50; roll++)
		{
			final int wimp = PhantomEncounterRules.actorLevel(Tier.WIMP, 50, roll);
			truth((wimp >= 42) && (wimp <= 47), "wimp 3-8 under: " + wimp);
			final int normie = PhantomEncounterRules.actorLevel(Tier.NORMIE, 50, roll);
			truth((normie >= 48) && (normie <= 52), "normie within 2: " + normie);
			final int pker = PhantomEncounterRules.actorLevel(Tier.PKER, 50, roll);
			truth((pker >= 54) && (pker <= 58), "pker 4-8 over: " + pker);
		}
		eq(80, PhantomEncounterRules.actorLevel(Tier.HORSEMEN, 80, 3), "clamped to 80");
		eq(1, PhantomEncounterRules.actorLevel(Tier.WIMP, 5, 5), "clamped to 1");
		truth(PhantomEncounterRules.actorLevel(Tier.NORMIE, 50, -7) >= 48, "negative roll is safe");
	}

	private static void testGear()
	{
		eq(-1, PhantomEncounterRules.gradeShift(Tier.WIMP), "wimp is a grade under");
		eq(0, PhantomEncounterRules.gradeShift(Tier.NORMIE), "normie is even");
		eq(1, PhantomEncounterRules.gradeShift(Tier.PKER), "pker is a grade over");
		eq(0, PhantomEncounterRules.shiftedGrade(0, -1, 5), "grade floor");
		eq(5, PhantomEncounterRules.shiftedGrade(5, 1, 5), "grade cap");
		eq(3, PhantomEncounterRules.shiftedGrade(2, 1, 5), "plain shift");
		for (int roll = 0; roll < 60; roll++)
		{
			eq(0, PhantomEncounterRules.enchantFor(Tier.WIMP, roll), "wimp is +0");
			final int n = PhantomEncounterRules.enchantFor(Tier.NORMIE, roll);
			truth((n >= 0) && (n <= 3), "normie 0-3: " + n);
			final int p = PhantomEncounterRules.enchantFor(Tier.PKER, roll);
			truth((p >= 14) && (p <= 20), "pker 14-20: " + p);
			final int h = PhantomEncounterRules.enchantFor(Tier.HORSEMEN, roll);
			truth((h >= 16) && (h <= 20), "horsemen 16-20: " + h);
		}
	}

	private static void testStrikeRules()
	{
		truth(PhantomEncounterRules.normieStrikeReady(true, 0, 4000), "strikes when fighting a mob");
		truth(PhantomEncounterRules.normieStrikeReady(false, 4000, 4000), "strikes after standing still");
		truth(!PhantomEncounterRules.normieStrikeReady(false, 3999, 4000), "waits while moving");
		truth(PhantomEncounterRules.wimpMayStrike(8000, 1000, 7000, false), "wimp strikes after the warning");
		truth(!PhantomEncounterRules.wimpMayStrike(7999, 1000, 7000, false), "wimp waits during the warning");
		truth(PhantomEncounterRules.wimpMayStrike(1001, 1000, 7000, true), "wimp strikes back if hit first");
		truth(!PhantomEncounterRules.wimpMayStrike(9999, 0, 7000, false), "no warning given yet, no strike");
	}

	private static void testDelay()
	{
		eq(100L, PhantomEncounterRules.delayMs(100, 200, 0), "delay low end");
		eq(199L, PhantomEncounterRules.delayMs(100, 200, 999), "delay high end");
		eq(150L, PhantomEncounterRules.delayMs(100, 200, 500), "delay middle");
		eq(100L, PhantomEncounterRules.delayMs(100, 100, 700), "equal range");
		eq(100L, PhantomEncounterRules.delayMs(100, 50, 700), "reversed range collapses");
		eq(100L, PhantomEncounterRules.delayMs(100, 200, -5), "negative roll clamps");
	}

	private static void testActorCount()
	{
		eq(1, PhantomEncounterRules.actorCount(1, 1, 4), "solo");
		eq(3, PhantomEncounterRules.actorCount(1, 5, 4), "party of 5");
		eq(4, PhantomEncounterRules.actorCount(1, 9, 4), "party of 9 capped");
		eq(4, PhantomEncounterRules.actorCount(4, 2, 6), "base wins for a small party");
		eq(1, PhantomEncounterRules.actorCount(0, 0, 0), "never fewer than one");
	}

	private static void testHostileRegistry()
	{
		truth(!PhantomEncounterRules.isHostile(10, 20), "nothing marked");
		PhantomEncounterRules.markHostile(10, 20);
		truth(PhantomEncounterRules.isHostile(10, 20), "marked");
		truth(!PhantomEncounterRules.isHostile(10, 21), "only that victim");
		truth(!PhantomEncounterRules.isHostile(11, 20), "only that actor");
		PhantomEncounterRules.clearHostile(10);
		truth(!PhantomEncounterRules.isHostile(10, 20), "cleared");
	}

	private static void testOver()
	{
		truth(PhantomEncounterRules.encounterOver(true, false, false, 0, 100), "actor dead");
		truth(PhantomEncounterRules.encounterOver(false, true, false, 0, 100), "victim dead");
		truth(PhantomEncounterRules.encounterOver(false, false, true, 0, 100), "victim gone");
		truth(PhantomEncounterRules.encounterOver(false, false, false, 100, 100), "clock out");
		truth(!PhantomEncounterRules.encounterOver(false, false, false, 99, 100), "still on");
	}

	private static void eq(Object expected, Object actual, String what)
	{
		checks++;
		if ((expected == null) ? (actual != null) : !expected.equals(actual))
		{
			failures++;
			System.out.println("FAIL: " + what + " -> expected [" + expected + "] but got [" + actual + "]");
		}
	}

	private static void truth(boolean condition, String what)
	{
		checks++;
		if (!condition)
		{
			failures++;
			System.out.println("FAIL: " + what);
		}
	}
}
