package com.combotags;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.client.game.ItemEquipmentStats;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStats;
import net.runelite.client.game.ItemVariationMapping;

/**
 * A searchable, variant-collapsed index of every named item — including untradeables that
 * {@link ItemManager#search} (GE-price backed) misses.
 *
 * <p>Each entry is a single BASE item: placeholders/notes are canonicalized and charged/ornamented
 * variants fold to their base via {@link ItemVariationMapping}, so e.g. all serpentine-helm states
 * (and an oathplate + ornament kit) collapse to one entry.
 *
 * <p>On top of that, the cosmetic recolors {@link ItemVariationMapping} misses ("Sanguine torva full helm", "Dark
 * infinity hat") fold onto their item from the hand-kept {@link ItemFilters#recolors()} list, exposed via
 * {@link #canonicalBase(int)} so combo ownership treats a recolor as its base item. Nothing is folded by name or by
 * stats: same-named and same-stat items are often different items (the runecrafting tiaras, the ancient sceptres,
 * Torag's and Guthan's helms, the god capes), with effects the item API doesn't expose.
 *
 * <p>{@link ItemFilters} decides what the index leaves out: excluded items (game-mode and PvP copies,
 * non-equippable twins and the like) are never an entry or a variant but still map to their item for ownership, and
 * split items (cosmetic "(g)"/"(t)"/"(h1)" versions, but not a charge state such as the empty "Amulet of glory (t)")
 * are always their own entry and never folded. A charge series (a potion's doses, a glory's charges) is listed under its
 * name without the count. A variant with no combat bonuses at all (a Barrows "... 0" piece) is a broken state of its
 * item rather than a different-stats version, so it stays one of that item's variants. A broken or uncharged state
 * ("Fire cape (broken)", "Toxic staff (uncharged)") joins its working item the same way, even with lower stats, and
 * never becomes the entry's id. An entry's variants are served in {@link VariantSort}'s order.
 *
 * <p>{@link #build} MUST run on the client thread (it uses {@code getItemComposition}/
 * {@code canonicalize}); the result is cached after the first build.
 */
@Slf4j
public final class ItemIndex
{
	/** An entry whose item isn't equippable. */
	public static final int NOT_EQUIPPABLE = -1;
	/** Search filter: match every item (no slot restriction). */
	public static final int SLOT_ANY = -2;
	/** Search filter: match any equippable item, regardless of which slot. */
	public static final int SLOT_EQUIPPABLE = -3;
	/** Search filter: items with an "Eat" or "Drink" inventory action. */
	public static final int SLOT_CONSUMABLE = -4;
	/** Search filter: items that are neither equippable nor consumable. */
	public static final int SLOT_MISC = -5;

	private static final Pattern CONSUME_ACTION = Pattern.compile("^(eat|drink)$", Pattern.CASE_INSENSITIVE);
	/** A broken, uncharged or inactive state: "Fire cape (broken)", "Toxic staff (uncharged)", "Torva full helm (damaged)". */
	private static final Pattern STATE = Pattern.compile(
		"\\s*\\((broken|uncharged|inactive|deg|degraded|damaged|deactivated|unpowered|dormant)\\)", Pattern.CASE_INSENSITIVE);
	/** Versions that never name an entry, beside the empty / broken ones: unfinished and Deadman versions. */
	private static final Pattern NAME_SKIP = Pattern.compile("\\(unf\\)|\\(deadman\\)", Pattern.CASE_INSENSITIVE);

	/** One base item: its id, display name, the icon to show, and equipment slot ({@link #NOT_EQUIPPABLE} if not gear). */
	public static final class Entry
	{
		public final int id;
		public final int equipSlot; // EquipmentInventorySlot.getSlotIdx(), or NOT_EQUIPPABLE
		// Set once during build(), before the index is published:
		// The name and icon shown for this entry: named after its lowest-id plain version ("Serpentine helm"), without
		// the count for a charge series ("Stamina potion"), which also shows its top variant's icon (the (4)).
		public String name;
		public int displayId;
		int flags;          // ItemFilters categories this entry belongs to
		boolean split;      // an ItemFilters split item: always its own entry, never folded
		boolean consumable; // not equippable, and has an "Eat" or "Drink" inventory action

		Entry(int id, String name, int displayId, int equipSlot)
		{
			this.id = id;
			this.equipSlot = equipSlot;
			this.name = name;
			this.displayId = displayId;
		}
	}

