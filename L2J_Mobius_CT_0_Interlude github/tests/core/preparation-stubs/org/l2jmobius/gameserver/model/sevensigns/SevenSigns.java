package org.l2jmobius.gameserver.model.sevensigns;
import org.l2jmobius.gameserver.model.actor.Player;
/** Seven Signs policy inputs, independent of persistent state. */
public final class SevenSigns
{
	public static final int CABAL_NULL = 0;
	private static final SevenSigns INSTANCE = new SevenSigns();
	public static int cabal;
	public static int winner = 1;
	public static boolean validation;
	public static SevenSigns getInstance() { return INSTANCE; }
	public int getPlayerCabal(int id) { return cabal; }
	public boolean isSealValidationPeriod() { return validation; }
	public int getCabalHighestScore() { return winner; }
}
