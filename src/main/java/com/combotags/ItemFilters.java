package com.combotags;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.gameval.ItemID;

/**
 * Which items the side panel's item search leaves out, keeps apart, or lets the config hide.
 *
 * <ul>
 * <li><b>Excluded</b> items don't exist as far as the search is concerned: never a result and never a variant of
 *   anything. They still count when owned: {@link ItemIndex} maps an excluded item to its item's entry, so a combo
 *   can resolve to the "Uncharged trident" actually in your bank. Broken and uncharged states ("Fire cape (broken)",
 *   "Serpentine helm (uncharged)") aren't excluded: they're their working item's last variants.</li>
 * <li><b>Split</b> items are always their own search entry, never a variant of another item and never folded, so
 *   each cosmetic version ("Rune platebody (g)", "Rune helm (h1)") is added to a combo on its own. A split name
 *   that is really a charge state (the empty "Amulet of glory (t)" beside "(t1)".."(t6)") is kept with its series by
 *   {@link ItemIndex}. Ornament "(or)" versions aren't split: they are variants of their item.</li>
 * <li><b>Recolors</b> ({@link #recolors()}) are cosmetic versions RuneLite's variation mapping doesn't group with
 *   their item, folded onto it from a hand-kept list: nothing in an item's name or stats tells a recolor from a
 *   different item. {@link #COSMETIC} marks them; the "Cosmetic variants" option hides them as their own results.</li>
 * </ul>
 *
 * <p>Game-mode, PvP-minigame, player-owned-house, guide, item-set and unobtainable-copy exclusions come from
 * {@code gamemode_items.tsv}, generated from Jagex's internal item names by {@code scripts/gen-gamemode-items.mjs}
 * (display names can't tell an LMS "Rune scimitar", or the unobtainable ROTTEN_AGS "Armadyl godsword", from the
 * real one). The same table carries the items that are neither wearable nor consumable and excluded by inventory
 * action (books, notes and maps; lamps; quest-only items whose only action is Destroy), worked out from the cache
 * dump when it's generated, so the search only looks ids up. The rest are matched by display name at runtime, so new
 * items are covered automatically.
 */
@Slf4j
public final class ItemFilters
{
	/** A folded recolor, hidden from search results when "Cosmetic variants" is off. */
	public static final int COSMETIC = 1;

	// Split: cosmetic versions. The space before the tag matters: trimmed skillcapes are "Strength cape(t)" and aren't
	// cosmetics; "Amulet of glory (t4)" is a charge state of the trimmed glory and stays one of its variants. "(or)"
	// isn't here: an ornament version shares its item's stats, so it is a variant of it.
	private static final Pattern SPLIT_NAME = Pattern.compile(" \\((g|t|h[1-5])\\)", Pattern.CASE_INSENSITIVE);
	// Excluded: items named as a broken or uncharged thing in their own right ("Broken axe", "Uncharged trident"). A
	// state tag on an item ("Fire cape (broken)", "Toxic staff (uncharged)") isn't excluded: ItemIndex folds that state
	// into its working item as its last variant, so an owned one still counts as the item.
	private static final Pattern BROKEN_NAME = Pattern.compile("^(broken|uncharged|damaged) ", Pattern.CASE_INSENSITIVE);
	// States whose working item is in another variation group under another name, so ItemIndex can't find it: the
	// state folds onto that item as a variant and isn't a search result of its own. None at the moment: the only one
	// was the Hardened wristbands of the arena, excluded with the rest of the PvP Arena rewards.
	private static final Map<Integer, Integer> STATE_PAIRS = Collections.emptyMap();
	// Everyday items that only share a name with an equippable event item (EGG vs SCRAMBLED_EGG), so the
	// non-equippable-twin rule must not exclude them.
	private static final Set<Integer> TWIN_EXCEPTIONS = new HashSet<>(Arrays.asList(
		ItemID.EGG, ItemID.GRAIN, ItemID.ORANGE, ItemID.BEER_GLASS));

