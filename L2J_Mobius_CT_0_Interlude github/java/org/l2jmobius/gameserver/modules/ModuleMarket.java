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
package org.l2jmobius.gameserver.modules;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.player.PrivateStoreType;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerAppearance;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerStoreItem;
import org.l2jmobius.gameserver.network.holders.TradeItem;
import org.l2jmobius.gameserver.network.holders.TradeList;

/**
 * The market observation point, handed to a module through {@link ModuleContext#market()}. It is read-only: it shows
 * the shops that are open right now (the bot vendors and any player's private store) and tells a listener about every
 * sale made in a shop. It changes nothing in the economy.
 */
public class ModuleMarket
{
	private static final Logger LOGGER = Logger.getLogger(ModuleMarket.class.getName());
	private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

	/** Told about every completed shop sale. */
	public interface Listener
	{
		/**
		 * @param shopName the owner of the shop
		 * @param shopIsBot {@code true} if the shop is a bot vendor
		 * @param customerName who bought from, or sold to, the shop
		 * @param shopSells {@code true} if the shop sold the item to the customer, {@code false} if the shop bought it
		 * @param itemId the item
		 * @param enchant its +level
		 * @param count how many
		 * @param unitPrice the price of one
		 */
		void onSale(String shopName, boolean shopIsBot, String customerName, boolean shopSells, int itemId, int enchant, long count, long unitPrice);
	}

	/** One line a shop sells or wants. */
	public static final class Line
	{
		public final int itemId;
		public final int enchant;
		public final int count;
		public final int price;

		Line(int itemId, int enchant, int count, int price)
		{
			this.itemId = itemId;
			this.enchant = enchant;
			this.count = count;
			this.price = price;
		}
	}

	/** A shop that is open right now. */
	public static final class Shop
	{
		public final String owner;
		public final boolean bot;
		/** {@code true} if the shop sells to customers, {@code false} if it buys from them. */
		public final boolean sells;
		public final String message;
		public final int x;
		public final int y;
		public final int z;
		public final List<Line> lines;

		Shop(String owner, boolean bot, boolean sells, String message, int x, int y, int z, List<Line> lines)
		{
			this.owner = owner;
			this.bot = bot;
			this.sells = sells;
			this.message = message;
			this.x = x;
			this.y = y;
			this.z = z;
			this.lines = lines;
		}
	}

	ModuleMarket()
	{
	}

	public boolean hasListeners()
	{
		return !LISTENERS.isEmpty();
	}

	/** Called by the shop code after a sale went through. */
	public static void sold(String shopName, boolean shopIsBot, String customerName, boolean shopSells, int itemId, int enchant, long count, long unitPrice)
	{
		for (Listener listener : LISTENERS)
		{
			try
			{
				listener.onSale(shopName, shopIsBot, customerName, shopSells, itemId, enchant, count, unitPrice);
			}
			catch (Exception e)
			{
				LOGGER.warning("Market listener failed: " + e.getMessage());
			}
		}
	}

	/** Be told about every completed shop sale. */
	public void addListener(Listener listener)
	{
		LISTENERS.add(listener);
	}

	/** @return a snapshot of every shop open right now, bot vendors and player stores (craft shops are left out) */
	public List<Shop> openShops()
	{
		final List<Shop> shops = new ArrayList<>();
		for (WorldObject object : World.getInstance().getVisibleObjects())
		{
			if (object instanceof Npc npc)
			{
				final FakePlayerAppearance look = npc.getFakePlayerAppearance();
				if ((look == null) || (look.getStoreItems() == null))
				{
					continue;
				}
				final int type = look.getPrivateStoreType();
				final boolean sells = (type == PrivateStoreType.SELL.getId()) || (type == PrivateStoreType.PACKAGE_SELL.getId());
				if (!sells && (type != PrivateStoreType.BUY.getId()))
				{
					continue;
				}
				final List<Line> lines = new ArrayList<>();
				for (FakePlayerStoreItem entry : new ArrayList<>(look.getStoreItems()))
				{
					if (entry.getCount() > 0)
					{
						lines.add(new Line(entry.getItemId(), entry.getEnchant(), entry.getCount(), entry.getPrice()));
					}
				}
				if (!lines.isEmpty())
				{
					shops.add(new Shop(look.getName(), true, sells, look.getStoreMessage(), npc.getX(), npc.getY(), npc.getZ(), lines));
				}
			}
		}
		for (Player player : World.getInstance().getPlayers())
		{
			final PrivateStoreType type = player.getPrivateStoreType();
			final boolean sells = (type == PrivateStoreType.SELL) || (type == PrivateStoreType.PACKAGE_SELL);
			if (!sells && (type != PrivateStoreType.BUY))
			{
				continue;
			}
			final TradeList list = sells ? player.getSellList() : player.getBuyList();
			if (list == null)
			{
				continue;
			}
			final List<Line> lines = new ArrayList<>();
			for (TradeItem item : list.getItems())
			{
				if ((item.getItem() != null) && (item.getCount() > 0))
				{
					lines.add(new Line(item.getItem().getId(), item.getEnchant(), item.getCount(), item.getPrice()));
				}
			}
			if (!lines.isEmpty())
			{
				shops.add(new Shop(player.getName(), false, sells, list.getTitle(), player.getX(), player.getY(), player.getZ(), lines));
			}
		}
		return shops;
	}
}
