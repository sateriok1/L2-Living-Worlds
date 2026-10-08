package modules.geargrimoire;

import java.io.FileReader;
import java.io.StringReader;
import java.util.List;

import modules.geargrimoire.GrimoireIndex.GearSet;
import modules.geargrimoire.GrimoireIndex.Piece;

/** Pure checks on the index and the pages. Run with the generated index: java -cp out modules.geargrimoire.GrimoireTest data/gear_index.tsv */
public class GrimoireTest
{
	private static int checks;
	private static int failed;

	static void check(String what, boolean ok)
	{
		checks++;
		if (!ok)
		{
			failed++;
			System.out.println("FAIL: " + what);
		}
	}

	public static void main(String[] a) throws Exception
	{
		// a tiny hand-made index
		final String tsv = "#SET\n"
			+ "SET\t1\tAlpha Plate\tA\tHEAVY\t10,0,0,0,0,0\tSTR +1\tHeavy armor, damage #2; Heavy armor, tank (x) #1\n"
			+ "SET\t2\tBeta Plate\tA\tHEAVY\t11,0,0,0,0,0\t-\tHeavy armor, damage #1\n"
			+ "SET\t3\tGamma Robe\tB\tMAGIC\t12,0,0,0,0,0\t-\tHealers #1\n"
			+ "SET\t4\tDelta Plate\tA\tHEAVY\t0,0,0,0,0,0\t-\t-\n"
			+ "PIECE\t10\tAlpha Plate Armor\tA\tarmor\tonepiece\tHEAVY\t1\tP.Def 300 M.Def 0\tShop: 5 adena || Drop: L70 Boss\n"
			+ "PIECE\t11\tBeta Plate Armor\tA\tarmor\tonepiece\tHEAVY\t2\tP.Def 290 M.Def 0\tExchange at X: Seal x1 [drop]\n"
			+ "PIECE\t12\tGamma Robe\tB\tarmor\tonepiece\tMAGIC\t3\tP.Def 100 M.Def 0\tCraft (lvl 3, 60%): Thing x2\n"
			+ "PIECE\t20\tSword A\tA\tweapon\trhand\tSWORD\t0\tP.Atk 200 M.Atk 100\tDrop: L60 Mob\n"
			+ "PIECE\t21\tSword B\tA\tweapon\trhand\tSWORD\t0\tP.Atk 250 M.Atk 100\tQuest: Q\n"
			+ "PIECE\t30\tRing X\tA\tjewel\tRing\t-\t0\tM.Def 50 MP 0\tShop: 1 adena\n"
			+ "PIECE\t31\tRing Y\tA\tjewel\tRing\t-\t0\tM.Def 80 MP 0\tShop: 1 adena\n"
			+ "garbage line\nSET\tx\tbroken\n";
		final GrimoireIndex i = GrimoireIndex.parse(new StringReader(tsv));
		check("sets parsed", i.sets.size() == 4);
		check("pieces parsed", i.pieces.size() == 7);
		check("bad lines skipped", true);
		check("set pieces", i.piecesOf(1).size() == 1 && i.piecesOf(1).get(0).id == 10);
		check("sources split", i.pieces.get(10).sources.size() == 2);

		final List<GearSet> dmg = i.findSets("A", "HEAVY", "damage");
		check("damage sets ranked", dmg.size() == 2 && dmg.get(0).id == 2 && dmg.get(1).id == 1);
		final List<GearSet> tank = i.findSets("A", "HEAVY", "tank");
		check("tank sets only ranked ones", tank.size() == 1 && tank.get(0).id == 1);
		check("healer role", i.findSets("-", "-", "healer").size() == 1);
		check("no role keeps unranked", i.findSets("A", "HEAVY", "-").size() == 3);
		check("grade filter", i.findSets("B", "-", "-").size() == 1);
		check("high grade first", i.findSets("-", "-", "-").get(0).grade.equals("A"));

		final List<Piece> w = i.findLoose("weapon", "A", "SWORD");
		check("weapons strongest first", w.size() == 2 && w.get(0).id == 21);
		final List<Piece> j = i.findLoose("jewel", "-", "Ring");
		check("jewelry by M.Def", j.size() == 2 && j.get(0).id == 31);
		check("slot filter for jewel", i.findLoose("jewel", "-", "Necklace").isEmpty());

		check("search finds set and pieces", i.search("alpha", 10).size() == 2);
		check("search all words", i.search("plate armor", 10).size() == 2);
		check("search sets first", i.search("alpha", 10).get(0) instanceof GearSet);
		check("search empty", i.search("  ", 10).isEmpty());
		check("search case", i.search("GAMMA", 10).size() == 2);
		check("search limit", i.search("sword", 1).size() == 1);

		check("paging clamps", GrimoireIndex.page(dmg, 9, 1).size() == 1);
		check("pages count", GrimoireIndex.pages(0, 8) == 1 && GrimoireIndex.pages(9, 8) == 2);

		// pages
		final String home = GrimoirePages.home();
		check("home has search", home.contains("<edit var=\"q\"") && home.contains("find $q"));
		check("home links all grades", home.contains("sets A HEAVY tank 1") && home.contains("loose weapon S - 1") && home.contains("loose jewel NG - 1"));
		final String sp = GrimoirePages.setPage(i.sets.get(1), i.piecesOf(1));
		check("set page shows bonus and piece", sp.contains("STR +1") && sp.contains("Alpha Plate Armor") && sp.contains("item 10"));
		final String ip = GrimoirePages.itemPage(i.pieces.get(10), i.sets.get(1));
		check("item page lists sources", ip.contains("Shop: 5 adena") && ip.contains("Drop: L70 Boss") && ip.contains("set 1"));
		check("item page links drop search", ip.contains("_bbs_search_item Alpha Plate Armor"));
		check("tags trimmed", GrimoirePages.tagText(i.sets.get(1)).equals("Heavy armor, damage #2; Heavy armor, tank #1"));
		check("escape", GrimoirePages.esc("<a&\"b>").equals("&lt;a&amp;&quot;b&gt;"));
		check("no hits page", GrimoirePages.found("zzz", java.util.Collections.emptyList()).contains("No gear called"));
		check("first source clipped", GrimoirePages.first(new Piece()).equals("no known source"));

		// the real index, if given
		if (a.length > 0)
		{
			final GrimoireIndex r = GrimoireIndex.parse(new FileReader(a[0]));
			check("real: sets", r.sets.size() > 30);
			check("real: pieces", r.pieces.size() > 1000);
			final List<Object> maj = r.search("majestic plate", 10);
			check("real: majestic plate set found", !maj.isEmpty() && maj.get(0) instanceof GearSet);
			final GearSet ms = (GearSet) maj.get(0);
			check("real: majestic is A heavy", ms.grade.equals("A") && ms.armorType.equals("HEAVY"));
			check("real: majestic pieces", r.piecesOf(ms.id).size() >= 3);
			check("real: majestic chest exchange resolves the sealed piece", r.pieces.get(2383).sources.toString().contains("Sealed Majestic Plate Armor"));
			check("real: top A heavy tank is ranked", !r.findSets("A", "HEAVY", "tank").isEmpty() && GrimoireIndex.bestRank(r.findSets("A", "HEAVY", "tank").get(0), "tank") < 999);
			check("real: A weapons exist", r.findLoose("weapon", "A", "-").size() > 20);
			check("real: S rings by M.Def", r.findLoose("jewel", "S", "Ring").size() > 0);
			check("real: no line over 4000 chars in item page", GrimoirePages.itemPage(r.pieces.get(2383), r.sets.get(ms.id)).length() < 4000);
		}
		System.out.println(checks + " checks, " + failed + " failed");
		System.exit(failed == 0 ? 0 : 1);
	}
}
