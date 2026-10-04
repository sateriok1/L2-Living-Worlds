import java.nio.file.Files;
import java.nio.file.Path;

import org.l2jmobius.commons.util.ConfigReader;
import org.l2jmobius.gameserver.config.custom.FakePlayersConfig;
import org.l2jmobius.gameserver.managers.PhantomChargePlanner;
import org.l2jmobius.gameserver.managers.PhantomCombatPolicy;
import org.l2jmobius.gameserver.managers.PhantomCombatPolicy.Availability;
import org.l2jmobius.gameserver.managers.PhantomCombatPolicy.Context;
import org.l2jmobius.gameserver.managers.PhantomSkillFallbackRules;
import org.l2jmobius.gameserver.managers.PhantomSkillFallbackRules.Owner;

/** Behavioral regressions for class policy, plan affordability and economic action ownership. */
public class PhantomClassPolicyTest
{
	private static int checks;

	private static void check(boolean result, String message)
	{
		checks++;
		if (!result)
		{
			throw new AssertionError(message);
		}
	}

	public static void main(String[] args) throws Exception
	{
		check(PhantomCombatPolicy.context(false, false, false, false) == Context.SOLO_PVE, "solo is a distinct policy");
		check(PhantomCombatPolicy.context(false, false, true, false) == Context.PARTY_PVE_SAFE, "stable party farming conserves");
		check(PhantomCombatPolicy.context(false, false, true, true) == Context.PARTY_PVE_DANGER, "party danger lifts conservation");
		check(PhantomCombatPolicy.context(false, true, true, true) == Context.RAID, "raid has its own context");
		check(PhantomCombatPolicy.context(true, true, true, false) == Context.PVP, "PvP does not inherit farming");
		for (String role : new String[] {"DAGGER", "WARRIOR", "MONK", "TANK"})
		{
			check(PhantomCombatPolicy.authoredRotation(role, Context.SOLO_PVE, 40, false)
				&& PhantomCombatPolicy.affordable(false, 1800, 2000, 165, 10), role + " can spend more than 4% on a useful authored action");
		}
		check(!PhantomCombatPolicy.affordable(false, 220, 2000, 165, 10), "post-action reserve still conserves at low MP");
		check(PhantomCombatPolicy.authoredRotation("ARCHER", Context.SOLO_PVE, 700, false), "solo active bow shots remain eligible");
		check(!PhantomCombatPolicy.authoredRotation("ARCHER", Context.PARTY_PVE_SAFE, 700, false), "safe party uses normal shots");
		check(PhantomCombatPolicy.authoredRotation("ARCHER", Context.PARTY_PVE_DANGER, 700, false), "party danger permits active shots");
		check(PhantomCombatPolicy.authoredRotation("ARCHER", Context.PVP, 700, false), "PvP permits active shots");
		check(!PhantomCombatPolicy.authoredRotation("ARCHER", Context.PVP, 40, false), "bow users do not choose contact attacks");
		check(!PhantomCombatPolicy.suppressSetup(true, Context.PARTY_PVE_SAFE, false, false), "Spoil is economic work on ordinary mobs");
		check(PhantomCombatPolicy.suppressSetup(false, Context.PARTY_PVE_SAFE, false, false), "routine setup still conserves");
		check(!PhantomCombatPolicy.suppressSetup(false, Context.PARTY_PVE_DANGER, false, false), "ally pressure permits control");
		check(PhantomCombatPolicy.availability(true, false, true) == Availability.APPROACHABLE, "out-of-range legal nuke needs approach");
		check(PhantomCombatPolicy.availability(false, false, true) == Availability.UNAVAILABLE, "wrong weapon/resources cannot be solved by movement");
		check(PhantomCombatPolicy.availability(true, true, false) == Availability.READY_NOW, "rooted in-range casting stays legal");
		check(PhantomCombatPolicy.availability(true, false, false) == Availability.UNAVAILABLE, "rooted out-of-range cast cannot approach");
		final var plan = PhantomChargePlanner.plan(0, 4, 7, 5, 25, 165, 0, 1800, 2000, 2000, 2000, 10);
		check((plan != null) && (plan.casts() == 4) && (plan.mpCost() == 185), "budget builders plus spender");
		check(PhantomChargePlanner.plan(0, 4, 7, 5, 25, 165, 0, 180, 2000, 2000, 2000, 10) == null, "never begin an unaffordable combo");
		check(PhantomChargePlanner.plan(0, 4, 3, 5, 25, 165, 0, 1800, 2000, 2000, 2000, 10) == null, "native builder rank must reach the spender");
		final var oneBuilder = PhantomChargePlanner.plan(3, 4, 7, 5, 25, 165, 0, 1800, 2000, 2000, 2000, 10);
		check(PhantomChargePlanner.preferPlan(oneBuilder, true, 0, 1, true), "Duelist builds once for earlier Triple Sonic Slash despite ready Double Sonic Slash");
		check(!PhantomChargePlanner.preferPlan(oneBuilder, true, 0, 1, false), "a dying target favors the immediate spender");
		check(!PhantomChargePlanner.preferPlan(oneBuilder, true, 2, 1, true), "a later authored spender cannot displace a ready earlier one");
		check(!PhantomChargePlanner.preferPlan(plan, true, 0, 1, true), "a ready spender is not delayed by several preparation casts");
		final var poorPlan = PhantomChargePlanner.plan(3, 4, 7, 5, 25, 165, 0, 360, 2000, 2000, 2000, 10);
		check(!PhantomChargePlanner.preferPlan(poorPlan, true, 0, 1, true), "the higher plan cannot violate post-plan MP reserve");
		check(!PhantomChargePlanner.preferPlan(null, true, 0, 1, true), "native-invalid or unavailable spender plans never displace immediate attacks");
		final var tyrantPlan = PhantomChargePlanner.plan(1, 2, 7, 5, 20, 100, 0, 1000, 1200, 2000, 2000, 10);
		check(PhantomChargePlanner.preferPlan(tyrantPlan, true, 0, 3, true), "Tyrant may prepare Hurricane Assault instead of spending Force Blaster at one charge");
		check(PhantomChargePlanner.survivesSetup(2500, 150, 500), "a durable target survives a damaging builder and ordinary-hit safety margin");
		check(!PhantomChargePlanner.survivesSetup(700, 150, 500), "damage builder payoff is rejected on short-lived targets");
		check(!PhantomChargePlanner.preferPlan(plan, false, 0, 1, false), "even an initial multi-builder plan requires enough target lifetime");
		check(PhantomChargePlanner.plan(4, 4, 7, 5, 25, 165, 0, 1800, 2000, 2000, 2000, 10) == null, "completed plan stops building");
		check(PhantomChargePlanner.plan(0, 4, 7, 5, 25, 165, 0, 1800, 2000, 550, 1000, 10) == null, "preparation cannot spend unsafe HP");
		for (int current = 0; current < 4; current++)
		{
			final var step = PhantomChargePlanner.plan(current, 4, 7, 5, 25, 165, 0, 1800 - current * 5, 2000, 2000 - current * 25, 2000, 10);
			check((step != null) && (step.casts() == 4 - current), "progress towards a bounded completed plan");
		}
		for (int id : PhantomSkillFallbackRules.reservedSkills())
		{
			check(PhantomSkillFallbackRules.owner(id, false) != Owner.NONE, "every excluded action has a deliberate owner: " + id);
		}
		int duelistCharges = 0;
		int tripleSlashes = 0;
		int doubleSlashes = 0;
		for (int tick = 0; tick < 10; tick++)
		{
			if (duelistCharges >= 4)
			{
				tripleSlashes++;
				duelistCharges -= 4;
			}
			else if (PhantomChargePlanner.preferPlan(PhantomChargePlanner.plan(duelistCharges, 4, 7, 5, 25, 165, 0, 1800, 2000, 2000, 2000, 10), duelistCharges >= 3, 0, 1, true))
			{
				duelistCharges++;
			}
			else if (duelistCharges >= 3)
			{
				doubleSlashes++;
				duelistCharges -= 3;
			}
		}
		check((tripleSlashes == 2) && (doubleSlashes == 0) && (duelistCharges == 0), "sustained Duelist cycle reaches and spends four charges twice without low-tier starvation");
		for (int pack : new int[] {2, 4})
		{
			boolean danger = false;
			for (int mob = 0; mob < pack; mob++)
			{
				danger |= PhantomCombatPolicy.partyVictimDanger(false, 100, true);
			}
			check(PhantomCombatPolicy.context(false, false, true, danger) == Context.PARTY_PVE_SAFE, pack + " controlled mobs on a healthy tank stay safe");
		}
		for (String backline : new String[] {"nuker", "healer", "archer"})
		{
			check(PhantomCombatPolicy.context(false, false, true, PhantomCombatPolicy.partyVictimDanger(true, 100, true)) == Context.PARTY_PVE_DANGER, "aggro on " + backline + " lifts conservation");
		}
		check(PhantomCombatPolicy.partyVictimDanger(false, 35, true), "low-HP tank holding the pack is danger");
		check(PhantomCombatPolicy.partyVictimDanger(false, 100, false), "a controlled victim becoming stunned or paralyzed loses stable ownership");
		check(!PhantomCombatPolicy.pressured(true, 100, 4, false), "healthy frontliner own pack does not create pressure from count alone");
		check(PhantomCombatPolicy.pressured(true, 100, 2, true), "multiple attackers on a fragile actor are meaningful pressure");
		check(PhantomCombatPolicy.protectedTankPack(3, 3), "Provoke preserves a healthy dedicated tank's whole pack");
		check(!PhantomCombatPolicy.protectedTankPack(3, 2), "partial tank ownership alone does not veto; runtime Provoke still requires UNDER_ATTACK and needs live target-transfer validation");
		check(!PhantomCombatPolicy.protectedTankPack(3, 0), "pack already on the Warlord remains eligible");
		check(PhantomSkillFallbackRules.owner(254, false) == Owner.PLAYSTYLE, "authored Spoil owns managed combat");
		check(PhantomSkillFallbackRules.owner(254, true) == Owner.AUTO_USE, "legacy free-hunt Spoil has a single native owner");
		check(PhantomSkillFallbackRules.owner(42, false) == Owner.SWEEP, "managed corpse work precedes target scanning");
		check(PhantomSkillFallbackRules.owner(286, false) == Owner.PLAYSTYLE, "Provoke has a positive guarded-pack owner");
		check(PhantomCombatPolicy.provokePack(70, 3), "healthy Warlord can consolidate a validated existing pack");
		check(!PhantomCombatPolicy.provokePack(69, 3), "low HP prevents a broad native hate cast");
		check(!PhantomCombatPolicy.provokePack(100, 2), "one or two mobs do not justify the aura");
		check(!PhantomCombatPolicy.provokePack(100, Integer.MIN_VALUE), "neutral, slept, raid or unrelated aura occupants veto Provoke");
		check(PhantomCombatPolicy.obviousWaste(20, 30, true, false, false), "one-hit trash conserves authored MP");
		check(!PhantomCombatPolicy.obviousWaste(20, 30, false, false, false), "distant targets do not force casters into melee");
		check(!PhantomCombatPolicy.obviousWaste(20, 30, true, true, false), "overhit finishers remain intentional");
		final Path fixture = Path.of("build", "controller-config-test-" + System.nanoTime());
		final Path custom = fixture.resolve("Custom");
		final Path config = custom.resolve("FakePlayers.ini");
		final String fixtureDefaults = Files.readString(Path.of("dist/game/config/Custom/FakePlayers.ini")).replaceAll("(?m)^PhantomCombatController\\s*=.*\\R?", "");
		Files.createDirectories(custom);
		try
		{
			Files.writeString(config, fixtureDefaults);
			FakePlayersConfig.load(fixture.toString());
			check(FakePlayersConfig.PHANTOM_COMBAT_CONTROLLER, "missing controller key defaults on");
			Files.writeString(config, fixtureDefaults + "\nPhantomCombatController = True\n");
			FakePlayersConfig.load(fixture.toString());
			check(FakePlayersConfig.PHANTOM_COMBAT_CONTROLLER, "explicit opt-in is honored");
			Files.writeString(config, fixtureDefaults + "\nPhantomCombatController = False\n");
			FakePlayersConfig.load(fixture.toString());
			check(!FakePlayersConfig.PHANTOM_COMBAT_CONTROLLER, "explicit rollback remains available");
			check(new ConfigReader("dist/game/config/Custom/FakePlayers.ini").getBoolean("PhantomCombatController", false), "shipped rollout config matches the missing-key default");
		}
		finally
		{
			Files.deleteIfExists(config);
			Files.deleteIfExists(custom);
			Files.deleteIfExists(fixture);
		}
		System.out.println("PhantomClassPolicyTest: " + checks + " checks passed");
	}
}
