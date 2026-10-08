package modules.geargrimoire;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The gear index: armor sets, and every piece (armor, shield, weapon, jewel) with where it comes from. Plain data and
 * searches, no server types, so it tests on its own.
 */
final class GrimoireIndex
{
	static final String[] GRADES = { "NG", "D", "C", "B", "A", "S" };
	private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");

	/** An armor set. {@code pieces} is chest, legs, head, gloves, feet, shield (0 where the set has none). */
	static final class GearSet
	{
		int id;
		String name;
		String grade;
		String armorType;
		int[] pieces = new int[6];
		String bonuses;
		/** Tier-list placements, e.g. "Heavy armor, damage #2". */
		List<String> tags = new ArrayList<>();
	}

	/** One item. */
	static final class Piece
	{
		int id;
		String name;
		String grade;
		/** armor, weapon or jewel. */
		String kind;
		String slot;
		String type;
		int setId;
		String stats;
		List<String> sources = new ArrayList<>();
	}

	final Map<Integer, GearSet> sets = new LinkedHashMap<>();
	final Map<Integer, Piece> pieces = new LinkedHashMap<>();
	private final Map<Integer, List<Integer>> _bySet = new HashMap<>();

	/** @return the index read from {@code reader} (the TSV the generator writes); bad lines are skipped */
	static GrimoireIndex parse(Reader reader) throws IOException
	{
		final GrimoireIndex index = new GrimoireIndex();
		try (BufferedReader in = new BufferedReader(reader))
		{
			String line;
			while ((line = in.readLine()) != null)
			{
				if (line.isEmpty() || (line.charAt(0) == '#'))
				{
					continue;
				}
				final String[] f = line.split("\t", -1);
				try
				{
					if (f[0].equals("SET") && (f.length >= 8))
					{
						final GearSet s = new GearSet();
						s.id = Integer.parseInt(f[1]);
						s.name = f[2];
						s.grade = f[3];
						s.armorType = f[4];
						final String[] ids = f[5].split(",");
						for (int i = 0; (i < 6) && (i < ids.length); i++)
						{
							s.pieces[i] = Integer.parseInt(ids[i].trim());
						}
						s.bonuses = f[6];
						if (!f[7].equals("-"))
						{
							for (String t : f[7].split(";"))
							{
								s.tags.add(t.trim());
							}
						}
						index.sets.put(s.id, s);
					}
					else if (f[0].equals("PIECE") && (f.length >= 10))
					{
						final Piece p = new Piece();
						p.id = Integer.parseInt(f[1]);
						p.name = f[2];
						p.grade = f[3];
						p.kind = f[4];
						p.slot = f[5];
						p.type = f[6];
						p.setId = Integer.parseInt(f[7]);
						p.stats = f[8];
						for (String s : f[9].split("\\|\\|"))
						{
							if (!s.trim().isEmpty())
							{
								p.sources.add(s.trim());
							}
						}
						index.pieces.put(p.id, p);
						if (p.setId != 0)
						{
							index._bySet.computeIfAbsent(p.setId, k -> new ArrayList<>()).add(p.id);
						}
					}
				}
				catch (RuntimeException e)
				{
					// skip a bad line
				}
			}
		}
		return index;
	}

	List<Piece> piecesOf(int setId)
	{
		final List<Piece> out = new ArrayList<>();
		final GearSet s = sets.get(setId);
		if (s == null)
		{
			return out;
		}
		for (int id : s.pieces)
		{
			final Piece p = pieces.get(id);
			if ((id != 0) && (p != null))
			{
				out.add(p);
			}
		}
		return out;
	}

	/** @return the rank of the set's best tier-list placement whose text contains {@code role} ({@code null} = any), or 999 if none */
	static int bestRank(GearSet s, String role)
	{
		int best = 999;
		for (String t : s.tags)
		{
			if ((role != null) && !t.toLowerCase(Locale.ROOT).contains(role))
			{
				continue;
			}
			final int hash = t.lastIndexOf('#');
			if (hash >= 0)
			{
				try
				{
					best = Math.min(best, Integer.parseInt(t.substring(hash + 1).trim()));
				}
				catch (NumberFormatException e)
				{
					// ignore
				}
			}
		}
		return best;
	}

