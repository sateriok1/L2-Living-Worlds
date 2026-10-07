package org.l2jmobius.gameserver.model.sevensigns;
import java.util.HashSet;
import java.util.Set;
import org.l2jmobius.gameserver.model.actor.Player;
/** Festival enrollment boundary for native player gates. */
public final class SevenSignsFestival
{
	private static final SevenSignsFestival INSTANCE = new SevenSignsFestival();
	public static final Set<Integer> participants = new HashSet<>();
	public static SevenSignsFestival getInstance() { return INSTANCE; }
	public boolean isParticipant(Player player) { return participants.contains(player.getObjectId()); }
}
