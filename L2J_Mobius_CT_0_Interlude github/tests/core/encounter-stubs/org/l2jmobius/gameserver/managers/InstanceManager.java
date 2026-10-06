package org.l2jmobius.gameserver.managers;

import org.l2jmobius.gameserver.model.instancezone.Instance;

/** No active instances; avoid the database-backed manager in the native encounter harness. */
public class InstanceManager
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
