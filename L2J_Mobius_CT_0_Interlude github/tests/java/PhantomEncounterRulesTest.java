import org.l2jmobius.gameserver.managers.PhantomEncounterRules;
import org.l2jmobius.gameserver.managers.PhantomEncounterRules.Approach;
import org.l2jmobius.gameserver.managers.PhantomEncounterRules.EncounterGroup;
import org.l2jmobius.gameserver.managers.PhantomEncounterRules.Style;

/** Standalone harness for the generic encounter engine rules. Exit code 0 when every check passes. */
public class PhantomEncounterRulesTest
{
	private static int checks = 0;
	private static int failures = 0;

	public static void main(String[] args)
	{
		testStyle();
		testGroup();
		testListener();
		testStrikeRules();
		testHostileRegistry();
		System.out.println("Ran " + checks + " checks, " + failures + " failure(s).");
		System.out.println(failures == 0 ? "OK" : "FAILED");
		System.exit(failures == 0 ? 0 : 1);
	}

	private static void testStyle()
	{
		final Style s = new Style(Approach.ASK_FIRST, 1, 1, 0, 0, new String[] { "hi" }, null);
		eq(10, s.approachSeconds, "approach time has a floor of 10 s");
		eq(30, s.fightSeconds, "fight time has a floor of 30 s");
		eq(1, s.warnSeconds, "warning has a floor of 1 s");
		eq(1, s.stillSeconds, "standing still has a floor of 1 s");
		eq(1, s.askLines.length, "ask lines kept");
		eq(0, s.winLines.length, "null win lines become empty");
		eq(Approach.STRIKE_ON_ARRIVAL, new Style(null, 60, 240, 7, 4, null, null).approach, "no approach strikes on arrival");
		final String[] lines = { "a", "b" };
		final Style copy = new Style(Approach.WAIT_FOR_MOMENT, 60, 240, 7, 4, lines, lines);
		lines[0] = "changed";
		eq("a", copy.askLines[0], "the style keeps its own copy of the lines");
		final EncounterGroup g = new EncounterGroup(2, null, null);
		eq(Approach.STRIKE_ON_ARRIVAL, g.style().approach, "a group with no style gets the default");
		final Style talk = new Style(Approach.STRIKE_ON_ARRIVAL, 60, 240, 7, 4, null, null, new String[] { "x" }, new String[] { "y", "z" });
		eq(1, talk.strikeLines.length, "strike lines kept");
		eq(2, talk.defeatLines.length, "defeat lines kept");
		eq(0, s.strikeLines.length, "a style without strike lines has none");
		final EncounterGroup tg = new EncounterGroup(3, talk, null);
		truth(tg.claimStrikeLine(), "first to start talks trash");
		truth(!tg.claimStrikeLine(), "only one talks trash");
		truth(tg.claimDefeatLine(), "first to fall whines");
		truth(!tg.claimDefeatLine(), "only one whines");
		eq(3, tg.size(), "group size reported");
		eq(0, tg.deadCount(), "nobody down yet");
		tg.memberDied();
		eq(1, tg.deadCount(), "one down");
	}

	private static void testGroup()
	{
		final EncounterGroup g = new EncounterGroup(3, null, null);
		truth(g.claimSpeech(500), "first member speaks");
		truth(!g.claimSpeech(900), "only one member speaks");
		eq(500L, g.warnedAt(), "warning time is the speaker's");
		truth(!g.isFighting(), "not fighting yet");
		g.startFight();
		truth(g.isFighting(), "fighting once one attacks");
		truth(g.claimWinLine() && !g.claimWinLine(), "the win line is said once");
		truth(!g.memberDied() && !g.memberDied(), "two of three down is not a wipe");
		truth(g.memberDied(), "the third death wipes the group");
		final EncounterGroup small = new EncounterGroup(4, null, null);
		small.shrinkTo(2);
		truth(!small.memberDied() && small.memberDied(), "a shrunken group wipes at its real size");
		final EncounterGroup solo = new EncounterGroup(0, null, null);
		truth(solo.memberDied(), "a size-0 group counts as one");
	}

	private static void testListener()
	{
		final int[] seen = { 0, 0 };
		final EncounterGroup g = new EncounterGroup(1, null, (victim, actor) ->
		{
			seen[0] = victim;
			seen[1] = actor;
		});
		truth(g.listener() != null, "the group carries its listener");
		g.listener().onGroupDefeated(7, 9);
		eq(7, seen[0], "listener gets the victim id");
		eq(9, seen[1], "listener gets the last actor id");
		eq(null, new EncounterGroup(1, null, null).listener(), "no listener is fine");
	}

	private static void testStrikeRules()
	{
		truth(PhantomEncounterRules.momentReady(true, 0, 4000), "strikes when fighting a mob");
		truth(PhantomEncounterRules.momentReady(false, 4000, 4000), "strikes after standing still");
		truth(!PhantomEncounterRules.momentReady(false, 3999, 4000), "waits while moving");
		truth(PhantomEncounterRules.mayStrikeAfterWarning(8000, 1000, 7000, false), "strikes after the warning");
		truth(!PhantomEncounterRules.mayStrikeAfterWarning(7999, 1000, 7000, false), "waits during the warning");
		truth(PhantomEncounterRules.mayStrikeAfterWarning(1001, 1000, 7000, true), "strikes back if hit first");
		truth(!PhantomEncounterRules.mayStrikeAfterWarning(9999, 0, 7000, false), "no warning given yet, no strike");
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