	private static volatile List<Entry> entries;
	private static volatile Map<Integer, Integer> mergeToCanonical; // folded base id -> canonical (lowest-id) base id
	private static volatile Map<Integer, List<Integer>> foldedMembers; // canonical base id -> folded recolor ids
	private static volatile Map<Integer, Integer> slotByBase; // equippable base id -> EquipmentInventorySlot.getSlotIdx()
	private static volatile Map<Integer, Integer> statBaseClass; // canon id -> stat-class representative base
	private static volatile Map<Integer, List<Integer>> displayVariants; // base -> its same-stat variation members
	private static volatile Map<Integer, String> variantNames; // variant id -> display name, for variant ordering
	// base -> variants(base), filled on first ask. The order can't change once the index is built, and the resolver
	// asks for it per member on every bank refresh. Set with the index; null (no caching) before it.
	private static volatile Map<Integer, List<Integer>> sortedVariants;

	// A count in a variant name, either bracketed CHARGES ("Amulet of glory(6)", "Amulet of glory (t4)", "Ring of
	// wealth (i5)", "Black mask (10) (i)": letter in group 1, count in 2, trailing tags in 3) or a bare DURABILITY
	// number ("Dharok's helm 75": count in 4, tags in 5). The two forms mean opposite things for the plain-named
	// sibling: "Amulet of glory" is the EMPTY amulet, "Dharok's helm" is the NEW, undegraded helm.
	private static final Pattern CHARGE = Pattern.compile(
		"\\s*\\(([a-z]?)(\\d+)\\)((?:\\s*\\([a-z]+\\))*)\\s*$|\\s(\\d+)((?:\\s*\\([a-z]+\\))*)\\s*$",
		Pattern.CASE_INSENSITIVE);
	// A version with no charges left: the empty and uncharged ones, and the broken / inactive states (STATE). It never
	// names an entry, and a series of one working and one empty version is still a charge series.
	private static final Pattern NO_CHARGE = Pattern.compile(
		"uncharged|inactive|broken|damaged|deactivated|unpowered|dormant|degraded|\\(deg\\)|\\(empty\\)|\\(u\\)",
		Pattern.CASE_INSENSITIVE);

	private ItemIndex()
	{
	}

	public static boolean isReady()
	{
		return entries != null;
	}

