package org.l2jmobius.gameserver.geoengine;

import java.util.HashSet;
import java.util.Set;

import org.l2jmobius.gameserver.model.WorldObject;

/** Control visibility and movement independently while native target selection runs unchanged. */
public final class GeoEngine
{
	private static final GeoEngine INSTANCE = new GeoEngine();
	public static final Set<Integer> invisible = new HashSet<>();
	public static final Set<Integer> blockedX = new HashSet<>();

	public static GeoEngine getInstance()
	{
		return INSTANCE;
	}

	public boolean canSeeTarget(WorldObject source, WorldObject target)
	{
		return !invisible.contains(target.getObjectId());
	}

	public boolean canMoveToTarget(int x, int y, int z, int tx, int ty, int tz, int instance)
	{
		return !blockedX.contains(tx);
	}
}
