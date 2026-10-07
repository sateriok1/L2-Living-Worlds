import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Set;
import org.l2jmobius.gameserver.model.actor.instance.Monster;
import org.l2jmobius.gameserver.config.custom.FakePlayersConfig;
import org.l2jmobius.gameserver.data.holders.AccessLevel;
import org.l2jmobius.gameserver.managers.PhantomManager;
import org.l2jmobius.gameserver.managers.PhantomSpotRules;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.holders.npc.AggroInfo;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.stat.CreatureStat;
import org.l2jmobius.gameserver.model.actor.stat.PlayerStat;
import org.l2jmobius.gameserver.model.clan.Clan;
import org.l2jmobius.gameserver.model.groups.Party;
import org.l2jmobius.gameserver.ai.PlayerAI;
import org.l2jmobius.gameserver.model.zone.ZoneId;
import org.l2jmobius.gameserver.network.GameClient;
import org.l2jmobius.gameserver.network.serverpackets.ServerPacket;

/** Exercise warning state on the actual manager, with only world and client boundaries isolated. */
public class PhantomSpotIntegrationTest
{
	private static int checks;
	private static int failures;
	private static final Class<?> DATA;
	static
	{
		try { DATA = Class.forName("org.l2jmobius.gameserver.managers.PhantomManager$PhantomData"); }
		catch (Exception e) { throw new ExceptionInInitializerError(e); }
	}
	private static void check(boolean result, String message)
	{
		checks++;
		if (!result) { throw new AssertionError(message); }
	}
	private static <T> T allocate(Class<T> type) throws Exception
	{
		Class<?> unsafe = Class.forName("sun.misc.Unsafe");
		Field field = unsafe.getDeclaredField("theUnsafe");
		field.setAccessible(true);
		return type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type));
	}
	private static void set(Object object, Class<?> owner, String name, Object value) throws Exception
	{
		Field field = owner.getDeclaredField(name);
		field.setAccessible(true);
		field.set(object, value);
	}
	private static Object get(Object object, String name) throws Exception
	{
		Field field = DATA.getDeclaredField(name);
		field.setAccessible(true);
		return field.get(object);
	}
	private static Object invoke(PhantomManager manager, String name, Class<?>[] types, Object... args) throws Exception
	{
		Method method = PhantomManager.class.getDeclaredMethod(name, types);
		method.setAccessible(true);
		return method.invoke(manager, args);
	}
	public static class Client extends GameClient
	{
		public Client() { super(null); }
		@Override public void sendPacket(ServerPacket packet) { }
		@Override public boolean isDetached() { return false; }
	}
	private static Player player(int id, boolean flagged) throws Exception
	{
		Player p = allocate(Player.class);
		set(p, WorldObject.class, "_objectId", id);
		set(p, WorldObject.class, "_location", new Location(0, 0, 0));
		set(p, Creature.class, "_zones", new byte[ZoneId.getZoneCount()]);
		set(p, Player.class, "_accessLevel", allocate(AccessLevel.class));
		set(p, Player.class, "_autoPlaying", new AtomicBoolean(false));
		set(p, Player.class, "_pvpFlag", (byte) (flagged ? 1 : 0));
		set(p, Player.class, "_client", allocate(Client.class));
		PlayerStat stat = allocate(PlayerStat.class);
		set(stat, CreatureStat.class, "_creature", p);
		set(stat, CreatureStat.class, "_level", (byte) 60);
		set(p, Creature.class, "_stat", stat);
		return p;
	}
	private static class Fixture
	{
		final PhantomManager manager = allocate(PhantomManager.class);
		final Player hunter = player(1, false);
		final Player a = player(2, true);
		final Player b = player(3, true);
		final Object data = allocate(DATA);
		final Map<Integer, Double> scores = new HashMap<>();
		Fixture() throws Exception
		{
			World.players.clear();
			World.players.put(2, a);
			World.players.put(3, b);
			set(manager, PhantomManager.class, "_phantoms", new ConcurrentHashMap<Integer, Object>());
			set(manager, PhantomManager.class, "_pvpVictimCooldownUntil", new ConcurrentHashMap<Integer, Long>());
			set(manager, PhantomManager.class, "_duelTargetCooldownUntil", new ConcurrentHashMap<Integer, Long>());
			set(data, DATA, "player", hunter);
			set(data, DATA, "spotScores", scores);
			try { set(data, DATA, "spotCrowding", new java.util.HashSet<Integer>()); }
			catch (NoSuchFieldException baseline) { }
			set(data, DATA, "role", java.util.Arrays.stream(DATA.getDeclaredField("role").getType().getEnumConstants()).filter(v -> ((Enum<?>) v).name().equals("NONE")).findFirst().orElseThrow());
			set(data, DATA, "spotTemper", PhantomSpotRules.Temper.NORMAL);
			FakePlayersConfig.PHANTOM_PVP_ENABLED = true;
			FakePlayersConfig.PHANTOM_PVP_SPOT_DEFENSE = true;
			FakePlayersConfig.PHANTOM_PVP_SPOT_RADIUS = 800;
			FakePlayersConfig.PHANTOM_PVP_MAX_LEVEL_GAP_ABOVE_PLAYER = 10;
			FakePlayersConfig.PHANTOM_PVP_SPOT_NORMAL_LIMIT = 4;
			FakePlayersConfig.PHANTOM_PVP_SPOT_PATIENT_LIMIT = 6;
			FakePlayersConfig.PHANTOM_PVP_SPOT_HOT_LIMIT = 1;
			FakePlayersConfig.PHANTOM_PVP_SPOT_STEAL_POINTS = 2;
		}
		void tick(long now) throws Exception { invoke(manager, "spotDefense", new Class<?>[] { Player.class, DATA, long.class }, hunter, data, now); }
		void theft(Monster monster) throws Exception { invoke(manager, "noteKillSteal", new Class<?>[] { DATA, Monster.class, long.class }, data, monster, 1000L); }
	}
	private static Monster monster(Fixture f, long hunterDamage, long playerDamage) throws Exception
	{
		Monster mob = allocate(Monster.class);
		set(mob, WorldObject.class, "_objectId", 10);
		set(mob, Creature.class, "_target", f.a);
		AggroInfo hunter = new AggroInfo(f.hunter);
		hunter.addDamage(hunterDamage);
		AggroInfo player = new AggroInfo(f.a);
		player.addDamage(playerDamage);
		player.addHate(100); // natural aggro can produce hate without the player doing damage
		set(mob, Attackable.class, "_aggroList", new ConcurrentHashMap<>(Map.of(f.hunter, hunter, f.a, player)));
		set(f.data, DATA, "claimedOid", 10);
		return mob;
	}
	private static void observe(Fixture f, Monster mob) throws Exception
	{
		set(mob, Creature.class, "_target", f.hunter);
		invoke(f.manager, "observeSpotEngagement", new Class<?>[] { DATA, Monster.class, long.class }, f.data, mob, 500L);
		set(mob, Creature.class, "_target", f.a);
	}
	private static void passingPlayer() throws Exception
	{
		Fixture f = new Fixture();
		Monster mob = monster(f, 50, 0);
		observe(f, mob);
		f.theft(mob);
		check(f.scores.isEmpty(), "monster target and natural hate alone cannot accuse a passing player");
	}
	private static void firstHitBelongsToPlayer() throws Exception
	{
		Fixture f = new Fixture();
		f.theft(monster(f, 0, 50));
		check(f.scores.isEmpty(), "unseen target reservation does not establish ownership");
	}
	private static void protectedThief() throws Exception
	{
		Fixture f = new Fixture();
		Monster mob = monster(f, 50, 0);
		observe(f, mob);
		mob.getAggroList().get(f.a).addDamage(50);
		f.a.setInsideZone(ZoneId.PEACE, true);
		f.theft(mob);
		check(f.scores.isEmpty(), "protected player receives no hidden grievance");
		f.a.setInsideZone(ZoneId.PEACE, false);
		check(f.scores.isEmpty(), "leaving protection exposes no earlier grievance");
	}
	private static void actualLaterDamage() throws Exception
	{
		Fixture f = new Fixture();
		Monster mob = monster(f, 50, 0);
		observe(f, mob);
		mob.getAggroList().get(f.a).addDamage(50);
		f.theft(mob);
		check(f.scores.getOrDefault(2, 0.0) == 2, "later eligible real damage counts one genuine stolen pull");
		f.theft(mob);
		check(f.scores.get(2) == 2, "same monster cannot count twice");
	}
	private static void ambiguousOrder() throws Exception
	{
		Fixture f = new Fixture();
		Monster mob = monster(f, 50, 50);
		observe(f, mob);
		f.theft(mob);
		check(f.scores.isEmpty(), "first-observed simultaneous contributions do not imply theft");
		f = new Fixture();
		mob = monster(f, 0, 50);
		observe(f, mob);
		mob.getAggroList().get(f.hunter).addDamage(50);
		f.theft(mob);
		check(f.scores.isEmpty(), "hunter damage after player first hit cannot establish prior ownership");
	}
	private static void engagementInvalidation() throws Exception
	{
		Fixture f = new Fixture();
		Monster mob = monster(f, 50, 0);
		observe(f, mob);
		AggroInfo replacement = new AggroInfo(f.hunter);
		replacement.addDamage(50);
		mob.getAggroList().put(f.hunter, replacement);
		mob.getAggroList().get(f.a).addDamage(50);
		f.theft(mob);
		check(f.scores.isEmpty(), "respawn/replaced native damage entry invalidates old evidence");
		f = new Fixture();
		mob = monster(f, 50, 0);
		observe(f, mob);
		invoke(f.manager, "setClaimedMob", new Class<?>[] { DATA, int.class }, f.data, 0);
		invoke(f.manager, "setClaimedMob", new Class<?>[] { DATA, int.class }, f.data, 10);
		mob.getAggroList().get(f.a).addDamage(50);
		f.theft(mob);
		check(f.scores.isEmpty(), "dropping and reclaiming the same mob invalidates old evidence");
	}
	private static void offlineFirstHit() throws Exception
	{
		Fixture f = new Fixture();
		Monster mob = monster(f, 50, 50);
		set(f.a, Player.class, "_client", null);
		observe(f, mob);
		set(f.a, Player.class, "_client", allocate(Client.class));
		f.theft(mob);
		check(f.scores.isEmpty(), "offline prior player damage cannot be reinterpreted as theft on reconnect");
	}
	private static void scoringProtections() throws Exception
	{
		for (String protection : new String[] { "_newbie", "_isInDuel", "_inOlympiadMode", "_observerMode", "_isOnEvent", "instance", "gm", "party", "clan", "ally" })
		{
			Fixture f = new Fixture();
			Monster mob = monster(f, 50, 0);
			observe(f, mob);
			mob.getAggroList().get(f.a).addDamage(50);
			switch (protection)
			{
				case "instance": f.a.getLocation().setInstanceId(1); break;
				case "gm":
					AccessLevel access = allocate(AccessLevel.class);
					set(access, AccessLevel.class, "_isGm", true);
					set(f.a, Player.class, "_accessLevel", access);
					break;
				case "party":
					Party party = allocate(Party.class);
					set(f.hunter, Player.class, "_party", party);
					set(f.a, Player.class, "_party", party);
					break;
				case "clan": case "ally":
					Clan hunterClan = allocate(Clan.class);
					Clan playerClan = protection.equals("clan") ? hunterClan : allocate(Clan.class);
					set(hunterClan, Clan.class, "_allyId", 77);
					set(playerClan, Clan.class, "_allyId", 77);
					set(f.hunter, Player.class, "_clan", hunterClan);
					set(f.a, Player.class, "_clan", playerClan);
					break;
				default: set(f.a, Player.class, protection, true);
			}
			f.theft(mob);
			check(f.scores.isEmpty(), protection + " prevents theft scoring");
		}
		Fixture f = new Fixture();
		f.scores.put(2, 5.0);
		set(f.a, Player.class, "_newbie", true);
		f.tick(1000);
		check(f.scores.isEmpty(), "becoming protected removes earlier actionable grievances");
		set(f.a, Player.class, "_newbie", false);
		f.tick(3000);
		check((long) get(f.data, "spotAttackAt") == 0, "leaving protection does not revive an old ultimatum");
	}
	public static class CombatAI extends PlayerAI
	{
		Creature opponent;
		public CombatAI(Player player) { super(player); }
		@Override public Creature getAttackTarget() { return opponent; }
	}
	private static void rolledDuelBusy() throws Exception
	{
		Fixture f = new Fixture();
		CombatAI ai = allocate(CombatAI.class);
		ai.opponent = f.a;
		set(f.hunter, Creature.class, "_ai", ai);
		FakePlayersConfig.PHANTOM_PVP_DUELS = true;
		FakePlayersConfig.PHANTOM_PVP_SPOT_FIGHT_PERCENT = 0;
		f.scores.put(2, 5.0);
		for (long now = 1000; now <= 7000; now += 2000) { f.tick(now); }
		check((int) get(f.data, "pvpTargetOid") == 0, "native busy duelist makes selected challenge stand down without PvP");
	}
	private static void rolledDuelUnavailable() throws Exception
	{
		Fixture f = new Fixture();
		FakePlayersConfig.PHANTOM_PVP_DUELS = false;
		FakePlayersConfig.PHANTOM_PVP_SPOT_FIGHT_PERCENT = 0;
		FakePlayersConfig.PHANTOM_PVP_SPOT_COOLDOWN_SECONDS = 300;
		f.scores.put(2, 5.0);
		try { for (long now = 1000; now <= 7000; now += 2000) { f.tick(now); } }
		catch (java.lang.reflect.InvocationTargetException e)
		{
			// A broken implementation may claim PvP before reaching world/AI services outside this fixture.
			check((int) get(f.data, "pvpTargetOid") == 0, "FightPercent zero never claims ordinary PvP");
			throw e;
		}
		check((int) get(f.data, "pvpTargetOid") == 0, "unavailable rolled duel stands down");
	}
	private static void disabledForgetsScores() throws Exception
	{
		Fixture f = new Fixture();
		observe(f, monster(f, 50, 0));
		f.scores.put(2, 5.0);
		FakePlayersConfig.PHANTOM_PVP_SPOT_DEFENSE = false;
		f.tick(1000);
		check(f.scores.isEmpty(), "disabled spot feature forgets annoyance");
		check(get(f.data, "spotEngagedMonster") == null, "disabled spot feature forgets theft evidence");
		FakePlayersConfig.PHANTOM_PVP_SPOT_DEFENSE = true;
		f.tick(3000);
		check(f.scores.isEmpty() && (long) get(f.data, "spotAttackAt") == 0, "re-enabled spot feature starts without old grievance or ultimatum");
	}
	private static void disabledMasterForgetsScores() throws Exception
	{
		Fixture f = new Fixture();
		f.scores.put(2, 5.0);
		set(f.manager, PhantomManager.class, "_phantoms", new ConcurrentHashMap<>(Map.of(1, f.data)));
		set(f.manager, PhantomManager.class, "_duelsToCancel", new ConcurrentHashMap<Integer, Long>());
		set(f.data, DATA, "spotAttackAt", 6000L);
		FakePlayersConfig.PHANTOM_PVP_ENABLED = false;
		invoke(f.manager, "pvpCombat", new Class<?>[0]);
		check(f.scores.isEmpty(), "disabled PvP master forgets annoyance through actual tick");
		check((long) get(f.data, "spotAttackAt") == 0, "disabled master forgets pending spot ultimatum");
		FakePlayersConfig.PHANTOM_PVP_ENABLED = true;
		set(f.data, DATA, "dormant", true);
		invoke(f.manager, "pvpCombat", new Class<?>[0]);
		check(f.scores.isEmpty(), "re-enabled master does not recover old scores");
	}
	private static void disabledSkippedActor() throws Exception
	{
		Fixture f = new Fixture();
		f.scores.put(2, 5.0);
		set(f.manager, PhantomManager.class, "_phantoms", new ConcurrentHashMap<>(Map.of(1, f.data)));
		set(f.manager, PhantomManager.class, "_duelsToCancel", new ConcurrentHashMap<Integer, Long>());
		set(f.data, DATA, "olympian", true);
		FakePlayersConfig.PHANTOM_PVP_SPOT_DEFENSE = false;
		invoke(f.manager, "pvpCombat", new Class<?>[0]);
		check(f.scores.isEmpty(), "feature-disable cleanup runs before excluded actor skips");
	}
	private static void whiteWarnings() throws Exception
	{
		Fixture f = new Fixture();
		set(f.a, Player.class, "_pvpFlag", (byte) 0);
		check(!f.a.isAutoAttackable(f.hunter), "white player retains native attack protection");
		check((boolean) invoke(f.manager, "spotOffender", new Class<?>[] { Player.class, Player.class, long.class }, f.hunter, f.a, 1000L), "white farmer can receive warnings");
		f.a.setInsideZone(ZoneId.NO_PVP, true);
		check(!(boolean) invoke(f.manager, "spotOffender", new Class<?>[] { Player.class, Player.class, long.class }, f.hunter, f.a, 1000L), "no-PvP player cannot receive escalation");
	}
	private static void offenderChange() throws Exception
	{
		Fixture f = new Fixture();
		f.scores.put(2, 5.0);
		f.tick(1000);
		check((long) get(f.data, "spotAttackAt") == 7000, "first offender gets six-second grace");
		f.scores.remove(2);
		f.scores.put(3, 5.0);
		f.tick(3000);
		check((long) get(f.data, "spotAttackAt") == 9000, "replacement offender gets its own full grace");
	}
	private static void calmedThenReturns() throws Exception
	{
		Fixture f = new Fixture();
		f.scores.put(2, 5.0);
		f.tick(1000);
		f.scores.put(2, 3.0);
		f.tick(3000);
		check((long) get(f.data, "spotAttackAt") == 0, "calming below attack limit cancels ultimatum");
		f.scores.put(2, 5.0);
		f.tick(9000);
		check((long) get(f.data, "spotAttackAt") == 15000, "renewed offense gets new grace");
	}
	private static void suspendedObservation() throws Exception
	{
		Fixture f = new Fixture();
		set(f.data, DATA, "spotTemper", PhantomSpotRules.Temper.PATIENT);
		set(f.data, DATA, "spotScoreAt", 1000L);
		set(f.data, DATA, "spotCooldownUntil", 301000L);
		set(f.a, Creature.class, "_attackEndTime", Long.MAX_VALUE);
		f.tick(300000);
		f.tick(302000);
		check(f.scores.getOrDefault(2, 0.0) < 0.1, "cooldown does not charge five minutes to a newly seen farmer");
		set(f.data, DATA, "spotNextAt", 0L);
		set(f.data, DATA, "spotScoreAt", 1000L);
		f.tick(900000);
		check(f.scores.getOrDefault(2, 0.0) < 0.1, "unobserved dormant interval is not charged as crowding");
	}
	private interface Checked { void run() throws Exception; }
	private static void sourceSafety() throws Exception
	{
		for (ZoneId zone : new ZoneId[] { ZoneId.PEACE, ZoneId.NO_PVP })
		{
			Fixture f = new Fixture();
			f.scores.put(2, 5.0);
			f.hunter.setInsideZone(zone, true);
			f.tick(1000);
			check((long) get(f.data, "spotAttackAt") == 0, "hunter in " + zone + " cannot issue an ultimatum");
			check((int) get(f.data, "pvpTargetOid") == 0, "hunter in " + zone + " cannot claim a fight");
		}
	}
	private static void whiteStandDown() throws Exception
	{
		Fixture f = new Fixture();
		set(f.a, Player.class, "_pvpFlag", (byte) 0);
		FakePlayersConfig.PHANTOM_PVP_DUELS = false;
		FakePlayersConfig.PHANTOM_PVP_SPOT_FIGHT_PERCENT = 100;
		FakePlayersConfig.PHANTOM_PVP_SPOT_COOLDOWN_SECONDS = 300;
		f.scores.put(2, 5.0);
		for (long now = 1000; now <= 7000; now += 2000) { f.tick(now); }
		check((int) get(f.data, "pvpTargetOid") == 0, "white player never gets a forced fight when duels are off");
		check((long) get(f.data, "spotCooldownUntil") == 307000, "unavailable white duel still starts hunter cooldown");
		check(!f.a.isAutoAttackable(f.hunter), "warning expiry never authorizes native attacks on white player");
	}
	private static class PausedMap extends HashMap<Integer, Double>
	{
		final CountDownLatch reading = new CountDownLatch(1);
		final CountDownLatch resume = new CountDownLatch(1);
		@Override public Set<Map.Entry<Integer, Double>> entrySet()
		{
			reading.countDown();
			try { if (!resume.await(5, TimeUnit.SECONDS)) { throw new AssertionError("reader timeout"); } }
			catch (InterruptedException e) { throw new AssertionError(e); }
			return super.entrySet();
		}
	}
	private static void concurrentTheft() throws Exception
	{
		Fixture f = new Fixture();
		PausedMap scores = new PausedMap();
		set(f.data, DATA, "spotScores", scores);
		Monster mob = monster(f, 50, 0);
		observe(f, mob);
		mob.getAggroList().get(f.a).addDamage(50);
		set(f.data, DATA, "spotScoreAt", 500L); // first observation would reset warning state only
		AtomicReference<Throwable> error = new AtomicReference<>();
		CountDownLatch recorded = new CountDownLatch(1);
		Thread reader = new Thread(() -> { try { f.tick(1000); } catch (Throwable e) { error.set(e); } });
		Thread writer = new Thread(() ->
		{
			try { invoke(f.manager, "noteKillSteal", new Class<?>[] { DATA, Monster.class, long.class }, f.data, mob, 1000L); }
			catch (Throwable e) { error.set(e); }
			finally { recorded.countDown(); }
		});
		reader.start();
		check(scores.reading.await(5, TimeUnit.SECONDS), "reader reaches score transaction");
		writer.start();
		boolean raced;
		try { raced = recorded.await(250, TimeUnit.MILLISECONDS); }
		finally { scores.resume.countDown(); reader.join(5000); writer.join(5000); }
		check(error.get() == null, "both native tasks finish without errors: " + error.get());
		check(!raced, "theft cannot mutate scores during the warning transaction");
		check(scores.get(2) == 2.0, "theft increment survives the observation transaction");
	}
	private static void run(String name, Checked test)
	{
		try { test.run(); System.out.println("PASS: " + name); }
		catch (Throwable e) { failures++; System.out.println("FAIL: " + name + ": " + e); e.printStackTrace(); }
	}
	public static void main(String[] args)
	{
		run("white warning eligibility", PhantomSpotIntegrationTest::whiteWarnings);
		run("offender replacement", PhantomSpotIntegrationTest::offenderChange);
		run("calm cancellation", PhantomSpotIntegrationTest::calmedThenReturns);
		run("suspended observation", PhantomSpotIntegrationTest::suspendedObservation);
		run("concurrent theft transaction", PhantomSpotIntegrationTest::concurrentTheft);
		run("hunter safe-zone gates", PhantomSpotIntegrationTest::sourceSafety);
		run("white stand-down", PhantomSpotIntegrationTest::whiteStandDown);
		run("passing player is innocent", PhantomSpotIntegrationTest::passingPlayer);
		run("first real-player hit is legitimate", PhantomSpotIntegrationTest::firstHitBelongsToPlayer);
		run("protected player has no hidden grievance", PhantomSpotIntegrationTest::protectedThief);
		run("genuine later damage counts once", PhantomSpotIntegrationTest::actualLaterDamage);
		run("ambiguous damage order is innocent", PhantomSpotIntegrationTest::ambiguousOrder);
		run("changed claim/respawn invalidates evidence", PhantomSpotIntegrationTest::engagementInvalidation);
		run("offline prior damage remains innocent", PhantomSpotIntegrationTest::offlineFirstHit);
		run("all scoring protections", PhantomSpotIntegrationTest::scoringProtections);
		run("unavailable rolled duel stands down", PhantomSpotIntegrationTest::rolledDuelUnavailable);
		run("native busy rolled duel stands down", PhantomSpotIntegrationTest::rolledDuelBusy);
		run("spot disable clears scores", PhantomSpotIntegrationTest::disabledForgetsScores);
		run("master disable clears scores", PhantomSpotIntegrationTest::disabledMasterForgetsScores);
		run("feature disable clears skipped actors", PhantomSpotIntegrationTest::disabledSkippedActor);
		System.out.println(checks + " native checks, " + failures + " failed cases");
		if (failures != 0) { System.exit(1); }
	}
}