	/** Builds the index once (no-op if already built). MUST be called on the client thread. */
	public static void build(Client client, ItemManager itemManager)
	{
		if (entries != null)
		{
			return;
		}

		try
		{
			int count = client.getItemCount();

			// Exclusions and splits (ItemFilters). The non-equippable-twin rule compares an item's name against every
			// equippable item's name, so collect those first. Notes, placeholders and worn copies are skipped here:
			// they canonicalize to the real item, which is what gets judged. Every id's name and canonical id are kept
			// for the later passes: ItemManager caches only a handful of compositions, so asking it again would decode
			// every item a second time on the client thread.
			String[] nameById = new String[count]; // null = no composition or a blank name
			int[] canonById = new int[count];
			Map<Integer, String> realNames = new HashMap<>();
			Set<Integer> equippable = new HashSet<>();
			Set<String> equippableNames = new HashSet<>();
			for (int id = 0; id < count; id++)
			{
				ItemComposition comp = itemManager.getItemComposition(id);
				if (comp == null || isBlank(comp.getName()))
				{
					continue;
				}
				nameById[id] = comp.getName();
				canonById[id] = itemManager.canonicalize(id);
				if (canonById[id] != id)
				{
					continue;
				}
				realNames.put(id, comp.getName());
				if (equipmentOf(itemManager, id) != null)
				{
					equippable.add(id);
					equippableNames.add(comp.getName().toLowerCase(Locale.ROOT));
				}
			}
			Set<Integer> excluded = new HashSet<>();
			Set<Integer> split = new HashSet<>();
			for (Map.Entry<Integer, String> e : realNames.entrySet())
			{
				int id = e.getKey();
				if (ItemFilters.isExcluded(id, e.getValue(), equippable.contains(id), equippableNames))
				{
					excluded.add(id);
				}
				else if (ItemFilters.isSplit(e.getValue()) && !isChargeState(itemManager, id, e.getValue(), realNames))
				{
					split.add(id);
				}
			}

			// Partition every variation group into stat-classes: items with identical equipment stats become one base,
			// so same-stat versions stay one item (a corrupted bow + its charge states, degrade states) while a
			// stat-changing imbue (a DK ring (i)) splits into its own base. Each item maps to its class's representative
			// (lowest id); the cosmetic-recolor fold (canonicalBase) is layered on afterwards. Excluded items get no
			// base of their own; partitionStatClasses maps them to their group's item for ownership only.
			Map<Integer, Integer> statBaseMap = new HashMap<>(); // canon id -> its stat-class representative
			Map<Integer, List<Integer>> displayVars = new HashMap<>(); // rep -> the members of its class
			Set<Integer> bases = new HashSet<>();
			Set<Integer> seenGroups = new HashSet<>();
			for (int id = 0; id < count; id++)
			{
				if (nameById[id] == null)
				{
					continue;
				}
				int canon = canonById[id];
				int group = ItemVariationMapping.map(canon);
				if (seenGroups.add(group))
				{
					partitionStatClasses(itemManager, group, statBaseMap, displayVars, bases, excluded, split);
				}
				if (excluded.contains(canon))
				{
					continue; // never an entry or a variant
				}
				if (!statBaseMap.containsKey(canon)) // safety net: every item gets a base
				{
					statBaseMap.put(canon, canon);
					bases.add(canon);
				}
			}

			List<Entry> list = new ArrayList<>(bases.size());
			Map<Integer, Integer> slots = new HashMap<>(); // equippable bases -> slot, for member-slot auto-detect
			for (int base : bases)
			{
				ItemComposition comp = itemManager.getItemComposition(base);
				String name = comp == null ? null : comp.getName();
				if (isBlank(name))
				{
					continue;
				}
				ItemEquipmentStats eq = equipmentOf(itemManager, base);
				Entry entry = new Entry(base, name, base, eq != null ? eq.getSlot() : NOT_EQUIPPABLE);
				entry.split = split.contains(base);
				entry.consumable = eq == null && hasConsumeAction(comp);
				list.add(entry);
				if (eq != null)
				{
					slots.put(base, eq.getSlot());
				}
			}

			// Fold the hand-kept cosmetic recolors onto their item's entry ("Sanguine torva full helm" -> "Torva full
			// helm"). Both ends go through the stat classes, so the fold lands on the entry that serves each item.
			Set<Integer> listed = new HashSet<>();
			for (Entry e : list)
			{
				listed.add(e.id);
			}
			Map<Integer, Integer> merge = new HashMap<>();
			for (Map.Entry<Integer, Integer> r : ItemFilters.recolors().entrySet())
			{
				Integer from = statBaseMap.get(r.getKey());
				Integer to = statBaseMap.get(r.getValue());
				if (from != null && to != null && !from.equals(to) && listed.contains(from) && listed.contains(to)
					&& !split.contains(from))
				{
					merge.put(from, to);
				}
			}
			// The hand-paired broken states fold the same way, but aren't search results of their own.
			Set<Integer> stateFolded = new HashSet<>();
			for (Map.Entry<Integer, Integer> r : ItemFilters.statePairs().entrySet())
			{
				Integer from = statBaseMap.get(r.getKey());
				Integer to = statBaseMap.get(r.getValue());
				if (from != null && to != null && !from.equals(to) && listed.contains(from) && listed.contains(to))
				{
					merge.put(from, to);
					stateFolded.add(from);
				}
			}
			list.removeIf(e -> stateFolded.contains(e.id));

			// Resolve chains so every folded id points at its ultimate canonical, then invert for variant display.
			Map<Integer, List<Integer>> folded = new HashMap<>();
			for (Integer k : new ArrayList<>(merge.keySet()))
			{
				int root = merge.get(k);
				Set<Integer> seen = new HashSet<>();
				while (merge.containsKey(root) && seen.add(root))
				{
					root = merge.get(root);
				}
				merge.put(k, root);
				folded.computeIfAbsent(root, x -> new ArrayList<>()).add(k);
			}
			// NOTE: folded variants stay in the search list (every d'hide colour is individually searchable);
			// the collapse happens at ADD time, where the picked variant is filed under its canonical base.

			// The display name of every id that can appear as a variant, for variant ordering (VariantSort: an
			// empty plain-named version, and the shortest name as the tiebreak: "Torva full helm" before "Sanguine
			// torva full helm"), taken from the first pass's names.
			Map<Integer, String> names = new HashMap<>();
			for (Map.Entry<Integer, List<Integer>> e : displayVars.entrySet())
			{
				putName(nameById, names, e.getKey());
				for (int id : e.getValue())
				{
					putName(nameById, names, id);
				}
			}
			for (Map.Entry<Integer, List<Integer>> e : folded.entrySet())
			{
				putName(nameById, names, e.getKey());
				for (int id : e.getValue())
				{
					putName(nameById, names, id);
				}
			}

			// An entry is named after its lowest-id plain version (entryLabel): "Serpentine helm", not the "Magma helm" it
			// serves first; "Asgarnian ale", not "Asgarnian ale(m)". A charge series drops the count ("Stamina potion")
			// and shows its top variant's icon (the (4)). Only the base's own variation members count: a folded recolor
			// such as Amulet of eternal glory (unlimited charges) is never the series' top. The ordering reads names
			// through variantName(), so they're published here, ahead of the rest; until entries is set nothing serves
			// variants from them.
			variantNames = names;
			for (Entry e : list)
			{
				List<Integer> series = displayVars.getOrDefault(e.id, Collections.emptyList());
				if (series.isEmpty())
				{
					continue;
				}
				boolean charged = isChargeOrdered(series);
				if (charged)
				{
					int top = VariantSort.sort(series).get(0);
					if (names.get(top) != null)
					{
						e.displayId = top;
					}
				}
				String label = entryLabel(series, names);
				if (label != null)
				{
					e.name = charged ? withoutCount(label) : label;
				}
			}
			list.sort(Comparator.comparing(e -> e.name.toLowerCase(Locale.ROOT)));

			// A folded recolor is a cosmetic entry, hidden as its own result when "Cosmetic variants" is off.
			for (Entry e : list)
			{
				e.flags = merge.containsKey(e.id) ? ItemFilters.COSMETIC : 0;
			}

			mergeToCanonical = merge; // publish before entries so canonicalBase is ready once isReady() is true
			foldedMembers = folded;
			slotByBase = slots;
			statBaseClass = statBaseMap;
			displayVariants = displayVars;
			variantNames = names;
			sortedVariants = new ConcurrentHashMap<>(); // read from the client thread and the panel's Swing thread
			entries = list; // publish LAST: isReady() flips true only once every other map is set
		}
		catch (Throwable t)
		{
			// Leave entries null so isReady() stays false and a later build() call retries cleanly (the
			// early-return guard at the top no-ops only once entries is published). All fields are local
			// until the publish block above, so a failure here cannot leave a half-built state.
			log.warn("ItemIndex build failed; will retry on next invocation", t);
		}
	}

