import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.l2jmobius.gameserver.managers.FakePlayerChatParsing;
import org.l2jmobius.gameserver.managers.FakePlayerChatParsing.TradeAd;
import org.l2jmobius.gameserver.managers.FakePlayerStorePricing;

/**
 * Standalone harness for the WTS/WTB parser v2 (parseTradeAd, item links, multi-item split, clarify pick, enchant
 * pricing, haggle limit). Exit code 0 when every check passes.
 */
public class TradeAdParserTest
{
	private static int checks = 0;
	private static int failures = 0;

	public static void main(String[] args)
	{
		testAdTable();
		testNotAds();
		testLinks();
		testSplit();
		testClarifyPick();
		testEnchantMultiplier();
		testCounterLimit();
		System.out.println(checks + " checks, " + failures + " failures");
		System.exit(failures == 0 ? 0 : 1);
	}

	private static void testAdTable()
	{
		final String[] items = { "ssd", "soulshot d", "bsoe", "spirit ore", "Mithril Alloy", "adena", "sps b grade", "Tallum Plate Armor", "scroll of escape", "Q" };
		final String[] amounts = { "", " 5k", " 2", " 1kk" };
		final String[] sellMarkers = { "wts", "WTS", "selling", "S>", "[WTS]", "+WTS", "wts:" };
		final String[] buyMarkers = { "wtb", "WTB", "buying", "B>", "[WTB]", "+WTB", "wtb:" };
		int rows = 0;
		for (String item : items)
		{
			for (String amount : amounts)
			{
				for (String marker : sellMarkers)
				{
					// marker first, and marker last
					check(marker + " " + item + amount, true, item);
					check(item + amount + " " + marker, true, item);
					rows += 2;
				}
				for (String marker : buyMarkers)
				{
					check(marker + " " + item + amount, false, item);
					check(item + amount + " " + marker, false, item);
					rows += 2;
				}
			}
		}
		truth(rows >= 100, "table has 100+ ads (" + rows + ")");
		// Prices and counts stay in the phrase for the quantity/price parsers.
		final TradeAd priced = FakePlayerChatParsing.parseTradeAd("WTB Soulshot D 5k @300");
		eq("Soulshot D 5k @300", priced.phrase, "price phrase kept");
		eq(300, FakePlayerChatParsing.parseTradeUnitPrice(priced.phrase), "price read from phrase");
		eq(5000, FakePlayerChatParsing.parseTradeQuantity(FakePlayerChatParsing.stripStatedPrices(priced.phrase), true), "qty read from phrase");
		// First marker wins when an ad carries two.
		final TradeAd two = FakePlayerChatParsing.parseTradeAd("wts ssd wtb adena");
		truth(two.selling, "first marker wins");
		eq("ssd", two.phrase, "second ad dropped");
	}

	private static void check(String text, boolean selling, String item)
	{
		final TradeAd ad = FakePlayerChatParsing.parseTradeAd(text);
		checks++;
		if ((ad == null) || (ad.selling != selling))
		{
			failures++;
			System.out.println("FAIL: [" + text + "] -> " + (ad == null ? "null" : ("selling=" + ad.selling)));
			return;
		}
		truth(ad.phrase.toLowerCase().contains(item.toLowerCase().split(" ")[0]), "[" + text + "] phrase keeps item, got [" + ad.phrase + "]");
	}

	private static void testNotAds()
	{
		for (String text : new String[] { "hello", "anyone want to party", "unselling", "wtsfoo", "abuying", "crabs>", "how are you today" })
		{
			truth(FakePlayerChatParsing.parseTradeAd(text) == null, "not an ad: " + text);
		}
		truth(FakePlayerChatParsing.parseTradeAd(null) == null, "null");
		truth(FakePlayerChatParsing.parseTradeAd("") == null, "empty");
	}

	private static void testLinks()
	{
		final char c = (char) 8;
		final String link = c + "Type=1 \tID=268439123 \tColor=0 \tUnderline=0 \tTitle=\u001BSoulshot: D-grade\u001B" + c;
		final String text = "wts " + link + " 5k";
		eq(Arrays.asList(268439123), FakePlayerChatParsing.extractLinkedObjectIds(text), "link id");
		final TradeAd ad = FakePlayerChatParsing.parseTradeAd(text);
		truth(ad != null && ad.selling, "ad with link parses");
		eq(1, ad.linkedObjectIds.size(), "one linked id");
		eq("5k", ad.phrase, "link text removed from phrase");
		eq("wts 5k", FakePlayerChatParsing.stripItemLinks(text), "strip links");
		final String two = "wtb " + link + " and " + link.replace("268439123", "7");
		eq(Arrays.asList(268439123, 7), FakePlayerChatParsing.extractLinkedObjectIds(two), "two links in order");
		eq(0, FakePlayerChatParsing.extractLinkedObjectIds("wts ssd").size(), "no links");
	}

