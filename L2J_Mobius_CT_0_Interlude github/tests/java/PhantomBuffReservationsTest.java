/*
 * Copyright (c) 2013 L2jMobius
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR
 * IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

import org.l2jmobius.gameserver.managers.PhantomBuffReservations;

/**
 * Standalone (no JUnit, no game server) regression harness for {@link PhantomBuffReservations} - the shared,
 * race-safe registry that stops two independent bot buff sources (a recruited-party support and the personal
 * buddy) from double-casting the same buff on the same target. The time source and key are injected, so the
 * expiry / caster-identity logic is verified deterministically.
 *
 * <p>Run from the project root ("L2J_Mobius_CT_0_Interlude github"):
 * <pre>
 *   javac -d build/test-classes \
 *         "java/org/l2jmobius/gameserver/managers/PhantomBuffReservations.java" \
 *         "tests/java/PhantomBuffReservationsTest.java"
 *   java -cp build/test-classes PhantomBuffReservationsTest
 * </pre>
 * Exit code is 0 when every check passes, 1 otherwise.
 */
public class PhantomBuffReservationsTest
{
	private static int checks = 0;
	private static int failures = 0;

	// Distinct object ids for readability.
	private static final int TARGET = 100;
	private static final int OTHER_TARGET = 200;
	private static final int PROPHET = 10;
	private static final int BUDDY = 11;
	private static final int MIGHT = 1068;
	private static final int SHIELD = 1040;
	private static final int HOLD = 3000;

	public static void main(String[] args)
	{
		testGrantAndBlock();
		testSameCasterRefresh();
		testExpiry();
		testIndependentSlots();
		testKeyUniqueness();
		testRefusedCastRelease();

		System.out.println();
		System.out.println("Ran " + checks + " checks, " + failures + " failure(s).");
		if (failures > 0)
		{
			System.exit(1);
		}
		System.out.println("OK");
	}

	/** First caster wins the slot; a different caster is refused while it is held. */
	private static void testGrantAndBlock()
	{
		final PhantomBuffReservations r = new PhantomBuffReservations();
		final long key = PhantomBuffReservations.key(TARGET, MIGHT);
		eq(true, r.reserve(key, 1000, PROPHET, HOLD), "first caster claims the slot");
		eq(false, r.reserve(key, 1500, BUDDY, HOLD), "a different caster is refused while the claim is live");
		eq(false, r.reserve(key, 3999, BUDDY, HOLD), "still refused just before expiry");
	}

	/** The holder re-claiming its own slot always succeeds (a support re-casting the same buff refreshes, never blocks itself). */
	private static void testSameCasterRefresh()
	{
		final PhantomBuffReservations r = new PhantomBuffReservations();
		final long key = PhantomBuffReservations.key(TARGET, MIGHT);
		eq(true, r.reserve(key, 1000, PROPHET, HOLD), "claim");
		eq(true, r.reserve(key, 1200, PROPHET, HOLD), "same caster re-claims (refresh, not blocked)");
		// The refresh moved expiry to 1200+HOLD, so another caster stays blocked past the original 4000 expiry.
		eq(false, r.reserve(key, 4100, BUDDY, HOLD), "refresh extended the hold, other caster still blocked");
	}

	/** Once a claim expires, another caster may take the slot. */
	private static void testExpiry()
	{
		final PhantomBuffReservations r = new PhantomBuffReservations();
		final long key = PhantomBuffReservations.key(TARGET, MIGHT);
		eq(true, r.reserve(key, 1000, PROPHET, HOLD), "claim held until 4000");
		eq(true, r.reserve(key, 4000, BUDDY, HOLD), "at expiry (exclusive) the slot is free again");
	}

	/** Different target/skill pairs are independent - claiming one never blocks another. */
	private static void testIndependentSlots()
	{
		final PhantomBuffReservations r = new PhantomBuffReservations();
		eq(true, r.reserve(PhantomBuffReservations.key(TARGET, MIGHT), 1000, PROPHET, HOLD), "claim Might on target");
		eq(true, r.reserve(PhantomBuffReservations.key(TARGET, SHIELD), 1000, BUDDY, HOLD), "different skill on same target is free");
		eq(true, r.reserve(PhantomBuffReservations.key(OTHER_TARGET, MIGHT), 1000, BUDDY, HOLD), "same skill on a different target is free");
	}

	/** The composed key must be unique per (target, skill) pair. */
	private static void testKeyUniqueness()
	{
		final long tMight = PhantomBuffReservations.key(TARGET, MIGHT);
		neq(tMight, PhantomBuffReservations.key(TARGET, SHIELD), "same target, different skill -> different key");
		neq(tMight, PhantomBuffReservations.key(OTHER_TARGET, MIGHT), "different target, same skill -> different key");
		eq(tMight, PhantomBuffReservations.key(TARGET, MIGHT), "same pair -> same key");
	}

	private static void testRefusedCastRelease()
	{
		final PhantomBuffReservations r = new PhantomBuffReservations();
		final long key = PhantomBuffReservations.key(TARGET, MIGHT);
		r.reserve(key, 1000, PROPHET, HOLD);
		r.release(key, BUDDY);
		eq(false, r.reserve(key, 1001, BUDDY, HOLD), "a non-holder cannot release another caster's claim");
		r.release(key, PROPHET);
		eq(true, r.reserve(key, 1002, BUDDY, HOLD), "a refused cast immediately frees the buff for another support");
		r.release(key, PROPHET);
		eq(true, r.isHeldByOther(key, 1003, PROPHET), "a late release cannot erase another caster's replacement claim");
		r.release(key, BUDDY);
		eq(0, r.size(), "releasing the holder removes the reservation");
	}

	// ===== tiny assertion helpers =====

	private static void eq(Object expected, Object actual, String what)
	{
		checks++;
		if ((expected == null) ? (actual != null) : !expected.equals(actual))
		{
			failures++;
			System.out.println("FAIL: " + what + " -> expected [" + expected + "] but got [" + actual + "]");
		}
	}

	private static void neq(long a, long b, String what)
	{
		checks++;
		if (a == b)
		{
			failures++;
			System.out.println("FAIL: " + what + " -> both were [" + a + "]");
		}
	}
}
