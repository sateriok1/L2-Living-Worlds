import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.l2jmobius.gameserver.config.PlayerConfig;
import org.l2jmobius.gameserver.data.holders.AccessLevel;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.StatSet;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.Summon;
import org.l2jmobius.gameserver.model.actor.enums.player.PrivateStoreType;
import org.l2jmobius.gameserver.model.actor.holders.creature.EffectList;
import org.l2jmobius.gameserver.model.actor.instance.Folk;
import org.l2jmobius.gameserver.model.actor.instance.Monster;
import org.l2jmobius.gameserver.model.actor.instance.Servitor;
import org.l2jmobius.gameserver.model.actor.stat.CreatureStat;
import org.l2jmobius.gameserver.model.actor.stat.NpcStat;
import org.l2jmobius.gameserver.model.actor.stat.PlayerStat;
import org.l2jmobius.gameserver.model.actor.status.CreatureStatus;
import org.l2jmobius.gameserver.model.actor.status.AttackableStatus;
import org.l2jmobius.gameserver.model.actor.status.FolkStatus;
import org.l2jmobius.gameserver.model.actor.status.PlayerStatus;
import org.l2jmobius.gameserver.model.actor.status.SummonStatus;
import org.l2jmobius.gameserver.model.actor.templates.PlayerTemplate;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.actor.templates.CreatureTemplate;
import org.l2jmobius.gameserver.model.effects.AbstractEffect;
import org.l2jmobius.gameserver.model.effects.EffectType;
import org.l2jmobius.gameserver.model.events.EventType;
import org.l2jmobius.gameserver.model.events.listeners.ConsumerEventListener;
import org.l2jmobius.gameserver.model.events.listeners.FunctionEventListener;
import org.l2jmobius.gameserver.model.events.returns.TerminateReturn;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.item.instance.Item;
import org.l2jmobius.gameserver.model.item.Weapon;
import org.l2jmobius.gameserver.model.item.type.WeaponType;
import org.l2jmobius.gameserver.model.itemcontainer.Inventory;
import org.l2jmobius.gameserver.model.itemcontainer.PlayerInventory;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.skill.EffectScope;
import org.l2jmobius.gameserver.model.skill.SkillOperateType;
import org.l2jmobius.gameserver.model.stats.Stat;
import org.l2jmobius.gameserver.model.stats.Formulas;
import org.l2jmobius.gameserver.model.stats.TraitType;
import org.l2jmobius.gameserver.model.zone.ZoneId;
import org.l2jmobius.gameserver.modules.ModuleDamage;
import org.l2jmobius.gameserver.modules.ModuleHandles;

import handlers.skill.effects.Heal;
import handlers.skill.effects.HealPercent;

/** Real native damage/status/effect paths; only database construction, packets and periodic tasks are isolated. */
public class ModuleDamageTest
{
	private record Hit(Creature source, Creature target, double amount, Skill skill, boolean dot) { }
	private static final List<Hit> hits = new ArrayList<>();
	private static final List<Hit> heals = new ArrayList<>();
	private static int checks;
	private static int failures;
	private static int id = 80000;

	private static void check(boolean result, String message)
	{
		checks++;
		if (!result)
		{
			failures++;
			System.err.println("FAIL: " + message);
		}
	}

