package org.l2jmobius.gameserver.model;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.l2jmobius.gameserver.model.actor.Player;

/** Isolate the visible-player boundary while running the native manager and Player gates. */
public class World
{
	private static final World INSTANCE = new World();
	public static final ConcurrentHashMap<Integer, Player> players = new ConcurrentHashMap<>();
	public static World getInstance() { return INSTANCE; }
	public Player getPlayer(int id) { return players.get(id); }
	public WorldObject findObject(int id) { return players.get(id); }
	public <T extends WorldObject> List<T> getVisibleObjectsInRange(WorldObject source, Class<T> type, int range)
	{
		return players.values().stream().filter(p -> p != source && type.isInstance(p) && source.calculateDistance2D(p) <= range).map(type::cast).toList();
	}
	public <T extends WorldObject> void forEachVisibleObject(WorldObject source, Class<T> type, Consumer<T> action) { }
}
