import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.l2jmobius.gameserver.ai.AbstractAI;
import org.l2jmobius.gameserver.ai.AttackableAI;
import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.managers.PhantomPartyManager;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.WorldRegion;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.Summon;
import org.l2jmobius.gameserver.model.actor.holders.npc.AggroInfo;
import org.l2jmobius.gameserver.model.actor.instance.Monster;
import org.l2jmobius.gameserver.model.actor.instance.Servitor;
import org.l2jmobius.gameserver.model.groups.Party;
import org.l2jmobius.gameserver.model.zone.ZoneId;

/** Native party threat and defense selection, with isolated discovery and geometry boundaries. */
public class PhantomPartyDefenseTest
{
	private static int checks;
	private static int failures;
	private static final Class<?> MEMBER;
	private static final Method HATING;
	private static final Method SELECT;
	private static final Method UNDER_ATTACK;
	private static final Method FREE_HUNT;
	private static final WorldRegion REGION = new WorldRegion(0, 0, 0);
	static
	{
		try
		{
			MEMBER = Class.forName(PhantomPartyManager.class.getName() + "$Member");
			HATING = PhantomPartyManager.class.getDeclaredMethod("isHatingParty", MEMBER, Monster.class);
			SELECT = PhantomPartyManager.class.getDeclaredMethod("partyAttacker", MEMBER, Monster.class);
			UNDER_ATTACK = PhantomPartyManager.class.getDeclaredMethod("partyUnderAttack", MEMBER);
			FREE_HUNT = PhantomPartyManager.class.getDeclaredMethod("freeHuntDefenseTarget", MEMBER);
			HATING.setAccessible(true);
			SELECT.setAccessible(true);
			UNDER_ATTACK.setAccessible(true);
			FREE_HUNT.setAccessible(true);
			REGION.setSurroundingRegions(new WorldRegion[] { REGION });
		}
		catch (Exception e)
		{
			throw new ExceptionInInitializerError(e);
		}
	}

	private static void check(boolean value, String message)
	{
		checks++;
		if (!value)
		{
			failures++;
			System.err.println("FAIL: " + message);
		}
	}