	private static <T> T allocate(Class<T> type) throws Exception
	{
		final Class<?> unsafe = Class.forName("sun.misc.Unsafe");
		final Field field = unsafe.getDeclaredField("theUnsafe");
		field.setAccessible(true);
		return type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type));
	}

	private static void set(Object target, Class<?> owner, String name, Object value) throws Exception
	{
		final Field field = owner.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static <T extends Creature> T initialize(T creature) throws Exception
	{
		set(creature, WorldObject.class, "_objectId", ++id);
		set(creature, WorldObject.class, "_name", "combat-test-" + id);
		set(creature, WorldObject.class, "_location", new Location(0, 0, 0));
		set(creature, Creature.class, "_zones", new byte[ZoneId.getZoneCount()]);
		set(creature, Creature.class, "_invulAgainst", new ConcurrentHashMap<>());
		set(creature, Creature.class, "_effectList", new EffectList(creature));
		return creature;
	}

	private static void hp(Creature creature, double hp) throws Exception
	{
		set(creature.getStatus(), CreatureStatus.class, "_currentHp", hp);
		creature.setDead(false);
	}

	private static TestCreature creature(double hp) throws Exception
	{
		final TestCreature creature = initialize(allocate(TestCreature.class));
		set(creature, Creature.class, "_template", allocate(CreatureTemplate.class));
		set(creature, Creature.class, "_status", new QuietStatus(creature));
		hp(creature, hp);
		return creature;
	}

	private static Player player(double hp, double cp) throws Exception
	{
		final Player player = initialize(allocate(Player.class));
		set(player, Player.class, "_accessLevel", new AccessLevel());
		set(player, Player.class, "_privateStoreType", PrivateStoreType.NONE);
		set(player, Player.class, "_broadcastStatusUpdateTask", new QuietFuture());
		set(player, Player.class, "_inOlympiadMode", true); // Native lethal path without database death handling.
		set(player, Creature.class, "_stat", new TestPlayerStat(player));
		set(player, Creature.class, "_template", allocate(PlayerTemplate.class));
		set(player, Creature.class, "_status", new QuietPlayerStatus(player));
		set(player.getStatus(), PlayerStatus.class, "_currentCp", cp);
		set(player.getStatus(), CreatureStatus.class, "_currentMp", 1000d);
		hp(player, hp);
		return player;
	}

	private static Skill skill(EffectScope scope, AbstractEffect effect) throws Exception
	{
		final Skill skill = allocate(Skill.class);
		set(skill, Skill.class, "_operateType", SkillOperateType.A1);
		set(skill, Skill.class, "_traitType", TraitType.NONE);
		set(skill, Skill.class, "_element", (byte) -1);
		set(skill, Skill.class, "_effectLists", new EnumMap<>(Map.of(scope, List.of(effect))));
		return skill;
	}

	private static StatSet attributes(String name)
	{
		final StatSet set = new StatSet();
		set.set("name", name);
		return set;
	}

	private static AbstractEffect heal(double power)
	{
		final StatSet params = new StatSet();
		params.set("power", power);
		return new Heal(null, null, attributes("Heal"), params)
		{
			@Override public boolean calcSuccess(Creature source, Creature target, Skill skill) { return true; }
		};
	}

	private static ModuleDamage surface(ModuleHandles owner) throws Exception
	{
		// The same fixture runs against the original PR and the fixed API's internal constructor.
		for (Constructor<?> constructor : ModuleDamage.class.getDeclaredConstructors())
		{
			constructor.setAccessible(true);
			return (ModuleDamage) (constructor.getParameterCount() == 0 ? constructor.newInstance() : constructor.newInstance(owner));
		}
		throw new AssertionError("No module surface constructor");
	}

	private static double amount(List<Hit> events, Creature target)
	{
		return events.stream().filter(event -> event.target() == target).mapToDouble(Hit::amount).sum();
	}

	private static void damageCases(Player attacker) throws Exception
	{
		final TestCreature target = creature(1000);
		final Skill nuke = skill(EffectScope.GENERAL, heal(1));
		target.reduceCurrentHp(125, attacker, nuke);
		check(target.getCurrentHp() == 875 && amount(hits, target) == 125, "normal native HP damage");
		check(hits.size() == 1 && hits.getFirst().skill() == nuke && !hits.getFirst().dot(), "one callback preserves source skill");
		hits.clear();
		target.setInvul(true);
		target.reduceCurrentHp(20, attacker, nuke);
		check(hits.isEmpty() && target.getCurrentHp() == 875, "invulnerable hit produces no damage");
		target.reduceCurrentHp(20, attacker, false, true, nuke);
		check(amount(hits, target) == 20 && hits.getFirst().dot(), "native DOT bypass and attribution");
		hits.clear();
		target.setInvul(false);
		target.setDead(true);
		target.reduceCurrentHp(20, attacker, nuke);
		check(hits.isEmpty(), "already dead target produces no damage");
		hp(target, 1000);
		target.reduceCurrentHp(0, attacker, nuke);
		target.reduceCurrentHp(-10, attacker, nuke);
		target.reduceCurrentHp(10, null, nuke);
		check(hits.isEmpty() && target.getCurrentHp() == 990, "zero, negative and unattributed hits do not report");

		final Player victim = player(1000, 200);
		victim.reduceCurrentHp(125, attacker, null);
		check(victim.getCurrentHp() == 1000 && victim.getCurrentCp() == 75, "native PvP consumes CP first");
		check(hits.size() == 1 && amount(hits, victim) == 125, "CP-only damage counts once");
		hits.clear();
		victim.reduceCurrentHp(125, attacker, null);
		check(victim.getCurrentHp() == 950 && victim.getCurrentCp() == 0, "native hit crosses CP into HP");
		check(hits.size() == 1 && amount(hits, victim) == 125, "CP and HP combine into one callback");
		hits.clear();
		final Player npcVictim = player(50, 500);
		npcVictim.reduceCurrentHp(250, target, null);
		check(npcVictim.getCurrentHp() == 0 && npcVictim.getCurrentCp() == 500, "NPC hit does not consume CP");
		check(amount(hits, npcVictim) == 50, "NPC overkill excludes unused CP");
		hits.clear();
		final Player bypassVictim = player(50, 500);
		set(nuke, Skill.class, "_directHpDmg", true);
		bypassVictim.reduceCurrentHp(250, attacker, nuke);
		check(bypassVictim.getCurrentHp() == 0 && bypassVictim.getCurrentCp() == 500, "native direct HP skill bypasses CP");
		check(amount(hits, bypassVictim) == 50, "direct HP overkill excludes unused CP");
		hits.clear();

		set(attacker.getAccessLevel(), AccessLevel.class, "_isGm", true);
		set(attacker.getAccessLevel(), AccessLevel.class, "_giveDamage", false);
		hp(target, 1000);
		target.reduceCurrentHp(50, attacker, null);
		final Player gmVictim = player(1000, 100);
		gmVictim.reduceCurrentHp(50, attacker, null);
		check(target.getCurrentHp() == 1000 && gmVictim.getCurrentCp() == 100, "native GM damage permission rejects both paths");
		check(hits.isEmpty(), "rejected GM damage produces no events");
		set(attacker.getAccessLevel(), AccessLevel.class, "_isGm", false);
		hits.clear();

		final Folk folk = initialize(allocate(Folk.class));
		set(folk, Creature.class, "_status", new FolkStatus(folk));
		hp(folk, 1000);
		folk.reduceCurrentHp(50, attacker, null);
		check(folk.getCurrentHp() == 1000 && hits.isEmpty(), "native Folk no-damage status produces no event");
		hits.clear();

		final Player shielded = player(1000, 0);
		((TestPlayerStat) shielded.getStat()).shield = 50;
		shielded.reduceCurrentHp(100, attacker, null);
		check(shielded.getCurrentHp() == 1000 && shielded.getCurrentMp() == 950, "native mana shield absorbs damage");
		check(hits.isEmpty(), "MP absorption is not HP or CP damage");
		hits.clear();

		final Player owner = player(1000, 0);
		final Servitor summon = initialize(allocate(Servitor.class));
		set(summon, Summon.class, "_owner", owner);
		set(summon, Creature.class, "_template", allocate(NpcTemplate.class));
		set(summon, Creature.class, "_stat", new TestStat(summon));
		set(summon, Creature.class, "_status", new QuietSummonStatus(summon));
		hp(summon, 1000);
		set(owner, Player.class, "_summon", summon);
		((TestPlayerStat) owner.getStat()).transfer = 50;
		owner.reduceCurrentHp(100, attacker, null);
		check(owner.getCurrentHp() == 950 && summon.getCurrentHp() == 950, "native servitor damage transfer");
		check(amount(hits, owner) == 50 && amount(hits, summon) == 50 && hits.size() == 2, "transferred damage has one actual amount per recipient");
		hits.clear();
		hp(owner, 1000);
		hp(summon, 1000);
		owner.reduceCurrentHp(100, attacker, false, true, nuke);
		check(amount(hits, owner) == 50 && amount(hits, summon) == 50, "native DOT transfer counts only actual recipients");
		check(hits.stream().allMatch(event -> event.skill() == nuke && event.dot()), "native transfer retains skill and DOT attribution");
		hits.clear();

		final TestCreature revived = creature(50);
		revived.reviveOnDeath = true;
		set(revived, Creature.class, "_isMortal", true);
		revived.reduceCurrentHp(500, attacker, null);
		check(revived.getCurrentHp() == 1000, "fixture restores HP during native death handling");
		check(amount(hits, revived) == 50, "later HP restoration does not erase committed lethal damage");
		hits.clear();

		final TestCreature dying = creature(50);
		dying.nativeDeath = true;
		set(dying, Creature.class, "_isMortal", true);
		final boolean[] observed = { false };
		final TestCreature deathObserverTarget = creature(1000);
		dying.addListener(new ConsumerEventListener(dying, EventType.ON_CREATURE_DEATH, event ->
		{
			observed[0] = true;
			check(amount(hits, dying) == 50, "synchronous native death recap sees the finishing hit");
			deathObserverTarget.setCurrentHp(900); // A death observer's separate state change is outside this hit.
		}, dying));
		// Stop after the real native death event, before world rewards, zone and database cleanup.
		dying.addListener(new FunctionEventListener(dying, EventType.ON_CREATURE_KILLED, event -> new TerminateReturn(true, false, false), dying));
		dying.reduceCurrentHp(500, attacker, null);
		check(observed[0] && dying.isDead(), "real Creature death event ran");
		check(hits.size() == 1 && amount(hits, dying) == 50, "lethal flush and scope close do not count twice");
		check(amount(hits, deathObserverTarget) == 0, "death observer state changes are not charged to the finishing hit");
		hits.clear();
	}

	private static Player reflectionPlayer(double hp, double cp) throws Exception
	{
		final Player player = player(hp, cp);
		// Native weapon lookup without constructing database-backed inventory listeners.
		final PlayerInventory inventory = allocate(PlayerInventory.class);
		set(inventory, Inventory.class, "_paperdoll", new Item[Inventory.PAPERDOLL_TOTALSLOTS]);
		set(player, Player.class, "_inventory", inventory);
		player.getTemplate().setBaseAttackType(WeaponType.FIST);
		return player;
	}

	private static void reflectionCases(ModuleDamage surface) throws Exception
	{
		final Player reflector = reflectionPlayer(1000, 0);
		((TestPlayerStat) reflector.getStat()).reflectionChance = 100;
		final Skill melee = skill(EffectScope.GENERAL, heal(1));
		final Player attacker = reflectionPlayer(1000, 250);
		Formulas.calcDamageReflected(attacker, reflector, melee, false);
		check(attacker.getCurrentHp() == 1000 && attacker.getCurrentCp() == 150, "native reflection consumes CP first");
		check(hits.size() == 1 && amount(hits, attacker) == 100, "ordinary reflection has one actual damage callback");
		check(hits.getFirst().source() == reflector && hits.getFirst().target() == attacker, "reflector is the reflected damage source");
		check(hits.getFirst().skill() == null && !hits.getFirst().dot(), "physical-skill reflection has null skill metadata");
		hits.clear();

		final Player critical = reflectionPlayer(1000, 250);
		final boolean[] nested = { false };
		surface.addListener((source, victim, amount, reportedSkill, dot) ->
		{
			if ((victim == critical) && !nested[0])
			{
				nested[0] = true;
				critical.reduceCurrentHp(5, reflector, melee);
			}
		});
		Formulas.calcDamageReflected(critical, reflector, melee, true);
		check(critical.getCurrentHp() == 1000 && critical.getCurrentCp() == 45, "critical reflection preserves both native counter-hits and nested hit");
		check(hits.size() == 3 && hits.stream().filter(event -> event.skill() == null && event.amount() == 100).count() == 2,
			"both critical counter-hits have null skill metadata");
		check(hits.stream().anyMatch(event -> event.skill() == melee && event.amount() == 5), "callback's independent skill damage does not inherit reflection metadata");
		hits.clear();

		final Player directHp = reflectionPlayer(1000, 250);
		set(melee, Skill.class, "_directHpDmg", true);
		Formulas.calcDamageReflected(directHp, reflector, melee, false);
		check(directHp.getCurrentHp() == 900 && directHp.getCurrentCp() == 250, "reflection retains original native direct-HP flag");
		check(hits.size() == 1 && hits.getFirst().skill() == null && amount(hits, directHp) == 100,
			"direct-HP reflection changes only observer skill metadata");
		set(melee, Skill.class, "_directHpDmg", false);
		hits.clear();

		final Player owner = reflectionPlayer(1000, 0);
		final Servitor summon = initialize(allocate(Servitor.class));
		set(summon, Summon.class, "_owner", owner);
		set(summon, Creature.class, "_template", allocate(NpcTemplate.class));
		set(summon, Creature.class, "_stat", new TestStat(summon));
		set(summon, Creature.class, "_status", new QuietSummonStatus(summon));
		hp(summon, 1000);
		set(owner, Player.class, "_summon", summon);
		((TestPlayerStat) owner.getStat()).transfer = 50;
		Formulas.calcDamageReflected(owner, reflector, melee, false);
		check(owner.getCurrentHp() == 950 && summon.getCurrentHp() == 950, "reflection retains native servitor transfer");
		check(hits.size() == 2 && amount(hits, owner) == 50 && amount(hits, summon) == 50, "reflected transfer reports each actual recipient once");
		check(hits.stream().allMatch(event -> event.source() == reflector && event.skill() == null && !event.dot()),
			"transferred reflection retains reflector and null skill metadata");
		hits.clear();
		owner.reduceCurrentHp(100, reflector, melee);
		check(hits.size() == 2 && hits.stream().allMatch(event -> event.skill() == melee), "subsequent normal skill and transfer retain skill metadata");
		hits.clear();

		set(melee, Skill.class, "_magic", 1);
		Formulas.calcDamageReflected(attacker, reflector, melee, false);
		set(melee, Skill.class, "_magic", 0);
		set(melee, Skill.class, "_castRange", 41);
		Formulas.calcDamageReflected(attacker, reflector, melee, false);
		set(melee, Skill.class, "_castRange", 0);
		((TestPlayerStat) reflector.getStat()).reflectionChance = 0;
		Formulas.calcDamageReflected(attacker, reflector, melee, false);
		check(hits.isEmpty() && attacker.getCurrentCp() == 150, "magic, ranged and zero-chance skills produce no reflection");
		((TestPlayerStat) reflector.getStat()).reflectionChance = 100;
		attacker.setInvul(true);
		Formulas.calcDamageReflected(attacker, reflector, melee, true);
		check(hits.isEmpty() && attacker.getCurrentCp() == 150, "native invulnerability rejects both reflected counter-hits");
		attacker.setInvul(false);
		attacker.reduceCurrentHp(10, reflector, melee);
		check(hits.size() == 1 && hits.getFirst().skill() == melee, "rejected reflection leaves no metadata on the thread");
		hits.clear();
	}

	private static void healCases(Creature healer) throws Exception
	{
		final TestCreature target = creature(900);
		final Skill healing = skill(EffectScope.GENERAL, heal(200));
		healing.applyEffects(healer, target);
		check(target.getCurrentHp() == 1000 && amount(heals, target) == 100, "native Heal reports actual capped restoration");
		check(heals.size() == 1 && heals.getFirst().source() == healer && heals.getFirst().skill() == healing, "heal preserves caster and skill");
		heals.clear();
		healing.applyEffects(healer, target);
		check(heals.isEmpty(), "full HP produces no heal event");
		hp(target, 900);
		target.setInvul(true);
		healing.applyEffects(healer, target);
		check(heals.isEmpty() && target.getCurrentHp() == 900, "native Heal rejects invulnerable target");
		target.setInvul(false);
		target.setDead(true);
		healing.applyEffects(healer, target);
		check(heals.isEmpty(), "dead target produces no heal event");
		hp(target, 900);
		final Skill potion = skill(EffectScope.GENERAL, heal(435));
		set(potion, Skill.class, "_magic", 2); // Stock Quick Healing Potion 2038 uses a static A1 Heal.
		potion.applyEffects(healer, target);
		check(target.getCurrentHp() == 1000 && heals.isEmpty(), "potion works but does not earn healing credit");
		hp(target, 900);
		set(potion, Skill.class, "_magic", 0);
		set(potion, Skill.class, "_isRecoveryHerb", true);
		potion.applyEffects(healer, target);
		check(target.getCurrentHp() == 1000 && heals.isEmpty(), "recovery herb is excluded");
		hp(target, 900);
		final Skill redistribution = skill(EffectScope.GENERAL, new HpChange(200));
		redistribution.applyEffects(healer, target);
		check(target.getCurrentHp() == 1000 && heals.isEmpty(), "non-HEAL HP changes are not healing credit");
		hp(target, 900);
		final Skill self = skill(EffectScope.SELF, heal(200));
		self.applyEffects(target, target, true, false, true, 0);
		check(amount(heals, target) == 100 && heals.size() == 1, "instant SELF healing is reported");
		heals.clear();
		hp(target, 900);
		healing.applyEffects(healer, target, false, false, false, 0);
		check(target.getCurrentHp() == 900 && heals.isEmpty(), "disabled instant effects are not reported");
		final StatSet percent = new StatSet();
		percent.set("power", 20);
		final Skill percentHeal = skill(EffectScope.GENERAL, new HealPercent(null, null, attributes("HealPercent"), percent)
		{
			@Override public boolean calcSuccess(Creature source, Creature target, Skill skill) { return true; }
		});
		percentHeal.applyEffects(healer, target);
		check(amount(heals, target) == 100, "native HealPercent reports actual restoration");
		heals.clear();
		hp(target, 900);
		final Skill channel = skill(EffectScope.CHANNELING, heal(200));
		channel.applyEffects(healer, target);
		check(amount(heals, target) == 100, "instant CHANNELING heal is reported");
		heals.clear();
		final Player playerHealer = player(1000, 0);
		final Player playerTarget = player(500, 0);
		final StatSet onePercent = new StatSet();
		onePercent.set("power", 1);
		final Skill pvpHeal = skill(EffectScope.PVP, new HealPercent(null, null, attributes("HealPercent"), onePercent)
		{
			@Override public boolean calcSuccess(Creature source, Creature victim, Skill skill) { return true; }
		});
		pvpHeal.applyEffects(playerHealer, playerTarget);
		check(amount(heals, playerTarget) == 10000, "native player-to-player PVP heal scope is reported");
		heals.clear();
		final Monster monster = initialize(allocate(Monster.class));
		set(monster, Creature.class, "_stat", new TestAttackableStat(monster));
		set(monster, Creature.class, "_status", new QuietAttackableStatus(monster));
		set(monster, Creature.class, "_template", allocate(NpcTemplate.class));
		hp(monster, 500);
		final Skill pveHeal = skill(EffectScope.PVE, new HealPercent(null, null, attributes("HealPercent"), onePercent)
		{
			@Override public boolean calcSuccess(Creature source, Creature victim, Skill skill) { return true; }
		});
		pveHeal.applyEffects(playerHealer, monster);
		check(amount(heals, monster) == 10, "native player-to-monster PVE heal scope is reported");
		heals.clear();
		hp(target, 900);
		target.setCurrentHp(950); // Regeneration and non-skill HP changes never enter a heal effect scope.
		check(heals.isEmpty(), "unscoped regeneration has no healing attribution");
	}

	private static void concurrencyAndExceptions(Player attacker, ModuleDamage surface) throws Exception
	{
		final TestCreature target = creature(1000);
		final TestCreature other = creature(1000);
		final AtomicReference<Throwable> failure = new AtomicReference<>();
		surface.addListener((source, victim, amount, skill, dot) ->
		{
			check(!Thread.holdsLock(victim.getStatus()), "callbacks run outside native status monitor");
			if (victim == target)
			{
				other.setCurrentHp(other.getCurrentHp() - 30); // A callback mutation must not be charged to its parent hit.
			}
		});
		final Thread worker = new Thread(() ->
		{
			try { target.reduceCurrentHp(20, attacker, null); }
			catch (Throwable error) { failure.set(error); }
		});
		worker.start();
		worker.join(5000);
		check(!worker.isAlive() && failure.get() == null, "damage worker completes");
		check(amount(hits, target) == 20 && amount(hits, other) == 0, "callback HP change is not attributed to the hit");
		hits.clear();
		final CountDownLatch entered = new CountDownLatch(1);
		final CountDownLatch resume = new CountDownLatch(1);
		final StatSet params = new StatSet();
		params.set("power", 50);
		final Skill racingHeal = skill(EffectScope.GENERAL, new Heal(null, null, attributes("Heal"), params)
		{
			@Override public boolean calcSuccess(Creature source, Creature victim, Skill skill) { return true; }
			@Override public void onStart(Creature source, Creature victim, Skill skill)
			{
				entered.countDown();
				try { if (!resume.await(5, TimeUnit.SECONDS)) { throw new AssertionError("heal wait timed out"); } }
				catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
				super.onStart(source, victim, skill);
			}
		});
		hp(target, 900);
		final TestCreature healer = creature(1000);
		final Thread healingThread = new Thread(() ->
		{
			try { racingHeal.applyEffects(healer, target); }
			catch (Throwable error) { failure.set(error); }
		});
		healingThread.start();
		try
		{
			check(entered.await(5, TimeUnit.SECONDS), "heal thread enters instant effect");
			target.setCurrentHp(920); // Other-thread regeneration must not enter the healer's attribution.
		}
		finally { resume.countDown(); }
		healingThread.join(5000);
		check(!healingThread.isAlive() && failure.get() == null && target.getCurrentHp() == 970, "native healing and concurrent HP gain complete");
		check(amount(heals, target) == 50, "heal attribution excludes concurrent regeneration");
		heals.clear();
		final Skill broken = skill(EffectScope.GENERAL, new HpChange(0)
		{
			@Override public EffectType getEffectType() { return EffectType.HEAL; }
			@Override public void onStart(Creature source, Creature victim, Skill skill) { throw new IllegalStateException("effect failure"); }
		});
		try { broken.applyEffects(attacker, target); }
		catch (IllegalStateException expected) { check(true, "native effect exception is preserved"); }
		target.setCurrentHp(900);
		check(heals.isEmpty(), "effect failure leaves no heal capture on the thread");
		surface.addListener((source, victim, amount, skill, dot) -> { throw new IllegalStateException("listener failure"); });
		surface.addListener((source, victim, amount, skill, dot) -> check(amount > 0, "later listener still receives positive damage"));
		target.reduceCurrentHp(10, attacker, null);
		check(amount(hits, target) == 10, "listener exception does not alter native damage or block later callbacks");
	}

	public static void main(String[] args) throws Exception
	{
		PlayerConfig.DISABLE_TUTORIAL = true;
		final Player attacker = player(1000, 0);
		final TestCreature disabled = creature(1000);
		check(!ModuleDamage.active() && !ModuleDamage.healActive(), "no modules starts with empty registries");
		disabled.reduceCurrentHp(10, attacker, null);
		check(disabled.getCurrentHp() == 990, "native damage works with no listener");
		final Player unobservedReflector = reflectionPlayer(1000, 0);
		((TestPlayerStat) unobservedReflector.getStat()).reflectionChance = 100;
		final Player unobservedAttacker = reflectionPlayer(1000, 250);
		Formulas.calcDamageReflected(unobservedAttacker, unobservedReflector, skill(EffectScope.GENERAL, heal(1)), false);
		check(unobservedAttacker.getCurrentCp() == 150 && unobservedAttacker.getCurrentHp() == 1000, "native reflection works with no listener");
		final ModuleHandles owner = new ModuleHandles("damage-test");
		final ModuleDamage surface = surface(owner);
		surface.addListener((source, target, amount, skill, dot) -> hits.add(new Hit(source, target, amount, skill, dot)));
		surface.addHealListener((source, target, amount, skill) -> heals.add(new Hit(source, target, amount, skill, false)));
		check(owner.size() == 2, "framework owns both registrations");
		damageCases(attacker);
		reflectionCases(surface);
		healCases(creature(1000));
		concurrencyAndExceptions(attacker, surface);
		try { surface.addListener(null); check(false, "null damage listener rejected"); }
		catch (NullPointerException expected) { check(true, "null damage listener rejected"); }
		try { surface.addHealListener(null); check(false, "null heal listener rejected"); }
		catch (NullPointerException expected) { check(true, "null heal listener rejected"); }
		System.out.println("Module damage: " + (checks - failures) + "/" + checks + " checks passed");
		if (failures != 0) { throw new AssertionError(failures + " module damage checks failed"); }
	}

	private static class QuietFuture extends FutureTask<Void> implements ScheduledFuture<Void>
	{
		QuietFuture() { super(() -> null); }
		@Override public long getDelay(TimeUnit unit) { return 0; }
		@Override public int compareTo(Delayed other) { return 0; }
	}

	private static class QuietStatus extends CreatureStatus
	{
		QuietStatus(Creature creature) { super(creature); }
		@Override public synchronized void startHpMpRegeneration() { }
		@Override public synchronized void stopHpMpRegeneration() { }
	}
	private static class QuietPlayerStatus extends PlayerStatus
	{
		QuietPlayerStatus(Player player) { super(player); }
		@Override public synchronized void startHpMpRegeneration() { }
		@Override public synchronized void stopHpMpRegeneration() { }
	}
	private static class QuietSummonStatus extends SummonStatus
	{
		QuietSummonStatus(Summon summon) { super(summon); }
		@Override public synchronized void startHpMpRegeneration() { }
		@Override public synchronized void stopHpMpRegeneration() { }
	}
	private static class QuietAttackableStatus extends AttackableStatus
	{
		QuietAttackableStatus(Attackable actor) { super(actor); }
		@Override public synchronized void startHpMpRegeneration() { }
		@Override public synchronized void stopHpMpRegeneration() { }
	}
	private static class TestPlayerStat extends PlayerStat
	{
		double shield;
		double transfer;
		double reflectionChance;
		TestPlayerStat(Player player) { super(player); }
		@Override public int getMaxHp() { return 1000000; } // Keep tests below the native UserInfo threshold.
		@Override public int getMaxCp() { return 1000; }
		@Override public int getMaxMp() { return 1000; }
		@Override public double getMAtk(Creature target, Skill skill) { return 0; }
		@Override public double getPAtk(Creature target) { return 100; }
		@Override public double getPDef(Creature target) { return 700; }
		@Override public double calcStat(Stat stat, double initial, Creature target, Skill skill)
		{
			return stat == Stat.MANA_SHIELD_PERCENT ? shield : stat == Stat.TRANSFER_DAMAGE_PERCENT ? transfer
				: stat == Stat.VENGEANCE_SKILL_PHYSICAL_DAMAGE ? reflectionChance : initial;
		}
	}
	private static class TestStat extends CreatureStat
	{
		TestStat(Creature creature) { super(creature); }
		@Override public int getMaxHp() { return 1000; }
	}
	private static class TestAttackableStat extends NpcStat
	{
		TestAttackableStat(Attackable actor) { super(actor); }
		@Override public int getMaxHp() { return 1000; }
		@Override public double calcStat(Stat stat, double initial, Creature target, Skill skill) { return initial; }
	}
	private static class HpChange extends AbstractEffect
	{
		private final double amount;
		HpChange(double amount) { super(null, null, attributes("RebalanceHP"), new StatSet()); this.amount = amount; }
		@Override public boolean isInstant() { return true; }
		@Override public boolean calcSuccess(Creature source, Creature target, Skill skill) { return true; }
		@Override public void onStart(Creature source, Creature target, Skill skill) { target.setCurrentHp(target.getCurrentHp() + amount); }
	}
	private static class TestCreature extends Creature
	{
		boolean reviveOnDeath;
		boolean nativeDeath;
		TestCreature() { super(null); }
		@Override public int getMaxHp() { return 1000; }
		@Override public int getMaxRecoverableHp() { return 1000; }
		@Override public double getMAtk(Creature target, Skill skill) { return 0; }
		@Override public double calcStat(Stat stat, double initial, Creature target, Skill skill) { return initial; }
		@Override public boolean doDie(Creature killer)
		{
			if (nativeDeath) { return super.doDie(killer); }
			if (reviveOnDeath) { setCurrentHp(1000); } else { setDead(true); }
			return true;
		}
		@Override public void broadcastStatusUpdate() { }
		@Override public void updateAbnormalEffect() { }
		@Override public Item getActiveWeaponInstance() { return null; }
		@Override public Weapon getActiveWeaponItem() { return null; }
		@Override public Item getSecondaryWeaponInstance() { return null; }
		@Override public ItemTemplate getSecondaryWeaponItem() { return null; }
		@Override public int getLevel() { return 80; }
		@Override public void sendInfo(Player player) { }
		@Override public boolean isAutoAttackable(Creature attacker) { return true; }
	}
}
