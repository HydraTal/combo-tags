package com.combotags;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;

/**
 * Resolves a combo group's "winner": from the group's ordered member list ({@link ComboGroup#members},
 * highest priority first), the highest-priority item the player currently has in the bank. If none are
 * in the bank, the top-priority member is returned as a "goal" (renders as a ghost), using the panel's
 * chosen display variant.
 */
@Slf4j
@RequiredArgsConstructor
public class ComboResolver
{
	private final ComboTagsPlugin plugin;

	// A base id -> actual bank item id snapshot, rebuilt lazily and reused until the bank changes. resolveWinner
	// runs per cell across every host tab on each bank refresh (plus reconcile/sync/render the same tick), so
	// without this each call would rescan the whole bank container. Invalidated from onItemContainerChanged(BANK).
	// Client-thread only (every reader canonicalizes, which asserts the client thread).
	private Map<Integer, Integer> bankByBaseCache;
	// canonicalize(id) -> actual bank item id, VARIANT-level (distinguishes recolors / charge states that share a
	// base). Drives per-member variant priority. Built and invalidated alongside bankByBaseCache.
	private Map<Integer, Integer> bankByVariantCache;
	// As above, but ALSO counting leftover bank placeholders (mapped to the real item they stand for). Used only by
	// resolveRestoreItem: when a combo cell is deleted, a placeholder still means "you have this", so it's restored.
	private Map<Integer, Integer> bankByBasePresentCache;
	private Map<Integer, Integer> bankByVariantPresentCache;

	// comboName -> canonical bases of that combo that ALSO appear in another combo of the SAME non-empty category.
	// Such a base is "contested": no single cell can own it without two cells fighting over the one physical item,
	// so it is excluded from winner resolution AND membership/scrub — the item is left unmanaged by the combos.
	// Cached against the combos-JSON string (any save writes a new string → the key mismatches and we recompute).
	private volatile String sharedCacheJson;
	private volatile Map<String, Set<Integer>> sharedBasesByCombo;

	/** Drops the cached bank snapshots. Call when the BANK container changes so the next resolve rescans. */
	public void invalidateBankCache()
	{
		bankByBaseCache = null;
		bankByVariantCache = null;
		bankByBasePresentCache = null;
		bankByVariantPresentCache = null;
	}

	/**
	 * The item id a combo "smart cell" should display: the highest-priority member present IN THE BANK
	 * (returned as the real bank item id so it renders solid), else the top-priority member as a ghost
	 * goal. Returns {@code -1} if the group is unknown or has no members.
	 */
	public int resolveWinner(String name)
	{
		// READ-ONLY: only reads members / variantOrder / displayIconId, so the shared cached snapshot is safe.
		ComboGroup group = ComboStore.cachedGet(plugin.configManager, plugin.gson, name);
		if (group == null || group.members.isEmpty())
		{
			return -1;
		}

		Map<Integer, Integer> bankByBase = bankItemsByBase();
		Map<Integer, Integer> bankByVariant = bankItemsByVariant();
		Set<Integer> shared = sharedBases(name);
		for (Integer base : group.members)
		{
			// A base shared with another combo in this category is never assigned to a cell (see sharedBases).
			if (shared.contains(ItemIndex.canonicalBase(base)))
			{
				continue;
			}
			// If the user gave this member a variant priority order, the highest-priority OWNED variant wins
			// (variant-level match, so a specific recolor/version is preferred when several are held).
			List<Integer> order = group.variantOrder == null ? null : group.variantOrder.get(base);
			if (order != null)
			{
				for (Integer variant : order)
				{
					Integer actual = bankByVariant.get(plugin.itemManager.canonicalize(variant));
					if (actual != null)
					{
						return actual;
					}
				}
			}
			// Default / fallback: any owned variant of this member counts. Canonicalize the member too, so a
			// member stored as a stat-duplicate still matches the bank (which is keyed by the canonical base).
			Integer actual = bankByBase.get(ItemIndex.canonicalBase(base));
			if (actual != null)
			{
				return actual;
			}
		}
		// None in the bank → ghost: the combo's favorite icon (or the top member's chosen variant).
		// Guard against a corrupt/imported variantOrder mapping a base to 0/negative: displayIconId() can
		// then be non-positive, which callers' (winner > 0) checks would mishandle (a negative id could be
		// pinned). Collapse any non-positive result to the documented -1 "unknown/no members" sentinel.
		int ghost = group.displayIconId();
		return ghost > 0 ? ghost : -1;
	}