	/** @return the sets of {@code grade} ("-" = any) and armor type ("-" = any: HEAVY, LIGHT, MAGIC) that suit {@code role} ("-" = any: damage, tank, healer, caster), best first */
	List<GearSet> findSets(String grade, String armorType, String role)
	{
		final String r = roleKey(role);
		final List<GearSet> out = new ArrayList<>();
		for (GearSet s : sets.values())
		{
			if (!any(grade) && !s.grade.equalsIgnoreCase(grade))
			{
				continue;
			}
			if (!any(armorType) && !s.armorType.equalsIgnoreCase(armorType))
			{
				continue;
			}
			if ((r != null) && (bestRank(s, r) == 999))
			{
				continue;
			}
			out.add(s);
		}
		out.sort(Comparator.comparingInt((GearSet s) -> gradeOrder(s.grade)).reversed().thenComparingInt(s -> bestRank(s, r)).thenComparing(s -> s.name));
		return out;
	}

	/** @return the weapons (kind "weapon") or jewelry (kind "jewel") of {@code grade} and {@code type} ("-" = any), strongest first */
	List<Piece> findLoose(String kind, String grade, String type)
	{
		final List<Piece> out = new ArrayList<>();
		for (Piece p : pieces.values())
		{
			if (!p.kind.equals(kind))
			{
				continue;
			}
			if (!any(grade) && !p.grade.equalsIgnoreCase(grade))
			{
				continue;
			}
			if (!any(type) && !p.type.equalsIgnoreCase(type) && !p.slot.equalsIgnoreCase(type))
			{
				continue;
			}
			out.add(p);
		}
		out.sort(Comparator.comparingInt((Piece p) -> gradeOrder(p.grade)).reversed().thenComparing(Comparator.comparingDouble((Piece p) -> power(p)).reversed()).thenComparing(p -> p.name));
		return out;
	}

	/** @return sets and pieces whose name contains every word of {@code text}; sets first, at most {@code limit} pieces */
	List<Object> search(String text, int limit)
	{
		final List<Object> out = new ArrayList<>();
		final String[] words = (text == null) ? new String[0] : text.toLowerCase(Locale.ROOT).trim().split("\\s+");
		if ((words.length == 0) || words[0].isEmpty())
		{
			return out;
		}
		for (GearSet s : sets.values())
		{
			if (matches(s.name, words))
			{
				out.add(s);
			}
		}
		final List<Piece> hits = new ArrayList<>();
		for (Piece p : pieces.values())
		{
			if (matches(p.name, words))
			{
				hits.add(p);
			}
		}
		hits.sort(Comparator.comparingInt((Piece p) -> gradeOrder(p.grade)).reversed().thenComparing(p -> p.name));
		out.addAll(hits.subList(0, Math.min(limit, hits.size())));
		return out;
	}

	/** @return the sort power of a piece: P.Atk for weapons, M.Def for jewelry (the first number in its stats text, or after "M.Def") */
	static double power(Piece p)
	{
		final String key = p.kind.equals("weapon") ? "P.Atk" : "M.Def";
		final int at = p.stats.indexOf(key);
		if (at < 0)
		{
			return 0;
		}
		final Matcher m = NUMBER.matcher(p.stats.substring(at + key.length()));
		return m.find() ? Double.parseDouble(m.group()) : 0;
	}

	static int gradeOrder(String grade)
	{
		for (int i = 0; i < GRADES.length; i++)
		{
			if (GRADES[i].equalsIgnoreCase(grade))
			{
				return i;
			}
		}
		return -1;
	}

	static boolean any(String s)
	{
		return (s == null) || s.isEmpty() || s.equals("-");
	}

	private static String roleKey(String role)
	{
		if (any(role))
		{
			return null;
		}
		final String r = role.toLowerCase(Locale.ROOT);
		return r.equals("healer") ? "healers" : r.equals("caster") ? "casters" : r;
	}

	private static boolean matches(String name, String[] words)
	{
		final String n = name.toLowerCase(Locale.ROOT);
		for (String w : words)
		{
			if (!n.contains(w))
			{
				return false;
			}
		}
		return true;
	}

	/** @return a copy of the list's page {@code page} (1-based) of {@code size}; the page is clamped into range */
	static <T> List<T> page(List<T> all, int page, int size)
	{
		final int pages = pages(all.size(), size);
		final int p = Math.max(1, Math.min(pages, page));
		final int from = (p - 1) * size;
		return new ArrayList<>(all.subList(Math.min(from, all.size()), Math.min(all.size(), from + size)));
	}

	static int pages(int count, int size)
	{
		return Math.max(1, (count + size - 1) / size);
	}

	/** @return an unmodifiable empty list helper for callers */
	static <T> List<T> none()
	{
		return Collections.emptyList();
	}
}