	// Recolors: a cosmetic version -> the item it is a recolor of. Hand-kept because no rule can find them: a recolor
	// shares its item's stats, but so do distinct items (the runecrafting tiaras, the ancient sceptres, the god capes
	// and halos, Torag's and Guthan's pieces), and a name ending in another item's name is no better ("Superior mining
	// gloves", "Volatile Nightmare staff"). Versions already in their item's RuneLite variation group ("(or)" kits,
	// twisted slayer helmets) need no entry. The filter lab's rules.js fold.recolors carries the same pairs.
	private static final Map<Integer, Integer> RECOLORS = Collections.unmodifiableMap(new HashMap<Integer, Integer>()
	{
		{
			// Sanguine torva (Ancient blood ornament kit)
			put(ItemID.TORVA_HELM_SANGUINE, ItemID.TORVA_HELM);
			put(ItemID.TORVA_CHEST_SANGUINE, ItemID.TORVA_CHEST);
			put(ItemID.TORVA_LEGS_SANGUINE, ItemID.TORVA_LEGS);
			// Twisted ancestral (Twisted ancestral colour kit)
			put(ItemID.ANCESTRAL_HAT_TWISTED, ItemID.ANCESTRAL_HAT);
			put(ItemID.ANCESTRAL_ROBE_TOP_TWISTED, ItemID.ANCESTRAL_ROBE_TOP);
			put(ItemID.ANCESTRAL_ROBE_BOTTOM_TWISTED, ItemID.ANCESTRAL_ROBE_BOTTOM);
			// Echo ornament kits (Raging Echoes League rewards)
			put(ItemID.BARROWS_AHRIM_HEAD_ORNAMENT, ItemID.BARROWS_AHRIM_HEAD);
			put(ItemID.BARROWS_AHRIM_BODY_ORNAMENT, ItemID.BARROWS_AHRIM_BODY);
			put(ItemID.BARROWS_AHRIM_LEGS_ORNAMENT, ItemID.BARROWS_AHRIM_LEGS);
			put(ItemID.BARROWS_AHRIM_WEAPON_ORNAMENT, ItemID.BARROWS_AHRIM_WEAPON);
			put(ItemID.VIRTUS_MASK_ORNAMENT, ItemID.VIRTUS_MASK);
			put(ItemID.VIRTUS_TOP_ORNAMENT, ItemID.VIRTUS_TOP);
			put(ItemID.VIRTUS_LEGS_ORNAMENT, ItemID.VIRTUS_LEGS);
			put(ItemID.VENATOR_BOW_ORNAMENT, ItemID.VENATOR_BOW);
			// Blazing ornament kits (Trailblazer Reloaded League rewards)
			put(ItemID.DINHS_BULWARK_ORNAMENT, ItemID.DINHS_BULWARK);
			put(ItemID.TOXIC_BLOWPIPE_ORNAMENT, ItemID.TOXIC_BLOWPIPE);
			put(ItemID.TOXIC_BLOWPIPE_LOADED_ORNAMENT, ItemID.TOXIC_BLOWPIPE_LOADED);
			// Dark and Light infinity (infinity colour kits)
			put(ItemID.INFINITY_HAT_TRIM, ItemID.MAGICTRAINING_INFINITYHAT);
			put(ItemID.INFINITY_TOP_TRIM, ItemID.MAGICTRAINING_INFINITYTOP);
			put(ItemID.INFINITY_BOTTOM_TRIM, ItemID.MAGICTRAINING_INFINITYBOTTOM);
			put(ItemID.INFINITY_HAT_GOLD, ItemID.MAGICTRAINING_INFINITYHAT);
			put(ItemID.INFINITY_TOP_GOLD, ItemID.MAGICTRAINING_INFINITYTOP);
			put(ItemID.INFINITY_BOTTOM_GOLD, ItemID.MAGICTRAINING_INFINITYBOTTOM);
			// Golden prospector (same Mining bonus as prospector)
			put(ItemID.MOTHERLODE_REWARD_HAT_GOLD, ItemID.MOTHERLODE_REWARD_HAT);
			put(ItemID.MOTHERLODE_REWARD_TOP_GOLD, ItemID.MOTHERLODE_REWARD_TOP);
			put(ItemID.MOTHERLODE_REWARD_LEGS_GOLD, ItemID.MOTHERLODE_REWARD_LEGS);
			put(ItemID.MOTHERLODE_REWARD_BOOTS_GOLD, ItemID.MOTHERLODE_REWARD_BOOTS);
			// Spirit angler (same Fishing bonus as angler)
			put(ItemID.SPIRIT_ANGLER_HAT, ItemID.TRAWLER_REWARD_HAT);
			put(ItemID.SPIRIT_ANGLER_TOP, ItemID.TRAWLER_REWARD_TOP);
			put(ItemID.SPIRIT_ANGLER_LEGS, ItemID.TRAWLER_REWARD_LEGS);
			put(ItemID.SPIRIT_ANGLER_BOOTS, ItemID.TRAWLER_REWARD_BOOTS);
			// Dyed goblin mail
			put(ItemID.GOBLIN_ARMOUR_BLACK, ItemID.GOBLIN_ARMOUR);
			put(ItemID.GOBLIN_ARMOUR_DARKBLUE, ItemID.GOBLIN_ARMOUR);
			put(ItemID.GOBLIN_ARMOUR_GREEN, ItemID.GOBLIN_ARMOUR);
			put(ItemID.GOBLIN_ARMOUR_ORANGE, ItemID.GOBLIN_ARMOUR);
			put(ItemID.GOBLIN_ARMOUR_PINK, ItemID.GOBLIN_ARMOUR);
			put(ItemID.GOBLIN_ARMOUR_PURPLE, ItemID.GOBLIN_ARMOUR);
			put(ItemID.GOBLIN_ARMOUR_RED, ItemID.GOBLIN_ARMOUR);
			put(ItemID.GOBLIN_ARMOUR_WHITE, ItemID.GOBLIN_ARMOUR);
			put(ItemID.GOBLIN_ARMOUR_YELLOW, ItemID.GOBLIN_ARMOUR);
			// Radiant oathplate
			put(ItemID.RADIANT_OATHPLATE_HELM, ItemID.OATHPLATE_HELM);
			put(ItemID.RADIANT_OATHPLATE_CHEST, ItemID.OATHPLATE_CHEST);
			put(ItemID.RADIANT_OATHPLATE_LEGS, ItemID.OATHPLATE_LEGS);
		}
	});