	/**
	 * The name an entry is listed under: its lowest-id plain version's, skipping empty, uncharged and broken states
	 * ({@link #NO_CHARGE}), unfinished "(unf)" and "(Deadman)" versions; the lowest id of all when every version is one
	 * of those. Null when no version has a name.
	 */
	private static String entryLabel(List<Integer> series, Map<Integer, String> names)
	{
		int plain = Integer.MAX_VALUE;
		int any = Integer.MAX_VALUE;
		for (int id : series)
		{
			String name = names.get(id);
			if (name == null)
			{
				continue;
			}
			any = Math.min(any, id);
			if (!NO_CHARGE.matcher(name).find() && !NAME_SKIP.matcher(name).find())
			{
				plain = Math.min(plain, id);
			}
		}
		int source = plain != Integer.MAX_VALUE ? plain : any;
		return source == Integer.MAX_VALUE ? null : names.get(source);
	}

	/** One stat-class of a variation group: members with identical equipment stats. */
	private static final class StatClass
	{
		final ItemEquipmentStats eq; // null = not equippable
		final boolean split;         // a single ItemFilters split item, never merged with anything
		int rep;                     // lowest member id
		final List<Integer> members = new ArrayList<>();

		StatClass(ItemEquipmentStats eq, boolean split, int first)
		{
			this.eq = eq;
			this.split = split;
			this.rep = first;
		}

		boolean hasBonuses()
		{
			return !split && eq != null && ItemIndex.hasBonuses(eq);
		}
	}

