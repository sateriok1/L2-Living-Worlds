package org.l2jmobius.gameserver.data.xml;
import java.util.List;
import org.l2jmobius.gameserver.model.actor.Summon;
import org.l2jmobius.gameserver.model.skill.Skill;
/** Known pet skills supplied by the test, without loading NPC and skill XML. */
public final class PetSkillData
{
	private static final PetSkillData INSTANCE = new PetSkillData();
	public static List<Skill> skills = List.of();
	public static PetSkillData getInstance() { return INSTANCE; }
	public List<Skill> getKnownSkills(Summon pet) { return skills; }
}
