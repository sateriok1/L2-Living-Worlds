package org.l2jmobius.gameserver.managers;

import java.io.File;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;

import org.l2jmobius.gameserver.config.ServerConfig;
import org.l2jmobius.gameserver.data.xml.ArmorSetData;
import org.l2jmobius.gameserver.data.xml.BuyListData;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.managers.PhantomManager.PartyRole;
import org.l2jmobius.gameserver.managers.PhantomWeaponSets.GearContext;
import org.l2jmobius.gameserver.managers.PhantomWeaponSets.WeaponKind;
import org.l2jmobius.gameserver.model.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.model.item.Armor;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.item.Weapon;
import org.l2jmobius.gameserver.model.item.enums.BodyPart;
import org.l2jmobius.gameserver.model.item.type.ArmorType;
import org.l2jmobius.gameserver.model.item.type.CrystalType;
import org.l2jmobius.gameserver.model.item.type.WeaponType;

/**
 * Gear audit for every phantom class (FPC-202): rolls the real PhantomManager weapon, spare, armor and jewelry picks
 * on the shipped item data, GM gear lists and armor sets. It fails when a class gets a weapon outside its grade, a
 * placeholder item, an NPC weapon, a weapon its role or specialist class does not use (the class families are written
 * out here, independent of PhantomWeaponSets), the wrong party spare, a caster special ability that does not suit the
 * role, the wrong armor family or shield, or raid and epic jewelry. Needs no world or database.
 * <p>
 * Run from {@code dist/game} against the built core and dependency JARs:
 * {@code java -cp "<core classes>:<libs>:<test classes>" org.l2jmobius.gameserver.managers.PhantomGearAuditTest [report.md]}.
 * The optional argument writes the full per-class table as Markdown.
 */
public class PhantomGearAuditTest
{
	private static final int ROLLS = 400;
	private static final CrystalType[] GRADES =
	{
		CrystalType.NONE,
		CrystalType.D,
		CrystalType.C,
		CrystalType.B,
		CrystalType.A,
		CrystalType.S
	};

	private static final Set<String> PROBLEMS = new LinkedHashSet<>();
	private static final Set<String> NOTES = new LinkedHashSet<>();

	private static Method method(String name, Class<?>... types) throws Exception
	{
		final Method result = PhantomManager.class.getDeclaredMethod(name, types);
		result.setAccessible(true);
		return result;
	}

	private static String describe(ItemTemplate item)
	{
		if (item == null)
		{
			return "(none)";
		}
		final StringBuilder sb = new StringBuilder(item.getName()).append(" [").append(item.getId()).append(' ').append(item.getCrystalType()).append(' ');
		if (item instanceof Weapon weapon)
		{
			sb.append(weapon.getItemType()).append(item.getBodyPart() == BodyPart.LR_HAND ? " 2H" : "").append(item.isMagicWeapon() ? " magic" : "");
		}
		else if (item instanceof Armor armor)
		{
			sb.append(armor.getItemType()).append(' ').append(item.getBodyPart());
		}
		return sb.append(']').toString();
	}

	private static boolean placeholder(ItemTemplate item)
	{
		return item.getName().chars().noneMatch(Character::isLetter);
	}

	/** A weapon family with its hand: {@code null} hand means either. Written out here, not read from PhantomWeaponSets. */
	private record Family(WeaponType type, BodyPart hand)
	{
		boolean matches(ItemTemplate item)
		{
			return (item instanceof Weapon weapon) && !item.isMagicWeapon() && (weapon.getItemType() == type) && ((hand == null) || (item.getBodyPart() == hand));
		}

		@Override
		public String toString()
		{
			return ((hand == BodyPart.LR_HAND) ? "two-handed " : (hand == BodyPart.R_HAND) ? "one-handed " : "") + type;
		}
	}

	private static final Family DUAL = new Family(WeaponType.DUAL, null);
	private static final Family POLE = new Family(WeaponType.POLE, null);
	private static final Family BLUNT_1H = new Family(WeaponType.BLUNT, BodyPart.R_HAND);
	private static final Family BLUNT_2H = new Family(WeaponType.BLUNT, BodyPart.LR_HAND);
	private static final Family SWORD_2H = new Family(WeaponType.SWORD, BodyPart.LR_HAND);

	private static boolean nameHas(PlayerClass playerClass, String... keys)
	{
		final String name = playerClass.name().toLowerCase();
		for (String key : keys)
		{
			if (name.contains(key))
			{
				return true;
			}
		}
		return false;
	}

