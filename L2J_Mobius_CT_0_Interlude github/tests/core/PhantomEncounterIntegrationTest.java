import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.l2jmobius.gameserver.config.PvpConfig;
import org.l2jmobius.gameserver.config.RatesConfig;
import org.l2jmobius.gameserver.config.custom.FakePlayersConfig;
import org.l2jmobius.gameserver.data.holders.AccessLevel;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.managers.PhantomEncounterRules;
import org.l2jmobius.gameserver.managers.PhantomManager;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.holders.player.AutoPlaySettingsHolder;
import org.l2jmobius.gameserver.model.effects.EffectType;
import org.l2jmobius.gameserver.model.clan.Clan;
import org.l2jmobius.gameserver.model.groups.Party;
import org.l2jmobius.gameserver.model.item.instance.Item;
import org.l2jmobius.gameserver.model.itemcontainer.PlayerInventory;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.skill.targets.TargetType;
import org.l2jmobius.gameserver.model.zone.ZoneId;

/** Native Player/Skill gates and PvP disable driver, with isolated world and teardown boundaries. */
public class PhantomEncounterIntegrationTest
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

	// Allocate only the fields exercised by native methods, bypassing database/world constructors.
	private static <T> T allocate(Class<T> type) throws Exception
	{
		final Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
		final Field unsafe = unsafeClass.getDeclaredField("theUnsafe");
		unsafe.setAccessible(true);
		return type.cast(unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe.get(null), type));
	}

	private static void set(Object target, Class<?> owner, String name, Object value) throws Exception
	{
		final Field field = owner.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static Player player(int id) throws Exception
	{
		final Player player = allocate(Player.class);
		set(player, WorldObject.class, "_objectId", id);
		set(player, WorldObject.class, "_location", new Location(0, 0, 0));
		set(player, Creature.class, "_zones", new byte[ZoneId.getZoneCount()]);
		set(player, Player.class, "_autoPlaying", new AtomicBoolean(false));
		set(player, Player.class, "_autoPlaySettings", new AutoPlaySettingsHolder());
		set(player, Player.class, "_accessLevel", allocate(AccessLevel.class));
		return player;
	}

	private static Skill skill(boolean debuff, boolean pvpOnly) throws Exception
	{
		final Skill skill = allocate(Skill.class);
		set(skill, Skill.class, "_effectPoint", -1);
		set(skill, Skill.class, "_targetType", TargetType.ONE);
		set(skill, Skill.class, "_effectRange", 1000);
		set(skill, Skill.class, "_isDebuff", debuff);
		set(skill, Skill.class, "_isPvPOnly", pvpOnly);
		set(skill, Skill.class, "_effectTypes", debuff ? new Byte[0] : new Byte[] { (byte) EffectType.MAGICAL_ATTACK.ordinal() });
		return skill;
	}

	private static void skillGates() throws Exception
	{
		final Player actor = player(10001);
		final Player victim = player(10002);
		final Player other = player(10003);
		final Skill nuke = skill(false, false);
		final Skill debuff = skill(true, false);
		final Skill pvpOnly = skill(true, true);
		actor.setCurrentSkill(nuke, false, false);
		actor.setAutoPlaying(true);
		actor.getAutoPlaySettings().setNextTargetMode(2); // encounter spawn uses Characters
		PhantomEncounterRules.markHostile(actor.getObjectId(), victim.getObjectId());
		check(victim.isAutoAttackable(actor), "white victim is attackable");
		check(actor.checkPvpSkill(victim, nuke), "white victim accepts damage skill");
		check(actor.checkPvpSkill(victim, debuff), "white victim accepts pure debuff");
		check(actor.checkPvpSkill(victim, pvpOnly), "white victim accepts PvP-only debuff");
		check(Skill.checkForAreaOffensiveSkills(actor, victim, nuke, false), "AutoUse area damage accepts victim");
		check(Skill.checkForAreaOffensiveSkills(actor, victim, debuff, false), "area debuff accepts victim");
		check(!actor.checkPvpSkill(other, debuff), "unrelated white player remains protected");
		check(!Skill.checkForAreaOffensiveSkills(actor, other, nuke, false), "area attack excludes unrelated white player");
		check(!victim.checkPvpSkill(actor, debuff), "hostility is directional");
		GeoEngine.visible = false;
		check(!Skill.checkForAreaOffensiveSkills(actor, victim, nuke, false), "encounter still needs line of sight");
		GeoEngine.visible = true;
		for (Player participant : new Player[] { actor, victim })
		{
			for (ZoneId zone : new ZoneId[] { ZoneId.PEACE, ZoneId.NO_PVP })
			{
				participant.setInsideZone(zone, true);
				check(!victim.isAutoAttackable(actor), "source/target safe zone prevents encounter autoattack");
				check(!actor.checkPvpSkill(victim, debuff), "source/target safe zone prevents encounter debuff");
				check(!Skill.checkForAreaOffensiveSkills(actor, victim, nuke, false), "safe zone excludes area target");
				participant.setInsideZone(zone, false);
			}
		}
		victim.getLocation().setInstanceId(1);
		check(!actor.checkPvpSkill(victim, debuff), "different instance blocks encounter exception");
		actor.getLocation().setInstanceId(1);
		check(!actor.checkPvpSkill(victim, debuff), "encounters cannot authorize attacks inside a shared instance");
		actor.getLocation().setInstanceId(0);
		victim.getLocation().setInstanceId(0);
		for (String flag : new String[] { "_isInDuel", "_inOlympiadMode" })
		{
			set(victim, Player.class, flag, true);
			check(!actor.checkPvpSkill(victim, debuff), "victim in duel/Olympiad has no encounter exception");
			set(victim, Player.class, flag, false);
			set(actor, Player.class, flag, true);
			check(!actor.checkPvpSkill(victim, debuff), "actor in duel/Olympiad has no encounter exception");
			set(actor, Player.class, flag, false);
		}
		final Party party = allocate(Party.class);
		set(party, Party.class, "_members", new CopyOnWriteArrayList<>(List.of(actor, victim)));
		set(actor, Player.class, "_party", party);
		set(victim, Player.class, "_party", party);
		check(!victim.isAutoAttackable(actor), "party membership blocks encounter autoattack");
		check(!actor.checkPvpSkill(victim, debuff), "party membership blocks debuff");
		check(!Skill.checkForAreaOffensiveSkills(actor, victim, nuke, false), "party membership blocks area damage");
		set(actor, Player.class, "_party", null);
		set(victim, Player.class, "_party", null);
		final Clan clan = allocate(Clan.class);
		set(clan, Clan.class, "_clanId", 41);
		set(clan, Clan.class, "_members", new ConcurrentHashMap<>(java.util.Map.of(actor.getObjectId(), new Object())));
		set(clan, Clan.class, "_atWarWith", Set.of());
		set(actor, Player.class, "_clan", clan);
		set(victim, Player.class, "_clan", clan);
		set(actor, Player.class, "_clanId", 41);
		set(victim, Player.class, "_clanId", 41);
		check(!victim.isAutoAttackable(actor), "same clan blocks encounter autoattack");
		check(!actor.checkPvpSkill(victim, debuff), "same clan blocks encounter debuff");
		check(!Skill.checkForAreaOffensiveSkills(actor, victim, nuke, false), "same clan blocks area damage");
		set(actor, Player.class, "_clan", null);
		set(victim, Player.class, "_clan", null);
		set(actor, Player.class, "_clanId", 0);
		set(victim, Player.class, "_clanId", 0);
		PhantomEncounterRules.clearHostile(actor.getObjectId());
		check(!victim.isAutoAttackable(actor), "ending encounter restores white autoattack gate");
		check(!actor.checkPvpSkill(victim, debuff), "ending encounter restores debuff gate");
		set(victim, Player.class, "_pvpFlag", (byte) 1);
		check(actor.checkPvpSkill(victim, debuff), "ordinary flagged target remains legal");
		actor.getAutoPlaySettings().setNextTargetMode(1);
		check(!Skill.checkForAreaOffensiveSkills(actor, victim, nuke, false), "ordinary monster AutoPlay mode remains unchanged");
	}

	private static class RecordingInventory extends PlayerInventory
	{
		int scans;

		RecordingInventory(Player owner)
		{
			super(owner);
		}

		@Override
		public Collection<Item> getItems()
		{
			scans++;
			return List.of();
		}
	}

	private static void karmaAndDrops() throws Exception
	{
		final Player actor = player(20001);
		final Player victim = player(20002);
		PhantomEncounterRules.registerActor(actor.getObjectId());
		PhantomEncounterRules.markHostile(actor.getObjectId(), victim.getObjectId());
		actor.onKillUpdatePvPKarma(victim);
		check((actor.getKarma() == 0) && (actor.getPkKills() == 0) && (actor.getPvpKills() == 0), "white victim kill gives actor no karma or counters");
		PhantomEncounterRules.clearHostile(actor.getObjectId());
		actor.onKillUpdatePvPKarma(victim);
		check(actor.getKarma() == 0, "protection survives fight completion");
		final RecordingInventory inventory = new RecordingInventory(actor);
		set(actor, Player.class, "_inventory", inventory);
		set(actor, Creature.class, "_karma", 100);
		set(actor, Player.class, "_pkKills", 10);
		RatesConfig.KARMA_RATE_DROP = 100;
		final Method drops = Player.class.getDeclaredMethod("onDieDropItem", Creature.class);
		drops.setAccessible(true);
		for (int threshold : new int[] { 6, 0 })
		{
			PvpConfig.KARMA_PK_LIMIT = threshold;
			drops.invoke(actor, victim);
			check(inventory.scans == 0, "actor gear never enters drop lottery at default/custom PK threshold");
		}
		set(actor, Creature.class, "_karma", 0);
		RatesConfig.PLAYER_RATE_DROP = 100;
		drops.invoke(actor, allocate(Npc.class));
		check(inventory.scans == 0, "white actor gear also bypasses customized NPC death drops");
		set(actor, Creature.class, "_karma", 100);
		PhantomEncounterRules.unregisterActor(actor.getObjectId());
		drops.invoke(actor, victim);
		check(inventory.scans == 1, "ordinary red player still enters configured drop lottery");
	}

	private static class RecordingManager extends PhantomManager
	{
		ConcurrentHashMap<Integer, Object> actors;
		int removals;
		boolean failNext;

		@Override
		public void despawnRecruit(Player actor)
		{
			check(!PhantomEncounterRules.isHostile(actor.getObjectId(), 30000), "disable revokes hostility before teardown");
			if (failNext)
			{
				failNext = false;
				throw new IllegalStateException("one failed teardown for retry regression");
			}
			removals++;
			actors.remove(actor.getObjectId());
			PhantomEncounterRules.unregisterActor(actor.getObjectId());
		}
	}

	private static void disabledCleanup() throws Exception
	{
		final RecordingManager manager = allocate(RecordingManager.class);
		manager.actors = new ConcurrentHashMap<>();
		set(manager, PhantomManager.class, "_phantoms", manager.actors);
		set(manager, PhantomManager.class, "_duelsToCancel", new ConcurrentHashMap<Integer, Long>());
		set(manager, PhantomManager.class, "_pvpWasEnabled", true);
		final Player victim = player(30000);
		final Class<?> dataType = Class.forName("org.l2jmobius.gameserver.managers.PhantomManager$PhantomData");
		for (int phase = 0; phase < 6; phase++)
		{
			final Player actor = player(30001 + phase);
			final Object data = allocate(dataType);
			set(data, dataType, "player", actor);
			set(data, dataType, "recruited", true);
			set(data, dataType, "encounterActor", true);
			set(data, dataType, "encounterPhase", Math.min(phase, 3));
			set(data, dataType, "encounterVictimOid", victim.getObjectId());
			set(data, dataType, "encounterGroup", new PhantomEncounterRules.EncounterGroup(1, null, null));
			set(data, dataType, "encounterEndAt", phase >= 4 ? 1L : 0L);
			set(actor, Creature.class, "_isDead", phase == 4);
			PhantomEncounterRules.registerActor(actor.getObjectId());
			PhantomEncounterRules.markHostile(actor.getObjectId(), victim.getObjectId());
			manager.actors.put(actor.getObjectId(), data);
		}
		check(manager.activeEncounterCount() == 6, "fixture has six independent encounter groups");
		check(manager.hasEncounterFor(victim), "fixture holds victim reservation");
		FakePlayersConfig.PHANTOM_PVP_ENABLED = false;
		final Method tick = PhantomManager.class.getDeclaredMethod("pvpCombat");
		tick.setAccessible(true);
		manager.failNext = true;
		tick.invoke(manager);
		check(manager.actors.size() == 1, "failed actor remains available for cleanup retry");
		tick.invoke(manager);
		check(manager.removals == 6, "disabled ticks remove approach/wait/warning/fight/corpse/departure actors");
		check(manager.activeEncounterCount() == 0, "disable frees active encounter slots");
		check(!manager.hasEncounterFor(victim), "disable frees victim reservation");
		for (int id = 30001; id <= 30006; id++)
		{
			check(!PhantomEncounterRules.isHostile(id, victim.getObjectId()), "disable leaves no hostile pair");
			check(!PhantomEncounterRules.isEncounterActor(id), "teardown releases lifetime marker");
		}
		check(manager.spawnEncounterActor(victim, new Location(0, 0, 0), 80, PhantomManager.PartyRole.WARRIOR, 0, new PhantomEncounterRules.EncounterGroup(1, null, null), null) == null, "disabled master switch rejects new actors");
	}

	public static void main(String[] args) throws Exception
	{
		skillGates();
		karmaAndDrops();
		disabledCleanup();
		System.out.println("PASS: " + checks + " native encounter checks");
	}
}
