package com.combotags;

import lombok.RequiredArgsConstructor;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

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
		keyName = "showSidePanel",
		name = "Show side panel",
		description = "Show the Combo Tags button in the side toolbar (the panel where you build and manage combos).",
		position = 1
	)
	default boolean showSidePanel()
	{
		return true;
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
}
