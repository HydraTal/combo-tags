package com.combotags;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.util.Text;

/**
 * Shared codec for the combo-cell slot config values ({@code comboslots_<tab>}, see {@link ComboSlots}),
 * stored as a CSV of {@code index:value} rows. Centralizes the {@link Text#fromCSV}/{@code split(":")}/
 * {@code parseInt} parse-serialize pair so a format change lands in one place. Values live under
 * {@link ComboTagsPlugin#CONFIG_GROUP}.
 */
public final class ComboCsv
{
	private ComboCsv()
	{
	}

	/**
	 * Reads a config CSV value, splitting each entry on {@code :} and invoking {@code parser} with the parsed
	 * int index and the full parts array. Entries with fewer than two parts or a non-integer index are skipped,
	 * as is a {@code null} parser result (so the parser can reject a malformed value). Order is preserved.
	 */
	public static <T> List<T> read(ConfigManager configManager, String key, BiFunction<Integer, String[], T> parser)
	{
		List<T> out = new ArrayList<>();
		String csv = configManager.getConfiguration(ComboTagsPlugin.CONFIG_GROUP, key);
		if (csv == null || csv.isEmpty())
		{
			return out;
		}
		for (String entry : Text.fromCSV(csv))
		{
			String[] parts = entry.split(":");
			if (parts.length < 2)
			{
				continue;
			}
			int index;
			try
			{
				index = Integer.parseInt(parts[0].trim());
			}
			catch (NumberFormatException ignored)
			{
				continue; // malformed index → skip
			}
			T value = parser.apply(index, parts);
			if (value != null)
			{
				out.add(value);
			}
		}
		return out;
	}

	/** Writes pre-formatted {@code index:value} rows to a config CSV value; unsets the key when empty. */
	public static void write(ConfigManager configManager, String key, List<String> rows)
	{
		if (rows.isEmpty())
		{
			configManager.unsetConfiguration(ComboTagsPlugin.CONFIG_GROUP, key);
			return;
		}
		configManager.setConfiguration(ComboTagsPlugin.CONFIG_GROUP, key, Text.toCSV(rows));
	}
}
