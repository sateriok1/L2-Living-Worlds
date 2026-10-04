import org.l2jmobius.gameserver.managers.PhantomPartyDowntime;
import org.l2jmobius.gameserver.managers.PhantomPartyDowntime.Recovery;
import org.l2jmobius.gameserver.managers.PhantomPartyDowntime.State;

public class PhantomPartyDowntimeTest
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
		final State hunt = new State();
		check(!hunt.deferScan(0, false, 1000), "normal free-hunt scans continue");
		check(hunt.deferScan(7, false, 1700), "scanner sees a kill before the party tick and preserves the pickup window");
		hunt.pause(2000);
		check(hunt.deferScan(7, false, 2400), "a loot walk owns the next scanner pass");
		hunt.pause(3000);
		check(hunt.deferScan(7, false, 4400), "multiple drops renew the same downtime without starting another pull");
		hunt.resume();
		check(!hunt.deferScan(7, false, 4500), "finishing pickup resumes immediately without repeatedly handing off the same corpse");
		check(hunt.deferScan(8, false, 4600), "a later kill gets its own handoff");
		check(!hunt.deferScan(8, false, 7100), "a missing party tick cannot freeze the scanner forever");
		hunt.pause(8000);
		check(!hunt.deferScan(8, false, 10500), "an abandoned managed pause expires too");

		final State recovery = new State();
		check(recovery.deferScan(0, true, 1000), "low mana with no target yields before the first pull");
		recovery.pause(2000);
		check(recovery.deferScan(0, true, 4000), "the party keeps ownership during recovery");
		recovery.resume();
		check(!recovery.deferScan(0, false, 4100), "recovery completion or an attacker releases the scanner");
		check(recovery.deferScan(0, true, 5000), "another low-mana break can start");
		check(!recovery.deferScan(0, true, 7500), "a refused sit cannot extend a scanner-only request forever");

		// recovery(sitting, need, threat, ownerFar, fighting, starved, standSuppressed, leaderSitting, topUp, sitOrdered)
		check(PhantomPartyDowntime.need(true, 80, 40) == 40, "an MP user rests on the lower of MP and HP");
		check(PhantomPartyDowntime.need(false, 5, 70) == 70, "a melee class ignores MP and rests for HP only");
		check(PhantomPartyDowntime.recovery(false, 29, false, false, false, false, false, false, false, false) == Recovery.REST, "a safe member below 30% rests");
		check(PhantomPartyDowntime.recovery(false, 30, false, false, false, false, false, false, false, false) == Recovery.CONTINUE, "the sit threshold is strict");
		check(PhantomPartyDowntime.recovery(true, 89, false, false, false, false, false, false, false, false) == Recovery.REST, "a break does not oscillate at the sit line");
		check(PhantomPartyDowntime.recovery(true, 90, false, false, false, false, false, false, false, false) == Recovery.STAND, "90% ends an ordinary break");
		check(PhantomPartyDowntime.recovery(true, 5, true, false, false, true, false, false, false, false) == Recovery.STAND, "an attacker or raid interrupts a break");
		check(PhantomPartyDowntime.recovery(false, 5, true, false, false, true, false, false, false, false) == Recovery.CONTINUE, "an exhausted actor never sits under threat");
		check(PhantomPartyDowntime.recovery(true, 5, false, true, false, true, false, false, false, false) == Recovery.STAND, "a distant leader ends recovery");
		check(PhantomPartyDowntime.recovery(false, 5, false, true, false, true, false, false, false, false) == Recovery.CONTINUE, "a distant leader prevents sitting");
		check(PhantomPartyDowntime.recovery(false, 5, false, false, false, true, true, false, false, false) == Recovery.CONTINUE, "the explicit stand order suppresses resitting");
		check(PhantomPartyDowntime.recovery(true, 5, false, false, false, true, true, false, false, false) == Recovery.STAND, "a stand or follow order interrupts existing recovery");
		check(PhantomPartyDowntime.recovery(false, 10, false, false, true, false, false, false, false, false) == Recovery.CONTINUE, "an affordable fight finishes below the sit threshold");
		check(PhantomPartyDowntime.recovery(false, 0, false, false, true, true, false, false, false, false) == Recovery.REST, "an unthreatened archer or nuker unable to attack yields a live target");
		check(PhantomPartyDowntime.recovery(false, 95, false, false, false, false, false, true, false, false) == Recovery.REST, "members sit with a sitting leader whenever they are not full");
		check(PhantomPartyDowntime.recovery(false, 100, false, false, false, false, false, true, false, false) == Recovery.CONTINUE, "a full member stays up when the leader sits");
		check(PhantomPartyDowntime.recovery(true, 95, false, false, false, false, false, false, true, false) == Recovery.REST, "after the leader stands, a member that sat with it tops up to full");
		check(PhantomPartyDowntime.recovery(true, 100, false, false, false, false, false, false, true, false) == Recovery.STAND, "full ends a top-up");
		check(PhantomPartyDowntime.recovery(false, 100, false, false, false, false, false, false, false, true) == Recovery.REST, "a sit order sits even at full");
		check(PhantomPartyDowntime.recovery(true, 100, false, false, false, false, false, false, true, true) == Recovery.REST, "a sit order keeps the member down at full");
		check(PhantomPartyDowntime.recovery(true, 100, true, false, false, false, false, false, true, true) == Recovery.STAND, "a threat ends a sit order");
		check(PhantomPartyDowntime.recovery(false, 40, true, false, false, false, false, false, false, true) == Recovery.CONTINUE, "a sit order is not obeyed under attack");

		final State loot = new State();
		check(!loot.lootDeferred(21, 1000), "new drops are eligible");
		loot.deferLoot(21, 1000);
		check(loot.lootDeferred(21, 30999), "a stuck claim backs off instead of retrying every tick");
		check(!loot.lootDeferred(21, 31000), "a stuck drop can be retried after 30 seconds");
		check(!loot.lootDeferred(22, 2000), "one stuck item does not suppress the rest of the loot");
		for (int id = 100; id < 165; id++)
		{
			loot.deferLoot(id, 40000);
		}
		check(!loot.lootDeferred(100, 40001) && loot.lootDeferred(164, 40001), "cooldown memory is bounded across despawned drops");
		System.out.println("PhantomPartyDowntimeTest: " + checks + " checks passed");
	}
}
