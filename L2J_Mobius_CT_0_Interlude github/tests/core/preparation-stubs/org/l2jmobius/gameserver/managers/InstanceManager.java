package org.l2jmobius.gameserver.managers;

import org.l2jmobius.gameserver.model.instancezone.Instance;

/** Stock peace-zone checks expect the ordinary world (instance zero) to exist. */
public final class InstanceManager
{
	private static final InstanceManager INSTANCE = new InstanceManager();
	private final Instance world = new Instance(0);
	public static InstanceManager getInstance() { return INSTANCE; }
	public Instance getInstance(int id) { return id == 0 ? world : null; }
}
