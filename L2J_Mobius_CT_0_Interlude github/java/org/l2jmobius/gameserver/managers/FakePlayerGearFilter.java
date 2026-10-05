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

import java.util.HashSet;
import java.util.Set;

import org.l2jmobius.gameserver.data.xml.BuyListData;
import org.l2jmobius.gameserver.model.buylist.BuyListHolder;
import org.l2jmobius.gameserver.model.buylist.Product;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.item.enums.BodyPart;

/**
 * Allow-list of item ids that fake players and phantoms may wear, sourced from the <b>GM shop's gear buy-lists</b>
 * (the ones the {@code data/html/admin/gmstore/gear/**} panels open via {@code admin_buy}). That union is a
 * hand-curated, render-safe set of player equipment:
 * <ul>
 * <li>pet/summon gear lives in the GM shop's separate Pet Section, so it is <b>not</b> in these lists - no
 * pet/monster deny-list needed;</li>
 * <li>armor pieces with no model for some race (e.g. "Tunic of Mana", which is invisible on an Orc mystic) were
 * deliberately left out of the GM gear lists, so drawing only from here <b>needs no race-specific skip</b>.</li>
 * </ul>
 * NPC-only weapons are dropped by the {@code isForNpc()} data flag when the allow-list is built (the "Monster
 * Weapons" section: Tomb Guard, For NPC (Dusk), "Monster Only ..."), which is robust where the old "Monster Only"
 * name match missed variants. Items with an UNMEETABLE equip gate (Clan Oath's academy pledgeClass, hero/noble
 * gear) are NOT filtered here - most normal armor carries a benign all-races {@code <conditions>} block, so a
 * blanket condition filter would strip real gear - they are caught per-phantom at equip time in
 * {@code PhantomManager.equipArmorSet} via {@link ItemTemplate#checkCondition}.
 * <p>
 * The allow-list is resolved lazily from {@link BuyListData} (already loaded at start-up) the first time gear is
 * built, so it stays in sync automatically if a buy-list is edited. If a listed buy-list id is missing it is
 * skipped. The set is only cached once it is non-empty, so a premature call (before BuyListData finished loading)
 * fails soft and rebuilds on the next call rather than caching an empty allow-list.
 */
public class FakePlayerGearFilter
{
	// GM shop weapon/armor/jewel buy-list ids, from data/html/admin/gmstore/gear/** (admin_buy <id>).
	private static final int[] GEAR_BUYLISTS =
	{
		9001, 9002, 9003, 9004, 9005, 9006, 9007, 9008, 9009, 9010, //
		9011, 9012, 9013, 9014, 9015, 9016, 9017, 9018, 9019, 9020, //
		9021, 9022, 9023, 9024, 9025, 9026, 9027, 9028, 9029, 9030, //
		9031, 9032, 9033, 9034, 9035, 9036, 9037, 9038, 9039, 9040, //
		9041, 9042, 9043, 9044, 9045, 9046, 9047, 9048, 9049, 9050, //
		9051, 9052, 9053, 9917, 9925, 9951, 9952, 9953, 9954, 9955, //
		9956, 9959, 9960, 9961, 9962, 9963, 9964, 9965, 9980, 9997, 9998
	};

	// Gap-fillers: the GM gear lists have NO no-grade heavy armor and NO no-grade shield, so a low-level heavy
	// role had nothing heavy to wear (it fell back to leather). These are the standard no-grade heavy pieces
	// (Bronze set + Bone Shield) - render-safe player gear - added so a no-grade tank/warrior looks heavy.
	private static final int[] EXTRA_ALLOWED =
	{
		26, // Bronze Breastplate (heavy chest)
		34, // Bronze Gaiters (heavy legs)
		46, // Bronze Helmet
		625, // Bone Shield
		// Real Interlude weapons the GM gear lists leave out (FPC-202). Without them the D, C and B "Double Handed
		// Blunts" pages hold only staves, so a Destroyer or Titan rolling a hammer found nothing in its grade, and a
		// B-grade Warcryer had no one-handed magic blunt. Each base weapon is followed by its SA versions.
		7880, // Steel Sword (D, two-handed)
		7881, // Titan Sword (D, two-handed)
		7882, 8102, 8103, 8104, // Pa'agrian Sword (C, two-handed)
		7883, 8105, 8106, 8107, // Guardian Sword (B, two-handed)
		7884, 8108, 8109, 8110, // Infernal Master (A, two-handed)
		7885, // Priest Sword (D, magic)
		7886, // Sword of Magic Fog (D, magic)
		7887, 8111, 8112, 8113, // Mysterious Sword (C, magic)
		7888, 8114, 8115, 8116, // Ecliptic Sword (C, magic)
		7889, 8117, 8118, 8119, // Wizard's Tear (B, magic)
		7890, // Priest Mace (D, magic blunt)
		7891, 8138, 8139, 8140, // Ecliptic Axe (C, magic blunt)
		7892, 8141, 8142, 8143, // Spell Breaker (B, magic blunt)
		7893, 8144, 8145, 8146, // Kaim Vanul's Bones (B, magic blunt)
		7896, // Titan Hammer (D, two-handed blunt)
		7897, 8120, 8121, 8122, // Dwarven Hammer (C, two-handed blunt)
		7898, 8123, 8124, 8125, // Karik Horn (C, two-handed blunt)
		7900, 8129, 8130, 8131, // Ice Storm Hammer (B, two-handed blunt)
		7901, 8132, 8133, 8134, // Star Buster (B, two-handed blunt)
		89, 4726, 4727, 4728, // Big Hammer (C)
		4729, 4730, 4731, // Battle Axe SAs (C)
		4732, 4733 // Silver Axe SAs (C)
	};

