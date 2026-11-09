package com.flipgoblin;

import java.util.Arrays;
import java.util.List;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.RuneScapeProfile;

/**
 * The "What's new" box at the top of the panel. It shows once per release, and only to people
 * who used an older version. ID is the release version these notes ship in; the release
 * script refuses to publish when it doesn't match. Dev builds in between share the notes.
 */
final class WhatsNew
{
	static final String ID = "2026.11.08";
	static final List<String> NOTES = Arrays.asList(
		"Hide the top-left panel in Settings",
		"Flips is now the first list tab",
		"Restarts keep your bank counted",
		"This box, after each update");

	/** Config key holding the ID of the notes the user last closed. */
	static final String SEEN_KEY = "whatsNewSeen";

	private WhatsNew()
	{
	}

	/** True when the box should show. */
	static boolean shouldShow(String seen, boolean usedBefore)
	{
		if (seen == null || seen.isEmpty())
		{
			// Never seen a box: show it only to people who used an older version.
			return usedBefore;
		}
		return !seen.equals(ID);
	}

	/** True when any saved character has data from an older version. */
	static boolean usedBefore(ConfigManager cm)
	{
		for (RuneScapeProfile p : cm.getRSProfiles())
		{
			String key = p.getKey();
			if (key != null && (has(cm, key, FlipGoblinPlugin.RECORDS_KEY)
				|| has(cm, key, FlipGoblinPlugin.ASSETS_KEY)
				|| has(cm, key, LinkedCharacters.TOKEN_PROFILE_KEY)))
			{
				return true;
			}
		}
		return false;
	}

	private static boolean has(ConfigManager cm, String profileKey, String key)
	{
		String v = cm.getConfiguration(FlipGoblinPlugin.CONFIG_GROUP, profileKey, key);
		return v != null && !v.trim().isEmpty();
	}
}
