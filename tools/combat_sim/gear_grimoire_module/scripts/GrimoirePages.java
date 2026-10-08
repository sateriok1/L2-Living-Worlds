package modules.geargrimoire;

import java.util.List;

import modules.geargrimoire.GrimoireIndex.GearSet;
import modules.geargrimoire.GrimoireIndex.Piece;

/** The Community Board pages of the Gear Grimoire. Every link is a {@code _bbs_gg} command. */
final class GrimoirePages
{
	static final String CMD = "_bbs_gg";
	private static final String GOLD = "CDB67F";
	private static final String GREY = "999999";
	private static final String GREEN = "99CC66";
	private static final String BLUE = "6699FF";

	private GrimoirePages()
	{
	}

	static String esc(String text)
	{
		return (text == null) ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}

	private static String link(String cmd, String label)
	{
		return "<a action=\"bypass " + CMD + " " + cmd + "\">" + esc(label) + "</a>";
	}

	private static String searchBox()
	{
		return "<table><tr><td><edit var=\"q\" width=170 height=12></td>"
			+ "<td><button value=\"Find\" action=\"bypass " + CMD + " find $q\" width=60 height=21 back=\"L2UI_ch3.Btn1_normalOn\" fore=\"L2UI_ch3.Btn1_normal\"></td></tr></table>";
	}

	static String home()
	{
		final StringBuilder b = new StringBuilder();
		b.append("<tr><td align=center height=30>").append(searchBox()).append("</td></tr>");
		b.append("<tr><td align=center><font color=").append(GREY).append(">Type a name (\"majestic\", \"tallum\", \"blessed\") or browse.</font></td></tr>");
		b.append("<tr><td height=8></td></tr>");
		b.append(section("Armor sets, best first", "sets", new String[][] {
			{ "Heavy, tank", "HEAVY", "tank" }, { "Heavy, damage", "HEAVY", "damage" }, { "Light", "LIGHT", "-" },
			{ "Robes, casters", "MAGIC", "caster" }, { "Robes, healers", "MAGIC", "healer" } }));
		b.append("<tr><td height=6></td></tr>");
		b.append("<tr><td align=center><font color=").append(GOLD).append(">Weapons</font> ");
		b.append(grades("loose weapon %s -")).append("</td></tr>");
		b.append("<tr><td height=4></td></tr>");
		b.append("<tr><td align=center><font color=").append(GOLD).append(">Jewelry</font> ");
		b.append(grades("loose jewel %s -")).append("</td></tr>");
		return frame("Gear Grimoire", b.toString());
	}

	private static String grades(String pattern)
	{
		final StringBuilder b = new StringBuilder();
		for (String g : GrimoireIndex.GRADES)
		{
			b.append(link(String.format(pattern, g) + " 1", g)).append("&nbsp;&nbsp;");
		}
		b.append(link(String.format(pattern, "-") + " 1", "All"));
		return b.toString();
	}

	private static String section(String title, String view, String[][] rows)
	{
		final StringBuilder b = new StringBuilder();
		b.append("<tr><td align=center><font color=").append(GOLD).append(">").append(esc(title)).append("</font></td></tr>");
		for (String[] r : rows)
		{
			b.append("<tr><td align=center>").append(esc(r[0])).append(": ");
			for (String g : GrimoireIndex.GRADES)
			{
				b.append(link(view + " " + g + " " + r[1] + " " + r[2] + " 1", g)).append("&nbsp;&nbsp;");
			}
			b.append("</td></tr>");
		}
		return b.toString();
	}

	static String setList(String title, String back, List<GearSet> page, int pageNo, int pages, String pageCmd)
	{
		final StringBuilder b = new StringBuilder();
		b.append("<tr><td align=center>").append(link("home", "Back")).append("</td></tr>");
		if (page.isEmpty())
		{
			b.append("<tr><td align=center><font color=").append(GREY).append(">Nothing matches.</font></td></tr>");
		}
		for (GearSet s : page)
		{
			b.append("<tr><td>").append(link("set " + s.id, s.name)).append(" <font color=").append(GREY).append(">").append(s.grade).append(" ").append(esc(s.armorType.toLowerCase())).append("</font><br1>");
			b.append("<font color=").append(GREEN).append(">").append(esc(tagText(s))).append("</font></td></tr>");
		}
		b.append(pager(pageCmd, pageNo, pages));
		return frame(title, b.toString());
	}

	static String looseList(String title, List<Piece> page, int pageNo, int pages, String pageCmd)
	{
		final StringBuilder b = new StringBuilder();
		b.append("<tr><td align=center>").append(link("home", "Back")).append("</td></tr>");
		if (page.isEmpty())
		{
			b.append("<tr><td align=center><font color=").append(GREY).append(">Nothing matches.</font></td></tr>");
		}
		for (Piece p : page)
		{
			b.append("<tr><td>").append(link("item " + p.id, p.name)).append(" <font color=").append(GREY).append(">").append(p.grade).append(" ").append(esc(p.type.equals("-") ? p.slot : p.type.toLowerCase())).append(" | ").append(esc(p.stats)).append("</font><br1>");
			b.append("<font color=").append(GREEN).append(">").append(esc(first(p))).append("</font></td></tr>");
		}
		b.append(pager(pageCmd, pageNo, pages));
		return frame(title, b.toString());
	}

