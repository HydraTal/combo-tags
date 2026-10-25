package com.combotags;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(ComboTagsPlugin.CONFIG_GROUP)
public interface ComboTagsConfig extends Config
{
	@RequiredArgsConstructor
	enum ComboHighlight
	{
		NONE("None"),
		OUTLINE("Box outline"),
		DOT("Dot"),
		UNDERLINE("Underline"),
		BACKGROUND("Highlight");
		final String name;

		@Override
		public String toString()
		{
			return name;
		}
	}

	@ConfigItem(
		keyName = "disablePanelTooltips",
		name = "Disable panel tooltips",
		description = "Hide the hover tooltips on the Combo Tags side-panel controls.",
		position = 1
	)
	default boolean disablePanelTooltips()
	{
		return false;
	}

	@ConfigItem(
		keyName = "comboHighlightStyle",
		name = "Bank overlay",
		description = "How combo smart cells are highlighted in bank tabs. The color is set per combo group in the side panel.",
		position = 2
	)
	default ComboHighlight comboHighlightStyle()
	{
		return ComboHighlight.OUTLINE;
	}

	@ConfigItem(
		keyName = "showSharedItemWarnings",
		name = "Shared item warnings",
		description = "Show a ⚠ on a group in the side panel when two or more of its combos contain the same item."
			+ " That item isn't assigned to any of their combo cells (no cell shows, claims or highlights it), so it"
			+ " stays a loose item in the bank. Untick to hide the warning; the item is still left out either way.",
		position = 3
	)
	default boolean showSharedItemWarnings()
	{
		return true;
	}

	@ConfigSection(
		name = "Search filters",
		description = "Which kinds of items the side panel's item search shows.",
		position = 10,
		closedByDefault = true
	)
	String searchFilters = "searchFilters";

	/** Keys of the "Search filters" options; changing one re-runs the panel's current search. */
	Set<String> SEARCH_FILTER_KEYS = new HashSet<>(Arrays.asList("showCosmeticItems"));

	@ConfigItem(
		keyName = "showCosmeticItems",
		name = "Cosmetic variants",
		description = "Show recolors that fold onto another item (Sanguine torva full helm, Dark infinity hat) as"
			+ " their own results. They can still be picked as that item's variant either way.",
		position = 11,
		section = searchFilters
	)
	default boolean showCosmeticItems()
	{
		return true;
	}
}
