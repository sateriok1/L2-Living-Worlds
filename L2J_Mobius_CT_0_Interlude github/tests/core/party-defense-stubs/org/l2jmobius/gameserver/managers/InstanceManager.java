package org.l2jmobius.gameserver.managers;

import org.l2jmobius.gameserver.model.instancezone.Instance;

/** Keep native zone queries independent of database-backed instance startup. */
public final class InstanceManager
{
	private static final InstanceManager INSTANCE = new InstanceManager();

	public static InstanceManager getInstance()
	{
		return INSTANCE;
	}

	public Instance getInstance(int id)
	{
		return null;
	}
}