	/**
	 * Partitions one variation group into stat-classes (members with identical equipment stats), assigning every
	 * member to its class's representative (lowest id), and records the class's members as that base's selectable
	 * variants. Each representative becomes a base, so same-stat versions stay one item while a
	 * different-stats imbue splits off. Groups are tiny, so the pairwise stats compare needs no hashing.
	 *
	 * <p>Four adjustments: a split item is always a class of its own; an equippable class with no combat bonuses in
	 * the main class's slot (a broken "Dharok's greataxe 0", a "Blood Moon helm (broken)") is a broken state rather
	 * than a different-stats version, so it joins the main class (the one holding the group's base item, else the
	 * first class with bonuses); excluded members get no class at all, only a mapping to the main class's
	 * representative so an owned copy still counts as that item; and a class of nothing but broken / uncharged states
	 * joins its working item ({@link #joinStates}). An ornament "(or)" version shares its item's stats, so it's already
	 * in its class; one that shares another class's stats stays there (Elidinis' ward (or) ornaments the fortified
	 * ward, not the plain). Every class
	 * takes its representative from a working version when it has one ({@link #preferWorkingRep}), so a state with a
	 * lower id never becomes the base a combo stores.
	 */
	private static void partitionStatClasses(ItemManager itemManager, int group, Map<Integer, Integer> statBaseMap,
		Map<Integer, List<Integer>> displayVars, Set<Integer> bases, Set<Integer> excluded, Set<Integer> split)
	{
		Collection<Integer> raw = ItemVariationMapping.getVariations(group);
		LinkedHashSet<Integer> members = new LinkedHashSet<>();
		if (raw.isEmpty())
		{
			members.add(group);
		}
		else
		{
			for (int m : raw)
			{
				members.add(itemManager.canonicalize(m));
			}
		}
		List<Integer> excludedMembers = new ArrayList<>();
		List<StatClass> classes = new ArrayList<>();
		for (int mc : members)
		{
			if (excluded.contains(mc))
			{
				excludedMembers.add(mc);
				continue;
			}
			ItemEquipmentStats e = equipmentOf(itemManager, mc);
			StatClass c = null;
			if (!split.contains(mc))
			{
				for (StatClass x : classes)
				{
					if (!x.split && Objects.equals(x.eq, e))
					{
						c = x;
						break;
					}
				}
			}
			if (c == null)
			{
				c = new StatClass(e, split.contains(mc), mc);
				classes.add(c);
			}
			else if (mc < c.rep)
			{
				c.rep = mc;
			}
			c.members.add(mc);
		}

		StatClass main = null;
		for (StatClass c : classes)
		{
			if (c.hasBonuses() && c.members.contains(group))
			{
				main = c;
				break;
			}
		}
		if (main == null)
		{
			main = classes.stream().filter(StatClass::hasBonuses).findFirst().orElse(null);
		}
		if (main != null)
		{
			int slot = main.eq.getSlot();
			for (Iterator<StatClass> it = classes.iterator(); it.hasNext(); )
			{
				StatClass c = it.next();
				if (c != main && !c.split && c.eq != null && !hasBonuses(c.eq) && c.eq.getSlot() == slot)
				{
					main.members.addAll(c.members); // the main class keeps its own representative
					it.remove();
				}
			}
		}

		joinStates(itemManager, classes, group);
		for (StatClass c : classes)
		{
			preferWorkingRep(itemManager, c);
		}
		if (main != null && !classes.contains(main)) // the main class was a state class that joined its item
		{
			main = classes.stream().filter(c -> c.members.contains(group)).findFirst().orElse(null);
		}

		for (StatClass c : classes)
		{
			bases.add(c.rep);
			for (int mc : c.members)
			{
				statBaseMap.put(mc, c.rep);
			}
			// Its members are the base's selectable variants: a charged/degraded item's states, or a potion's doses
			// (each dose a variant of the 4-dose base, not the 4-dose alone).
			displayVars.put(c.rep, new ArrayList<>(c.members));
		}

		StatClass owner = main != null ? main : classes.stream().filter(c -> !c.split).findFirst().orElse(null);
		if (owner != null)
		{
			for (int mc : excludedMembers)
			{
				statBaseMap.put(mc, owner.rep); // ownership only: never a base or a variant
			}
		}
	}

	/**
	 * Moves each class made only of broken / uncharged states into its working item's class: the class holding the
	 * same name without the state ("Fire cape (broken)" into "Fire cape"'s, "Void knight top (l) (broken)" into the
	 * class holding "Void knight top (l)"), else the group's main working class in the same slot ("Toxic staff
	 * (uncharged)" into Toxic Staff of the Dead's). States with their item's stats are already in its class; these are
	 * the ones whose stats drop. Client thread.
	 */
	private static void joinStates(ItemManager itemManager, List<StatClass> classes, int group)
	{
		for (Iterator<StatClass> it = classes.iterator(); it.hasNext(); )
		{
			StatClass c = it.next();
			if (c.split || !allStates(itemManager, c))
			{
				continue;
			}
			Set<String> plain = new HashSet<>();
			for (int m : c.members)
			{
				plain.add(STATE.matcher(nameOf(itemManager, m)).replaceAll("").trim().toLowerCase(Locale.ROOT));
			}
			StatClass host = null;
			for (StatClass x : classes)
			{
				if (x != c && !x.split && !allStates(itemManager, x) && containsName(itemManager, x.members, plain))
				{
					host = x;
					break;
				}
			}
			if (host == null)
			{
				host = mainWorkingClass(itemManager, classes, c, group);
			}
			if (host != null)
			{
				host.members.addAll(c.members);
				it.remove();
			}
		}
	}

	/** The group's main working class for a state class: same slot (or both unwearable), the group base's if any. */
	private static StatClass mainWorkingClass(ItemManager itemManager, List<StatClass> classes, StatClass state, int group)
	{
		StatClass first = null;
		for (StatClass x : classes)
		{
			boolean sameKind = state.eq == null ? x.eq == null : x.eq != null && x.eq.getSlot() == state.eq.getSlot();
			if (x == state || x.split || !sameKind || allStates(itemManager, x))
			{
				continue;
			}
			if (x.members.contains(group))
			{
				return x;
			}
			if (first == null || (!first.hasBonuses() && x.hasBonuses()))
			{
				first = x;
			}
		}
		return first;
	}