	/**
	 * The item to leave behind when a combo's cell is removed (on delete): the highest-priority member you have
	 * in the bank as a real item OR a leftover bank placeholder (returned as the real item id, so it relays as the
	 * item or its placeholder). Only if NONE of the combo's items are in the bank at all does it fall back to the
	 * combo's goal — its favorite, else its top member. Returns {@code -1} if the combo is unknown or empty.
	 */
	public int resolveRestoreItem(String name)
	{
		ComboGroup group = ComboStore.cachedGet(plugin.configManager, plugin.gson, name);
		if (group == null || group.members.isEmpty())
		{
			return -1;
		}
		Map<Integer, Integer> byBase = bankItemsByBasePresent();
		Map<Integer, Integer> byVariant = bankItemsByVariantPresent();
		for (Integer base : group.members)
		{
			List<Integer> order = group.variantOrder == null ? null : group.variantOrder.get(base);
			if (order != null)
			{
				for (Integer variant : order)
				{
					Integer actual = byVariant.get(plugin.itemManager.canonicalize(variant));
					if (actual != null)
					{
						return actual;
					}
				}
			}
			Integer actual = byBase.get(ItemIndex.canonicalBase(base));
			if (actual != null)
			{
				return actual;
			}
		}
		int goal = group.displayIconId();
		return goal > 0 ? goal : -1;
	}

	/** Whether a real (non-placeholder, qty&gt;0) copy of the given item is currently in the bank. */
	public boolean isOwnedReal(int itemId)
	{
		Map<Integer, Integer> byBase = bankItemsByBase(); // also ensures the stat-duplicate merge map is built
		int base = ItemIndex.comboBaseOf(plugin.itemManager, itemId); // shared base definition (see ItemIndex.comboBaseOf)
		return byBase.containsKey(base);
	}

	/**
	 * A group's member base ids in priority order (the manual list order), canonicalized to their
	 * stat-duplicate roots so callers comparing tab/bank item bases line up. Used for membership/scrub
	 * comparison sets — NOT for variant display (which keys off the raw stored base).
	 */
	public List<Integer> orderedMemberBases(String name)
	{
		// READ-ONLY: only reads group.members (copied into a fresh list below), so the cached snapshot is safe.
		ComboGroup group = ComboStore.cachedGet(plugin.configManager, plugin.gson, name);
		if (group == null)
		{
			return new ArrayList<>();
		}
		Set<Integer> shared = sharedBases(name);
		List<Integer> bases = new ArrayList<>(group.members.size());
		for (int base : group.members)
		{
			int canon = ItemIndex.canonicalBase(base);
			if (!shared.contains(canon)) // a contested (shared) base is left unmanaged: not a member for scrub/placement
			{
				bases.add(canon);
			}
		}
		return bases;
	}

	/**
	 * Canonical bases of {@code comboName} that are SHARED with at least one other combo in the same non-empty
	 * category, and so are excluded from winner resolution and membership. Empty for an ungrouped combo or one
	 * whose items are unique within its group. Cached against the combos-JSON (auto-refreshes on any combo edit).
	 */
	public Set<Integer> sharedBases(String comboName)
	{
		String json = plugin.configManager.getConfiguration(ComboTagsPlugin.CONFIG_GROUP, ComboStore.CONFIG_KEY);
		String key = json == null ? "" : json;
		Map<String, Set<Integer>> cache = sharedBasesByCombo;
		if (cache == null || !key.equals(sharedCacheJson))
		{
			cache = computeSharedBases();
			sharedBasesByCombo = cache;
			sharedCacheJson = key;
		}
		return cache.getOrDefault(comboName, Collections.emptySet());
	}

