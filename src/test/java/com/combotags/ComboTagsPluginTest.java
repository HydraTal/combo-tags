package com.combotags;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.util.Properties;
import net.runelite.client.RuneLite;
import net.runelite.client.RuneLiteProperties;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Launches RuneLite with the Combo Tags plugin sideloaded (used by the {@code runClient} Gradle task).
 * Requires assertions enabled (-ea); the task sets that.
 */
public class ComboTagsPluginTest
{
	public static void main(String[] args) throws Exception
	{
		setWindowTitle("combo-tags (" + getCurrentGitBranch() + ") RL-" + RuneLiteProperties.getVersion());

		ExternalPluginManager.loadBuiltin(ComboTagsPlugin.class);
		RuneLite.main(args);
	}

	private static void setWindowTitle(String title) throws NoSuchFieldException, IllegalAccessException
	{
		Field propertiesField = RuneLiteProperties.class.getDeclaredField("properties");
		propertiesField.setAccessible(true);
		Properties properties = (Properties) propertiesField.get(null);
		properties.setProperty("runelite.title", title);
	}

	private static String getCurrentGitBranch()
	{
		try
		{
			Process process = Runtime.getRuntime().exec("git rev-parse --abbrev-ref HEAD");
			process.waitFor();
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream())))
			{
				return reader.readLine();
			}
		}
		catch (Exception e)
		{
			return "unknown";
		}
	}
}
