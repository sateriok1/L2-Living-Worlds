import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.ai.PlayerAI;
import org.l2jmobius.gameserver.config.custom.FakePlayersConfig;
import org.l2jmobius.gameserver.data.holders.AccessLevel;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.data.xml.PetSkillData;
import org.l2jmobius.gameserver.managers.PhantomEncounterRules;
import org.l2jmobius.gameserver.managers.PhantomManager;
import org.l2jmobius.gameserver.managers.PhantomPartyManager;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.StatSet;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.Summon;
import org.l2jmobius.gameserver.model.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.model.actor.enums.player.PrivateStoreType;
import org.l2jmobius.gameserver.model.olympiad.Olympiad;
import org.l2jmobius.gameserver.model.sevensigns.SevenSignsFestival;
import org.l2jmobius.gameserver.model.sevensigns.SevenSigns;
import org.l2jmobius.gameserver.model.actor.instance.Servitor;
import org.l2jmobius.gameserver.model.actor.stat.PlayerStat;
import org.l2jmobius.gameserver.model.actor.stat.SummonStat;
import org.l2jmobius.gameserver.model.actor.status.CreatureStatus;
import org.l2jmobius.gameserver.model.actor.status.PlayerStatus;
import org.l2jmobius.gameserver.model.actor.templates.PlayerTemplate;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.conditions.ConditionPlayerHp;
import org.l2jmobius.gameserver.model.effects.EffectType;
import org.l2jmobius.gameserver.model.actor.holders.creature.EffectList;
import org.l2jmobius.gameserver.model.itemcontainer.PlayerInventory;
import org.l2jmobius.gameserver.model.groups.Party;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.skill.BuffInfo;
import org.l2jmobius.gameserver.model.skill.AbnormalType;
import org.l2jmobius.gameserver.model.stats.Stat;
import org.l2jmobius.gameserver.model.skill.targets.TargetType;
import org.l2jmobius.gameserver.model.zone.ZoneId;
import org.l2jmobius.gameserver.network.GameClient;
import org.l2jmobius.gameserver.network.serverpackets.ServerPacket;