	/** Whether every member of a class is a broken / uncharged state. Client thread. */
	private static boolean allStates(ItemManager itemManager, StatClass c)
	{
		for (int m : c.members)
		{
			String name = nameOf(itemManager, m);
			if (name == null || !STATE.matcher(name).find())
			{
				return false;
			}
		}
		return !c.members.isEmpty();
	}

	/**
	 * Takes a class's representative (its base id, which combos store) from a working version when it has one, so
	 * a broken or uncharged state with a lower id ("Torva full helm (damaged)", "Warped sceptre (uncharged)") never
	 * becomes the entry. Client thread.
	 */
	private static void preferWorkingRep(ItemManager itemManager, StatClass c)
	{
		String repName = nameOf(itemManager, c.rep);
		if (repName == null || !STATE.matcher(repName).find())
		{
			return;
		}
		int best = Integer.MAX_VALUE;
		for (int m : c.members)
		{
			String name = nameOf(itemManager, m);
			if (name != null && !STATE.matcher(name).find() && m < best)
			{
				best = m;
			}
		}
		if (best != Integer.MAX_VALUE)
		{
			c.rep = best;
		}
	}

	/** Whether any of {@code ids} has one of {@code lowerNames} as its display name. Client thread. */
	private static boolean containsName(ItemManager itemManager, List<Integer> ids, Set<String> lowerNames)
	{
		for (int id : ids)
		{
			String name = nameOf(itemManager, id);
			if (name != null && lowerNames.contains(name.trim().toLowerCase(Locale.ROOT)))
			{
				return true;
			}
		}
		return false;
	}

	/** An item's display name, or null when blank. Client thread. */
	private static String nameOf(ItemManager itemManager, int id)
	{
		ItemComposition comp = itemManager.getItemComposition(id);
		return comp == null || isBlank(comp.getName()) ? null : comp.getName();
	}

	/** An item's equipment stats, or null when it isn't equippable. Client thread. */
	private static ItemEquipmentStats equipmentOf(ItemManager itemManager, int id)
	{
		ItemStats s = itemManager.getItemStats(id);
		return s != null && s.isEquipable() ? s.getEquipment() : null;
	}

	/** Whether equipment has any combat bonus (everything but slot, two-handedness and attack speed). */
	private static boolean hasBonuses(ItemEquipmentStats e)
	{
		return e.getAstab() != 0 || e.getAslash() != 0 || e.getAcrush() != 0 || e.getAmagic() != 0
			|| e.getArange() != 0 || e.getDstab() != 0 || e.getDslash() != 0 || e.getDcrush() != 0
			|| e.getDmagic() != 0 || e.getDrange() != 0 || e.getStr() != 0 || e.getRstr() != 0
			|| e.getMdmg() != 0 || e.getPrayer() != 0;
	}

	/** Whether an item has an "Eat" or "Drink" inventory action. */
	private static boolean hasConsumeAction(ItemComposition comp)
	{
		String[] actions = comp.getInventoryActions();
		if (actions != null)
		{
			for (String a : actions)
			{
				if (a != null && CONSUME_ACTION.matcher(a).matches())
				{
					return true;
				}
			}
		}
		return false;
	}

	/** Equipment slot index ({@code EquipmentInventorySlot.getSlotIdx()}) for a base, or {@link #NOT_EQUIPPABLE}. */
	public static int slotOf(int baseId)
	{
		Map<Integer, Integer> m = slotByBase;
		return m == null ? NOT_EQUIPPABLE : m.getOrDefault(baseId, NOT_EQUIPPABLE);
	}

	/**
	 * The stats-aware combo base of an already-canonicalized item id: items with identical equipment stats in a
	 * variation group share one base, but a stat-changing imbue is its own base — then the cosmetic-recolor fold
	 * is applied. Used for combo membership AND bank ownership so an imbued item resolves to its own member, not
	 * the plain version. Falls back to the plain variation map before the index is built.
	 */
	public static int statBaseOfCanon(int canon)
	{
		Map<Integer, Integer> m = statBaseClass;
		int base = m != null && m.containsKey(canon) ? m.get(canon) : ItemVariationMapping.map(canon);
		return canonicalBase(base);
	}

	/** {@code getPlaceholderTemplateId()} of a bank placeholder (the layout-placeholder model id). */
	private static final int PLACEHOLDER_TEMPLATE_ID = 14401;

