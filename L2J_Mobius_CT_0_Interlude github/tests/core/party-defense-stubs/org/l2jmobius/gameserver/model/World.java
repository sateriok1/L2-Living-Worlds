package org.l2jmobius.gameserver.model;

import java.util.ArrayList;
import java.util.List;

/** Supply region candidates without starting the live world. */
public final class World
{
	private static final World INSTANCE = new World();
	public static final List<WorldObject> objects = new ArrayList<>();

	public static World getInstance()
	{
		return INSTANCE;
	}

	public <T extends WorldObject> List<T> getVisibleObjectsInRange(WorldObject source, Class<T> type, int range)
	{
		return objects.stream().filter(type::isInstance)
			.filter(o -> (o.getInstanceId() == source.getInstanceId()) && (o.calculateDistance3D(source) <= range))
			.map(type::cast).toList();
	}
}
