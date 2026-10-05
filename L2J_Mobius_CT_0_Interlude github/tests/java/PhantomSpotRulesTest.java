import org.l2jmobius.gameserver.managers.PhantomSpotRules;
import org.l2jmobius.gameserver.managers.PhantomSpotRules.Temper;

public class PhantomSpotRulesTest
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
		// Temper mix 20 / 50 / 30.
		int hot = 0;
		int normal = 0;
		int patient = 0;
		for (int roll = 0; roll < 100; roll++)
		{
			final Temper t = PhantomSpotRules.rollTemper(roll, 20, 50);
			if (t == Temper.HOT)
			{
				hot++;
			}
			else if (t == Temper.NORMAL)
			{
				normal++;
			}
			else
			{
				patient++;
			}
		}
		check(hot == 20 && normal == 50 && patient == 30, "temper mix " + hot + "/" + normal + "/" + patient);
		check(PhantomSpotRules.rollTemper(0, 0, 0) == Temper.PATIENT, "no hot and no normal leaves everyone patient");
		check(PhantomSpotRules.rollTemper(99, 100, 0) == Temper.HOT, "all hot");
		check(PhantomSpotRules.rollTemper(50, 80, 80) == Temper.HOT, "an oversized mix is clamped to 100");

		check(PhantomSpotRules.thresholdFor(Temper.HOT, 1, 4, 6) == 1.0, "hot threshold");
		check(PhantomSpotRules.thresholdFor(Temper.NORMAL, 1, 4, 6) == 4.0, "normal threshold");
		check(PhantomSpotRules.thresholdFor(Temper.PATIENT, 1, 4, 6) == 6.0, "patient threshold");
		check(PhantomSpotRules.thresholdFor(Temper.HOT, 0, 4, 6) > 0, "a zero threshold is lifted");

		check(PhantomSpotRules.warnAt(1) == 1.0, "a hot hunter complains the moment it attacks");
		check(PhantomSpotRules.warnAt(4) == 2.0, "normal complains at half");
		check(PhantomSpotRules.warnAt(6) == 3.0, "patient complains at half");

		// The patient hunter's limit: five minutes close by and a couple of stolen kills (2 points each).
		double score = PhantomSpotRules.crowd(0, 5 * 60_000L);
		check(score == 2.5, "five minutes of crowding is 2.5 points");
		check(score < 6.0, "five minutes alone does not set off a patient hunter");
		score += 2 + 2;
		check(score >= 6.0, "five minutes and two steals does");

		// Fading.
		check(PhantomSpotRules.fade(3, 2 * 60_000L) == 2.0, "fades by half a point a minute");
		check(PhantomSpotRules.fade(0.2, 5 * 60_000L) == 0.0, "never below zero");
		check(PhantomSpotRules.crowd(1, -5) == 1.0, "negative time changes nothing");

		check(PhantomSpotRules.realFight(69, 70) && !PhantomSpotRules.realFight(70, 70), "70% real fights");
		check(!PhantomSpotRules.realFight(0, 0) && PhantomSpotRules.realFight(99, 100), "fight share bounds");

		System.out.println("Ran " + checks + " checks, 0 failure(s).");
	}
}
