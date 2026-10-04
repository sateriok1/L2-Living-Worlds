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
package org.l2jmobius.gameserver.managers;

/** Ranged endpoint selection. Native geodata supplies the reachable point and visibility. */
public final class PhantomCasterPositioning
{
	public record Point(int x, int y, int z)
	{
		public double distance(Point other)
		{
			return Math.hypot(x - other.x, y - other.y);
		}
	}

	public interface Geometry
	{
		Point reachable(Point candidate);
		boolean visible(Point candidate);
	}

	private PhantomCasterPositioning()
	{
	}

	/** Failed visibility never sends a ranged caster to the target's tile. */
	public static Point destination(Point actor, Point target, int reach, int spread, double collision, Geometry geo)
	{
		final double distance = Math.max(1, actor.distance(target));
		final double angle = Math.atan2(actor.y() - target.y(), actor.x() - target.x());
		final int desired = Math.max(40, Math.min(reach - 40, (int) distance));
		final double minimum = (reach >= 200) ? Math.max(collision + 40, Math.min(desired, Math.max(120, reach / 2))) : 0;
		for (int offset : new int[] { 0, 30, -30, 60, -60 })
		{
			final double nx = Math.cos(angle + Math.toRadians(offset));
			final double ny = Math.sin(angle + Math.toRadians(offset));
			for (int range = desired; range >= Math.max(40, minimum); range -= 80)
			{
				final int lateral = Math.min(Math.abs(spread), range / 6) * Integer.signum(spread);
				final Point candidate = geo.reachable(new Point(target.x() + (int) ((nx * range) - (ny * lateral)), target.y() + (int) ((ny * range) + (nx * lateral)), actor.z()));
				if ((candidate != null) && (actor.distance(candidate) > 8) && ((candidate.distance(target) + 1) >= minimum) && (candidate.distance(target) <= (reach + collision)) && geo.visible(candidate))
				{
					return candidate;
				}
			}
		}
		return null;
	}
}
