import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.l2jmobius.gameserver.managers.PhantomPartyCommandRules;
import org.l2jmobius.gameserver.managers.PhantomPartyCommandRules.Order;
import org.l2jmobius.gameserver.managers.PhantomPartyCommandRules.RescueHold;

public class PhantomPartyCommandRulesTest
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
		final Map<Order, List<String>> commands = Map.of(
			Order.TANK_ATTACK, List.of("tank attack", "tank pull", "tank go", "tank engage", "tank initiate", "tank in", "pull it", "pull the boss", "pull boss", "initiate"),
			Order.ALL_ATTACK, List.of("all attack", "everyone attack", "all in", "open fire", "engage all", "attack the raid", "everyone in", "burn it"),
			Order.ENGAGE, List.of("engage", "attack it", "attack this", "kill it"),
			Order.FREE_HUNT, List.of("attack freely", "free hunt", "go wild", "ffa", "hunt freely", "do your own", "attack anything"),
			Order.ASSIST, List.of("assist", "focus", "help me", "on my target", "kill my target", "attack my"));
		for (Map.Entry<Order, List<String>> group : commands.entrySet())
		{
			for (String command : group.getValue())
			{
				check(PhantomPartyCommandRules.classify(command) == group.getKey(), "positive alias remains available: " + command);
				for (String prefix : List.of("don't ", "dont ", "do not ", "no ", "not ", "never "))
				{
					final String negative = prefix + command;
					check(PhantomPartyCommandRules.classify(negative) == Order.STOP, "negative alias cannot authorize combat: " + negative);
					check(PhantomPartyCommandRules.classify(negative + " and follow me") == Order.STOP, "follow cannot preempt: " + negative);
				}
			}
		}
		for (String message : List.of("disengage", "disengage all", "disengage and follow me", "disengage then assist", "disengage and camp here",
			"don't kill it, go wild", "don't pull it, switch to polearm", "do not tank attack and regroup", "do not all attack; help me",
			"stop and follow me", "stop and attack freely", "don't attack and hold here", "DON'T KILL IT", "do  not  all  attack", "don\u2019t pull it"))
		{
			check(PhantomPartyCommandRules.classify(message) == Order.STOP, "negative combat instruction wins over other modes: " + message);
		}
		for (String command : List.of("hold fire", "hold dps", "wait for tank", "fall back", "stop dps", "back off", "hold fire and all attack"))
		{
			check(PhantomPartyCommandRules.classify(command) == Order.HOLD_FIRE, "rescue hold remains a distinct command: " + command);
		}
		for (String message : List.of("stop recharge", "stop charging", "stop the recharge", "stop mp", "stop pulling", "stop pull", "stop fetching", "stop the viper cubic",
			"follow me", "camp here", "my assistant is here", "engagement rewards", "no killers here", "please recharge me"))
		{
			check(PhantomPartyCommandRules.classify(message) == Order.NONE, "specialized commands and ordinary text do not become combat Stop: " + message);
		}

		final RescueHold hold = new RescueHold();
		final Set<Integer> fighting = new HashSet<>(Set.of(17));
		hold.block(List.of(17));
		check(hold.blocked(fighting::contains), "Hold Fire prevents rescue while the captured boss encounter is active");
		fighting.clear();
		check(!hold.blocked(fighting::contains), "a reset boss ends the block even when it remains selected");
		fighting.add(17);
		check(!hold.blocked(fighting::contains), "the same boss's later encounter does not inherit an expired hold");
		hold.block(List.of(17));
		fighting.clear();
		fighting.add(29);
		check(!hold.blocked(fighting::contains), "switching directly to another boss fight cannot retain the previous encounter's hold");
		hold.block(List.of(17));
		fighting.clear();
		check(!hold.blocked(fighting::contains), "a new idle boss target cannot keep an ended encounter blocked");
		hold.block(List.of(17, 18));
		fighting.add(18);
		check(hold.blocked(fighting::contains), "a captured live add keeps the held encounter active after the boss dies");
		fighting.clear();
		check(!hold.blocked(fighting::contains), "the hold ends after the captured boss and adds finish or disappear");
		hold.block(List.of(29));
		fighting.add(29);
		hold.clear();
		check(!hold.blocked(fighting::contains), "a positive attack order explicitly releases an active hold");
		hold.block(List.of());
		check(!hold.blocked(fighting::contains), "Hold Fire with no active encounter cannot block a later unrelated encounter");
		System.out.println("PhantomPartyCommandRulesTest: " + checks + " checks passed");
	}
}
