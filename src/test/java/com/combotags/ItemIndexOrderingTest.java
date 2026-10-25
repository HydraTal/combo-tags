package com.combotags;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Variant ordering and search-filter rules, checked against real item ids and display names (RuneLite 1.13.2
 * gameval ItemID). The index normally learns names from the client; here they are injected directly.
 */
public class ItemIndexOrderingTest
{
	private static final Map<Integer, String> NAMES = new HashMap<>();

	static
	{
		// Amulet of glory: plain = empty, (t) = empty trimmed, eternal = unlimited.
		NAMES.put(1704, "Amulet of glory");
		NAMES.put(1706, "Amulet of glory(1)");
		NAMES.put(1712, "Amulet of glory(4)");
		NAMES.put(11978, "Amulet of glory(6)");
		NAMES.put(10360, "Amulet of glory (t1)");
		NAMES.put(10354, "Amulet of glory (t4)");
		NAMES.put(11964, "Amulet of glory (t6)");
		NAMES.put(10362, "Amulet of glory (t)");
		NAMES.put(19707, "Amulet of eternal glory");
		// Ring of dueling
		NAMES.put(2552, "Ring of dueling(8)");
		NAMES.put(2560, "Ring of dueling(4)");
		NAMES.put(2566, "Ring of dueling(1)");
		// Barrows: plain = new (undegraded), "0" = broken.
		NAMES.put(4716, "Dharok's helm");
		NAMES.put(4880, "Dharok's helm 100");
		NAMES.put(4882, "Dharok's helm 50");
		NAMES.put(4884, "Dharok's helm 0");
		// Imbued black mask: tag after the count.
		NAMES.put(11774, "Black mask (10) (i)");
		NAMES.put(11783, "Black mask (1) (i)");
		NAMES.put(11784, "Black mask (i)");
		// Ring of wealth: plain and imbued, letter inside the count.
		NAMES.put(2572, "Ring of wealth");
		NAMES.put(11980, "Ring of wealth (5)");
		NAMES.put(20786, "Ring of wealth (i5)");
		NAMES.put(12785, "Ring of wealth (i)");
		// Ahrim's hood and its folded recolor Echo ahrim's hood, each with durability states.
		NAMES.put(4708, "Ahrim's hood");
		NAMES.put(4856, "Ahrim's hood 100");
		NAMES.put(4857, "Ahrim's hood 75");
		NAMES.put(4860, "Ahrim's hood 0");
		NAMES.put(30445, "Echo ahrim's hood");
		NAMES.put(30519, "Echo ahrim's hood 100");
		NAMES.put(30525, "Echo ahrim's hood 75");
		NAMES.put(30543, "Echo ahrim's hood 0");
		// Torva full helm: a damaged state and a recolor, none counted.
		NAMES.put(26382, "Torva full helm");
		NAMES.put(26376, "Torva full helm (damaged)");
		NAMES.put(28254, "Sanguine torva full helm");
		// Webweaver bow: only charged and uncharged.
		NAMES.put(27652, "Webweaver bow (u)");
		NAMES.put(27655, "Webweaver bow");
	}

	private static Object savedNames;

	@BeforeClass
	public static void injectNames() throws Exception
	{
		Field f = ItemIndex.class.getDeclaredField("variantNames");
		f.setAccessible(true);
		savedNames = f.get(null);
		f.set(null, NAMES);
	}

	@AfterClass
	public static void restoreNames() throws Exception
	{
		Field f = ItemIndex.class.getDeclaredField("variantNames");
		f.setAccessible(true);
		f.set(null, savedNames);
	}

	/** The default order of a shuffled copy, so the result can't just be the input order. */
	private static List<Integer> sorted(List<Integer> ids)
	{
		List<Integer> shuffled = new ArrayList<>(ids);
		Collections.shuffle(shuffled, new java.util.Random(7));
		return VariantSort.sort(shuffled);
	}

