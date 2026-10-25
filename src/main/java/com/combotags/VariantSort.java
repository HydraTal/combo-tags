package com.combotags;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

/**
 * The default variant order: which of an entry's versions is served first when a combo member has no order of its
 * own. A player who wants another order drags the variants; there are no other sort options.
 *
 * <p>The rules are the filter lab's {@code rules.js} variantTypes in its variantSort priority order, matched on
 * Jagex's internal item names ("Needs repair / recharge" last, then the count the name ends in, highest first, then
 * the source copies, recolors and ornaments last...). The plugin doesn't have internal names at runtime, so
 * {@code scripts/gen-variant-sort.mjs} works out every item's value under each rule into {@code variant_sort.tsv};
 * this class only compares them, then breaks ties by the shorter display name, then the lower id.
 *
 * <p>One value is worked out here: an item with no count under the Count rule is empty (0) when its name is another
 * version's without a bracketed count ("Amulet of glory" beside "Amulet of glory(4)") or says uncharged, and unlimited
 * otherwise (Amulet of eternal glory, "New crystal bow", an unused Barrows piece), which depends on the other versions.
 */
@Slf4j
final class VariantSort
{
	private static final String RESOURCE = "variant_sort.tsv";
	/** A Count-style unmatched value: empty for a plain or uncharged name, else unlimited. */
	private static final String PLAIN = "plain";
	private static final Pattern UNCHARGED_NAME = Pattern.compile("\\((uncharged|empty|u)\\)", Pattern.CASE_INSENSITIVE);

	/** One sort rule: its direction, and what an item it doesn't match takes. */
	private static final class Step
	{
		final String dir;      // last / first (flag), high / low (value), az / za (tag)
		final boolean tag;
		final boolean plain;   // unmatched is the Count-style plain / unlimited value
		final long unmatched;  // otherwise

		Step(String dir, String unmatched)
		{
			this.dir = dir;
			this.tag = "az".equals(dir) || "za".equals(dir);
			this.plain = PLAIN.equals(unmatched);
			this.unmatched = plain ? 0 : Long.parseLong(unmatched);
		}

		boolean descending()
		{
			return "high".equals(dir) || "first".equals(dir) || "za".equals(dir);
		}
	}

	/** The table: the steps in priority order, and per item the raw value of each step it matches. */
	private static final class Table
	{
		final List<Step> steps;
		final Map<Integer, String[]> rows; // id -> raw value per step: null = unmatched, "" = a matched flag

		Table(List<Step> steps, Map<Integer, String[]> rows)
		{
			this.steps = steps;
			this.rows = rows;
		}
	}

	private static volatile Table table;

	private VariantSort()
	{
	}

	/** The variants in the default order: most-preferred first. */
	static List<Integer> sort(List<Integer> variants)
	{
		Table t = table();
		List<Integer> series = new ArrayList<>(variants);
		Set<String> plains = plainNames(series);
		Comparator<Integer> order = (a, b) -> 0;
		for (int i = 0; i < t.steps.size(); i++)
		{
			Step s = t.steps.get(i);
			int at = i;
			Comparator<Integer> c = s.tag
				? Comparator.comparing((Integer id) -> tagOf(t, at, id))
				: Comparator.comparingLong((Integer id) -> valueOf(t, s, at, id, plains));
			order = order.thenComparing(s.descending() ? c.reversed() : c);
		}
		order = order.thenComparingInt(VariantSort::nameLength).thenComparingInt(id -> id);
		List<Integer> ids = new ArrayList<>(variants);
		ids.sort(order);
		return ids;
	}

	private static String tagOf(Table t, int step, int id)
	{
		String[] row = t.rows.get(id);
		String raw = row == null ? null : row[step];
		return raw == null ? "" : raw.toLowerCase(Locale.ROOT);
	}

	private static long valueOf(Table t, Step s, int step, int id, Set<String> plains)
	{
		String[] row = t.rows.get(id);
		String raw = row == null ? null : row[step];
		if (raw != null)
		{
			return raw.isEmpty() ? 1 : Long.parseLong(raw); // a matched flag is 1
		}
		if (!s.plain)
		{
			return s.unmatched;
		}
		String name = ItemIndex.variantName(id);
		boolean empty = name != null && (UNCHARGED_NAME.matcher(name).find()
			|| (!ItemIndex.hasCount(name) && plains.contains(name.trim().toLowerCase(Locale.ROOT))));
		return empty ? 0 : Long.MAX_VALUE;
	}

	/**
	 * The plain names of the versions with a bracketed count, lowercased: "Amulet of glory" from "Amulet of glory(4)",
	 * "Amulet of glory (t)" from "(t4)". A trailing count doesn't make one: "Dharok's helm" beside "Dharok's helm 100"
	 * is the new helm, not an empty one.
	 */
	private static Set<String> plainNames(List<Integer> series)
	{
		Set<String> plains = new HashSet<>();
		for (int id : series)
		{
			String name = ItemIndex.variantName(id);
			if (name != null && ItemIndex.hasBracketedCount(name))
			{
				plains.add(ItemIndex.withoutCount(name).toLowerCase(Locale.ROOT));
			}
		}
		return plains;
	}

	private static int nameLength(int id)
	{
		String name = ItemIndex.variantName(id);
		return name == null ? Integer.MAX_VALUE : name.length();
	}

	/** The generated table, loaded once from the plugin jar. Missing or unreadable: no steps, so name then id. */
	private static Table table()
	{
		Table t = table;
		if (t != null)
		{
			return t;
		}
		List<Step> steps = new ArrayList<>();
		Map<Integer, String[]> rows = new HashMap<>();
		try (InputStream in = VariantSort.class.getResourceAsStream(RESOURCE))
		{
			if (in == null)
			{
				log.warn("{} missing from the plugin jar; variants are served by name only", RESOURCE);
			}
			else
			{
				BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
				Map<String, Integer> stepIndex = new HashMap<>();
				for (String line; (line = reader.readLine()) != null; )
				{
					if (line.isEmpty() || line.startsWith("#"))
					{
						continue;
					}
					String[] cells = line.split("\t");
					if (cells[0].equals("@steps"))
					{
						for (int i = 1; i < cells.length; i++)
						{
							String[] p = cells[i].split(":");
							stepIndex.put(p[0], steps.size());
							steps.add(new Step(p[1], p[2]));
						}
						continue;
					}
					String[] row = new String[steps.size()];
					for (int i = 2; i < cells.length; i++) // cells[1] is the internal name, for reading the table
					{
						int eq = cells[i].indexOf('=');
						Integer at = stepIndex.get(eq < 0 ? cells[i] : cells[i].substring(0, eq));
						if (at != null)
						{
							row[at] = eq < 0 ? "" : cells[i].substring(eq + 1);
						}
					}
					rows.put(Integer.parseInt(cells[0]), row);
				}
			}
		}
		catch (IOException | RuntimeException ex)
		{
			log.warn("failed to read {}; variants are served by name only", RESOURCE, ex);
			steps = Collections.emptyList();
			rows = Collections.emptyMap();
		}
		t = new Table(steps, rows);
		table = t;
		return t;
	}
}
