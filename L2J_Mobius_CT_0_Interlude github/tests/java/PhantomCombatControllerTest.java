/*
 * Copyright (c) 2013 L2jMobius
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR
 * IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
import org.l2jmobius.gameserver.managers.PhantomCombatController;
import org.l2jmobius.gameserver.managers.PhantomCombatController.Outcome;
import org.l2jmobius.gameserver.managers.PhantomCombatController.Request;
import org.l2jmobius.gameserver.managers.PhantomCombatController.State;
import org.l2jmobius.gameserver.managers.PhantomCombatPolicy;

/** Stateful regression scenarios exercise the shipping executor through its native-action port. */
public class PhantomCombatControllerTest
{
	private static int checks;

	private static final class Actor implements PhantomCombatController.Actor
	{
		boolean available = true;
		boolean casting;
		boolean alive = true;
		boolean reach = true;
		boolean movable = true;
		boolean accepted = true;
		boolean instant;
		boolean ownsCast = true;
		int casts;
		int moves;
		int attacks;
		int aborts;

		@Override public boolean available() { return available; }
		@Override public boolean casting() { return casting; }
		@Override public boolean ownsCast(int id) { return ownsCast; }
		@Override public boolean pendingTargetAlive(int id) { return alive; }
		@Override public boolean inReach() { return reach; }
		@Override public boolean canMove() { return movable; }
		@Override public void approach() { moves++; }
		@Override public boolean cast() { casts++; casting = accepted && !instant; return accepted; }
		@Override public void abortCast() { aborts++; casting = false; }
		@Override public void attack() { attacks++; }
	}

	private static Request request(int skill, int target, int repeat)
	{
		return new Request(skill, target, true, repeat, 1000, false);
	}

	private static final class Cast implements PhantomCombatController.CastPort
	{
		boolean legal = true;
		boolean accepted = true;
		boolean moving = true;
		final StringBuilder events = new StringBuilder();

		@Override public boolean legal() { events.append("check "); return legal; }
		@Override public void stopMoving() { moving = false; events.append("stop "); }
		@Override public void launch() { events.append("cast "); }
		@Override public boolean accepted() { events.append("accepted"); return accepted; }
	}

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
		final Cast movingCaster = new Cast();
		check(PhantomCombatController.launchCast(movingCaster, 1500, false) && !movingCaster.moving && movingCaster.events.toString().equals("check stop cast accepted"), "a timed cast validates, stops native movement, then launches");
		final Cast rejectedCaster = new Cast();
		rejectedCaster.legal = false;
		check(!PhantomCombatController.launchCast(rejectedCaster, 1500, false) && rejectedCaster.moving && rejectedCaster.events.toString().equals("check "), "a refused condition preserves the existing movement");
		final Cast simultaneous = new Cast();
		check(PhantomCombatController.launchCast(simultaneous, 1500, true) && simultaneous.moving && simultaneous.events.toString().equals("check cast accepted"), "a simultaneous cast preserves native movement semantics");
		final Cast instantCast = new Cast();
		check(PhantomCombatController.launchCast(instantCast, 50, false) && instantCast.moving, "an instant cast does not stop movement");
		final Cast refusedLaunch = new Cast();
		refusedLaunch.accepted = false;
		check(!PhantomCombatController.launchCast(refusedLaunch, 1500, false), "native launch rejection is still reported after movement handoff");
		final State control = new State();
		final Actor mage = new Actor();
		check(PhantomCombatController.execute(control, mage, request(1206, 7, 15000), false, 1000) == Outcome.STARTED, "control launches");
		mage.casting = false;
		check(PhantomCombatController.execute(control, mage, request(1177, 7, 0), false, 2000) == Outcome.STARTED, "damage follows control without a 15-second global pause");
		check(!control.ready(1206, 7, 2000), "the control skill itself remains throttled");
		check(control.ready(1177, 7, 2000), "another skill has independent pacing");
		check(PhantomCombatController.execute(control, mage, request(1177, 7, 0), false, 2200) == Outcome.BUSY, "a tick cannot interrupt a native cast");
		mage.alive = false;
		check(PhantomCombatController.observe(control, mage, 2300) == Outcome.TARGET_LOST && mage.aborts == 1, "target death cancels an obsolete offensive cast");