	// ---- the default order (VariantSort; the expected orders are the filter lab's, see VariantSortParityTest)

	@Test
	public void gloryMostChargesFirst()
	{
		List<Integer> glory = Arrays.asList(1704, 1706, 1712, 11978, 19707);
		assertTrue(ItemIndex.isChargeOrdered(glory));
		// Count (key end): eternal (no count, not plain) is unlimited, the plain glory is empty.
		assertEquals(Arrays.asList(19707, 11978, 1712, 1706, 1704), sorted(glory));
	}

	@Test
	public void trimmedGloryPlainIsEmpty()
	{
		// "Amulet of glory (t)" is "(t4)" without its count, so it's the empty one.
		assertEquals(Arrays.asList(10354, 1706, 10362), sorted(Arrays.asList(10354, 10362, 1706)));
	}

	@Test
	public void ringOfDueling()
	{
		assertEquals(Arrays.asList(2552, 2560, 2566), sorted(Arrays.asList(2566, 2552, 2560)));
	}

	@Test
	public void barrowsPlainIsNewAndZeroIsLast()
	{
		// The plain helm has no count but isn't a bracketed one's plain name: unlimited, so new. " 0" needs repair.
		assertEquals(Arrays.asList(4716, 4880, 4882, 4884), sorted(Arrays.asList(4716, 4880, 4882, 4884)));
	}

	@Test
	public void imbuedSeriesWithTrailingTag()
	{
		List<Integer> mask = Arrays.asList(11774, 11783, 11784);
		assertTrue(ItemIndex.isChargeOrdered(mask));
		assertEquals(Arrays.asList(11774, 11783, 11784), sorted(mask));
	}

	@Test
	public void singleCountIsNotASeries()
	{
		assertFalse(ItemIndex.isChargeOrdered(Arrays.asList(1704, 1706)));
	}

	@Test
	public void unchargedAndChargedPairIsASeries()
	{
		List<Integer> bow = Arrays.asList(27652, 27655);
		assertTrue(ItemIndex.isChargeOrdered(bow));
		assertEquals(Arrays.asList(27655, 27652), sorted(bow));
	}

	@Test
	public void trimmedGloryAfterPlainAtEqualCharges()
	{
		// (6), (t6), (1), (t1): TRAIL_ (a recolor-kit word) sorts the trimmed one after the plain one of each count.
		assertEquals(Arrays.asList(11978, 11964, 1706, 10360), sorted(Arrays.asList(11978, 11964, 1706, 10360)));
	}

	@Test
	public void imbuedRingOfWealth()
	{
		// (5), (i5), empty, empty (i): the counts first, the shorter name first at an equal count.
		assertEquals(Arrays.asList(11980, 20786, 2572, 12785), sorted(Arrays.asList(11980, 20786, 2572, 12785)));
	}

	// ---- search filters

	private static final Set<String> EQUIPPABLE_NAMES = new HashSet<>(Arrays.asList("bow of faerdhinen (c)", "egg"));

	@Test
	public void lmsCopyIsExcludedButTheRealItemIsNot()
	{
		assertTrue(ItemFilters.isExcluded(20402, "Rune scimitar", true, EQUIPPABLE_NAMES));
		assertFalse(ItemFilters.isExcluded(1333, "Rune scimitar", true, EQUIPPABLE_NAMES));
	}

	@Test
	public void onlyBrokenNamedItemsAreExcluded()
	{
		// A state tag is a variant of the working item, not excluded; a name that is itself broken is.
		assertFalse(ItemFilters.isExcluded(22441, "Avernic defender (broken)", true, EQUIPPABLE_NAMES));
		assertTrue(ItemFilters.isExcluded(11908, "Uncharged trident", true, EQUIPPABLE_NAMES));
		assertFalse(ItemFilters.isExcluded(4884, "Dharok's helm 0", true, EQUIPPABLE_NAMES));
		assertFalse(ItemFilters.isExcluded(297, "Glarial's urn (empty)", false, EQUIPPABLE_NAMES));
	}

