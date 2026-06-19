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
}
