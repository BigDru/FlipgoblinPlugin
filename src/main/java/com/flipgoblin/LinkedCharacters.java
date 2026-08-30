package com.flipgoblin;

import java.util.ArrayList;
import java.util.List;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.RuneScapeProfile;
import net.runelite.client.config.RuneScapeProfileType;

/**
 * Lists and unlinks the characters that hold an account token. Each token is stored per
 * RuneScape profile, and this class enumerates those stores across all profiles so the
 * Settings tab can manage them without logging each character in. The token itself never
 * leaves this machine's config; only a masked tail is ever displayed.
 */
final class LinkedCharacters
{
	/** Shared with FlipGoblinPlugin, which stores tokens under these same keys. */
	static final String TOKEN_PROFILE_KEY = "apiTokenChar";
	static final String TOKEN_OWNER_KEY = "apiTokenOwnerProfile";

	private LinkedCharacters()
	{
	}

	/** One linked character: profile identity + display bits. */
	static final class Row
	{
		final String profileKey;
		final String name;
		/** The token's last 4 characters, enough to match against the website's list. */
		final String tokenTail;
		/** True for the character currently logged in. Its token drives the syncs. */
		final boolean current;

		Row(String profileKey, String name, String tokenTail, boolean current)
		{
			this.profileKey = profileKey;
			this.name = name;
			this.tokenTail = tokenTail;
			this.current = current;
		}
	}

	/** Masks a token down to its last 4 characters for display. Pure. */
	static String tail(String token)
	{
		String t = token == null ? "" : token.trim();
		return t.length() <= 4 ? t : "…" + t.substring(t.length() - 4);
	}

	/** Display name for a profile row. Non-standard worlds (leagues, beta, DMM) get a type tag. Pure. */
	static String displayName(String name, RuneScapeProfileType type)
	{
		String base = name == null || name.isEmpty() ? "(unnamed)" : name;
		return type == null || type == RuneScapeProfileType.STANDARD
			? base
			: base + " (" + type.name().toLowerCase().replace('_', ' ') + ")";
	}

	/** Every profile holding a token, current character first, then by name. */
	static List<Row> list(ConfigManager cm)
	{
		String currentKey = cm.getRSProfileKey();
		List<Row> rows = new ArrayList<>();
		List<RuneScapeProfile> profiles = cm.getRSProfiles();
		if (profiles == null)
		{
			return rows; // pre-session/harness — nothing enumerable yet
		}
		for (RuneScapeProfile p : profiles)
		{
			String key = p.getKey();
			if (key == null)
			{
				continue;
			}
			String token = cm.getConfiguration(FlipGoblinPlugin.CONFIG_GROUP, key, TOKEN_PROFILE_KEY);
			if (token == null || token.trim().isEmpty())
			{
				continue;
			}
			rows.add(new Row(key, displayName(p.getDisplayName(), p.getType()), tail(token),
				key.equals(currentKey)));
		}
		rows.sort((a, b) -> a.current != b.current ? (a.current ? -1 : 1) : a.name.compareToIgnoreCase(b.name));
		return rows;
	}

	/**
	 * Unlinks one character by dropping its stored token. If the visible token field is
	 * currently mirroring this profile, the field and the owner marker are cleared too.
	 * Leaving them in place would let the next login re-adopt the token we just removed.
	 */
	static void unlink(ConfigManager cm, String profileKey)
	{
		cm.unsetConfiguration(FlipGoblinPlugin.CONFIG_GROUP, profileKey, TOKEN_PROFILE_KEY);
		String owner = cm.getConfiguration(FlipGoblinPlugin.CONFIG_GROUP, TOKEN_OWNER_KEY);
		if (profileKey.equals(owner))
		{
			cm.unsetConfiguration(FlipGoblinPlugin.CONFIG_GROUP, TOKEN_OWNER_KEY);
			// Fires the plugin's ConfigChanged handler, which re-clears profile/owner idempotently.
			cm.setConfiguration(FlipGoblinPlugin.CONFIG_GROUP, "apiToken", "");
		}
	}
}
