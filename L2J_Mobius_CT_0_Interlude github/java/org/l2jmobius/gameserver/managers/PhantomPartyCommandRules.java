package org.l2jmobius.gameserver.managers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Command precedence and encounter-scoped raid rescue holds, without native actor dependencies. */
public final class PhantomPartyCommandRules
{
	public enum Order
	{
		NONE, STOP, HOLD_FIRE, TANK_ATTACK, ALL_ATTACK, ENGAGE, FREE_HUNT, ASSIST
	}

	private static final Map<Order, List<String>> ALIASES = Map.of(
		Order.TANK_ATTACK, List.of("tank attack", "tank pull", "tank go", "tank engage", "tank initiate", "tank in", "pull it", "pull the boss", "pull boss", "initiate"),
		Order.ALL_ATTACK, List.of("all attack", "everyone attack", "all in", "open fire", "engage all", "attack the raid", "everyone in", "burn it"),
		Order.ENGAGE, List.of("engage", "attack it", "attack this", "kill it"),
		Order.FREE_HUNT, List.of("attack freely", "free hunt", "go wild", "ffa", "hunt freely", "do your own", "attack anything"),
		Order.ASSIST, List.of("assist", "focus", "help me", "on my target", "kill my target", "attack my"),
		Order.HOLD_FIRE, List.of("hold fire", "hold dps", "wait for tank", "fall back", "stop dps", "back off"));
	private static final Map<Order, Pattern> MATCHERS = ALIASES.entrySet().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> pattern(entry.getValue())));
	private static final Pattern NEGATED_ATTACK = negatedAttackPattern();
	private static final Pattern DISENGAGE = Pattern.compile("\\bdisengage\\b");
	private static final Pattern STOP = Pattern.compile("\\bstop\\b");
	private static final Pattern SPECIAL_STOP = pattern(List.of("stop recharge", "stop charging", "stop the recharge", "stop mp", "stop pull", "stop pulling", "stop fetching"));

	private PhantomPartyCommandRules()
	{
	}

	private static String alternatives(Collection<String> aliases)
	{
		return String.join("|", aliases.stream().map(alias -> Pattern.quote(alias).replace(" ", "\\E\\s+\\Q")).toList());
	}

	private static Pattern pattern(Collection<String> aliases)
	{
		return Pattern.compile("\\b(?:" + alternatives(aliases) + ")\\b");
	}

	private static Pattern negatedAttackPattern()
	{
		final List<String> attacks = new ArrayList<>(List.of("attack", "kill", "pull", "engage", "initiate", "hunt"));
		ALIASES.forEach((order, aliases) ->
		{
			if (order != Order.HOLD_FIRE)
			{
				attacks.addAll(aliases);
			}
		});
		return Pattern.compile("\\b(?:don't|dont|do\\s+not|not|no|never)\\s+(?:please\\s+)?(?:" + alternatives(attacks) + ")\\b");
	}

	/** A negative combat instruction wins even when the line also requests follow, assist or another mode. */
	public static Order classify(String message)
	{
		final String text = message.toLowerCase(Locale.ROOT).replace('\u2019', '\'');
		if (DISENGAGE.matcher(text).find() || NEGATED_ATTACK.matcher(text).find())
		{
			return Order.STOP;
		}
		if (MATCHERS.get(Order.HOLD_FIRE).matcher(text).find())
		{
			return Order.HOLD_FIRE;
		}
		// Keep recharge, cubic and camp-puller stop requests with their existing specialized handlers.
		if (STOP.matcher(text).find() && !SPECIAL_STOP.matcher(text).find() && !text.contains("cubic"))
		{
			return Order.STOP;
		}
		for (Order order : List.of(Order.TANK_ATTACK, Order.ALL_ATTACK, Order.ENGAGE, Order.FREE_HUNT, Order.ASSIST))
		{
			if (MATCHERS.get(order).matcher(text).find())
			{
				return order;
			}
		}
		return Order.NONE;
	}

	/** Hold Fire belongs to captured active encounters, never to whichever raid is currently selected. */
	public static final class RescueHold
	{
		private final Set<Integer> _encounters = new HashSet<>();

		public synchronized void block(Collection<Integer> encounterIds)
		{
			_encounters.clear();
			_encounters.addAll(encounterIds);
		}

		public synchronized boolean blocked(IntPredicate encounterActive)
		{
			_encounters.removeIf(id -> !encounterActive.test(id));
			return !_encounters.isEmpty();
		}

		public synchronized void clear()
		{
			_encounters.clear();
		}
	}
}
