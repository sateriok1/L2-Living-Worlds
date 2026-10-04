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
		testLevelsAndEnchant();
		testGroupSize();
		testGroup();
		testStrikeRules();
		testDelay();
		testHostileRegistry();
		System.out.println("Ran " + checks + " checks, " + failures + " failure(s).");
		System.out.println(failures == 0 ? "OK" : "FAILED");
		System.exit(failures == 0 ? 0 : 1);
	}

	private static void testPickTier()
	{
		final int[] w = { 40, 30, 18, 9, 3 };
		eq(Tier.WIMP, PhantomEncounterRules.pickTier(w, 0), "roll 0 is wimp");
		eq(Tier.WIMP, PhantomEncounterRules.pickTier(w, 39), "roll 39 is wimp");
		eq(Tier.NORMIE, PhantomEncounterRules.pickTier(w, 40), "roll 40 is normie");
		eq(Tier.HARD, PhantomEncounterRules.pickTier(w, 70), "roll 70 is hard");
		eq(Tier.HORSEMEN, PhantomEncounterRules.pickTier(w, 88), "roll 88 is horsemen");
		eq(Tier.PKER, PhantomEncounterRules.pickTier(w, 97), "roll 97 is pker");
		eq(Tier.WIMP, PhantomEncounterRules.pickTier(w, 100), "roll wraps");
		eq(null, PhantomEncounterRules.pickTier(new int[] { 0, 0, 0, 0, 0 }, 5), "no weights, no tier");
		eq(null, PhantomEncounterRules.pickTier(new int[] { -3, 0, 0, 0, 0 }, 5), "negative weight counts as 0");
		eq(Tier.PKER, PhantomEncounterRules.pickTier(new int[] { 0, 0, 0, 0, 5 }, 3), "only the pker");
		final int[] counts = new int[5];
		for (int r = 0; r < 100; r++)
		{
			counts[PhantomEncounterRules.pickTier(w, r).ordinal()]++;
		}
		for (int i = 0; i < 5; i++)
		{
			eq(w[i], counts[i], "share of " + Tier.values()[i]);
		}
	}

	private static void testWeightFor()
	{
		eq(40, PhantomEncounterRules.weightFor(40, 40, 20), "eligible");
		eq(0, PhantomEncounterRules.weightFor(40, 19, 20), "below the tier's level");
		eq(0, PhantomEncounterRules.weightFor(0, 60, 20), "weight 0 is off");
		eq(3, PhantomEncounterRules.weightFor(3, 40, 40), "exactly at the unlock level");
		eq(0, PhantomEncounterRules.weightFor(3, 39, 40), "one below the unlock level");
	}

	private static void testLevelsAndEnchant()
	{
		for (int roll = 0; roll < 50; roll++)
		{
			final int wimp = PhantomEncounterRules.levelFor(50, -3, -2, roll);
			truth((wimp == 47) || (wimp == 48), "wimp 2-3 under: " + wimp);
			eq(50, PhantomEncounterRules.levelFor(50, 0, 0, roll), "normie same level");
			eq(53, PhantomEncounterRules.levelFor(50, 3, 3, roll), "hard +3");
			eq(56, PhantomEncounterRules.levelFor(50, 6, 6, roll), "horsemen +6");
			eq(61, PhantomEncounterRules.levelFor(50, 11, 11, roll), "pker +11");
			final int n = PhantomEncounterRules.enchantIn(0, 3, roll);
			truth((n >= 0) && (n <= 3), "normie 0-3: " + n);
			final int h = PhantomEncounterRules.enchantIn(3, 4, roll);
			truth((h == 3) || (h == 4), "hard 3-4: " + h);
			final int hm = PhantomEncounterRules.enchantIn(7, 10, roll);
			truth((hm >= 7) && (hm <= 10), "horsemen 7-10: " + hm);
			eq(16, PhantomEncounterRules.enchantIn(16, 16, roll), "pker is a full +16");
			eq(0, PhantomEncounterRules.enchantIn(0, 0, roll), "wimp +0");
		}
		eq(80, PhantomEncounterRules.levelFor(78, 11, 11, 3), "level clamped to 80");
		eq(1, PhantomEncounterRules.levelFor(3, -3, -3, 5), "level clamped to 1");
		eq(52, PhantomEncounterRules.levelFor(50, 2, 2, -7), "negative roll is safe");
		final int reversed = PhantomEncounterRules.levelFor(50, 3, -3, 4);
		truth((reversed >= 47) && (reversed <= 53), "reversed offsets still land in range: " + reversed);
		eq(30, PhantomEncounterRules.enchantIn(99, 99, 1), "enchant capped at 30");
		eq(0, PhantomEncounterRules.enchantIn(-5, -2, 1), "negative enchant floors at 0");
	}

	private static void testGroupSize()
	{
		eq(1, PhantomEncounterRules.groupSize(Tier.WIMP, 1, 4, 9), "wimp solo");
		eq(5, PhantomEncounterRules.groupSize(Tier.NORMIE, 5, 4, 9), "normie matches the party");
		eq(9, PhantomEncounterRules.groupSize(Tier.HARD, 9, 4, 9), "hard matches a party of 9");
		eq(9, PhantomEncounterRules.groupSize(Tier.WIMP, 12, 4, 9), "capped");
		eq(4, PhantomEncounterRules.groupSize(Tier.HORSEMEN, 1, 4, 9), "horsemen solo still four");
		eq(4, PhantomEncounterRules.groupSize(Tier.HORSEMEN, 3, 4, 9), "horsemen small party still four");
		eq(7, PhantomEncounterRules.groupSize(Tier.HORSEMEN, 7, 4, 9), "horsemen match a bigger party");
		eq(9, PhantomEncounterRules.groupSize(Tier.HORSEMEN, 20, 4, 9), "horsemen capped");
		eq(1, PhantomEncounterRules.groupSize(Tier.PKER, 9, 4, 9), "pker is always one");
		eq(1, PhantomEncounterRules.groupSize(Tier.WIMP, 0, 4, 0), "never fewer than one");
		truth(PhantomEncounterRules.strikesOnArrival(Tier.HORSEMEN) && PhantomEncounterRules.strikesOnArrival(Tier.PKER), "horsemen and pker strike on arrival");
		truth(!PhantomEncounterRules.strikesOnArrival(Tier.WIMP) && !PhantomEncounterRules.strikesOnArrival(Tier.NORMIE) && !PhantomEncounterRules.strikesOnArrival(Tier.HARD), "the others wait");
	}

	private static void testGroup()
	{
		final PhantomEncounterRules.EncounterGroup g = new PhantomEncounterRules.EncounterGroup(3);
		truth(g.claimSpeech(500), "first member speaks");
		truth(!g.claimSpeech(900), "only one member speaks");
		eq(500L, g.warnedAt(), "warning time is the speaker's");
		truth(!g.isFighting(), "not fighting yet");
		g.startFight();
		truth(g.isFighting(), "fighting once one attacks");
		truth(g.claimWinLine() && !g.claimWinLine(), "the win line is said once");
		truth(!g.memberDied() && !g.memberDied(), "two of three down is not a wipe");
		truth(g.memberDied(), "the third death wipes the group");
		truth(g.claimLoot() && !g.claimLoot(), "one loot drop per group");
		final PhantomEncounterRules.EncounterGroup small = new PhantomEncounterRules.EncounterGroup(4);
		small.shrinkTo(2);
		truth(!small.memberDied() && small.memberDied(), "a shrunken group wipes at its real size");
		final PhantomEncounterRules.EncounterGroup solo = new PhantomEncounterRules.EncounterGroup(0);
		truth(solo.memberDied(), "a size-0 group counts as one");
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
