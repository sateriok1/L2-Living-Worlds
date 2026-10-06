package org.l2jmobius.gameserver.managers;

/**
 * The numbers behind spot defense: how annoyed a field hunter gets at a player who steals its kills or crowds its
 * spot, and how patient it is. Plain numbers in and out, so they test on their own.
 */
public final class PhantomSpotRules
{
	/** How much it takes to push a hunter over the edge. */
	public enum Temper
	{
		HOT, // no tolerance: the first offense is enough
		NORMAL,
		PATIENT // puts up with a lot: five minutes of crowding and a couple of stolen kills
	}

	/** Points the annoyance fades by each minute while the player is not offending. */
	public static final double DECAY_PER_MINUTE = 0.5;
	/** Points the annoyance grows by each minute the player hunts close by. */
	public static final double CROWDING_PER_MINUTE = 0.5;

	private PhantomSpotRules()
	{
	}

	/**
	 * @param roll a roll in [0, 100)
	 * @param hotPercent share of hunters with no tolerance
	 * @param normalPercent share with normal tolerance; the rest are patient
	 */
	public static Temper rollTemper(int roll, int hotPercent, int normalPercent)
	{
		final int hot = Math.max(0, Math.min(100, hotPercent));
		final int normal = Math.max(0, Math.min(100 - hot, normalPercent));
		if (roll < hot)
		{
			return Temper.HOT;
		}
		return (roll < (hot + normal)) ? Temper.NORMAL : Temper.PATIENT;
	}

	/** @return the score at which a hunter of this temper attacks. */
	public static double thresholdFor(Temper temper, double hot, double normal, double patient)
	{
		switch (temper)
		{
			case HOT:
			{
				return Math.max(0.1, hot);
			}
			case NORMAL:
			{
				return Math.max(0.1, normal);
			}
			default:
			{
				return Math.max(0.1, patient);
			}
		}
	}

	/** @return the score at which a hunter complains first: half way to its limit, and at least one point (or the limit itself if lower). */
	public static double warnAt(double threshold)
	{
		return Math.min(threshold, Math.max(1.0, threshold / 2));
	}

	/** @return the annoyance after {@code elapsedMs} of the player crowding the spot. */
	public static double crowd(double score, long elapsedMs)
	{
		return score + ((Math.max(0, elapsedMs) / 60_000.0) * CROWDING_PER_MINUTE);
	}

	/** @return the annoyance after {@code elapsedMs} with the player not offending; never below 0. */
	public static double fade(double score, long elapsedMs)
	{
		return Math.max(0, score - ((Math.max(0, elapsedMs) / 60_000.0) * DECAY_PER_MINUTE));
	}

	/** @return {@code true} for a real fight, {@code false} for a duel challenge, given a roll in [0, 100). */
	public static boolean realFight(int roll, int pvpPercent)
	{
		return roll < Math.max(0, Math.min(100, pvpPercent));
	}
}