	private static volatile Set<Integer> _allowed = null;
	private static volatile int _version = 0;

	private FakePlayerGearFilter()
	{
	}

	/**
	 * Invalidates the allow-list after {@link BuyListData} finishes loading. Dependent gear caches compare
	 * {@link #getVersion()} before use and rebuild lazily when this version changes.
	 */
	public static synchronized void onBuyListsReloaded()
	{
		_allowed = null;
		_version++;
	}

	/** @return the current allow-list generation used by dependent caches. */
	public static int getVersion()
	{
		return _version;
	}

	private static Set<Integer> allowed()
	{
		Set<Integer> set = _allowed;
		if (set != null)
		{
			return set;
		}
		synchronized (FakePlayerGearFilter.class)
		{
			set = _allowed;
			if (set != null)
			{
				return set;
			}
			final Set<Integer> built = new HashSet<>(2048);
			for (int listId : GEAR_BUYLISTS)
			{
				final BuyListHolder buyList = BuyListData.getInstance().getBuyList(listId);
				if (buyList == null)
				{
					continue;
				}
				for (Product product : buyList.getProducts())
				{
					final ItemTemplate item = product.getItem();
					// Drop NPC-only "Monster Weapons" (Tomb Guard, For NPC (Dusk), "Monster Only ...") by the for_npc
					// data flag - robust where the old "Monster Only" NAME match missed the ones not named that way.
					// Do NOT filter by isConditionAttached() here: most normal armor carries a benign <conditions>
					// block (an all-races <player races=.../> list), so excluding any conditioned item stripped whole
					// grades of real armor and shields. Items with an UNMEETABLE equip gate (Clan Oath's academy
					// pledgeClass, hero/noble/olympiad) are caught per-phantom at equip time in
					// PhantomManager.equipArmorSet via ItemTemplate.checkCondition instead.
					if ((item != null) && !item.isForNpc() && hasRealName(item) && !isBossJewel(item))
					{
						built.add(item.getId());
					}
				}
			}
			if (built.isEmpty()) // BuyListData not ready yet - don't cache an empty allow-list, retry next call
			{
				return built;
			}
			for (int id : EXTRA_ALLOWED) // fill the no-grade heavy / shield gap the GM lists leave
			{
				built.add(id);
			}
			_allowed = built;
			return built;
		}
	}

	/**
	 * Unused template slots in the item data carry a placeholder name with no letter in it ("0" for item 749, "_" for
	 * 163 and 170). They still sit in the GM gear lists, but the client shows them as a nameless item, so a phantom
	 * must never wear one (FPC-202).
	 */
	private static boolean hasRealName(ItemTemplate item)
	{
		final String name = item.getName();
		if (name == null)
		{
			return false;
		}
		for (int i = 0; i < name.length(); i++)
		{
			if (Character.isLetter(name.charAt(i)))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Raid and epic jewelry (Necklace of Frintezza, Earring of Antharas, Ring of Queen Ant...) is the only jewelry that
	 * carries an item skill. The GM lists sell it, but the best-in-grade jewel pick would hand it to every ordinary
	 * phantom of the grade (the A-grade necklace was always Frintezza's), so it is not phantom gear.
	 */
	private static boolean isBossJewel(ItemTemplate item)
	{
		final BodyPart part = item.getBodyPart();
		return ((part == BodyPart.NECK) || (part == BodyPart.LR_EAR) || (part == BodyPart.LR_FINGER)) && item.hasSkills();
	}

	/** @return {@code true} if {@code itemId} is player gear the GM shop sells (safe to render on any race/class). */
	public static boolean isPlayerGear(int itemId)
	{
		return allowed().contains(itemId);
	}

	/** @return {@code true} if {@code item} is player gear the GM shop sells (safe to render on any race/class). */
	public static boolean isPlayerGear(ItemTemplate item)
	{
		return (item != null) && allowed().contains(item.getId());
	}
}
