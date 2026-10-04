import org.l2jmobius.gameserver.managers.PhantomCasterPositioning;
import org.l2jmobius.gameserver.managers.PhantomCasterPositioning.Geometry;
import org.l2jmobius.gameserver.managers.PhantomCasterPositioning.Point;

public class PhantomCasterPositioningTest
{
	private static int checks;

	private static final class Geo implements Geometry
	{
		Point clamped;
		boolean blocked;
		boolean sideOnly;
		double smallestRequest = Double.MAX_VALUE;

		@Override
		public Point reachable(Point candidate)
		{
			smallestRequest = Math.min(smallestRequest, candidate.distance(new Point(0, 0, 0)));
			return clamped == null ? candidate : clamped;
		}

		@Override
		public boolean visible(Point candidate)
		{
			return !blocked && (!sideOnly || (candidate.y() > 200));
		}
	}

	private static void check(boolean condition, String message)
	{
		checks++;
		if (!condition)
		{
			throw new AssertionError(message);
		}
	}

	public static void main(String[] args)
	{
		final Point actor = new Point(900, 0, 0);
		final Point target = new Point(0, 0, 0);
		final Geo clear = new Geo();
		final Point direct = PhantomCasterPositioning.destination(actor, target, 600, 0, 40, clear);
		check(direct != null && (direct.x() == 560) && (direct.y() == 0), "a 600-range caster approaches to a ranged endpoint");
		final Geo obstacle = new Geo();
		obstacle.sideOnly = true;
		final Point side = PhantomCasterPositioning.destination(actor, target, 600, 110, 40, obstacle);
		check(side != null && (side.y() > 200) && (side.distance(target) >= 300), "a blocked straight approach tries a visible side position at range");
		final Geo blocked = new Geo();
		blocked.blocked = true;
		check(PhantomCasterPositioning.destination(actor, target, 600, 0, 40, blocked) == null, "no visible ranged endpoint means hold rather than walk onto the mob");
		check(blocked.smallestRequest >= 300, "failed searches never request melee positions for this caster");
		final Geo targetClamp = new Geo();
		targetClamp.clamped = target;
		check(PhantomCasterPositioning.destination(actor, target, 600, 0, 40, targetClamp) == null, "geodata cannot clamp a safe requested point onto the mob");
		final Geo wallClamp = new Geo();
		wallClamp.clamped = actor;
		check(PhantomCasterPositioning.destination(actor, target, 600, 0, 40, wallClamp) == null, "a blocked path clamped to the actor cannot count as useful movement");
		final Geo farClamp = new Geo();
		farClamp.clamped = new Point(1000, 0, 0);
		check(PhantomCasterPositioning.destination(actor, target, 600, 0, 40, farClamp) == null, "a clamped endpoint beyond cast reach is rejected");
		final Point largeMob = PhantomCasterPositioning.destination(actor, target, 600, 0, 500, clear);
		check(largeMob != null && (largeMob.distance(target) >= 540), "large target collision still leaves ranged clearance");
		final Point contact = PhantomCasterPositioning.destination(actor, target, 40, 0, 40, clear);
		check(contact != null && (contact.x() == 40), "shared approach still supports contact skills");
		final Point alreadyClose = PhantomCasterPositioning.destination(new Point(250, 0, 0), target, 600, 0, 40, clear);
		check(alreadyClose != null && (Math.abs(alreadyClose.y()) > 100) && (alreadyClose.distance(target) >= 249), "a close caster seeking visibility tries sideways without reducing its existing separation");
		System.out.println("PhantomCasterPositioningTest: " + checks + " checks passed");
	}
}