		final State refusal = new State();
		final Actor fighter = new Actor();
		fighter.accepted = false;
		check(PhantomCombatController.execute(refusal, fighter, request(358, 9, 8000), true, 1000) == Outcome.REJECTED, "a refused cast is reported");
		check(fighter.attacks == 1, "a rejected melee skill resumes attacking in the same tick");
		check(!refusal.ready(358, 9, 1500), "the refused target/skill gets bounded retry backoff");
		check(refusal.ready(358, 10, 1500), "failure against one target does not blacklist another");
		check(refusal.reactionReady(1000), "a refused cast does not consume the reaction beat");
		fighter.accepted = true;
		check(PhantomCombatController.execute(refusal, fighter, request(1, 9, 0), true, 1001) == Outcome.STARTED, "another legal skill can launch after rejection");
		fighter.casting = false;
		check(PhantomCombatController.execute(refusal, fighter, null, true, 2000) == Outcome.ATTACKING && fighter.attacks == 2, "a finished cast returns to physical attack");

		final State rooted = new State();
		final Actor rootMage = new Actor();
		rootMage.movable = false;
		check(PhantomCombatController.execute(rooted, rootMage, request(1177, 4, 0), false, 1000) == Outcome.STARTED && rootMage.moves == 0, "root permits a legal in-range cast without movement");
		rootMage.casting = false;
		rootMage.reach = false;
		check(PhantomCombatController.execute(rooted, rootMage, request(1177, 4, 0), false, 2000) == Outcome.BLOCKED && rootMage.moves == 0, "root cannot chase an out-of-range target");
		rootMage.movable = true;
		check(PhantomCombatController.execute(rooted, rootMage, request(1177, 4, 0), false, 3000) == Outcome.APPROACHING && rootMage.moves == 1, "range or visibility failure asks for approach rather than a cast");
		rootMage.available = false;
		check(PhantomCombatController.execute(rooted, rootMage, request(1177, 4, 0), true, 4000) == Outcome.BLOCKED && rootMage.attacks == 0, "stun, sleep and death block actions");

		final State support = new State();
		final Actor healer = new Actor();
		check(PhantomCombatController.execute(support, healer, new Request(1016, 5, false, 0, 7000, true), false, 1000) == Outcome.STARTED, "a slow support cast launches");
		healer.alive = false;
		check(PhantomCombatController.observe(support, healer, 7000) == Outcome.BUSY && healer.aborts == 0, "a legitimate resurrection is not aborted because its target is dead or a fixed six seconds elapsed");
		check(PhantomCombatController.observe(support, healer, 10000) == Outcome.TIMED_OUT && healer.aborts == 1, "a stuck native flag has a duration-aware timeout");

		final State replaced = new State();
		final State sweep = new State();
		final Actor spoiler = new Actor();
		spoiler.alive = false;
		spoiler.reach = false;
		final Request corpse = new Request(42, 12, false, 1000, 500, false);
		check(PhantomCombatController.execute(sweep, spoiler, corpse, false, 1000) == Outcome.APPROACHING, "corpse work can approach before a new pull");
		spoiler.reach = true;
		check(PhantomCombatController.execute(sweep, spoiler, corpse, false, 2000) == Outcome.STARTED, "a corpse action launches against a dead target");
		check(PhantomCombatController.observe(sweep, spoiler, 2100) == Outcome.BUSY && spoiler.aborts == 0, "sweeping is not aborted by the offensive target-death guard");
		spoiler.casting = false;
		check(PhantomCombatController.observe(sweep, spoiler, 2500) == Outcome.CAST_ENDED, "native completion releases corpse ownership");

