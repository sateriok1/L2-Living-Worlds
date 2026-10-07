import org.l2jmobius.gameserver.managers.PhantomServitorRules;

public class PhantomServitorRulesTest
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
		// The strongest known summon wins; nothing known gives 0.
		check(PhantomServitorRules.pickSummon(id -> id == 1111) == 1111, "only Kat");
		check(PhantomServitorRules.pickSummon(id -> (id == 1111) || (id == 1225)) == 1225, "Mew beats Kat");
		check(PhantomServitorRules.pickSummon(id -> (id == 1226) || (id == 1332) || (id == 1277)) == 1332, "Seraphim beats Merrow and Boxer");
		check(PhantomServitorRules.pickSummon(id -> true) == 1406, "King is the top tier");
		check(PhantomServitorRules.pickSummon(id -> false) == 0, "nothing usable");
		check(PhantomServitorRules.pickSummon(id -> (id != 1406) && (id != 1407) && (id != 1408)) == 1331, "next tier down");
		check(PhantomServitorRules.isSummonSkill(1128) && PhantomServitorRules.isSummonSkill(1408), "summon ids recognised");
		check(!PhantomServitorRules.isSummonSkill(1403) && !PhantomServitorRules.isSummonSkill(1127) && !PhantomServitorRules.isSummonSkill(1334), "friend, heal and corpse summon are not servitor summons");

		check(PhantomServitorRules.servitorNeedsHeal(59) && !PhantomServitorRules.servitorNeedsHeal(60), "heal threshold 60");
		check(PhantomServitorRules.servitorNeedsRecharge(34) && !PhantomServitorRules.servitorNeedsRecharge(35), "recharge threshold 35");

		for (String yes : new String[] { "summon me", "Summon me pls", "can you summon me please", "summon us", "summon friend", "summon bob", "port me", "summon Bob to you", "summon me over here", "summon everyone", "SUMMON ME", "no worries, summon me", "no problem, summon Bob", "do not worry, summon me" })
		{
			check(PhantomServitorRules.isSummonRequest(yes), "summon request: " + yes);
		}
		for (String no : new String[] { "summon storm cubic", "summon your cat", "resummon", "summon your servitor", "summoning crystal drops", "i need a summoner", "swap life for storm cubic", "hello", "follow me", "don't summon me", "do not summon me", "never summon Bob", "stop porting me", "dont tp me", "don't ever summon me", "do not please summon me", "stop trying to summon me" })
		{
			check(!PhantomServitorRules.isSummonRequest(no), "not a summon request: " + no);
		}
		check(PhantomServitorRules.secondsLeft(0) == 0 && PhantomServitorRules.secondsLeft(1) == 1 && PhantomServitorRules.secondsLeft(1000) == 1 && PhantomServitorRules.secondsLeft(1001) == 2 && PhantomServitorRules.secondsLeft(-5) == 0, "seconds left rounds up");
		check(PhantomServitorRules.summonTarget("summon Joann").equals("Joann"), "Joann stays an exact name, not Ann");
		check(PhantomServitorRules.summonTarget("summon Ann to you").equals("Ann"), "explicit name retained");
		check(PhantomServitorRules.summonTarget("summon Missing").equals("Missing"), "unknown explicit name never becomes me");
		check(PhantomServitorRules.summonTarget("can you summon me please").equals("me"), "caller request resolves to caller");
		check(PhantomServitorRules.isSummonCancellation("cancel summon") && PhantomServitorRules.isSummonCancellation("do not port me"), "pending summons can be cancelled");

		System.out.println("PhantomServitorRulesTest: " + checks + " checks passed");
	}
}
