package org.l2jmobius.gameserver.model;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.l2jmobius.gameserver.model.actor.Player;

/** Isolate encounter discovery and packet broadcast from a running server. */
public final class World
{
	private static final World INSTANCE = new World();
	public static final ConcurrentHashMap<Integer, Player> players = new ConcurrentHashMap<>();
	public static World getInstance() { return INSTANCE; }
	public WorldObject findObject(int id) { return players.get(id); }
	public Player getPlayer(int id) { return players.get(id); }
	public <T extends WorldObject> List<T> getVisibleObjectsInRange(WorldObject source, Class<T> type, int range) { return List.of(); }
	public <T extends WorldObject> void forEachVisibleObject(WorldObject source, Class<T> type, Consumer<T> action) { }
}