	/** @return the main weapon families a specialist melee class may roll in {@code context}, or {@code null} when it has no specialist rule */
	private static List<Family> expectedMain(PlayerClass playerClass, GearContext context)
	{
		if (nameHas(playerClass, "gladiator", "duelist"))
		{
			return List.of(DUAL);
		}
		if (nameHas(playerClass, "warlord", "dreadnought"))
		{
			return List.of(POLE);
		}
		if (nameHas(playerClass, "raider", "destroyer", "titan"))
		{
			return (context == GearContext.OLYMPIAD) ? List.of(BLUNT_2H) : List.of(SWORD_2H, BLUNT_2H);
		}
		if (nameHas(playerClass, "artisan", "warsmith", "maestro", "scavenger", "bounty", "seeker"))
		{
			return List.of(BLUNT_1H);
		}
		return null;
	}

	/** @return the spare family a party member of this class carries, or {@code null} for none */
	private static Family expectedSpare(PlayerClass playerClass)
	{
		if (nameHas(playerClass, "gladiator", "duelist", "warlord", "dreadnought"))
		{
			return BLUNT_1H;
		}
		if (nameHas(playerClass, "raider", "destroyer", "titan", "artisan", "warsmith", "maestro", "scavenger", "bounty", "seeker"))
		{
			return POLE;
		}
		return null;
	}

	/** Practical armor family per role: heavy for front line and bards, light for archers, daggers and monks, robe for casters. */
	private static ArmorType expectedArmor(PartyRole role)
	{
		switch (role)
		{
			case ARCHER:
			case DAGGER:
			case MONK:
				return ArmorType.LIGHT;
			case NUKER:
			case HEALER:
			case BUFFER:
				return ArmorType.MAGIC;
			default:
				return ArmorType.HEAVY;
		}
	}

	/** @return why {@code item} is wrong for this class, or {@code null} when it fits */
	private static String misfit(PlayerClass playerClass, PartyRole role, boolean mage, GearContext context, ItemTemplate item)
	{
		if (!(item instanceof Weapon weapon))
		{
			return "not a weapon";
		}
		if (placeholder(item))
		{
			return "placeholder item";
		}
		if (item.isForNpc())
		{
			return "NPC weapon";
		}
		final WeaponType type = weapon.getItemType();
		if (mage)
		{
			if (!item.isMagicWeapon())
			{
				return "caster with a physical weapon";
			}
			if (PhantomManager.isWarcryerLine(playerClass) && ((type != WeaponType.BLUNT) || (item.getBodyPart() != BodyPart.R_HAND)))
			{
				return "Warcryer line without a one-handed magic blunt";
			}
			if (PhantomManager.casterSaScore(role, item) < 1)
			{
				return "caster with a special ability that does not suit a " + role;
			}
			return null;
		}
		if (item.isMagicWeapon())
		{
			return "fighter with a magic weapon";
		}
		final List<Family> main = expectedMain(playerClass, context);
		if ((main != null) && main.stream().noneMatch(family -> family.matches(item)))
		{
			return "is not the class weapon " + main;
		}
		switch (role)
		{
			case ARCHER:
				return (type == WeaponType.BOW) ? null : "archer without a bow";
			case DAGGER:
				return (type == WeaponType.DAGGER) ? null : "dagger class without a dagger";
			case DANCER:
				return (type == WeaponType.DUAL) ? null : "dancer without dual swords";
			case MONK:
				return ((type == WeaponType.FIST) || (type == WeaponType.DUALFIST)) ? null : "monk without fists";
			case TANK:
			case SINGER:
				return ((type == WeaponType.SWORD) && (item.getBodyPart() == BodyPart.R_HAND)) ? null : "shield class without a one-handed sword";
			case BOUNTY_HUNTER:
				return ((type == WeaponType.BLUNT) && (item.getBodyPart() == BodyPart.R_HAND)) ? null : "bounty hunter without a one-handed blunt";
			default:
				return null;
		}
	}

