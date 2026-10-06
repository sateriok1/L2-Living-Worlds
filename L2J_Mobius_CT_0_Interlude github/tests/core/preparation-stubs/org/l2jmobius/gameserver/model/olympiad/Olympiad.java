package org.l2jmobius.gameserver.model.olympiad;
import java.util.HashSet;
import java.util.Set;
import org.l2jmobius.gameserver.model.actor.Player;
/** Registration boundary; no scheduler or database is started. */
public final class Olympiad
{
	private static final Olympiad INSTANCE = new Olympiad();
	public static final Set<Integer> registered = new HashSet<>();
	public static Olympiad getInstance() { return INSTANCE; }
	public boolean isRegisteredInComp(Player player) { return registered.contains(player.getObjectId()); }
}