/** Native cast gates and manager lifecycle, with packet, AI scheduling and pet removal boundaries isolated. */
public class PhantomPreparationIntegrationTest
{
	private static int checks;
	private static int failures;
	private static final Class<?> DATA;
	static
	{
		try { DATA = Class.forName(PhantomManager.class.getName() + "$PhantomData"); }
		catch (Exception e) { throw new ExceptionInInitializerError(e); }
	}
	private static void check(boolean ok, String message)
	{
		checks++;
		if (!ok) { failures++; System.err.println("FAIL: " + message); }
	}
	private static Object get(Object object, Class<?> type, String name) throws Exception
	{
		Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(object);
	}
	private static <T> T allocate(Class<T> type) throws Exception
	{
		Class<?> unsafe = Class.forName("sun.misc.Unsafe");
		Field field = unsafe.getDeclaredField("theUnsafe"); field.setAccessible(true);
		return type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type));
	}
	private static void set(Object object, Class<?> type, String name, Object value) throws Exception
	{
		Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(object, value);
	}
	private static Object invoke(PhantomManager manager, String name, Class<?>[] types, Object... args) throws Exception
	{
		Method method = PhantomManager.class.getDeclaredMethod(name, types); method.setAccessible(true);
		return method.invoke(manager, args);
	}
	public static class Client extends GameClient
	{
		public Client() { super(null); }
		@Override public void sendPacket(ServerPacket packet) { }
	}
	private static class Stats extends PlayerStat
	{
		Stats(Player actor) { super(actor); }
		@Override public int getMaxHp() { return 1000; }
		@Override public int getMaxMp() { return 1000; }
		@Override public double getRunSpeed() { return 100; }
		@Override public double getWalkSpeed() { return 100; }
		@Override public double calcStat(Stat stat, double initial, Creature target, Skill skill) { return initial; }
	}
	private static class CrystalInventory extends PlayerInventory
	{
		CrystalInventory(Player player) { super(player); }
		@Override public int getInventoryItemCount(int id, int enchant) { return id == 8615 ? 1 : 0; }
	}
	private static class PetStats extends SummonStat
	{
		PetStats(Summon pet) { super(pet); }
		@Override public int getMaxHp() { return 1000; }
		@Override public int getMaxMp() { return 1000; }
		@Override public double calcStat(Stat stat, double initial, Creature target, Skill skill) { return initial; }
	}
	private static class TargetedSkill extends Skill
	{
		TargetedSkill() { super((StatSet) null); }
		@Override public WorldObject getFirstOfTargetList(Creature caster) { return caster.getTarget(); }
	}
	private static class IdleAI extends PlayerAI
	{
		IdleAI(Player actor) { super(actor); }
		@Override public void setIntention(Intention intention, Object a, Object b)
		{
			_intention = intention;
			_attackTarget = (intention == Intention.ATTACK) ? (Creature) a : null;
		}
		@Override protected void onActionFinishCasting() { }
	}
	private static class Pet extends Servitor
	{
		int aborts;
		int removals;
		Pet() { super(null, null); }
		@Override public void abortAttack() { aborts++; }
		@Override public void abortCast() { setCastingNow(false); }
		@Override public void unSummon(Player owner) { removals++; owner.setPet(null); }
	}
	private static Player player(int id, int classId) throws Exception
	{
		Player actor = allocate(Player.class);
		set(actor, WorldObject.class, "_objectId", id);
		set(actor, WorldObject.class, "_location", new Location(0, 0, 0));
		set(actor, Creature.class, "_zones", new byte[ZoneId.getZoneCount()]);
		set(actor, Creature.class, "_isRunning", true);
		set(actor, Creature.class, "_skills", new ConcurrentHashMap<Integer, Skill>());
		set(actor, Creature.class, "_reuseTimeStampsSkills", new ConcurrentHashMap<>());
		set(actor, Creature.class, "_disabledSkills", new ConcurrentHashMap<>());
		set(actor, Creature.class, "_effectList", new EffectList(actor));
		set(actor, Player.class, "_accessLevel", allocate(AccessLevel.class));
		set(actor, Player.class, "_autoPlaying", new AtomicBoolean(false));
		set(actor, Player.class, "_charges", new AtomicInteger());
		set(actor, Player.class, "_privateStoreType", PrivateStoreType.NONE);
		PlayerTemplate template = allocate(PlayerTemplate.class);
		set(template, PlayerTemplate.class, "_playerClass", PlayerClass.getPlayerClass(classId));
		set(actor, Creature.class, "_template", template);
		set(actor, Player.class, "_client", allocate(Client.class));
		set(actor, Player.class, "_inventory", new PlayerInventory(actor));
		set(actor, Creature.class, "_stat", new Stats(actor));
		PlayerStatus status = allocate(PlayerStatus.class);
		set(status, CreatureStatus.class, "_currentHp", 1000d);
		set(status, CreatureStatus.class, "_currentMp", 50d);
		set(actor, Creature.class, "_status", status);
		set(actor, Creature.class, "_ai", new IdleAI(actor));
		actor.setSpawned(true);
		return actor;
	}
	private static Skill skill(int id) throws Exception
	{
		Skill skill = allocate(Skill.class);
		set(skill, Skill.class, "_id", id);
		set(skill, Skill.class, "_targetType", TargetType.SELF);
		set(skill, Skill.class, "_mpConsume", 36);
		set(skill, Skill.class, "_mpInitialConsume", 144);
		return skill;
	}
	private static Object data(Set<Integer> attempts, long until) throws Exception
	{
		Object data = allocate(DATA);
		set(data, DATA, "encounterPrepDone", attempts);
		set(data, DATA, "encounterPrepUntil", until);
		set(data, DATA, "encounterActor", true);
		set(data, DATA, "encounterGroup", new PhantomEncounterRules.EncounterGroup(1, null, null));
		return data;
	}
	private static void illegalPreparation() throws Exception
	{
		PhantomManager manager = allocate(PhantomManager.class);
		Player actor = player(41001, 114);
		Skill zealot = skill(420);
		set(zealot, Skill.class, "_preCondition", List.of(new ConditionPlayerHp(30)));
		set(actor, Creature.class, "_skills", new ConcurrentHashMap<>(java.util.Map.of(420, zealot)));
		check(!zealot.checkCondition(actor, actor, false), "native Zealot condition rejects full HP");
		Set<Integer> attempts = new HashSet<>();
		Object data = data(attempts, 20000);
		invoke(manager, "prepareEncounterActor", new Class<?>[] { Player.class, DATA, long.class }, actor, data, 10000L);
		check(!attempts.contains(420), "illegal full-HP Zealot is never attempted");
		actor.getAllSkills().clear();
		Skill lionheart = skill(287);
		set(actor, Creature.class, "_skills", new ConcurrentHashMap<>(java.util.Map.of(287, lionheart)));
		set(actor.getTemplate(), PlayerTemplate.class, "_playerClass", PlayerClass.getPlayerClass(88));
		attempts.clear();
		check(!actor.checkDoCastConditions(lionheart), "native MP gate includes initial MP");
		invoke(manager, "prepareEncounterActor", new Class<?>[] { Player.class, DATA, long.class }, actor, data, 10000L);
		check(!attempts.contains(287), "unaffordable preparation does not consume the one-time attempt");
		Method allowed = PhantomPartyManager.class.getDeclaredMethod("canCastSupportSkill", Player.class, Skill.class, WorldObject.class);
		allowed.setAccessible(true);
		set(lionheart, Skill.class, "_magic", 2); // Isolate resource tests from mute/weapon-dependent effects.
		set(actor.getStatus(), CreatureStatus.class, "_currentMp", 179d);
		check(!(boolean) allowed.invoke(null, actor, lionheart, actor), "179 MP cannot pay a 36 + 144 MP cast");
		set(actor.getStatus(), CreatureStatus.class, "_currentMp", 180d);
		check((boolean) allowed.invoke(null, actor, lionheart, actor), "exact full MP cost is accepted");
		set(lionheart, Skill.class, "_chargeConsume", 4);
		check(!(boolean) allowed.invoke(null, actor, lionheart, actor), "native charge requirement remains authoritative");
		set(actor, Player.class, "_charges", new AtomicInteger(4));
		check((boolean) allowed.invoke(null, actor, lionheart, actor), "sufficient charges restore eligibility");
		set(lionheart, Skill.class, "_hpConsume", 1000);
		check(!(boolean) allowed.invoke(null, actor, lionheart, actor), "native HP cost cannot kill the caster");
		set(lionheart, Skill.class, "_hpConsume", 0);
		set(lionheart, Skill.class, "_preCondition", List.of(new ConditionPlayerHp(30)));
		check(!(boolean) allowed.invoke(null, actor, lionheart, actor), "full resources never bypass XML HP eligibility");
		set(actor.getStatus(), CreatureStatus.class, "_currentHp", 300d);
		check((boolean) allowed.invoke(null, actor, lionheart, actor), "legal low-HP condition with full resources is accepted");
		set(actor, Creature.class, "_isCastingNow", true);
		check((boolean) invoke(manager, "prepareEncounterActor", new Class<?>[] { Player.class, DATA, long.class }, actor, data, 21000L), "in-flight summon/preparation cast survives preparation deadline");
	}
	private static void teardown() throws Exception
	{
		PhantomManager manager = allocate(PhantomManager.class);
		Player actor = player(42001, 96);
		Player victim = player(42002, 88);
		Pet pet = allocate(Pet.class);
		set(pet, WorldObject.class, "_objectId", 42003);
		set(pet, WorldObject.class, "_location", new Location(0, 0, 0));
		set(pet, Creature.class, "_ai", new IdleAI(actor));
		pet.setTarget(victim);
		pet.getAI().setIntention(Intention.ATTACK, victim);
		actor.setPet(pet);
		Object data = data(new HashSet<>(), 0);
		PhantomEncounterRules.markHostile(actor.getObjectId(), victim.getObjectId());
		invoke(manager, "endEncounter", new Class<?>[] { Player.class, DATA, Player.class, long.class, long.class, boolean.class }, actor, data, victim, 30000L, 1500L, false);
		check(!PhantomEncounterRules.isHostile(actor.getObjectId(), victim.getObjectId()), "owner hostility ends");
		check(pet.aborts > 0, "pet attack is aborted immediately");
		check(pet.getTarget() == null && pet.getAI().getIntention() == Intention.IDLE, "pet target and autonomous attack intention end immediately");
		check(pet.removals == 1 && actor.getSummon() == null, "encounter pet leaves before the owner departure delay");
	}
	private static void latePetAndRevocation() throws Exception
	{
		PhantomManager manager = allocate(PhantomManager.class);
		Player actor = player(43001, 96);
		Player victim = player(43002, 88);
		Player other = player(43004, 88);
		Pet pet = allocate(Pet.class);
		set(pet, WorldObject.class, "_objectId", 43003);
		set(pet, WorldObject.class, "_location", new Location(0, 0, 0));
		set(pet, Creature.class, "_zones", new byte[ZoneId.getZoneCount()]);
		set(pet, Creature.class, "_effectList", new EffectList(pet));
		set(pet, Creature.class, "_reuseTimeStampsSkills", new ConcurrentHashMap<>());
		set(pet, Creature.class, "_disabledSkills", new ConcurrentHashMap<>());
		set(pet, Creature.class, "_stat", new PetStats(pet));
		PlayerStatus status = allocate(PlayerStatus.class);
		set(status, CreatureStatus.class, "_currentHp", 1000d);
		set(status, CreatureStatus.class, "_currentMp", 1000d);
		set(pet, Creature.class, "_status", status);
		set(pet, Creature.class, "_ai", new IdleAI(actor));
		pet.setOwner(actor);
		actor.setPet(pet);
		Object data = data(new HashSet<>(), 0);
		SkillData.requested.clear();
		invoke(manager, "prepareEncounterActor", new Class<?>[] { Player.class, DATA, long.class }, actor, data, 31000L);
		check(SkillData.requested.contains(1204) && SkillData.requested.contains(1068) && SkillData.requested.contains(1062), "late pet requests common/melee/Berserker spawn kit after budget expires");
		int kitSize = SkillData.requested.size();
		invoke(manager, "prepareEncounterActor", new Class<?>[] { Player.class, DATA, long.class }, actor, data, 32000L);
		check(SkillData.requested.size() == kitSize, "same pet is initialized once");
		set(data, DATA, "encounterPhase", 3);
		PhantomEncounterRules.markHostile(actor.getObjectId(), victim.getObjectId());
		FakePlayersConfig.PHANTOM_PVP_ENABLED = true;
		Class<?>[] gateTypes = { Player.class, DATA, Player.class };
		check((boolean) invoke(manager, "encounterPetAttackAllowed", gateTypes, actor, data, victim), "exact white encounter victim authorized");
		check(!(boolean) invoke(manager, "encounterPetAttackAllowed", gateTypes, actor, data, other), "white bystander never authorized");
		for (Player participant : new Player[] { actor, victim })
		{
			for (ZoneId zone : new ZoneId[] { ZoneId.PEACE, ZoneId.NO_PVP })
			{
				participant.setInsideZone(zone, true);
				check(!(boolean) invoke(manager, "encounterPetAttackAllowed", gateTypes, actor, data, victim), "protected source/target zone rejects pet command");
				participant.setInsideZone(zone, false);
			}
			for (String field : new String[] { "_isInDuel", "_inOlympiadMode" })
			{
				set(participant, Player.class, field, true);
				check(!(boolean) invoke(manager, "encounterPetAttackAllowed", gateTypes, actor, data, victim), "source/target duel or Olympiad rejects pet command");
				set(participant, Player.class, field, false);
			}
			participant.getLocation().setInstanceId(1);
			check(!(boolean) invoke(manager, "encounterPetAttackAllowed", gateTypes, actor, data, victim), "instanced participant rejects pet command");
			participant.getLocation().setInstanceId(0);
		}
		Skill damage = allocate(TargetedSkill.class);
		set(damage, Skill.class, "_id", 900001);
		set(damage, Skill.class, "_targetType", TargetType.ONE);
		set(damage, Skill.class, "_effectPoint", -1);
		set(damage, Skill.class, "_effectTypes", new Byte[] { (byte) EffectType.MAGICAL_ATTACK.ordinal() });
		pet.setTarget(victim);
		check(pet.getOwner() == actor && pet.getTarget() == victim && !pet.isDead() && !pet.isCastingNow(), "native pet skill fixture is live with its requested target");
		check(pet.getCurrentHp() > damage.getHpConsume() && pet.getCurrentMp() >= pet.getStat().getMpConsume(damage), "native pet skill fixture has resources");
		check(!pet.useMagic(damage, false, false), "native unforced targeted pet skill rejects white victim");
		check(pet.useMagic(damage, true, false), "native forced pet target selection accepts exact white victim");
		check(actor.checkPvpSkill(victim, damage), "native owner PvP gate permits exact victim");
		check(!actor.checkPvpSkill(other, damage), "pet force-use does not force owner's bystander PvP gate");
		pet.getAI().setIntention(Intention.ATTACK, victim);
		pet.setCastingNow(true);
		set(victim, Player.class, "_isInDuel", true);
		invoke(manager, "commandEncounterPet", gateTypes, actor, data, victim);
		check(pet.getTarget() == null && pet.getAI().getIntention() == Intention.IDLE && !pet.isCastingNow(), "revoked authorization stops existing attack and cast");
		check(pet.removals == 1, "revoked authorization removes pet immediately");
		PhantomEncounterRules.clearHostile(actor.getObjectId());
	}
	private static void fullServicePreparationClock() throws Exception
	{
		PhantomManager manager = allocate(PhantomManager.class);
		Player actor = player(44001, 96);
		Player victim = player(44002, 88);
		victim.setOnlineStatus(true, false);
		victim.getLocation().setXYZ(1000, 0, 0);
		World.players.put(victim.getObjectId(), victim);
		Object data = data(new HashSet<>(), 110000L);
		PhantomEncounterRules.Style style = new PhantomEncounterRules.Style(PhantomEncounterRules.Approach.ASK_FIRST, 10, 30, 7, 4, null, null);
		set(data, DATA, "encounterGroup", new PhantomEncounterRules.EncounterGroup(1, style, null));
		set(data, DATA, "encounterVictimOid", victim.getObjectId());
		set(data, DATA, "encounterPhase", 1);
		set(data, DATA, "encounterDeadline", 0L);
		set(data, DATA, "encounterPreparing", true);
		// Native cast cancellation state; completion and service time are controlled without wall-clock sleeps.
		FutureTask<Void> summon = new FutureTask<>(() -> null);
		set(actor, Creature.class, "_skillCast", summon);
		actor.setCastingNow(true); // Started at 101000, completion at 116000 (native summon base hit time).
		Class<?>[] tick = { Player.class, DATA, long.class };
		invoke(manager, "serviceEncounter", tick, actor, data, 110000L);
		check((long) get(data, DATA, "encounterEndAt") == 0 && actor.isCastingNow() && !summon.isCancelled(), "full service preserves fifteen-second summon at minimum approach deadline");
		if ((long) get(data, DATA, "encounterEndAt") != 0) { return; }
		invoke(manager, "serviceEncounter", tick, actor, data, 115000L);
		check(!summon.isCancelled() && actor.isCastingNow(), "preparation remains active while native summon is in flight");
		summon.run(); actor.setCastingNow(false);
		invoke(manager, "serviceEncounter", tick, actor, data, 116000L);
		check((long) get(data, DATA, "encounterDeadline") == 126000L, "approach receives its full ten seconds after preparation finishes");
		invoke(manager, "serviceEncounter", tick, actor, data, 125999L);
		check((long) get(data, DATA, "encounterEndAt") == 0, "approach survives until its own deadline");
		invoke(manager, "serviceEncounter", tick, actor, data, 126000L);
		check((long) get(data, DATA, "encounterEndAt") > 0, "separate approach allowance still expires");
		World.players.clear();
		for (boolean offline : new boolean[] { false, true })
		{
			Object pending = data(new HashSet<>(), 210000L);
			set(pending, DATA, "encounterGroup", new PhantomEncounterRules.EncounterGroup(1, style, null));
			set(pending, DATA, "encounterVictimOid", victim.getObjectId());
			set(pending, DATA, "encounterPreparing", true);
			World.players.put(victim.getObjectId(), victim);
			victim.setOnlineStatus(!offline, false);
			victim.setInsideZone(ZoneId.NO_PVP, !offline);
			FutureTask<Void> cast = new FutureTask<>(() -> null);
			set(actor, Creature.class, "_skillCast", cast); actor.setCastingNow(true);
			invoke(manager, "serviceEncounter", tick, actor, pending, 205000L);
			check((long) get(pending, DATA, "encounterEndAt") > 0 && cast.isCancelled(), "protected or offline victim cancels preparation immediately: " + offline);
			victim.setInsideZone(ZoneId.NO_PVP, false);
		}
		victim.setOnlineStatus(true, false);
		Object fighting = data(new HashSet<>(), 0L);
		set(fighting, DATA, "encounterGroup", new PhantomEncounterRules.EncounterGroup(1, style, null));
		set(fighting, DATA, "encounterVictimOid", victim.getObjectId());
		set(fighting, DATA, "encounterPhase", 3); set(fighting, DATA, "encounterDeadline", 230000L);
		invoke(manager, "serviceEncounter", tick, actor, fighting, 230000L);
		check((long) get(fighting, DATA, "encounterEndAt") > 0, "fight deadline still expires independently of preparation");
		World.players.clear();
	}
	private static Skill utility(int id, TargetType type, AbnormalType slot) throws Exception
	{
		Skill skill = allocate(TargetedSkill.class);
		set(skill, Skill.class, "_id", id);
		set(skill, Skill.class, "_targetType", type);
		set(skill, Skill.class, "_abnormalType", slot);
		set(skill, Skill.class, "_affectRange", 1000);
		set(skill, Skill.class, "_castRange", 900);
		set(skill, Skill.class, "_magic", 1);
		return skill;
	}
	private static void effect(Player player, Skill skill, boolean debuff) throws Exception
	{
		BuffInfo info = allocate(BuffInfo.class);
		set(info, BuffInfo.class, "_skill", skill);
		(debuff ? player.getEffectList().getDebuffs() : player.getEffectList().getBuffs()).add(info);
	}
	private static void supportServitorUtility() throws Exception
	{
		Player owner = player(46001, 96), enemy = player(46002, 88);
		Pet pet = allocate(Pet.class);
		set(pet, Creature.class, "_template", allocate(NpcTemplate.class));
		set(pet, WorldObject.class, "_objectId", 46003);
		set(pet, WorldObject.class, "_location", new Location(0, 0, 0));
		set(pet, Creature.class, "_zones", new byte[ZoneId.getZoneCount()]);
		set(pet, Creature.class, "_effectList", new EffectList(pet));
		set(pet, Creature.class, "_reuseTimeStampsSkills", new ConcurrentHashMap<>());
		set(pet, Creature.class, "_disabledSkills", new ConcurrentHashMap<>());
		set(pet, Creature.class, "_stat", new PetStats(pet));
		PlayerStatus status = allocate(PlayerStatus.class);
		set(status, CreatureStatus.class, "_currentHp", 1000d);
		set(status, CreatureStatus.class, "_currentMp", 1000d);
		set(pet, Creature.class, "_status", status);
		set(pet, Creature.class, "_ai", new IdleAI(owner));
		pet.setOwner(owner); owner.setPet(pet);
		Method action = PhantomPartyManager.class.getDeclaredMethod("tryServitorUtility", Summon.class, Creature.class, boolean.class);
		action.setAccessible(true);
		Skill blessing = utility(4699, TargetType.PARTY, AbnormalType.BUFF_QUEEN_OF_CAT);
		Skill gift = utility(4700, TargetType.PARTY, AbnormalType.BUFF_QUEEN_OF_CAT);
		PetSkillData.skills = List.of(blessing, gift);
		check((boolean) action.invoke(null, pet, null, false) && pet.getAI().getIntention() == Intention.CAST, "Queen starts native party buff");
		Class<?> memberType = Class.forName(PhantomPartyManager.class.getName() + "$Member");
		Object member = allocate(memberType);
		set(member, memberType, "npc", owner); set(member, memberType, "buffedPetOid", pet.getObjectId());
		set(member, memberType, "holding", true);
		Method upkeep = PhantomPartyManager.class.getDeclaredMethod("maintainServitor", memberType); upkeep.setAccessible(true);
		pet.setLastSkillCast(blessing); pet.setCastingNow(true);
		check((boolean) upkeep.invoke(allocate(PhantomPartyManager.class), member), "passive party tick preserves an in-progress four-second beneficial pet cast");
		Method stop = PhantomPartyManager.class.getDeclaredMethod("stopServitorCombat", Player.class); stop.setAccessible(true); stop.invoke(null, owner);
		check(!pet.isCastingNow(), "explicit Stop still aborts beneficial pet cast immediately");
		owner.getLocation().setXYZ(0, 0, 1001);
		check(!(boolean) action.invoke(null, pet, null, false), "party utility excludes an owner outside native three-dimensional range");
		owner.getLocation().setXYZ(0, 0, 1000);
		check((boolean) action.invoke(null, pet, null, false), "party utility matches inclusive native three-dimensional range boundary");
		owner.getLocation().setXYZ(0, 0, 0);
		effect(owner, gift, false);
		check(!(boolean) action.invoke(null, pet, null, false), "Queen does not replace the alternative in its shared buff slot");
		owner.getEffectList().getBuffs().clear();
		PetSkillData.skills = List.of(utility(4701, TargetType.PARTY, AbnormalType.NONE));
		check(!(boolean) action.invoke(null, pet, null, false), "Queen cure is not wasted without matching debuffs");
		effect(owner, utility(9991, TargetType.ONE, AbnormalType.SLEEP), true);
		check(!(boolean) action.invoke(null, pet, null, false), "Queen does not try to cure sleep");
		effect(owner, utility(9992, TargetType.ONE, AbnormalType.PA_DOWN), true);
		check((boolean) action.invoke(null, pet, null, false), "Queen cures matching physical debuff");
		owner.getEffectList().getDebuffs().clear();
		PetSkillData.skills = List.of(utility(4704, TargetType.PARTY, AbnormalType.NONE));
		effect(owner, utility(9993, TargetType.ONE, AbnormalType.SILENCE), true);
		check((boolean) action.invoke(null, pet, null, false), "Seraphim cures silence");
		owner.getEffectList().getDebuffs().clear();
		PetSkillData.skills = List.of(utility(4702, TargetType.PARTY, AbnormalType.BUFF_UNICORN_SERAPHIM), utility(4703, TargetType.PARTY, AbnormalType.BUFF_UNICORN_SERAPHIM));
		check((boolean) action.invoke(null, pet, null, false), "Seraphim starts useful party buff");
		effect(owner, PetSkillData.skills.get(1), false);
		check(!(boolean) action.invoke(null, pet, null, false), "Seraphim shared slot prevents alternating buffs");
		Skill heal = utility(4707, TargetType.ONE, AbnormalType.NONE);
		set(heal, Skill.class, "_hpConsume", 100);
		PetSkillData.skills = List.of(heal);
		check(!(boolean) action.invoke(null, pet, enemy, false), "Nightshade does not heal healthy owner or hostile target");
		set(owner.getStatus(), CreatureStatus.class, "_currentHp", 500d);
		check((boolean) action.invoke(null, pet, enemy, false) && pet.getTarget() == owner, "Nightshade heals hurt owner through native targeted cast");
		set(status, CreatureStatus.class, "_currentHp", 100d);
		check(!(boolean) action.invoke(null, pet, null, false), "Nightshade cannot sacrifice its remaining HP");
		set(status, CreatureStatus.class, "_currentHp", 1000d);
		set(heal, Skill.class, "_mpConsume", 900); set(heal, Skill.class, "_mpInitialConsume", 101);
		check(!(boolean) action.invoke(null, pet, null, false), "utility obeys initial plus final native MP cost");
		set(heal, Skill.class, "_mpConsume", 0); set(heal, Skill.class, "_mpInitialConsume", 0);
		owner.getLocation().setXYZ(901, 0, 0);
		check(!(boolean) action.invoke(null, pet, null, false), "Nightshade does not chase a patient beyond native cast range");
		owner.getLocation().setXYZ(0, 0, 0);
		set(owner.getStatus(), CreatureStatus.class, "_currentHp", 1000d);
		Skill curse = utility(4705, TargetType.ONE, AbnormalType.DEBUFF_NIGHTSHADE);
		set(curse, Skill.class, "_effectPoint", -1);
		PetSkillData.skills = List.of(curse);
		check(!(boolean) action.invoke(null, pet, null, false), "Nightshade does not invent a hostile target while idle");
		check(!(boolean) action.invoke(null, pet, enemy, false), "unforced curse cannot target a white player");
		PhantomEncounterRules.markHostile(owner.getObjectId(), enemy.getObjectId());
		check((boolean) action.invoke(null, pet, enemy, true) && pet.getTarget() == enemy, "authorized encounter can use targeted Nightshade curse");
		effect(enemy, curse, true);
		check(!(boolean) action.invoke(null, pet, enemy, true), "Nightshade does not recast an existing curse slot");
		PhantomEncounterRules.clearHostile(owner.getObjectId());
		PetSkillData.skills = List.of();
	}
	private static void summonFriendPreflight() throws Exception
	{
		Player caster = player(45001, 96), target = player(45002, 88);
		set(caster.getStatus(), CreatureStatus.class, "_currentMp", 500d);
		Skill summon = skill(1403);
		Method gate = PhantomPartyManager.class.getDeclaredMethod("canCastSupportSkill", Player.class, Skill.class, WorldObject.class);
		gate.setAccessible(true);
		check((boolean) gate.invoke(null, caster, summon, target), "ordinary target passes summon preflight with native resources");
		for (String field : new String[] { "_inOlympiadMode", "_observerMode", "_isOnEvent" })
		{
			set(target, Player.class, field, true);
			check(!(boolean) gate.invoke(null, caster, summon, target), "summon preflight blocks target " + field);
			set(target, Player.class, field, false);
		}
		set(target, Player.class, "_privateStoreType", PrivateStoreType.SELL);
		check(!(boolean) gate.invoke(null, caster, summon, target), "private store does not burn summon reuse");
		set(target, Player.class, "_privateStoreType", PrivateStoreType.NONE);
		for (ZoneId zone : new ZoneId[] { ZoneId.NO_SUMMON_FRIEND, ZoneId.JAIL })
		{
			target.setInsideZone(zone, true);
			check(!(boolean) gate.invoke(null, caster, summon, target), "blocked target zone " + zone);
			target.setInsideZone(zone, false);
		}
		Olympiad.registered.add(target.getObjectId());
		check(!(boolean) gate.invoke(null, caster, summon, target), "registered Olympiad target is blocked before entering match");
		Olympiad.registered.clear();
		SevenSignsFestival.participants.add(target.getObjectId());
		check(!(boolean) gate.invoke(null, caster, summon, target), "festival target is blocked");
		SevenSignsFestival.participants.clear();
		caster.getLocation().setInstanceId(9);
		check(!(boolean) gate.invoke(null, caster, summon, target), "missing or prohibited caster instance is blocked");
		caster.getLocation().setInstanceId(0);
		set(caster, Player.class, "_isIn7sDungeon", true);
		check(!(boolean) gate.invoke(null, caster, summon, target), "Seven Signs outsider cannot be summoned into dungeon");
		SevenSigns.cabal = 1;
		check((boolean) gate.invoke(null, caster, summon, target), "eligible Seven Signs participant can be summoned");
		SevenSigns.validation = true; SevenSigns.winner = 2;
		check(!(boolean) gate.invoke(null, caster, summon, target), "Seven Signs validation requires winning cabal");
		SevenSigns.cabal = 0; SevenSigns.validation = false;
		check(!caster.isCastingNow() && ((java.util.Map<?, ?>) get(caster, Creature.class, "_reuseTimeStampsSkills")).isEmpty(), "preflight checks do not start cast or consume reuse");
		set(caster, Player.class, "_isIn7sDungeon", false);
		set(caster, Creature.class, "_skills", new ConcurrentHashMap<>(java.util.Map.of(1403, summon)));
		Party party = allocate(Party.class);
		set(caster, Player.class, "_party", party); set(target, Player.class, "_party", party);
		target.setOnlineStatus(true, false);
		set(target, Player.class, "_inventory", new CrystalInventory(target));
		set(target, Player.class, "_privateStoreType", PrivateStoreType.SELL);
		Class<?> memberType = Class.forName(PhantomPartyManager.class.getName() + "$Member");
		Object member = allocate(memberType);
		set(member, memberType, "npc", caster); set(member, memberType, "summonFor", target);
		set(member, memberType, "summonAskedAt", System.currentTimeMillis()); set(member, memberType, "summonWaitSaid", true);
		Method serve = PhantomPartyManager.class.getDeclaredMethod("serveSummonFriend", memberType); serve.setAccessible(true);
		check(!(boolean) serve.invoke(allocate(PhantomPartyManager.class), member), "full party service waits on blocked Summon Friend target");
		check(get(member, memberType, "summonFor") == target && caster.getTarget() == null && !caster.isCastingNow() && ((java.util.Map<?, ?>) get(caster, Creature.class, "_reuseTimeStampsSkills")).isEmpty(), "blocked service preserves request without cast target or reuse");
	}
	public static void main(String[] args) throws Exception
	{
		if (args.length > 0) { summonFriendPreflight(); if (failures > 0) { throw new AssertionError("preflight regressions"); } return; }
		illegalPreparation();
		teardown();
		latePetAndRevocation();
		fullServicePreparationClock();
		summonFriendPreflight();
		supportServitorUtility();
		System.out.println("PhantomPreparationIntegrationTest: " + checks + " checks, " + failures + " failures");
		if (failures > 0) { throw new AssertionError("preparation regressions"); }
	}
}
