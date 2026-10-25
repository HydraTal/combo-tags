package com.combotags;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.Test;

/**
 * The plugin serves variants in the same order as the filter lab: every entry with two or more variants, as the lab's
 * engine orders them ({@code variant_sort_golden.tsv}, written by {@code scripts/gen-variant-sort.mjs} next to
 * {@code variant_sort.tsv}), must come out of {@link VariantSort} in that order from a shuffled start.
 */
public class VariantSortParityTest
{
	@Test
	public void everyEntryIsServedInTheLabsOrder() throws Exception
	{
		List<List<Integer>> entries = new ArrayList<>();
		Map<Integer, String> names = new HashMap<>();
		try (InputStream in = VariantSortParityTest.class.getResourceAsStream("variant_sort_golden.tsv"))
		{
			BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
			for (String line; (line = reader.readLine()) != null; )
			{
				if (line.isEmpty() || line.startsWith("#"))
				{
					continue;
				}
				String[] cells = line.split("\t", -1); // cells[0] is the entry id; then id, name pairs
				List<Integer> order = new ArrayList<>();
				for (int i = 1; i + 1 < cells.length; i += 2)
				{
					int id = Integer.parseInt(cells[i]);
					order.add(id);
					if (!cells[i + 1].isEmpty())
					{
						names.put(id, cells[i + 1]);
					}
				}
				entries.add(order);
			}
		}
		assertTrue("the golden file lists entries", entries.size() > 1000);

		Field f = ItemIndex.class.getDeclaredField("variantNames");
		f.setAccessible(true);
		Object saved = f.get(null);
		f.set(null, names);
		try
		{
			Random random = new Random(7);
			List<String> mismatches = new ArrayList<>();
			for (List<Integer> want : entries)
			{
				List<Integer> shuffled = new ArrayList<>(want);
				Collections.shuffle(shuffled, random);
				List<Integer> got = VariantSort.sort(shuffled);
				if (!got.equals(want))
				{
					mismatches.add("want " + want + " got " + got);
				}
			}
			assertEquals("entries served differently from the lab, e.g. "
				+ mismatches.subList(0, Math.min(5, mismatches.size())), 0, mismatches.size());
		}
		finally
		{
			f.set(null, saved);
		}
	}
}