	// Initialize only the native fields under test, avoiding database/world constructors.
	private static <T> T allocate(Class<T> type) throws Exception
	{
		final Class<?> unsafe = Class.forName("sun.misc.Unsafe");
		final Field field = unsafe.getDeclaredField("theUnsafe");
		field.setAccessible(true);
		return type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type));
	}

	private static void set(Object object, Class<?> type, String name, Object value) throws Exception
	{
		final Field field = type.getDeclaredField(name);
		field.setAccessible(true);
		field.set(object, value);
	}

	private static <T extends Creature> T creature(Class<T> type, int id, int x) throws Exception
	{
		final T object = allocate(type);
		set(object, WorldObject.class, "_objectId", id);
		set(object, WorldObject.class, "_location", new Location(x, 0, 0));
		set(object, WorldObject.class, "_worldRegion", REGION);
		set(object, Creature.class, "_zones", new byte[ZoneId.getZoneCount()]);
		object.setSpawned(true);
		return object;
	}

	private static void hate(Monster mob, Creature victim) throws Exception
	{
		final AggroInfo hate = new AggroInfo(victim);
		hate.addHate(100);
		set(mob, Attackable.class, "_aggroList", new ConcurrentHashMap<>(Map.of(victim, hate)));
	}

	private static Monster monster(int id, int x, Creature victim) throws Exception
	{
		final Monster mob = creature(Monster.class, id, x);
		hate(mob, victim);
		final AttackableAI ai = allocate(AttackableAI.class);
		set(ai, AbstractAI.class, "_actor", mob);
		set(ai, AbstractAI.class, "_attackTarget", victim);
		set(ai, AbstractAI.class, "_intention", Intention.ATTACK);
		set(mob, Creature.class, "_ai", ai);
		return mob;
	}

	private static Monster select(Object manager, Object member, Monster exclude) throws Exception
	{
		return (Monster) SELECT.invoke(manager, member, exclude);
	}

	public static void main(String[] args) throws Exception
	{
		final Player owner = creature(Player.class, 70001, 0);
		final Player recruit = creature(Player.class, 70002, 0);
		final Player stranger = creature(Player.class, 70003, 0);
		final Player humanMember = creature(Player.class, 70004, 0);
		final Party party = allocate(Party.class);
		set(party, Party.class, "_members", new CopyOnWriteArrayList<>(List.of(owner, recruit, humanMember)));
		for (Player player : List.of(owner, recruit, humanMember))
		{
			set(player, Player.class, "_party", party);
		}
		final Object member = allocate(MEMBER);
		set(member, MEMBER, "npc", recruit);
		set(member, MEMBER, "owner", owner);
		final PhantomPartyManager manager = allocate(PhantomPartyManager.class);
		final Monster melee = monster(71001, 100, owner);
		World.objects.add(melee);
		check(melee.getMostHated() == owner, "native hate list identifies the leader");
		check(melee.getAI().getAttackTarget() == owner && melee.isInCombat(), "native AI is fighting the leader");
		check(melee.getTarget() == null, "physical combat can have no selected target");
		check((boolean) HATING.invoke(manager, member, melee), "leader melee aggro is recognized without a selected target");
		check(select(manager, member, null) == melee, "defense answers a melee attacker on the leader");
		check((boolean) UNDER_ATTACK.invoke(manager, member), "recovery and looting see the same native threat");
		set(melee, Creature.class, "_target", melee);
		check((boolean) HATING.invoke(manager, member, melee), "a self-buff target does not hide actual aggro or break focus fire");
		set(melee, Creature.class, "_target", stranger);
		check((boolean) HATING.invoke(manager, member, melee), "temporary selected targets do not override native hate");
		hate(melee, stranger);
		set(melee, Creature.class, "_target", owner);
		check(!(boolean) HATING.invoke(manager, member, melee), "stranger hate wins over stale selected and AI targets");
		check(select(manager, member, null) == null, "mobs attacking strangers are not defended");
		check(!(boolean) UNDER_ATTACK.invoke(manager, member), "stale selection does not suppress recovery or looting");
		hate(melee, recruit);
		check(select(manager, member, null) == melee, "recruited party member is defended");
		hate(melee, humanMember);
		check(select(manager, member, null) == melee, "another real party member is defended");
		final Servitor pet = creature(Servitor.class, 72001, 0);
		set(pet, Summon.class, "_owner", owner);
		hate(melee, pet);
		check(select(manager, member, null) == melee, "leader summon is defended through its owner");
		set(pet, Summon.class, "_owner", stranger);
		check(select(manager, member, null) == null, "stranger summon is excluded");
		set(pet, Summon.class, "_owner", null);
		check(select(manager, member, null) == null, "summon without an owner is excluded");
		set(melee, Attackable.class, "_aggroList", new ConcurrentHashMap<>());
		set(melee.getAI(), AbstractAI.class, "_attackTarget", owner);
		check(select(manager, member, null) == melee, "an active native attack target covers a missing hate entry");
		for (Intention intention : List.of(Intention.IDLE, Intention.ACTIVE, Intention.MOVE_TO, Intention.CAST))
		{
			set(melee.getAI(), AbstractAI.class, "_intention", intention);
			check(select(manager, member, null) == null, "stale AI target is excluded in " + intention);
		}
		set(melee.getAI(), AbstractAI.class, "_intention", Intention.ATTACK);
		owner.setDead(true);
		check(select(manager, member, null) == null, "dead AI victim is excluded");
		owner.setDead(false);
		owner.setSpawned(false);
		check(select(manager, member, null) == null, "unspawned AI victim is excluded");
		owner.setSpawned(true);
		owner.getLocation().setInstanceId(1);
		check(!(boolean) HATING.invoke(manager, member, melee), "victim in another instance is excluded");
		owner.getLocation().setInstanceId(0);
		hate(melee, owner);
		check(select(manager, member, melee) == null, "current focus is excluded from the alternate scan");
		set(melee, Attackable.class, "_isRaid", true);
		check(select(manager, member, null) == null, "raid is excluded from ordinary defense");
		check((boolean) UNDER_ATTACK.invoke(manager, member), "raid threat still blocks unsafe resting");
		set(melee, Attackable.class, "_isRaid", false);
		melee.setDead(true);
		check(select(manager, member, null) == null, "corpse is excluded");
		melee.setDead(false);
		melee.setSpawned(false);
		check(select(manager, member, null) == null, "departed mob is excluded");
		melee.setSpawned(true);
		set(melee, Npc.class, "_isQuestMonster", true);
		check(select(manager, member, null) == null, "quest monster is excluded");
		set(melee, Npc.class, "_isQuestMonster", false);
		set(melee, Npc.class, "_isFakePlayer", true);
		check(select(manager, member, null) == null, "fake player is excluded");
		set(melee, Npc.class, "_isFakePlayer", false);
		melee.setInsideZone(ZoneId.PEACE, true);
		check(select(manager, member, null) == null, "peace-zone target is excluded");
		melee.setInsideZone(ZoneId.PEACE, false);
		recruit.setInsideZone(ZoneId.PEACE, true);
		check(select(manager, member, null) == null, "recruit in a peace zone does not defend");
		recruit.setInsideZone(ZoneId.PEACE, false);
		recruit.getLocation().setInstanceId(1);
		check(select(manager, member, null) == null, "recruit cannot defend into the leader's other instance");
		recruit.getLocation().setInstanceId(0);

		final Monster near = monster(71002, 50, owner);
		final Monster far = monster(71003, 100, owner);
		// Set both selected targets too, so geometry checks also fail against the original selector independently.
		set(near, Creature.class, "_target", owner);
		set(far, Creature.class, "_target", owner);
		World.objects.clear();
		World.objects.addAll(List.of(near, far));
		check(select(manager, member, null) == near, "nearest eligible attacker wins");
		GeoEngine.invisible.add(near.getObjectId());
		check(select(manager, member, null) == far, "occluded nearer mob cannot hide a reachable attacker");
		GeoEngine.invisible.clear();
		GeoEngine.blockedX.add(near.getX());
		check(select(manager, member, null) == far, "visible but unreachable nearer mob cannot hide a reachable attacker");
		World.objects.clear();
		World.objects.addAll(List.of(far, near));
		check(select(manager, member, null) == far, "blocked target is rejected regardless of discovery order");
		GeoEngine.blockedX.add(far.getX());
		check(select(manager, member, null) == null, "no defense target is returned when every path is blocked");
		GeoEngine.blockedX.clear();
		near.getLocation().setXYZ(50, 0, 850);
		check(select(manager, member, null) == far, "another-floor candidate cannot hide an eligible attacker");
		near.getLocation().setXYZ(950, 0, 0);
		World.objects.clear();
		World.objects.add(near);
		check(select(manager, member, null) == null, "defense scan remains bounded around the leader");
		near.getLocation().setXYZ(50, 0, 0);
		recruit.getLocation().setXYZ(-2300, 0, 0);
		check(select(manager, member, null) == null, "recruit chase range remains bounded");
		recruit.getLocation().setXYZ(0, 0, 0);
		set(member, MEMBER, "owner", null);
		check(select(manager, member, null) == null, "missing leader has no defense target");
		set(member, MEMBER, "owner", owner);
		recruit.getLocation().setXYZ(1200, 0, 0);
		final Monster local = monster(71004, 1250, recruit);
		World.objects.clear();
		World.objects.add(local);
		check(select(manager, member, null) == null, "local attacker is outside the leader's defense radius");
		check(FREE_HUNT.invoke(manager, member) == local, "free-hunt answers native melee hate at the outer leash with no selected target");
		set(local, Creature.class, "_target", local);
		check(FREE_HUNT.invoke(manager, member) == local, "a self-buff selected target does not hide outer-leash retaliation");
		set(local, Creature.class, "_target", stranger);
		check(FREE_HUNT.invoke(manager, member) == local, "a stranger selected target does not hide native local hate");
		hate(local, stranger);
		set(local, Creature.class, "_target", recruit);
		check(FREE_HUNT.invoke(manager, member) == null, "stale selection of the recruit cannot trigger retaliation against stranger hate");
		hate(local, recruit);
		set(local, Attackable.class, "_isRaid", true);
		check(FREE_HUNT.invoke(manager, member) == null, "free-hunt local retaliation leaves raids to raid logic");
		set(local, Attackable.class, "_isRaid", false);
		local.setDead(true);
		check(FREE_HUNT.invoke(manager, member) == null, "free-hunt local retaliation excludes corpses");
		local.setDead(false);
		final Monster onLeader = monster(71005, 500, owner);
		World.objects.add(onLeader);
		set(local, Creature.class, "_target", null);
		check(FREE_HUNT.invoke(manager, member) == local, "local native attacker takes priority over a mob on the leader");
		hate(local, stranger);
		check(FREE_HUNT.invoke(manager, member) == onLeader, "without local hate free-hunt falls back to reachable party defense");
		GeoEngine.invisible.add(onLeader.getObjectId());
		check(FREE_HUNT.invoke(manager, member) == null, "free-hunt party fallback retains visibility checks");
		GeoEngine.invisible.clear();
		GeoEngine.blockedX.add(onLeader.getX());
		check(FREE_HUNT.invoke(manager, member) == null, "free-hunt party fallback retains movement checks");
		GeoEngine.blockedX.clear();
		if (failures > 0)
		{
			throw new AssertionError(failures + " of " + checks + " native party-defense checks failed");
		}
		System.out.println("PASS: " + checks + " native party-defense checks.");
	}
}