	/**
	 * The stats-aware combo base of a RAW item id (a bank item, a layout {@code int[]} slot, or a menu item id):
	 * a placeholder is first resolved to the real item it stands for, then canonicalized and mapped to its
	 * stat-class base + cosmetic-recolor root via {@link #statBaseOfCanon}. This is the SINGLE definition of
	 * "an item's combo base" shared by the layout scrub/membership checks (plugin) and bank-ownership/winner
	 * checks ({@link ComboResolver}), so the two can never disagree on whether an id belongs to a member.
	 * Client thread (uses {@code getItemComposition} / {@code canonicalize}).
	 */
	public static int comboBaseOf(ItemManager itemManager, int itemId)
	{
		ItemComposition comp = itemManager.getItemComposition(itemId);
		int nonPlaceholder = (comp != null && comp.getPlaceholderTemplateId() == PLACEHOLDER_TEMPLATE_ID)
			? comp.getPlaceholderId() : itemId;
		return statBaseOfCanon(itemManager.canonicalize(nonPlaceholder));
	}

	/**
	 * A base's selectable display variants in the default served order ({@link VariantSort}: the filter lab's
	 * key-based rules, then the shortest display name, then the lowest id), so the top variant is the one a combo
	 * serves until the player drags another order. The set is the base's same-stat variation
	 * members plus any folded cosmetic recolors, each with its own variants (Echo ahrim's hood and its 100..0 states).
	 * Different-stats imbues and split cosmetic versions are their own
	 * bases, and excluded versions are never variants; a DT2 ring's non-equippable base model is a class (and base)
	 * of its own, so it never shows among the equippable ring's variants. Falls back to the raw,
	 * unsorted variation set before the index is built. Once built, the list is cached and unmodifiable.
	 */
	public static List<Integer> variants(int base)
	{
		Map<Integer, List<Integer>> dv = displayVariants;
		if (dv == null)
		{
			LinkedHashSet<Integer> raw = new LinkedHashSet<>(ItemVariationMapping.getVariations(base));
			raw.add(base);
			raw.addAll(mergedVariants(base));
			return new ArrayList<>(raw);
		}
		Map<Integer, List<Integer>> cache = sortedVariants;
		List<Integer> hit = cache == null ? null : cache.get(base);
		if (hit != null)
		{
			return hit;
		}
		LinkedHashSet<Integer> ids = new LinkedHashSet<>(dv.getOrDefault(base, Collections.emptyList()));
		// A folded recolor brings its own variants too: Ahrim's hood also offers Echo ahrim's hood 100..0, not just
		// the full-durability echo hood.
		for (int folded : mergedVariants(base))
		{
			ids.add(folded);
			ids.addAll(dv.getOrDefault(folded, Collections.emptyList()));
		}
		if (ids.isEmpty())
		{
			ids.add(base);
		}
		List<Integer> sorted = Collections.unmodifiableList(VariantSort.sort(new ArrayList<>(ids)));
		if (cache != null)
		{
			cache.put(base, sorted);
		}
		return sorted;
	}

	/** Whether a name carries a count: bracketed ("Amulet of glory(4)", "(t4)", "(i5)") or trailing ("Dharok's helm 75"). */
	static boolean hasCount(String name)
	{
		return CHARGE.matcher(name).find();
	}

	/** Whether a name carries a bracketed count ("Amulet of glory(4)"), not just a trailing one ("Dharok's helm 75"). */
	static boolean hasBracketedCount(String name)
	{
		Matcher m = CHARGE.matcher(name);
		return m.find() && m.group(2) != null;
	}

	/**
	 * A name without its charge count, keeping any letter tag: "Stamina potion(4)" -&gt; "Stamina potion", "Amulet of
	 * glory (t4)" -&gt; "Amulet of glory (t)", "Black mask (10) (i)" -&gt; "Black mask (i)". Trimmed, otherwise unchanged.
	 */
	static String withoutCount(String name)
	{
		Matcher m = CHARGE.matcher(name);
		if (!m.find())
		{
			return name.trim();
		}
		boolean bracketed = m.group(2) != null;
		String tail = bracketed ? m.group(3) : m.group(5);
		return (name.substring(0, m.start()) + (bracketed && !m.group(1).isEmpty() ? " (" + m.group(1) + ")" : "")
			+ (tail == null ? "" : tail)).trim();
	}

	/**
	 * Whether a split-named item is really a charge state: a variation sibling carries a count in the same family, so
	 * "Amulet of glory (t)" is the empty end of the (t1)..(t6) series rather than a cosmetic "(t)" version, and stays
	 * one of the glory's variants. Client thread.
	 *
	 * @param realNames display names of every real (canonical) item
	 */
	private static boolean isChargeState(ItemManager itemManager, int id, String name, Map<Integer, String> realNames)
	{
		String plain = name.trim();
		for (int m : ItemVariationMapping.getVariations(ItemVariationMapping.map(id)))
		{
			String other = realNames.get(itemManager.canonicalize(m));
			if (other != null && CHARGE.matcher(other).find() && withoutCount(other).equalsIgnoreCase(plain))
			{
				return true;
			}
		}
		return false;
	}