	@Test
	public void nonEquippableTwinIsExcludedExceptEverydayItems()
	{
		assertTrue(ItemFilters.isExcluded(25869, "Bow of Faerdhinen (c)", false, EQUIPPABLE_NAMES));
		assertFalse(ItemFilters.isExcluded(25867, "Bow of Faerdhinen (c)", true, EQUIPPABLE_NAMES));
		assertFalse(ItemFilters.isExcluded(1944, "Egg", false, EQUIPPABLE_NAMES)); // shares a name with Scrambled egg
	}

	@Test
	public void cosmeticVersionsAreSplit()
	{
		assertFalse(ItemFilters.isSplit("Dragon scimitar (or)")); // an ornament version is a variant of its item
		assertTrue(ItemFilters.isSplit("Rune platebody (g)"));
		assertTrue(ItemFilters.isSplit("Rune helm (h1)"));
		assertFalse(ItemFilters.isSplit("Strength cape(t)"));     // trimmed skillcape: no space before the tag
		assertFalse(ItemFilters.isSplit("Amulet of glory (t4)")); // a charge state of the trimmed glory
		assertFalse(ItemFilters.isSplit("Dragon scimitar"));
	}

	@Test
	public void chargeSeriesNamesDropTheCount()
	{
		assertEquals("Stamina potion", ItemIndex.withoutCount("Stamina potion(4)"));
		assertEquals("Ring of dueling", ItemIndex.withoutCount("Ring of dueling(8)"));
		assertEquals("Brown spice", ItemIndex.withoutCount("Brown spice (4)"));
		assertEquals("Amulet of glory (t)", ItemIndex.withoutCount("Amulet of glory (t4)"));
		assertEquals("Ring of wealth (i)", ItemIndex.withoutCount("Ring of wealth (i5)"));
		assertEquals("Black mask (i)", ItemIndex.withoutCount("Black mask (10) (i)"));
		assertEquals("Dharok's helm", ItemIndex.withoutCount("Dharok's helm 100"));
		assertEquals("Amulet of glory", ItemIndex.withoutCount("Amulet of glory"));
	}

	@Test
	public void foldedRecolorBringsItsOwnVariants() throws Exception
	{
		Map<Integer, List<Integer>> dv = new HashMap<>();
		dv.put(4708, Arrays.asList(4856, 4857, 4708, 4860));
		dv.put(30445, Arrays.asList(30519, 30525, 30445, 30543));
		Map<Integer, List<Integer>> folded = new HashMap<>();
		folded.put(4708, Collections.singletonList(30445));
		Field dvField = ItemIndex.class.getDeclaredField("displayVariants");
		Field foldedField = ItemIndex.class.getDeclaredField("foldedMembers");
		dvField.setAccessible(true);
		foldedField.setAccessible(true);
		Object savedDv = dvField.get(null);
		Object savedFolded = foldedField.get(null);
		try
		{
			dvField.set(null, dv);
			foldedField.set(null, folded);
			// Both families, interleaved by durability; the plain (new) versions first, the folded recolor after its item.
			assertEquals(Arrays.asList(4708, 30445, 4856, 30519, 4857, 30525, 4860, 30543), ItemIndex.variants(4708));
		}
		finally
		{
			dvField.set(null, savedDv);
			foldedField.set(null, savedFolded);
		}
	}

	@Test
	public void brokenStatesSortAfterWorkingVersions()
	{
		List<Integer> torva = Arrays.asList(26376, 26382, 28254);
		assertTrue(ItemIndex.isChargeOrdered(torva));
		// The damaged helm needs repair (last); the Sanguine recolor after the plain helm.
		assertEquals(Arrays.asList(26382, 28254, 26376), sorted(torva));
	}
}
