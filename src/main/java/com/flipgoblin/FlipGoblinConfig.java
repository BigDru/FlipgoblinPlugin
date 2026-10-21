package com.flipgoblin;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Notification;

@ConfigGroup("flipgoblin")
public interface FlipGoblinConfig extends Config
{
	// RuneLite renders each description as an "<html>name:<br>description</html>" tooltip. An
	// unconstrained one lays out as a single line and clips at the screen edge, so every visible
	// description wraps its text in this width-capped div.
	String DESC_OPEN = "<div style='width:300px'>";
	String DESC_CLOSE = "</div>";

	// The server URL is baked into FlipGoblinPlugin.API_BASE; there is no config item for it.
	// All real settings live in the Flip Goblin side panel's Settings tab. The items below are
	// hidden ConfigManager-backed storage the panel writes through, so values persist and
	// live-apply as normal. Two items stay visible: the pointer note, and the offer notification,
	// because only RuneLite's own page has the sound, flash and tray options for it.

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
			+ "All FlipGoblin settings live in the Flip Goblin side panel's Settings tab (account linking, hover tooltips, graphs, opacity). Clicking this box (ticking OR unticking) opens it."
			+ DESC_CLOSE
	)
	default boolean openPanel()
	{
		return false;
	}

	@ConfigItem(
		position = 12,
		keyName = "offerCompleteNotification",
		name = "Notify when an offer completes",
		description = DESC_OPEN
			+ "Notifies you when a buy or sell offer completes. Purely local: nothing is sent anywhere."
			+ DESC_CLOSE
	)
	default Notification offerCompleteNotification()
	{
		return Notification.ON;
	}

	@ConfigItem(
		position = 2,
		keyName = "apiToken",
		name = "Account token",
		description = DESC_OPEN
			+ "Paste the one-time token from your FlipGoblin dashboard (Settings → generate token; free account) to link THE CHARACTER YOU ARE LOGGED IN ON. Each character keeps its own token (mint one per character for separate dashboard tracking; this field always shows the current character's). Linking is the plugin's ONE data switch. While a token is set: live market data is fetched for items you view at the GE or hover in your inventory (each request carries the item id and your token, nothing else); your GE fills (item, buy/sell, price, quantity, time, GE slot) and the items you mark Ignore (item, time) sync to YOUR dashboard; those same fills (minus slot) feed FlipGoblin's shared price stream, where only blended aggregates from many contributors are ever shown; your asset snapshot (bank, inventory, equipment, GE-held items, coins) syncs to YOUR account; whether the plugin still trusts its saved copy of your bank is logged to YOUR account at each login, plugin start and bank open (the custody result, how long ago the game and the plugin say your last session ended, and the plugin build), so gaps in your asset history can be explained; and this character's name rides along purely as a dashboard label. Remove the token to unlink this character; the plugin then works purely locally on it."
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
			+ "Hovering an inventory item outside the Grand Exchange (banking, skilling) shows the same FlipGoblin market tooltip that GE hovering always shows (ask/bid, after-tax margin, ROI, volume, buy limit). Requires the account token. Each price request carries the item id and your token, nothing else."
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

	@ConfigItem(
		position = 4,
		keyName = "geShowBottomGraph",
		name = "Show bottom graph",
		description = DESC_OPEN
			+ "Show the graph below the Grand Exchange window."
			+ DESC_CLOSE,
		hidden = true
	)
	default boolean geShowBottomGraph()
	{
		return true;
	}

	// The two timeframe pickers are HIDDEN here: they live in the sidebar panel; only the
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

	@net.runelite.client.config.Range(min = 80, max = 400)
	@ConfigItem(position = 6, keyName = "geTopGraphHeight", name = "Top graph height",
		description = "Height of the graph above the Grand Exchange window, in pixels.", hidden = true)
	default int geTopGraphHeight()
	{
		return 176;
	}

	@net.runelite.client.config.Range(min = -200, max = 200)
	@ConfigItem(position = 6, keyName = "geTopGraphOffset", name = "Top graph offset",
		description = "Moves the graph above the Grand Exchange window up (negative) or down, in pixels.", hidden = true)
	default int geTopGraphOffset()
	{
		return 0;
	}

	@net.runelite.client.config.Range(min = 80, max = 400)
	@ConfigItem(position = 6, keyName = "geBottomGraphHeight", name = "Bottom graph height",
		description = "Height of the graph below the Grand Exchange window, in pixels.", hidden = true)
	default int geBottomGraphHeight()
	{
		return 176;
	}

	@net.runelite.client.config.Range(min = -200, max = 200)
	@ConfigItem(position = 6, keyName = "geBottomGraphOffset", name = "Bottom graph offset",
		description = "Moves the graph below the Grand Exchange window up (negative) or down, in pixels.", hidden = true)
	default int geBottomGraphOffset()
	{
		return 0;
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
		position = 7,
		keyName = "gePanelBorder",
		name = "GE panel borders",
		description = DESC_OPEN
			+ "Draw a border around the GE graphs and panels."
			+ DESC_CLOSE,
		hidden = true
	)
	default boolean gePanelBorder()
	{
		return false;
	}

	@ConfigItem(
		position = 8,
		keyName = "panelScopeAll",
		name = "Panel shows all characters",
		description = DESC_OPEN
			+ "Side panel P/L and assets sum EVERY linked character (other characters counted from their locally stored 7-day fill history and last bank photo) instead of just the one logged in. Purely local: nothing extra is sent anywhere."
			+ DESC_CLOSE,
		hidden = true // the picker lives in the side panel's Session tab
	)
	default boolean panelScopeAll()
	{
		return false;
	}

	@ConfigItem(
		position = 10,
		keyName = "hideUntrackedSales",
		name = "Hide untracked sales",
		description = DESC_OPEN
			+ "Session items leaves out items with only untracked sales: sold without a buy the plugin saw (loot, stock from before tracking), so no profit is counted for them. Items with a flip always show, and the fills list still shows every sale. Display only: nothing is sent anywhere."
			+ DESC_CLOSE,
		hidden = true // set in the side panel's Settings tab
	)
	default boolean hideUntrackedSales()
	{
		return true;
	}

	@ConfigItem(
		position = 11,
		keyName = "hideIgnored",
		name = "Hide ignored items",
		description = DESC_OPEN
			+ "Session items leaves out items you only ignored: no flip and nothing still open (for example food you bought and ate). Items with a flip always show. Untick to see them (and their Undo Ignore button). Display only: nothing is sent anywhere."
			+ DESC_CLOSE,
		hidden = true // set in the side panel's Settings tab
	)
	default boolean hideIgnored()
	{
		return true;
	}

}
