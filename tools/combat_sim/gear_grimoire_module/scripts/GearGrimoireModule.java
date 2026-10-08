package modules.geargrimoire;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import org.l2jmobius.gameserver.cache.HtmCache;
import org.l2jmobius.gameserver.handler.CommunityBoardHandler;
import org.l2jmobius.gameserver.handler.IParseBoardHandler;
import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.modules.GameModule;
import org.l2jmobius.gameserver.modules.ModuleConfig;
import org.l2jmobius.gameserver.modules.ModuleContext;
import org.l2jmobius.gameserver.modules.ModulesConfig;

import modules.geargrimoire.GrimoireIndex.GearSet;
import modules.geargrimoire.GrimoireIndex.Piece;

/**
 * Gear Grimoire: a Community Board book of armor sets, weapons and jewelry. Browse by grade, type and role, best first
 * (by our tier lists), open a set or a piece to see every way to get it, or search by name. The gear data is a
 * generated index file in the module's data folder; nothing here touches the game's items.
 */
public class GearGrimoireModule implements GameModule, IParseBoardHandler
{
	private static final String NAVIGATION_PATH = "data/html/CommunityBoard/Custom/navigation.html";
	private static final String[] COMMANDS = { GrimoirePages.CMD };

	private GrimoireIndex _index;
	private int _pageSize = 8;

	@Override
	public void onEnable(ModuleContext context)
	{
		final ModuleConfig config = context.config();
		if (!config.getBoolean("Enabled", false))
		{
			return;
		}
		_pageSize = Math.max(3, Math.min(12, config.getInt("PageSize", 8)));
		final String rel = config.getString("IndexFile", "data/gear_index.tsv");
		try
		{
			_index = load(rel);
		}
		catch (IOException e)
		{
			context.logging().warning("Gear Grimoire: could not read " + rel + " (" + e.getMessage() + "); module stays off.");
			return;
		}
		context.handlers().registerBoard(this);
		context.handlers().registerBoardTab(config.getString("TabLabel", "Gear Grimoire"), GrimoirePages.CMD);
		context.handlers().registerVoicedCommand(new IVoicedCommandHandler()
		{
			@Override
			public boolean onCommand(String command, Player player, String params)
			{
				final String p = (params == null) ? "" : params.trim();
				return GearGrimoireModule.this.onCommand(p.isEmpty() ? GrimoirePages.CMD : (GrimoirePages.CMD + " find " + p), player);
			}

			@Override
			public String[] getCommandList()
			{
				return new String[] { "gg", "grimoire" };
			}
		});
		context.logging().info("Gear Grimoire enabled (tab and .gg): " + _index.sets.size() + " sets, " + _index.pieces.size() + " pieces.");
	}

	/** The index sits in the module's own folder: modules root / gear-grimoire / (relative path). */
	private static GrimoireIndex load(String relative) throws IOException
	{
		File file = new File(relative);
		if (!file.isAbsolute())
		{
			file = new File(new File(ModulesConfig.getModulesRoot(), "gear-grimoire"), relative);
		}
		try (Reader r = new InputStreamReader(Files.newInputStream(file.toPath()), StandardCharsets.UTF_8))
		{
			return GrimoireIndex.parse(r);
		}
	}

	@Override
	public String[] getCommandList()
	{
		return COMMANDS;
	}

	@Override
	public boolean onCommand(String command, Player player)
	{
		String html;
		try
		{
			html = page(command.trim().split("\\s+"));
		}
		catch (RuntimeException e) // a hand-typed or stale bypass: show the front page, never an error
		{
			html = GrimoirePages.home();
		}
		final String navigation = HtmCache.getInstance().getHtm(player, NAVIGATION_PATH);
		CommunityBoardHandler.separateAndSend(html.replace("%navigation%", (navigation != null) ? navigation : ""), player);
		return true;
	}

	/** @param p the command split on spaces: {@code _bbs_gg <view> <args...>} */
	String page(String[] p)
	{
		final String view = (p.length > 1) ? p[1].toLowerCase() : "home";
		switch (view)
		{
			case "sets":
			{
				// sets <grade> <armortype> <role> <page>
				final List<GearSet> all = _index.findSets(p[2], p[3], p[4]);
				final int page = Integer.parseInt(p[5]);
				final int pages = GrimoireIndex.pages(all.size(), _pageSize);
				final String title = ("-".equals(p[2]) ? "All grades" : p[2] + " grade") + " " + role(p[3], p[4]);
				return GrimoirePages.setList(title, "home", GrimoireIndex.page(all, page, _pageSize), Math.max(1, Math.min(pages, page)), pages, "sets " + p[2] + " " + p[3] + " " + p[4]);
			}
			case "loose":
			{
				// loose <weapon|jewel> <grade> <type> <page>
				final List<Piece> all = _index.findLoose(p[2], p[3], p[4]);
				final int page = Integer.parseInt(p[5]);
				final int pages = GrimoireIndex.pages(all.size(), _pageSize);
				final String title = ("-".equals(p[3]) ? "All grades" : p[3] + " grade") + (p[2].equals("weapon") ? " weapons" : " jewelry");
				return GrimoirePages.looseList(title, GrimoireIndex.page(all, page, _pageSize), Math.max(1, Math.min(pages, page)), pages, "loose " + p[2] + " " + p[3] + " " + p[4]);
			}
			case "set":
			{
				final GearSet s = _index.sets.get(Integer.parseInt(p[2]));
				return (s == null) ? GrimoirePages.notice("No such set.") : GrimoirePages.setPage(s, _index.piecesOf(s.id));
			}
			case "item":
			{
				final Piece item = _index.pieces.get(Integer.parseInt(p[2]));
				return (item == null) ? GrimoirePages.notice("No such item.") : GrimoirePages.itemPage(item, _index.sets.get(item.setId));
			}
			case "find":
			{
				final StringBuilder text = new StringBuilder();
				for (int i = 2; i < p.length; i++)
				{
					text.append(i > 2 ? " " : "").append(p[i]);
				}
				final List<Object> hits = _index.search(text.toString(), 12);
				// one set and nothing else: go straight to it
				if ((hits.size() == 1) && (hits.get(0) instanceof GearSet))
				{
					final GearSet s = (GearSet) hits.get(0);
					return GrimoirePages.setPage(s, _index.piecesOf(s.id));
				}
				return GrimoirePages.found(text.toString(), hits);
			}
			default:
				return GrimoirePages.home();
		}
	}

	private static String role(String armorType, String role)
	{
		final String type = "HEAVY".equals(armorType) ? "heavy" : "LIGHT".equals(armorType) ? "light" : "MAGIC".equals(armorType) ? "robe" : "";
		final String r = "-".equals(role) ? "" : (" " + role);
		return (type + r + " sets").trim();
	}
}