	/** Builds {@code comboName -> shared canonical bases} by counting, per category, how many combos hold each base. */
	private Map<String, Set<Integer>> computeSharedBases()
	{
		List<ComboGroup> all = ComboStore.cachedAll(plugin.configManager, plugin.gson);
		// Per non-empty category: canonical base -> number of distinct combos that contain it.
		Map<String, Map<Integer, Integer>> countByCat = new HashMap<>();
		for (ComboGroup g : all)
		{
			if (g.category == null || g.category.isEmpty())
			{
				continue;
			}
			Map<Integer, Integer> counts = countByCat.computeIfAbsent(g.category, k -> new HashMap<>());
			Set<Integer> seen = new HashSet<>(); // a single combo listing an item twice must not self-conflict
			for (int base : g.members)
			{
				int canon = ItemIndex.canonicalBase(base);
				if (seen.add(canon))
				{
					counts.merge(canon, 1, Integer::sum);
				}
			}
		}
		Map<String, Set<Integer>> result = new HashMap<>();
		for (ComboGroup g : all)
		{
			if (g.category == null || g.category.isEmpty())
			{
				continue;
			}
			Map<Integer, Integer> counts = countByCat.get(g.category);
			Set<Integer> shared = new HashSet<>();
			for (int base : g.members)
			{
				int canon = ItemIndex.canonicalBase(base);
				if (counts.getOrDefault(canon, 0) >= 2)
				{
					shared.add(canon);
				}
			}
			if (!shared.isEmpty())
			{
				result.put(g.name, shared);
			}
		}
		return result;
	}

	/** Maps base id → an actual item id currently in the bank (excludes empty slots and placeholders). */
	private Map<Integer, Integer> bankItemsByBase()
	{
		ensureBankSnapshot();
		return bankByBaseCache != null ? bankByBaseCache : new HashMap<>();
	}

	/** Maps {@code canonicalize(id)} → an actual bank item id, distinguishing variants that share a base. */
	private Map<Integer, Integer> bankItemsByVariant()
	{
		ensureBankSnapshot();
		return bankByVariantCache != null ? bankByVariantCache : new HashMap<>();
	}

	/** Like {@link #bankItemsByBase} but also counting leftover bank placeholders (for restore-on-delete). */
	private Map<Integer, Integer> bankItemsByBasePresent()
	{
		ensureBankSnapshot();
		return bankByBasePresentCache != null ? bankByBasePresentCache : new HashMap<>();
	}

	/** Like {@link #bankItemsByVariant} but also counting leftover bank placeholders (for restore-on-delete). */
	private Map<Integer, Integer> bankItemsByVariantPresent()
	{
		ensureBankSnapshot();
		return bankByVariantPresentCache != null ? bankByVariantPresentCache : new HashMap<>();
	}

	/** Builds both bank snapshots (base-level and variant-level) once, until the next {@link #invalidateBankCache}. */
	private void ensureBankSnapshot()
	{
		if (bankByBaseCache != null)
		{
			return;
		}
		// Ensure the stat-duplicate merge map exists so cosmetic recolors (e.g. Sanguine Torva) key to the
		// same base as their member; no-op once built. Safe here — the resolver is client-thread only.
		ItemIndex.build(plugin.client, plugin.itemManager);
		ItemContainer bank = plugin.client.getItemContainer(InventoryID.BANK);
		if (bank == null)
		{
			// The bank just isn't loaded yet (no ItemContainerChanged to invalidate on) → don't cache; retry later.
			return;
		}
		Map<Integer, Integer> byBase = new HashMap<>();          // real, owned items only (drives cell display)
		Map<Integer, Integer> byVariant = new HashMap<>();
		Map<Integer, Integer> byBasePresent = new HashMap<>();   // + bank placeholders, keyed to their real item
		Map<Integer, Integer> byVariantPresent = new HashMap<>();
		for (Item item : bank.getItems())
		{
			int id = item.getId();
			if (id < 0)
			{
				continue;
			}
			boolean placeholder = plugin.isPlaceholder(id);
			// The real item this slot represents: itself, or the item a leftover bank placeholder stands for.
			int realId = placeholder ? plugin.itemManager.getItemComposition(id).getPlaceholderId() : id;
			if (!placeholder && item.getQuantity() <= 0)
			{
				continue; // genuinely empty slot
			}
			if (realId <= 0)
			{
				continue;
			}
			int canon = plugin.itemManager.canonicalize(realId);
			int base = ItemIndex.comboBaseOf(plugin.itemManager, realId);
			byVariantPresent.putIfAbsent(canon, realId);
			byBasePresent.putIfAbsent(base, realId);
			// A placeholder (count 0) means you don't actually own it → excluded from the cell-display maps.
			if (!placeholder)
			{
				byVariant.putIfAbsent(canon, realId);
				byBase.putIfAbsent(base, realId);
			}
		}
		bankByVariantCache = byVariant;
		bankByBaseCache = byBase;
		bankByVariantPresentCache = byVariantPresent;
		bankByBasePresentCache = byBasePresent;
	}
}