	/** Records {@code id}'s display name (once) for variant ordering, from the build's scan of every item. */
	private static void putName(String[] nameById, Map<Integer, String> names, int id)
	{
		String name = id >= 0 && id < nameById.length ? nameById[id] : null;
		if (name != null)
		{
			names.putIfAbsent(id, name);
		}
	}

	/** A variant's display name, or null if unknown (index not built, or not a variant). */
	public static String variantName(int id)
	{
		Map<Integer, String> n = variantNames;
		return n == null ? null : n.get(id);
	}

	/**
	 * Whether a variant list is a charge series: at least two members carry a count, like glory(1)..(6), or it pairs
	 * an uncharged version with a charged one ("Webweaver bow (u)" / "Webweaver bow"). Such an entry is listed under
	 * its name without the count, with its top variant's icon.
	 */
	public static boolean isChargeOrdered(List<Integer> variants)
	{
		int counted = 0;
		int empty = 0;
		int named = 0;
		for (int id : variants)
		{
			String name = variantName(id);
			if (name == null)
			{
				continue;
			}
			named++;
			if (CHARGE.matcher(name).find())
			{
				counted++;
			}
			else if (NO_CHARGE.matcher(name).find())
			{
				empty++;
			}
		}
		return counted >= 2 || (empty >= 1 && named > empty);
	}

	/**
	 * The canonical base for a (already {@link ItemVariationMapping}-mapped) base id: its stat-duplicate
	 * root if it was folded, else the id unchanged. Safe (identity) before the index is built.
	 */
	public static int canonicalBase(int baseId)
	{
		Map<Integer, Integer> m = mergeToCanonical;
		return m == null ? baseId : m.getOrDefault(baseId, baseId);
	}

	/**
	 * Item ids folded into the given canonical base (stat-duplicate / recolor cosmetics) — the extra
	 * selectable display "variants" for that base (each is still individually searchable; the collapse
	 * happens at add time). Empty before the index is built or for an unmerged base.
	 */
	public static List<Integer> mergedVariants(int canonicalBaseId)
	{
		Map<Integer, List<Integer>> m = foldedMembers;
		List<Integer> v = m == null ? null : m.get(canonicalBaseId);
		return v == null ? Collections.emptyList() : v;
	}

	/**
	 * Substring search by name (prefix matches first), up to {@code limit} results, restricted to
	 * {@code slotFilter}: a specific {@code EquipmentInventorySlot.getSlotIdx()}, {@link #SLOT_EQUIPPABLE}
	 * (any gear), {@link #SLOT_CONSUMABLE}, {@link #SLOT_MISC}, or {@link #SLOT_ANY} (no restriction), skipping
	 * entries in any {@link ItemFilters} category set in {@code hiddenFlags}.
	 */
	public static List<Entry> search(String query, int limit, int slotFilter, int hiddenFlags)
	{
		List<Entry> all = entries;
		if (all == null || isBlank(query))
		{
			return Collections.emptyList();
		}

		String q = query.trim().toLowerCase(Locale.ROOT);
		List<Entry> prefix = new ArrayList<>();
		List<Entry> contains = new ArrayList<>();
		for (Entry e : all)
		{
			if (!matchesSlot(e, slotFilter) || (e.flags & hiddenFlags) != 0)
			{
				continue;
			}
			String lower = e.name.toLowerCase(Locale.ROOT);
			if (lower.startsWith(q))
			{
				prefix.add(e);
			}
			else if (lower.contains(q))
			{
				contains.add(e);
			}
		}
		prefix.addAll(contains);
		return prefix.size() > limit ? new ArrayList<>(prefix.subList(0, limit)) : prefix;
	}

	private static boolean matchesSlot(Entry e, int slotFilter)
	{
		switch (slotFilter)
		{
			case SLOT_ANY:
				return true;
			case SLOT_EQUIPPABLE:
				return e.equipSlot != NOT_EQUIPPABLE;
			case SLOT_CONSUMABLE:
				return e.consumable;
			case SLOT_MISC:
				return e.equipSlot == NOT_EQUIPPABLE && !e.consumable;
			default:
				return e.equipSlot == slotFilter;
		}
	}

	private static boolean isBlank(String s)
	{
		return s == null || s.isEmpty() || "null".equalsIgnoreCase(s);
	}
}