		final Actor otherCast = new Actor();
		PhantomCombatController.execute(replaced, otherCast, request(1177, 7, 0), false, 1000);
		otherCast.ownsCast = false;
		otherCast.alive = false;
		check(PhantomCombatController.observe(replaced, otherCast, 6000) == Outcome.BUSY && otherCast.aborts == 0, "an unrelated native support cast is not aborted by a previous offensive target or deadline");

		final State instant = new State();
		final Actor toggle = new Actor();
		toggle.instant = true;
		check(PhantomCombatController.execute(instant, toggle, request(312, 1, 0), false, 1000) == Outcome.STARTED, "an immediate toggle can be confirmed");
		check(PhantomCombatController.execute(instant, toggle, request(1, 2, 0), false, 1001) == Outcome.IDLE && toggle.casts == 1, "two callers cannot spend the same reaction beat on immediate casts");
		check(PhantomCombatController.execute(instant, toggle, new Request(230, 1, false, 0, 0, true), false, 1002) == Outcome.STARTED, "an emergency can bypass the brief reaction delay");

		check(PhantomCombatPolicy.affordable(true, 10, 100, 8, 20), "a nuker can finish with its last affordable nuke");
		check(!PhantomCombatPolicy.affordable(false, 22, 100, 5, 20), "a fighter protects its post-cast MP reserve");
		check(!PhantomCombatPolicy.worthwhile("ARCHER", false, false, false, false, 5, 100, 700, false), "a safe farming archer prefers normal shots");
		check(PhantomCombatPolicy.worthwhile("ARCHER", false, false, true, false, 5, 100, 700, false), "an archer can spend a ranged skill under pressure");
		check(!PhantomCombatPolicy.worthwhile("ARCHER", false, true, true, true, 1, 100, 40, false), "a bow user never switches to a generic melee skill");
		check(!PhantomCombatPolicy.worthwhile("WARRIOR", false, false, false, false, 10, 100, 40, false), "ordinary melee farming avoids expensive skill spam");
		check(PhantomCombatPolicy.priority("PANIC") > PhantomCombatPolicy.priority("ROTATION"), "an emergency outranks ordinary damage");
		check(!PhantomCombatPolicy.pressured(true, 100, 1, false), "ordinary aggro alone is not farming pressure");
		check(PhantomCombatPolicy.pressured(true, 60, 1, false), "falling health warrants spending MP");
		check(PhantomCombatPolicy.pressured(true, 100, 2, true), "multiple attackers on a fragile actor warrant spending MP");
		check(!PhantomCombatPolicy.pressured(false, 60, 0, false), "old injuries outside combat do not trigger offensive spending");
		check(!Double.isFinite(PhantomCombatPolicy.damageScore(0, 1000, 10, 1000, 0, true, false)), "a pure debuff is not a generic damage rotation");
		check(!Double.isFinite(PhantomCombatPolicy.damageScore(100, 1000, 10, 1000, 150, false, false)), "a physical skill losing more weapon damage than it deals is skipped");
		check(PhantomCombatPolicy.damageScore(200, 1000, 5, 550, 0, true, false) > PhantomCombatPolicy.damageScore(250, 1000, 30, 1500, 0, true, false), "a fast efficient nuke beats a slightly stronger expensive slow nuke");
		check(PhantomCombatPolicy.damageScore(500, 50, 30, 1000, 0, true, false) < PhantomCombatPolicy.damageScore(100, 50, 5, 1000, 0, true, false), "overkill does not justify a more expensive finisher");
		check(PhantomCombatPolicy.damageScore(100, 1000, 10, 1000, 0, true, true) > PhantomCombatPolicy.damageScore(100, 1000, 10, 1000, 0, true, false), "a useful drain gets a sustain preference");
		System.out.println("PhantomCombatControllerTest: " + checks + " checks passed");
	}
}