	public static void main(String[] args) throws Exception
	{
		ServerConfig.DATAPACK_ROOT = new File(".");
		ItemData.getInstance();
		BuyListData.getInstance();
		ArmorSetData.getInstance();
		final Method partyWeapon = method("partyWeapon", PlayerClass.class, PartyRole.class, boolean.class, CrystalType.class, GearContext.class);
		final Method partyWeaponInGrade = method("partyWeaponInGrade", PlayerClass.class, PartyRole.class, boolean.class, CrystalType.class, GearContext.class);
		final Method randomInGrade = method("randomInGrade", CrystalType.class, Predicate.class);
		final Method gradeForLevel = method("gradeForLevel", int.class);
		final Method bestArmor = method("bestArmor", BodyPart.class, CrystalType.class, ArmorType.class, Set.class);
		final Method bestEquip = method("bestEquip", CrystalType.class, Predicate.class);
		final Method shouldEquipShield = method("shouldEquipShield", PartyRole.class, boolean.class, ItemTemplate.class);
		// Support buddies are exactly Elven Elder, Prophet and Warcryer (FPC-206). Any other class a friend is saved as
		// (a Bounty Hunter included) respawns on the normal friend path, with the caster flag of its own class, which
		// must agree with the role its gear is picked for.
		for (PlayerClass playerClass : PlayerClass.values())
		{
			final PhantomManager.BuddyRole buddy = PhantomManager.buddyRoleForClass(playerClass.getId());
			final int id = playerClass.getId();
			final PhantomManager.BuddyRole wanted = (id == 30) ? PhantomManager.BuddyRole.ELDER : (id == 17) ? PhantomManager.BuddyRole.PROPHET : (id == 52) ? PhantomManager.BuddyRole.WARCRYER : PhantomManager.BuddyRole.NONE;
			if (buddy != wanted)
			{
				PROBLEMS.add(playerClass + " (" + id + ") maps to buddy role " + buddy + ", expected " + wanted);
			}
			if (!playerClass.name().startsWith("DUMMY") && (playerClass.getRace() != null) && !buddy.isBuddy() && (playerClass.isMage() != PhantomManager.roleForClass(playerClass).mage))
			{
				PROBLEMS.add(playerClass + " friend respawns with caster=" + playerClass.isMage() + " but its role " + PhantomManager.roleForClass(playerClass) + " gears caster=" + PhantomManager.roleForClass(playerClass).mage);
			}
		}

		final List<String> report = new ArrayList<>();
		report.add("# Phantom gear audit");
		report.add("");
		report.add("Every class, every grade its level range reaches, and every gear context, " + ROLLS + " rolls each through the real PhantomManager gear picks.");
		report.add("");
		report.add("## Weapons per class");
		report.add("");
		report.add("| Class | Role | Grade | Context | Main weapons rolled | Spare |");
		report.add("|---|---|---|---|---|---|");
		final int[] lowest = {1, 20, 40, 76};
		final int[] highest = {19, 39, 75, 80};
		for (PlayerClass playerClass : PlayerClass.values())
		{
			if (playerClass.name().startsWith("DUMMY") || (playerClass.getRace() == null))
			{
				continue;
			}
			final PartyRole role = PhantomManager.roleForClass(playerClass);
			final boolean mage = role.mage;
			final int tier = Math.min(playerClass.level(), 3);
			final TreeSet<CrystalType> grades = new TreeSet<>();
			for (int level = lowest[tier]; level <= highest[tier]; level++)
			{
				grades.add((CrystalType) gradeForLevel.invoke(null, level));
			}
			for (CrystalType grade : grades)
			{
				for (GearContext context : GearContext.values())
				{
					if ((context == GearContext.OLYMPIAD) && (tier < 2))
					{
						continue; // nobles are second class or higher
					}
					final String where = playerClass + " (" + role + ") " + grade + " " + context;
					if (partyWeaponInGrade.invoke(null, playerClass, role, mage, grade, context) == null)
					{
						PROBLEMS.add(where + ": no weapon in its own grade");
					}
					final TreeMap<String, Integer> mains = new TreeMap<>();
					final TreeMap<String, Integer> spares = new TreeMap<>();
					final WeaponKind spareKind = mage ? null : PhantomWeaponSets.spareKind(playerClass, context);
					if ((context == GearContext.PARTY) && !mage && ((spareKind == null) != (expectedSpare(playerClass) == null)) && (tier > 0))
					{
						PROBLEMS.add(where + ": party spare is " + spareKind + ", expected " + expectedSpare(playerClass));
					}
					if (role.armor != expectedArmor(role))
					{
						PROBLEMS.add(where + ": armor family " + role.armor + ", expected " + expectedArmor(role));
					}
					for (int roll = 0; roll < ROLLS; roll++)
					{
						final ItemTemplate weapon = (ItemTemplate) partyWeapon.invoke(null, playerClass, role, mage, grade, context);
						mains.merge(describe(weapon), 1, Integer::sum);
						if (weapon == null)
						{
							PROBLEMS.add(where + ": unarmed");
							break;
						}
						final boolean shield = (boolean) shouldEquipShield.invoke(null, role, mage, weapon);
						final boolean twoHanded = weapon.getBodyPart() != BodyPart.R_HAND;
						final boolean wantShield = !twoHanded && (mage || (role == PartyRole.TANK) || (role == PartyRole.SINGER) || (role == PartyRole.WARRIOR));
						if (shield != wantShield)
						{
							PROBLEMS.add(where + ": shield " + shield + " with " + describe(weapon));
						}
						if (weapon.getCrystalType() != grade)
						{
							PROBLEMS.add(where + ": " + describe(weapon) + " is not " + grade + " grade");
						}
						final String misfit = misfit(playerClass, role, mage, context, weapon);
						if (misfit != null)
						{
							PROBLEMS.add(where + ": " + describe(weapon) + " " + misfit);
						}
						if (spareKind != null)
						{
							final Predicate<ItemTemplate> filter = spareKind::matches;
							final ItemTemplate spare = (ItemTemplate) randomInGrade.invoke(null, grade, filter);
							spares.merge((spare == null) ? "(none in grade)" : describe(spare), 1, Integer::sum);
							if ((spare != null) && (placeholder(spare) || (spare.getCrystalType() != grade) || spare.isMagicWeapon()))
							{
								PROBLEMS.add(where + ": spare " + describe(spare));
							}
							final Family wantedSpare = expectedSpare(playerClass);
							if ((spare != null) && ((wantedSpare == null) || !wantedSpare.matches(spare)))
							{
								PROBLEMS.add(where + ": spare " + describe(spare) + " is not " + wantedSpare);
							}
						}
					}
					report.add("| " + playerClass + " | " + role + " | " + grade + " | " + context + " | " + String.join("<br>", mains.keySet()) + " | " + (spares.isEmpty() ? "-" : String.join("<br>", spares.keySet())) + " |");
				}
			}
		}

		report.add("");
		report.add("## Armor per family and grade");
		report.add("");
		report.add("| Family | Grade | Matching sets | Single pieces when no set fits (chest, legs, gloves, feet, head) |");
		report.add("|---|---|---|---|");
		FakePlayerArmorSets.random(ArmorType.LIGHT, CrystalType.NONE); // builds the set table
		final Field setsField = FakePlayerArmorSets.class.getDeclaredField("SETS");
		setsField.setAccessible(true);
		@SuppressWarnings("unchecked")
		final Map<ArmorType, EnumMap<CrystalType, List<FakePlayerArmorSets.Outfit>>> sets = (Map<ArmorType, EnumMap<CrystalType, List<FakePlayerArmorSets.Outfit>>>) setsField.get(null);
		for (ArmorType family : new ArmorType[] {ArmorType.LIGHT, ArmorType.HEAVY, ArmorType.MAGIC})
		{
			for (CrystalType grade : GRADES)
			{
				final List<String> setNames = new ArrayList<>();
				for (FakePlayerArmorSets.Outfit outfit : sets.get(family).get(grade))
				{
					final ItemTemplate chest = ItemData.getInstance().getTemplate(outfit.chest);
					setNames.add(chest.getName());
					for (int id : new int[] {outfit.chest, outfit.legs, outfit.gloves, outfit.feet, outfit.head, outfit.shield})
					{
						final ItemTemplate piece = (id > 0) ? ItemData.getInstance().getTemplate(id) : null;
						if ((piece != null) && (placeholder(piece) || (piece.getCrystalType() != grade)))
						{
							PROBLEMS.add("armor set " + chest.getName() + ": piece " + describe(piece));
						}
					}
				}
				final List<String> pieces = new ArrayList<>();
				for (BodyPart slot : new BodyPart[] {BodyPart.CHEST, BodyPart.LEGS, BodyPart.GLOVES, BodyPart.FEET, BodyPart.HEAD})
				{
					final ItemTemplate piece = (ItemTemplate) bestArmor.invoke(null, slot, grade, family, null);
					pieces.add(describe(piece));
					if (piece == null)
					{
						continue;
					}
					if (placeholder(piece))
					{
						PROBLEMS.add("armor " + family + " " + grade + " " + slot + ": " + describe(piece));
					}
					else if (piece.getCrystalType() != grade)
					{
						// The single-piece path runs only when no set of the family and grade can be worn.
						NOTES.add("armor " + family + " " + grade + " " + slot + " single piece is " + describe(piece) + (setNames.isEmpty() ? "" : " (used only if the " + String.join(", ", setNames) + " set cannot be worn)"));
					}
				}
				report.add("| " + family + " | " + grade + " | " + (setNames.isEmpty() ? "(none)" : String.join(", ", setNames)) + " | " + String.join("<br>", pieces) + " |");
			}
		}

		report.add("");
		report.add("## Shields and jewelry per grade");
		report.add("");
		report.add("| Grade | Shield | Necklace | Earring | Ring |");
		report.add("|---|---|---|---|---|");
		final List<Predicate<ItemTemplate>> accessories = List.of( //
			item -> (item instanceof Armor) && (((Armor) item).getItemType() == ArmorType.SHIELD), //
			item -> (item instanceof Armor) && (item.getBodyPart() == BodyPart.NECK), //
			item -> (item instanceof Armor) && (item.getBodyPart() == BodyPart.LR_EAR), //
			item -> (item instanceof Armor) && (item.getBodyPart() == BodyPart.LR_FINGER));
		for (CrystalType grade : GRADES)
		{
			final List<String> cells = new ArrayList<>();
			for (Predicate<ItemTemplate> filter : accessories)
			{
				final ItemTemplate item = (ItemTemplate) bestEquip.invoke(null, grade, filter);
				cells.add(describe(item));
				if ((item != null) && (placeholder(item) || (item.getCrystalType() != grade)))
				{
					PROBLEMS.add(grade + " accessory " + describe(item));
				}
				if ((item != null) && (item.getBodyPart() != BodyPart.L_HAND) && item.hasSkills())
				{
					PROBLEMS.add(grade + " accessory " + describe(item) + " is raid or epic jewelry");
				}
			}
			report.add("| " + grade + " | " + String.join(" | ", cells) + " |");
		}

		report.add("");
		report.add("## Field phantom weapon pool");
		report.add("");
		method("buildGear").invoke(null);
		for (String pool : new String[] {"FIGHTER_GEAR", "MAGE_GEAR"})
		{
			final Field field = PhantomManager.class.getDeclaredField(pool);
			field.setAccessible(true);
			@SuppressWarnings("unchecked")
			final Map<BodyPart, EnumMap<CrystalType, List<ItemTemplate>>> gear = (Map<BodyPart, EnumMap<CrystalType, List<ItemTemplate>>>) field.get(null);
			for (CrystalType grade : GRADES)
			{
				final List<String> names = new ArrayList<>();
				for (ItemTemplate item : gear.get(BodyPart.R_HAND).get(grade))
				{
					names.add(describe(item));
					if (placeholder(item) || item.isForNpc() || (pool.equals("FIGHTER_GEAR") == item.isMagicWeapon()))
					{
						PROBLEMS.add(pool + " " + grade + ": " + describe(item));
					}
				}
				if (names.isEmpty())
				{
					PROBLEMS.add(pool + " " + grade + ": no weapon in grade");
				}
				report.add("- " + pool + " " + grade + ": " + String.join(", ", names));
			}
		}

		report.add("");
		report.add("## Problems");
		report.add("");
		report.add(PROBLEMS.isEmpty() ? "None." : "");
		PROBLEMS.forEach(problem -> report.add("- " + problem));
		report.add("");
		report.add("## Notes");
		report.add("");
		report.add(NOTES.isEmpty() ? "None." : "");
		NOTES.forEach(note -> report.add("- " + note));
		if (args.length > 0)
		{
			try (PrintWriter out = new PrintWriter(args[0], "UTF-8"))
			{
				report.forEach(out::println);
			}
		}
		NOTES.forEach(note -> System.out.println("NOTE  " + note));
		PROBLEMS.forEach(problem -> System.out.println("FAIL  " + problem));
		System.out.println(PROBLEMS.isEmpty() ? "PhantomGearAuditTest: all classes pass" : "PhantomGearAuditTest: " + PROBLEMS.size() + " problems");
		System.exit(PROBLEMS.isEmpty() ? 0 : 1);
	}
}
