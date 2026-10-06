package org.l2jmobius.gameserver.data.xml;

import java.util.ArrayList;
import java.util.List;
import org.l2jmobius.gameserver.model.skill.Skill;

/** Observe the spawn-kit lookup boundary without starting datapack effect handlers. Native cast conditions stay real. */
public final class SkillData
{
	private static final SkillData INSTANCE = new SkillData();
	public static final List<Integer> requested = new ArrayList<>();
	public static SkillData getInstance() { return INSTANCE; }
	public int getMaxLevel(int id) { requested.add(id); return 1; }
	public Skill getSkill(int id, int level) { return null; }
}