	private static final String RESOURCE = "gamemode_items.tsv";
	private static volatile Set<Integer> modeItems;

	private ItemFilters()
	{
	}

	/** The flags hidden by the current config. */
	public static int hiddenFlags(ComboTagsConfig config)
	{
		return config.showCosmeticItems() ? 0 : COSMETIC;
	}

	/**
	 * Whether an item is left out of the search entirely: a game-mode, PvP-minigame or unobtainable copy, an item named
	 * as a broken or uncharged thing ("Broken axe"), or a non-equippable twin (an item that can't be worn but has the exact name of one that can: a
	 * display dummy, prop, trophy or guide picture, like the second "Bow of Faerdhinen (c)").
	 *
	 * @param equippableNames lowercased names of every equippable item
	 */
	static boolean isExcluded(int id, String name, boolean equippable, Set<String> equippableNames)
	{
		return modes().contains(id)
			|| BROKEN_NAME.matcher(name).find()
			|| (!equippable && !TWIN_EXCEPTIONS.contains(id) && equippableNames.contains(name.toLowerCase(Locale.ROOT)));
	}

	/** Broken / uncharged states paired by hand with their working item in another group: state id -> item id. */
	static Map<Integer, Integer> statePairs()
	{
		return STATE_PAIRS;
	}

	/** The hand-kept cosmetic recolors: recolor item id -> the id of the item it is a recolor of. */
	static Map<Integer, Integer> recolors()
	{
		return RECOLORS;
	}

	/** Whether an item is always its own search entry (a cosmetic version), never a variant of another item. */
	static boolean isSplit(String name)
	{
		return SPLIT_NAME.matcher(name).find();
	}

	/** Generated game-mode / PvP id table, loaded once from the plugin jar. */
	private static Set<Integer> modes()
	{
		Set<Integer> m = modeItems;
		if (m != null)
		{
			return m;
		}
		m = new HashSet<>();
		try (InputStream in = ItemFilters.class.getResourceAsStream(RESOURCE))
		{
			if (in == null)
			{
				log.warn("{} missing from the plugin jar; game-mode exclusions are disabled", RESOURCE);
			}
			else
			{
				BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
				for (String line; (line = reader.readLine()) != null; )
				{
					if (!line.isEmpty() && !line.startsWith("#"))
					{
						m.add(Integer.parseInt(line.split("\t", 2)[0]));
					}
				}
			}
		}
		catch (IOException | RuntimeException ex)
		{
			log.warn("failed to read {}; game-mode exclusions are disabled", RESOURCE, ex);
			m = Collections.emptySet();
		}
		modeItems = m;
		return m;
	}
}
