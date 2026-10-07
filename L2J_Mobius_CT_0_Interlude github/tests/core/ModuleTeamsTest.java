import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.l2jmobius.gameserver.data.holders.AccessLevel;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.creature.Team;
import org.l2jmobius.gameserver.model.actor.holders.player.AutoPlaySettingsHolder;
import org.l2jmobius.gameserver.model.zone.ZoneId;
import org.l2jmobius.gameserver.modules.ModuleTeams;

/** Native team-event rules: solo flag reset, teammate protection, outsider gate, lock restore and party validation. */
public class ModuleTeamsTest
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
		set(player, Creature.class, "_team", Team.NONE);
		set(player, Player.class, "_autoPlaying", new AtomicBoolean(false));
		set(player, Player.class, "_autoPlaySettings", new AutoPlaySettingsHolder());
		set(player, Player.class, "_accessLevel", allocate(AccessLevel.class));
		return player;
	}

	private static void event(Player player, Team team, boolean solo) throws Exception
	{
		set(player, Creature.class, "_team", team);
		player.setOnEvent(true);
		player.setOnSoloEvent(solo);
	}

	private static ModuleTeams teams() throws Exception
	{
		final Constructor<ModuleTeams> constructor = ModuleTeams.class.getDeclaredConstructor();
		constructor.setAccessible(true);
		return constructor.newInstance();
	}

	public static void main(String[] args) throws Exception
	{
		final ModuleTeams teams = teams();
		final Player a = player(20001);
		final Player b = player(20002);
		final Player red = player(20003);
		final Player outsider = player(20004);

		// Event flags as ModuleTeams.join and joinSolo set them. Player.setTeam broadcasts UserInfo, which needs a full
		// character, so the team field is set directly here.
		event(a, Team.BLUE, false);
		event(b, Team.BLUE, false);
		event(red, Team.RED, false);

		// FPC-247: the stale solo flag that join() used to leave behind breaks teammate protection...
		a.setOnSoloEvent(true);
		check(!Creature.areEventTeammates(a, b), "a stale solo flag drops the teammate guard");
		check(a.isAutoAttackable(b), "a stale solo flag makes a blue teammate attackable");
		// ...and with the flag cleared, as join() now does, blue teammates are protected again.
		a.setOnSoloEvent(false);
		check(Creature.areEventTeammates(a, b), "blue players are teammates");
		check(!b.isAutoAttackable(a), "a blue teammate is not attackable");
		check(red.isAutoAttackable(a), "the red player is attackable");
		check(!Creature.areEventTeammates(a, red), "blue and red are not teammates");

		// Outsider gate: a player outside the event cannot attack event players; event enemies still can.
		check(!a.isAutoAttackable(outsider), "an outsider cannot attack an event player");
		check(a.isAutoAttackable(red), "an event enemy can attack");

		// FPC-253: unlock restores the earlier state instead of clearing it.
		b.setInvul(true);
		teams.lock(b, true);
		check(b.isImmobilized() && b.isInvulRaw(), "lock holds the player");
		teams.lock(b, false);
		check(b.isInvulRaw(), "unlock keeps invulnerability the player already had");
		check(!b.isImmobilized(), "unlock frees movement the lock took");
		b.setInvul(false);
		teams.lock(red, true);
		teams.lock(red, false);
		check(!red.isInvulRaw() && !red.isImmobilized(), "unlock restores a plain player");

		// FPC-255: a cross-team or outsider list is refused before any party is touched.
		check(!teams.formParty(List.of(a, red)), "blue and red cannot share a party");
		check(!teams.formParty(List.of(a, outsider)), "an outsider cannot join an event party");
		check(!teams.formParty(List.of(a)), "a party needs two members");
		check(!a.isInParty() && !red.isInParty(), "a refused party changes nothing");

		System.out.println("ModuleTeamsTest: " + checks + " checks passed");
	}
}