	static String setPage(GearSet s, List<Piece> pieces)
	{
		final StringBuilder b = new StringBuilder();
		b.append("<tr><td align=center>").append(link("home", "Home")).append("</td></tr>");
		b.append("<tr><td align=center><font color=").append(GREY).append(">").append(s.grade).append(" grade ").append(esc(s.armorType.toLowerCase())).append(" set</font></td></tr>");
		b.append("<tr><td align=center>Set bonus: ").append(esc(s.bonuses.equals("-") ? "none beyond the set skill" : s.bonuses)).append("</td></tr>");
		b.append("<tr><td align=center><font color=").append(GREEN).append(">").append(esc(tagText(s))).append("</font></td></tr>");
		b.append("<tr><td height=6></td></tr>");
		for (Piece p : pieces)
		{
			b.append("<tr><td>").append(link("item " + p.id, p.name)).append(" <font color=").append(GREY).append(">").append(esc(p.stats)).append("</font><br1>");
			b.append(esc(first(p))).append("</td></tr>");
		}
		return frame(s.name, b.toString());
	}

	static String itemPage(Piece p, GearSet set)
	{
		final StringBuilder b = new StringBuilder();
		b.append("<tr><td align=center>").append(link("home", "Home"));
		if (set != null)
		{
			b.append(" | ").append(link("set " + set.id, "Set: " + set.name));
		}
		b.append("</td></tr>");
		b.append("<tr><td align=center><font color=").append(GREY).append(">").append(p.grade).append(" grade ").append(esc(p.type.equals("-") ? p.slot : p.type.toLowerCase() + " " + p.slot)).append(" | ").append(esc(p.stats)).append("</font></td></tr>");
		b.append("<tr><td height=6></td></tr><tr><td><font color=").append(GOLD).append(">Where to get it</font></td></tr>");
		for (String s : p.sources)
		{
			b.append("<tr><td>").append(esc(s)).append("</td></tr><tr><td height=4></td></tr>");
		}
		b.append("<tr><td align=center><a action=\"bypass _bbs_search_item ").append(esc(p.name)).append("\">Drop search for this item</a></td></tr>");
		return frame(p.name, b.toString());
	}

	static String found(String text, List<Object> hits)
	{
		final StringBuilder b = new StringBuilder();
		b.append("<tr><td align=center height=30>").append(searchBox()).append("</td></tr>");
		if (hits.isEmpty())
		{
			b.append("<tr><td align=center><font color=").append(GREY).append(">No gear called \"").append(esc(text)).append("\".</font></td></tr>");
		}
		for (Object o : hits)
		{
			if (o instanceof GearSet)
			{
				final GearSet s = (GearSet) o;
				b.append("<tr><td><font color=").append(BLUE).append(">Set</font> ").append(link("set " + s.id, s.name)).append(" <font color=").append(GREY).append(">").append(s.grade).append(" ").append(esc(s.armorType.toLowerCase())).append("</font></td></tr>");
			}
			else
			{
				final Piece p = (Piece) o;
				b.append("<tr><td>").append(link("item " + p.id, p.name)).append(" <font color=").append(GREY).append(">").append(p.grade).append(" ").append(esc(p.kind)).append("</font></td></tr>");
			}
		}
		return frame("Find: " + text, b.toString());
	}

	static String notice(String text)
	{
		return frame("Gear Grimoire", "<tr><td align=center height=60><font color=" + GREY + ">" + esc(text) + "</font></td></tr><tr><td align=center>" + link("home", "Home") + "</td></tr>");
	}

	/** @return a tier-list text for a set: its best placements, with the long parts of the list names trimmed */
	static String tagText(GearSet s)
	{
		if (s.tags.isEmpty())
		{
			return "not on a tier list";
		}
		final StringBuilder b = new StringBuilder();
		for (int i = 0; (i < s.tags.size()) && (i < 2); i++)
		{
			if (i > 0)
			{
				b.append("; ");
			}
			b.append(s.tags.get(i).replaceAll("\\s*\\([^)]*\\)", ""));
		}
		return b.toString();
	}

	/** @return the first (best) way to get a piece, shortened to fit a list row */
	static String first(Piece p)
	{
		final String s = p.sources.isEmpty() ? "no known source" : p.sources.get(0);
		return (s.length() > 120) ? (s.substring(0, 117) + "...") : s;
	}

	private static String pager(String cmd, int page, int pages)
	{
		final StringBuilder b = new StringBuilder("<tr><td align=center height=24>");
		if (page > 1)
		{
			b.append(link(cmd + " " + (page - 1), "< Prev")).append("&nbsp;&nbsp;");
		}
		b.append("<font color=").append(GREY).append(">").append(page).append(" / ").append(pages).append("</font>");
		if (page < pages)
		{
			b.append("&nbsp;&nbsp;").append(link(cmd + " " + (page + 1), "Next >"));
		}
		return b.append("</td></tr>").toString();
	}

	private static String frame(String title, String body)
	{
		return "<html><body><table width=500><tr><td height=10></td></tr></table>"
			+ "<table width=10><tr><td>%navigation%</td><td><center>"
			+ "<table border=0 bgcolor=\"000000\" cellpadding=0 cellspacing=0 width=500 height=415>"
			+ "<tr><td height=15></td></tr><tr><td height=25 align=\"center\"><font color=\"" + GOLD + "\">" + esc(title) + "</font></td></tr>"
			+ "<tr><td><center><img src=\"L2UI.SquareGray\" width=500 height=1></center></td></tr>"
			+ body
			+ "<tr><td height=14></td></tr></table>"
			+ "<table border=0 bgcolor=\"000000\" cellpadding=0 cellspacing=0 width=500><tr><td height=20 align=center><font color=696969>LINEAGE II - COMMUNITY BOARD</font></td></tr></table>"
			+ "</center></td></tr></table></body></html>";
	}
}
