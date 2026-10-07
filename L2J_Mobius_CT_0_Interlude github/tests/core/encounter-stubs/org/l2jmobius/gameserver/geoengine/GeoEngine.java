package org.l2jmobius.gameserver.geoengine;

import org.l2jmobius.gameserver.model.WorldObject;

/** Control only line of sight; the real Skill and Player target gates run unchanged. */
public class GeoEngine
{
	private static final GeoEngine INSTANCE = new GeoEngine();
	public static boolean visible = true;

	public static GeoEngine getInstance()
	{
		return INSTANCE;
	}

	public boolean canSeeTarget(WorldObject source, WorldObject target)
	{
		return visible;
	}
}
