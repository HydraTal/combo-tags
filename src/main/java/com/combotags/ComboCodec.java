package com.combotags;

import com.google.gson.Gson;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Share-string codec for combos. A single combo or a whole category (its combos bundled with it) is encoded
 * as a short prefixed token — {@code combotag:v1:<base64 json>} / {@code combogroup:v1:<base64 json>} — that
 * round-trips through {@link Gson} (the same serialization {@link ComboStore} persists). Base64 keeps it a
 * single opaque token with no whitespace/braces to mangle on copy-paste.
 */
@Slf4j
public final class ComboCodec
{
	public static final String COMBO_PREFIX = "combotag:v1:";
	public static final String GROUP_PREFIX = "combogroup:v1:";

	/** A category plus the combos filed under it, for a whole-group export. */
	public static final class Bundle
	{
		public ComboCategory category;
		public List<ComboGroup> combos;
	}

	private ComboCodec()
	{
	}

	public static String exportCombo(Gson gson, ComboGroup g)
	{
		return COMBO_PREFIX + encode(gson.toJson(g));
	}

	public static String exportGroup(Gson gson, Bundle bundle)
	{
		return GROUP_PREFIX + encode(gson.toJson(bundle));
	}

	public static boolean isComboString(String s)
	{
		String t = s == null ? "" : s.trim();
		return t.startsWith(COMBO_PREFIX) || t.startsWith(GROUP_PREFIX);
	}

	public static boolean isGroupString(String s)
	{
		return s != null && s.trim().startsWith(GROUP_PREFIX);
	}

	/** Decodes a single-combo string; returns a normalized {@link ComboGroup}, or null if invalid. */
	public static ComboGroup importCombo(Gson gson, String s)
	{
		String t = s == null ? "" : s.trim();
		if (!t.startsWith(COMBO_PREFIX))
		{
			return null;
		}
		try
		{
			ComboGroup g = gson.fromJson(decode(t.substring(COMBO_PREFIX.length())), ComboGroup.class);
			if (g == null || g.name == null || g.name.isEmpty())
			{
				return null;
			}
			g.normalize();
			return g;
		}
		catch (Exception ex)
		{
			log.warn("failed to parse combo import string", ex);
			return null;
		}
	}

	/** Decodes a whole-group string; returns its {@link Bundle}, or null if invalid. */
	public static Bundle importGroup(Gson gson, String s)
	{
		String t = s == null ? "" : s.trim();
		if (!t.startsWith(GROUP_PREFIX))
		{
			return null;
		}
		try
		{
			Bundle b = gson.fromJson(decode(t.substring(GROUP_PREFIX.length())), Bundle.class);
			if (b == null || b.category == null || b.category.name == null || b.category.name.isEmpty())
			{
				return null;
			}
			if (b.combos == null)
			{
				return null;
			}
			b.combos.forEach(ComboGroup::normalize);
			return b;
		}
		catch (Exception ex)
		{
			log.warn("failed to parse combo group import string", ex);
			return null;
		}
	}

	public static void toClipboard(String s)
	{
		Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
		clipboard.setContents(new StringSelection(s), null);
	}

	public static String fromClipboard()
	{
		try
		{
			Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
			Object data = clipboard.getData(DataFlavor.stringFlavor);
			return data == null ? null : data.toString();
		}
		catch (Exception ex)
		{
			return null;
		}
	}

	private static String encode(String json)
	{
		return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
	}

	private static String decode(String token)
	{
		return new String(Base64.getDecoder().decode(token.trim()), StandardCharsets.UTF_8);
	}
}