	private static void testSplit()
	{
		eq(Arrays.asList("ssd 5k"), FakePlayerChatParsing.splitTradeItems("ssd 5k", 3), "single");
		eq(Arrays.asList("ssd 5k", "bsoe 2", "spirit ore"), FakePlayerChatParsing.splitTradeItems("ssd 5k, bsoe 2 and spirit ore", 3), "three items");
		eq(Arrays.asList("ssd 5k", "bsoe"), FakePlayerChatParsing.splitTradeItems("ssd 5k, bsoe, spirit ore", 2), "capped at 2");
		eq(Arrays.asList("ssd 5k 300 adena"), FakePlayerChatParsing.splitTradeItems("ssd 5k, 300 adena", 3), "price piece stays attached");
		eq(Arrays.asList("ssd 5k @300", "bsoe"), FakePlayerChatParsing.splitTradeItems("ssd 5k @300 & bsoe", 3), "ampersand");
		truth(FakePlayerChatParsing.splitTradeItems("", 3).isEmpty(), "empty phrase");
		truth(FakePlayerChatParsing.splitTradeItems(null, 3).isEmpty(), "null phrase");
	}

	private static void testClarifyPick()
	{
		final List<String> opts = new ArrayList<>(Arrays.asList("Mithril Alloy", "Mithril Shirt", "Mithril Gaiters"));
		eq(0, FakePlayerChatParsing.pickClarifiedOption("1", opts), "number 1");
		eq(1, FakePlayerChatParsing.pickClarifiedOption("the second one", opts), "ordinal");
		eq(2, FakePlayerChatParsing.pickClarifiedOption("gaiters pls", opts), "unique word");
		eq(0, FakePlayerChatParsing.pickClarifiedOption("alloy", opts), "unique word alloy");
		eq(-1, FakePlayerChatParsing.pickClarifiedOption("mithril", opts), "shared word is not a pick");
		eq(-1, FakePlayerChatParsing.pickClarifiedOption("how much", opts), "no pick");
		eq(-1, FakePlayerChatParsing.pickClarifiedOption("alloy or shirt", opts), "two picks");
		eq(-1, FakePlayerChatParsing.pickClarifiedOption(null, opts), "null reply");
	}

	private static void testEnchantMultiplier()
	{
		eq(1.0, FakePlayerStorePricing.enchantMultiplier(3, 0), "+0 is 1.0");
		eq(1.0, FakePlayerStorePricing.enchantMultiplier(3, -2), "negative is 1.0");
		for (int grade = 0; grade <= 5; grade++)
		{
			double last = 1.0;
			for (int e = 1; e <= 20; e++)
			{
				final double m = FakePlayerStorePricing.enchantMultiplier(grade, e);
				truth(m >= last, "monotonic grade " + grade + " +" + e);
				last = m;
			}
			eq(FakePlayerStorePricing.enchantMultiplier(grade, 16), FakePlayerStorePricing.enchantMultiplier(grade, 30), "capped at +16, grade " + grade);
		}
		truth(FakePlayerStorePricing.enchantMultiplier(5, 10) > FakePlayerStorePricing.enchantMultiplier(1, 10), "S gains more than D");
		truth(FakePlayerStorePricing.enchantMultiplier(3, 3) < 1.3, "safe enchant is a small bump");
		truth(FakePlayerStorePricing.enchantMultiplier(3, 10) > 3.0, "+10 B is worth several times base");
		truth(FakePlayerStorePricing.enchantMultiplier(99, 5) > 1.0, "out of range grade is clamped");
	}

	private static void testCounterLimit()
	{
		eq(85, FakePlayerChatParsing.counterLimit(100, true), "seller floor is 15% under ask");
		eq(115, FakePlayerChatParsing.counterLimit(100, false), "buyer ceiling is 15% over");
		eq(0, FakePlayerChatParsing.counterLimit(0, true), "no ask, no limit");
		truth(FakePlayerChatParsing.acceptsCounter(85, 100, true) && !FakePlayerChatParsing.acceptsCounter(84, 100, true), "limit matches acceptsCounter (sell)");
		truth(FakePlayerChatParsing.acceptsCounter(115, 100, false) && !FakePlayerChatParsing.acceptsCounter(116, 100, false), "limit matches acceptsCounter (buy)");
	}

	private static void eq(Object expected, Object actual, String what)
	{
		checks++;
		if ((expected == null) ? (actual != null) : !expected.equals(actual))
		{
			failures++;
			System.out.println("FAIL: " + what + " -> expected [" + expected + "] but got [" + actual + "]");
		}
	}

	private static void truth(boolean condition, String what)
	{
		checks++;
		if (!condition)
		{
			failures++;
			System.out.println("FAIL: " + what);
		}
	}
}
