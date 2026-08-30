package com.flipgoblin;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("flipgoblin")
public interface FlipGoblinConfig extends Config
{
	// RuneLite renders each description as an "<html>name:<br>description</html>" tooltip — an
	// unconstrained one lays out as a single line and clips at the screen edge, so every visible
	// description wraps its text in this width-capped div.
	String DESC_OPEN = "<div style='width:300px'>";
	String DESC_CLOSE = "</div>";

	// The server URL is baked into FlipGoblinPlugin.API_BASE; there is no config item for it.
	// All real settings live in the Flip Goblin side panel's Settings tab. The items below are
	// hidden ConfigManager-backed storage the panel writes through, so values persist and
	// live-apply as normal. The one visible item is the pointer note.

	// RuneLite's config system has no plain-label or button widgets, so the pointer is a
	// checkbox acting as a pure clicker: every toggle, tick or untick, opens the panel's
	// Settings tab. We never write the value back, because a programmatic reset is not
	// repainted by the open config page and the box would look stuck. The stored value is
	// meaningless.
	@ConfigItem(
		position = 1,
		keyName = "openPanel",
		name = "→ Open FlipGoblin settings",
		description = DESC_OPEN
			+ "All FlipGoblin settings live in the Flip Goblin side panel's Settings tab (account linking, hover tooltips, graphs, opacity). Clicking this box — ticking OR unticking — opens it."
			+ DESC_CLOSE
	)
	default boolean openPanel()
	{
		return false;
	}

	@ConfigItem(
		position = 2,
		keyName = "apiToken",
		name = "Account token",
		description = DESC_OPEN
			+ "Paste the one-time token from your FlipGoblin dashboard (Settings → generate token; free account) to link THE CHARACTER YOU ARE LOGGED IN ON — each character keeps its own token (mint one per character for separate dashboard tracking; this field always shows the current character's). Linking is the plugin's ONE data switch — while a token is set: live market data is fetched for items you view at the GE or hover in your inventory (each request carries the item id and your token, nothing else); your GE fills (item, buy/sell, price, quantity, time, GE slot) sync to YOUR dashboard; those same fills (minus slot) feed FlipGoblin's shared price stream, where only blended aggregates from many contributors are ever shown; your asset snapshot (bank, inventory, equipment, GE-held items, coins) syncs to YOUR account; and this character's name rides along purely as a dashboard label. Remove the token to unlink this character — the plugin then works purely locally on it."
			+ DESC_CLOSE,
		secret = true,
		hidden = true
	)
	default String apiToken()
	{
		return "";
	}

	@ConfigItem(
		position = 3,
		keyName = "inventoryHover",
		name = "Inventory hover",
		description = DESC_OPEN
			+ "Hovering an inventory item outside the Grand Exchange (banking, skilling) shows the same FlipGoblin market tooltip that GE hovering always shows (ask/bid, after-tax margin, ROI, volume, buy limit). Requires the account token — each price request carries the item id and your token, nothing else."
			+ DESC_CLOSE,
		hidden = true
	)
	default boolean inventoryHover()
	{
		return true;
	}

	/**
	 * A graph timeframe: the display label, plus the candle interval and point count that
	 * render it. Points × interval = the span.
	 */
	enum Timeframe
	{
		MIN_45("45 Minutes (1m)", "45 Min", "1m", 45, 60),
		HOURS_4("4 Hours (5m)", "4 Hour", "5m", 48, 300),
		HOURS_12("12 Hours (15m)", "12 Hour", "15m", 48, 900),
		DAYS_2("2 Days (1h)", "2 Day", "1h", 48, 3_600),
		DAYS_7("7 Days (4h)", "7 Day", "4h", 42, 14_400);

		final String label;
		/** Short form for readout rows, e.g. "7 Day High" / "7 Day Low". */
		final String shortName;
		final String interval;
		final int points;
		/** One bucket's span in seconds. Feeds the readout's "Next bucket" countdown. */
		final int intervalSec;

		Timeframe(String label, String shortName, String interval, int points, int intervalSec)
		{
			this.label = label;
			this.shortName = shortName;
			this.interval = interval;
			this.points = points;
			this.intervalSec = intervalSec;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	@ConfigItem(
		position = 4,
		keyName = "geShowTopGraph",
		name = "Show top graph",
		description = DESC_OPEN
			+ "Also show a second graph above the Grand Exchange window. Same keyed price fetch (item id only)."
			+ DESC_CLOSE,
		hidden = true
	)
	default boolean geShowTopGraph()
	{
		return false;
	}

	// The two timeframe pickers are HIDDEN here — they live in the sidebar panel; only the
	// show-top-graph master toggle stays in this settings page. The keys remain config-backed
	// so the panel writes through ConfigManager and the values persist.
	@ConfigItem(
		position = 5,
		keyName = "geTopGraph",
		name = "Top graph",
		description = "Timeframe for the graph above the Grand Exchange window (candle interval in parentheses).",
		hidden = true
	)
	default Timeframe geTopGraph()
	{
		return Timeframe.DAYS_7;
	}

	@ConfigItem(
		position = 6,
		keyName = "geBottomGraph",
		name = "Bottom graph",
		description = "Timeframe for the graph below the Grand Exchange window (candle interval in parentheses).",
		hidden = true
	)
	default Timeframe geBottomGraph()
	{
		return Timeframe.DAYS_2;
	}

	@net.runelite.client.config.Range(max = 100)
	@ConfigItem(
		position = 7,
		keyName = "gePanelOpacity",
		name = "Panel opacity",
		description = DESC_OPEN
			+ "Background opacity (%) of the GE graphs, readout panels, info panel, tooltips, and slot price tags. 100 = fully opaque."
			+ DESC_CLOSE,
		hidden = true
	)
	default int gePanelOpacity()
	{
		return 55; // ≈ the original translucent black (140/255)
	}

	@ConfigItem(
		position = 8,
		keyName = "panelScopeAll",
		name = "Panel shows all characters",
		description = DESC_OPEN
			+ "Side panel P/L and assets sum EVERY linked character (other characters counted from their locally stored 7-day fill history and last bank photo) instead of just the one logged in. Purely local — nothing extra is sent anywhere."
			+ DESC_CLOSE,
		hidden = true // the picker lives in the side panel's Session tab
	)
	default boolean panelScopeAll()
	{
		return false;
	}

}
