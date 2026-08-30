package com.flipgoblin;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JSlider;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;

/**
 * The side panel. Top to bottom: a stats card (session P/L and the composed asset totals),
 * the active-offers board (every standing ask and bid with fill progress and phase),
 * per-item session cards that collapse on header click, and a compact fill log with
 * "(offline)" markers.
 *
 * Each feed (records, positions, assets) updates its own slice of state and re-renders,
 * so the sections never clobber each other. EDT only. Icons are ItemManager async images,
 * and names arrive pre-resolved from the client thread.
 */
public final class FlipGoblinPanel extends PluginPanel
{
	private static final Color PROFIT = ColorScheme.PROGRESS_COMPLETE_COLOR;
	private static final Color LOSS = ColorScheme.PROGRESS_ERROR_COLOR;
	private static final Color MUTED = ColorScheme.LIGHT_GRAY_COLOR;
	private static final Color PRICE = ColorScheme.GRAND_EXCHANGE_PRICE;
	private static final Color WORKING = ColorScheme.BRAND_ORANGE;
	private static final Color CARD_BG = ColorScheme.DARKER_GRAY_COLOR;
	/** Progress-bar track color, between CARD_BG and the muted grays so 0% still reads. */
	private static final Color BAR_TRACK = new Color(55, 55, 55);
	// RuneLite item sprites are 36x32 with the quantity overlay in the top-left corner.
	// Any smaller label clips the overlay digits.
	private static final int ICON_W = 36;
	private static final int ICON_H = 32;
	private static final int MAX_FILL_ROWS = 30;
	private static final SimpleDateFormat HHMM = new SimpleDateFormat("HH:mm");

	private final ItemManager itemManager;
	private final ConfigManager configManager;
	private final FlipGoblinConfig config;
	private final JLabel plKey = keyLabel("P/L (7d)");
	private final JLabel totalValue = value("0 gp", MUTED);
	// "All characters" scope: the picker writes through to config (panelScopeAll);
	// the plugin pushes the other linked characters' summed contribution via setOtherCharacters.
	private final JComboBox<String> scopeCombo =
		new JComboBox<>(new String[]{"This character", "All characters"});
	private CharacterLedger.Totals others;
	private final JLabel assetsValue = value("open your bank", MUTED);
	/** The second assets line (bank age or refresh hint). One string per line, so nothing collides. */
	private final JLabel assetsDetail = value("", MUTED);
	private final JPanel assetsDetailRow = kvRow("", assetsDetail);
	/** The account-link status line: the current character's token state, made visible. */
	private final JLabel linkValue = value("not linked", MUTED);
	// Settings tab. Controls write through ConfigManager, refreshSettings() syncs them
	// back, and the guard stops a programmatic sync from echoing writes.
	private final JLabel settingsLinkValue = value("not linked", MUTED);
	private final JPasswordField tokenField = new JPasswordField();
	private final JCheckBox invHoverBox = new JCheckBox("Inventory hover tooltip");
	private final JCheckBox topGraphBox = new JCheckBox("Show top GE graph");
	private final JSlider opacitySlider = new JSlider(0, 100, 55);
	private boolean refreshingSettings;
	// Kept as fields so the config page's open-settings clicker can land on the Settings tab.
	private final MaterialTabGroup tabGroup;
	private final MaterialTab settingsTab;
	private final JPanel cards = new JPanel();
	// Linked-characters management. The rows are rebuilt from config by refreshSettings().
	private final JPanel charactersRows = new JPanel();
	/** The profileKey armed for removal, for the two-click unlink confirm. */
	private String confirmingUnlinkKey;
	/** The current character's lapse-lock verdict, pushed from the server via setLinkStatus. */
	private boolean characterLockedNow;
	// The GE graph timeframe pickers live here, not in the settings page. The
	// show-top-graph master toggle stays in settings, and this row follows it live.
	private final JComboBox<FlipGoblinConfig.Timeframe> topGraphCombo =
		new JComboBox<>(FlipGoblinConfig.Timeframe.values());
	private final JComboBox<FlipGoblinConfig.Timeframe> bottomGraphCombo =
		new JComboBox<>(FlipGoblinConfig.Timeframe.values());
	private final JPanel topGraphRow;
	/** Item ids whose card the user collapsed. Survives the wholesale rebuilds within a session. */
	private final Set<Integer> collapsed = new HashSet<>();

	private List<TradeRecord> records = Collections.emptyList();
	private List<GePositions.Position> positions = Collections.emptyList();
	private final Map<Integer, String> names = new HashMap<>();

	public FlipGoblinPanel(ItemManager itemManager, ConfigManager configManager, FlipGoblinConfig config)
	{
		this.itemManager = itemManager;
		this.configManager = configManager;
		this.config = config;
		setLayout(new BorderLayout(0, 8));
		setBorder(new EmptyBorder(10, 10, 10, 10));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		totalValue.setFont(FontManager.getRunescapeBoldFont());
		JPanel stats = card();
		scopeCombo.setFont(FontManager.getRunescapeSmallFont());
		scopeCombo.setFocusable(false);
		scopeCombo.setToolTipText("All characters: P/L and assets sum every linked character — "
			+ "others counted from their locally stored 7-day history and last bank photo");
		scopeCombo.addActionListener(e ->
		{
			if (!refreshingSettings)
			{
				configManager.setConfiguration(FlipGoblinPlugin.CONFIG_GROUP, "panelScopeAll",
					scopeCombo.getSelectedIndex() == 1);
			}
		});
		JPanel scopeRow = new JPanel(new BorderLayout(6, 0));
		scopeRow.setOpaque(false);
		scopeRow.setBorder(new EmptyBorder(2, 0, 2, 0));
		JLabel scopeKey = keyLabel("Show");
		scopeRow.add(scopeKey, BorderLayout.WEST);
		scopeRow.add(scopeCombo, BorderLayout.EAST);
		stats.add(scopeRow);
		stats.add(separator());
		stats.add(kvRow(plKey, totalValue));
		stats.add(separator());
		stats.add(kvRow("Assets", assetsValue));
		assetsDetailRow.setVisible(false);
		stats.add(assetsDetailRow);
		stats.add(separator());
		linkValue.setToolTipText("One token per character — link it in the Settings tab above. "
			+ "Guide: flipgoblin.com/plugin");
		stats.add(kvRow("Account", linkValue));

		cards.setLayout(new BoxLayout(cards, BoxLayout.Y_AXIS));
		cards.setOpaque(false);

		// The Session tab is the live view (stats, offers, fills). The Settings tab holds
		// everything configurable; RuneLite's config page just points here.
		JPanel sessionContent = new JPanel(new BorderLayout(0, 8));
		sessionContent.setOpaque(false);
		sessionContent.add(stats, BorderLayout.NORTH);
		sessionContent.add(cards, BorderLayout.CENTER);

		topGraphRow = comboRow("Top graph", topGraphCombo, "geTopGraph");
		JPanel settingsContent = buildSettingsTab();

		JPanel display = new JPanel(new BorderLayout());
		display.setOpaque(false);
		tabGroup = new MaterialTabGroup(display);
		MaterialTab sessionTab = new MaterialTab("Session", tabGroup, sessionContent);
		settingsTab = new MaterialTab("Settings", tabGroup, settingsContent);
		tabGroup.setBorder(new EmptyBorder(0, 0, 8, 0));
		tabGroup.addTab(sessionTab);
		tabGroup.addTab(settingsTab);
		tabGroup.select(sessionTab);
		add(tabGroup, BorderLayout.NORTH);
		add(display, BorderLayout.CENTER);

		// The easy report channel: one click to the site's feedback form. Works logged out.
		JLabel feedback = new JLabel("<html><u>Report a bug / request a feature</u></html>");
		feedback.setFont(FontManager.getRunescapeSmallFont());
		feedback.setForeground(MUTED);
		feedback.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		feedback.setToolTipText("Opens Flip Goblin's feedback form in your browser");
		feedback.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e)
			{
				net.runelite.client.util.LinkBrowser.browse("https://flipgoblin.com/feedback");
			}
		});
		JPanel south = new JPanel(new BorderLayout());
		south.setOpaque(false);
		south.add(feedback, BorderLayout.WEST);
		add(south, BorderLayout.SOUTH);

		refreshSettings();
		render();
	}

	/**
	 * The Settings tab: account linking (token per character) + display options. Every control
	 * writes through ConfigManager to the same hidden config keys as before, so values persist and
	 * live-apply identically; refreshSettings() pulls external changes back in.
	 */
	private JPanel buildSettingsTab()
	{
		JPanel account = card();
		account.add(kvRow("Account", settingsLinkValue));
		tokenField.setFont(FontManager.getRunescapeSmallFont());
		tokenField.setToolTipText("Paste a token from Flip Goblin's website Settings while logged in "
			+ "on the character you want to link");
		JPanel tokenRow = new JPanel(new BorderLayout(6, 0));
		tokenRow.setOpaque(false);
		tokenRow.setBorder(new EmptyBorder(2, 0, 2, 0));
		JLabel tokenLabel = new JLabel("Token");
		tokenLabel.setFont(FontManager.getRunescapeSmallFont());
		tokenLabel.setForeground(MUTED);
		tokenRow.add(tokenLabel, BorderLayout.WEST);
		tokenRow.add(tokenField, BorderLayout.CENTER);
		account.add(tokenRow);
		JButton linkBtn = new JButton("Link this character");
		linkBtn.setFont(FontManager.getRunescapeSmallFont());
		linkBtn.setFocusable(false);
		linkBtn.addActionListener(e -> configManager.setConfiguration(FlipGoblinPlugin.CONFIG_GROUP,
			"apiToken", new String(tokenField.getPassword()).trim()));
		JButton unlinkBtn = new JButton("Unlink");
		unlinkBtn.setFont(FontManager.getRunescapeSmallFont());
		unlinkBtn.setFocusable(false);
		unlinkBtn.addActionListener(e ->
			configManager.setConfiguration(FlipGoblinPlugin.CONFIG_GROUP, "apiToken", ""));
		JPanel buttons = new JPanel(new BorderLayout(6, 0));
		buttons.setOpaque(false);
		buttons.setBorder(new EmptyBorder(4, 0, 2, 0));
		buttons.add(linkBtn, BorderLayout.CENTER);
		buttons.add(unlinkBtn, BorderLayout.EAST);
		account.add(buttons);
		JLabel guide = new JLabel("<html><div style='width:180px'><u>One token per character — "
			+ "generate on the website, paste while logged in on that character. Click for the "
			+ "guide.</u></div></html>");
		guide.setFont(FontManager.getRunescapeSmallFont());
		guide.setForeground(MUTED);
		guide.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		guide.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				net.runelite.client.util.LinkBrowser.browse("https://flipgoblin.com/plugin#link");
			}
		});
		account.add(guide);
		JLabel disclosure = new JLabel("<html><div style='width:180px'>While linked: live market "
			+ "data for items you view; your GE fills + bank snapshot sync to YOUR dashboard; your "
			+ "fills feed the shared price stream (aggregates only, never your GE slot). Unlinked: "
			+ "zero network calls.</div></html>");
		disclosure.setFont(FontManager.getRunescapeSmallFont());
		disclosure.setForeground(MUTED);
		disclosure.setBorder(new EmptyBorder(4, 0, 0, 0));
		account.add(disclosure);

		// Linked characters: every profile holding a token, unlinkable in place. The rows
		// rebuild in refreshSettings(), which fires on every config change, including
		// per-profile token writes.
		JPanel charactersCard = card();
		JLabel charactersTitle = new JLabel("Linked characters");
		charactersTitle.setFont(FontManager.getRunescapeSmallFont());
		charactersTitle.setForeground(Color.WHITE);
		charactersCard.add(charactersTitle);
		charactersRows.setLayout(new BoxLayout(charactersRows, BoxLayout.Y_AXIS));
		charactersRows.setOpaque(false);
		charactersRows.setAlignmentX(Component.LEFT_ALIGNMENT);
		charactersCard.add(charactersRows);

		JPanel displayCard = card();
		invHoverBox.setToolTipText("Market tooltip when hovering inventory items outside the GE");
		displayCard.add(checkboxRow(invHoverBox, "inventoryHover"));
		topGraphBox.setToolTipText("A second graph above the GE window");
		displayCard.add(checkboxRow(topGraphBox, "geShowTopGraph"));
		displayCard.add(topGraphRow);
		displayCard.add(comboRow("Bottom graph", bottomGraphCombo, "geBottomGraph"));
		JPanel opacityRow = new JPanel(new BorderLayout(6, 0));
		opacityRow.setOpaque(false);
		opacityRow.setBorder(new EmptyBorder(2, 0, 2, 0));
		JLabel opacityLabel = new JLabel("GE panel opacity");
		opacityLabel.setFont(FontManager.getRunescapeSmallFont());
		opacityLabel.setForeground(MUTED);
		opacitySlider.setOpaque(false);
		opacitySlider.setFocusable(false);
		opacitySlider.addChangeListener(e ->
		{
			if (!refreshingSettings && !opacitySlider.getValueIsAdjusting())
			{
				configManager.setConfiguration(FlipGoblinPlugin.CONFIG_GROUP, "gePanelOpacity",
					opacitySlider.getValue());
			}
		});
		opacityRow.add(opacityLabel, BorderLayout.WEST);
		opacityRow.add(opacitySlider, BorderLayout.CENTER);
		displayCard.add(opacityRow);

		// The version label: the on-machine way to verify which build loaded.
		JPanel devCard = card();
		JLabel buildLabel = new JLabel("Flip Goblin build " + FlipGoblinPlugin.BUILD);
		buildLabel.setFont(FontManager.getRunescapeSmallFont());
		buildLabel.setForeground(MUTED);
		buildLabel.setBorder(new EmptyBorder(4, 0, 0, 0));
		devCard.add(buildLabel);

		JPanel wrap = new JPanel();
		wrap.setLayout(new BoxLayout(wrap, BoxLayout.Y_AXIS));
		wrap.setOpaque(false);
		wrap.add(account);
		wrap.add(Box.createVerticalStrut(8));
		wrap.add(charactersCard);
		wrap.add(Box.createVerticalStrut(8));
		wrap.add(displayCard);
		wrap.add(Box.createVerticalStrut(8));
		wrap.add(devCard);
		JPanel outer = new JPanel(new BorderLayout());
		outer.setOpaque(false);
		outer.add(wrap, BorderLayout.NORTH);
		return outer;
	}

	/** A settings checkbox that writes through to a boolean config key. */
	private JPanel checkboxRow(JCheckBox box, String key)
	{
		box.setFont(FontManager.getRunescapeSmallFont());
		box.setForeground(MUTED);
		box.setOpaque(false);
		box.setFocusable(false);
		box.addActionListener(e ->
		{
			if (!refreshingSettings)
			{
				configManager.setConfiguration(FlipGoblinPlugin.CONFIG_GROUP, key, box.isSelected());
			}
		});
		JPanel row = new JPanel(new BorderLayout());
		row.setOpaque(false);
		row.setBorder(new EmptyBorder(2, 0, 2, 0));
		row.add(box, BorderLayout.WEST);
		return row;
	}

	/** Jumps straight to the Settings tab, for the config page's open-settings clicker. EDT. */
	public void showSettingsTab()
	{
		tabGroup.select(settingsTab);
	}

	/** Pulls config values into every Settings-tab control, guarded against listener echo. EDT. */
	public void refreshSettings()
	{
		refreshingSettings = true;
		try
		{
			String tok = config.apiToken();
			if (!new String(tokenField.getPassword()).equals(tok))
			{
				tokenField.setText(tok);
			}
			int scopeIdx = config.panelScopeAll() ? 1 : 0;
			if (scopeCombo.getSelectedIndex() != scopeIdx)
			{
				scopeCombo.setSelectedIndex(scopeIdx);
			}
			invHoverBox.setSelected(config.inventoryHover());
			topGraphBox.setSelected(config.geShowTopGraph());
			if (opacitySlider.getValue() != config.gePanelOpacity())
			{
				opacitySlider.setValue(config.gePanelOpacity());
			}
		}
		finally
		{
			refreshingSettings = false;
		}
		rebuildCharacterRows();
		refreshGraphControls();
		render(); // the scope picker changes what the P/L row means
	}

	/** Rebuilds the Linked-characters card's rows from config. EDT. */
	private void rebuildCharacterRows()
	{
		charactersRows.removeAll();
		List<LinkedCharacters.Row> rows = LinkedCharacters.list(configManager);
		if (rows.isEmpty())
		{
			JLabel none = new JLabel("<html><div style='width:180px'>None yet — paste a token above "
				+ "while logged in on a character to link it.</div></html>");
			none.setFont(FontManager.getRunescapeSmallFont());
			none.setForeground(MUTED);
			none.setBorder(new EmptyBorder(4, 0, 0, 0));
			none.setAlignmentX(Component.LEFT_ALIGNMENT);
			charactersRows.add(none);
		}
		for (LinkedCharacters.Row r : rows)
		{
			charactersRows.add(characterRow(r));
		}
		charactersRows.revalidate();
		charactersRows.repaint();
	}

	/** One linked character row: name (green when logged in), masked token tail, two-click unlink. */
	private JPanel characterRow(LinkedCharacters.Row r)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setOpaque(false);
		row.setBorder(new EmptyBorder(3, 0, 3, 0));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		boolean lockedRow = r.current && characterLockedNow;
		JLabel name = new JLabel(lockedRow ? r.name + " (locked)" : r.name);
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(lockedRow ? LOSS : r.current ? PROFIT : Color.WHITE);
		name.setToolTipText(lockedRow
			? "Another linked character holds the active slot — syncing and market data are "
				+ "paused for this character until you unlink the other on the website"
			: r.current
				? "Logged in now — this character's token drives the syncs"
				: "Linked; its token activates automatically when this character logs in");
		row.add(name, BorderLayout.CENTER);
		JPanel east = new JPanel(new BorderLayout(6, 0));
		east.setOpaque(false);
		JLabel tail = new JLabel(r.tokenTail);
		tail.setFont(FontManager.getRunescapeSmallFont());
		tail.setForeground(MUTED);
		tail.setToolTipText("Token ending — match it against the list on the website's Settings page");
		east.add(tail, BorderLayout.CENTER);
		boolean confirming = r.profileKey.equals(confirmingUnlinkKey);
		JButton unlink = new JButton(confirming ? "Sure?" : "Unlink");
		unlink.setFont(FontManager.getRunescapeSmallFont());
		unlink.setFocusable(false);
		unlink.setToolTipText(confirming
			? "Click again to remove this character's token from the plugin"
			: "Remove this character's token from the plugin (the token itself stays valid — "
				+ "revoke it on the website to kill it everywhere)");
		if (confirming)
		{
			unlink.setForeground(LOSS);
		}
		unlink.addActionListener(e ->
		{
			if (!r.profileKey.equals(confirmingUnlinkKey))
			{
				confirmingUnlinkKey = r.profileKey;
				rebuildCharacterRows();
				return;
			}
			confirmingUnlinkKey = null;
			LinkedCharacters.unlink(configManager, r.profileKey);
			rebuildCharacterRows(); // ConfigChanged also refreshes, but don't wait on the bus
		});
		east.add(unlink, BorderLayout.EAST);
		row.add(east, BorderLayout.EAST);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	/** The other linked characters' summed contribution, for the all-characters scope. EDT. */
	public void setOtherCharacters(CharacterLedger.Totals totals)
	{
		others = totals;
		render();
	}

	/** A label on the left, a timeframe dropdown on the right. Selecting writes through to config. */
	private JPanel comboRow(String label, JComboBox<FlipGoblinConfig.Timeframe> combo, String key)
	{
		combo.setFont(FontManager.getRunescapeSmallFont());
		combo.setFocusable(false);
		combo.addActionListener(e ->
		{
			Object v = combo.getSelectedItem();
			if (v != null)
			{
				configManager.setConfiguration(FlipGoblinPlugin.CONFIG_GROUP, key, (FlipGoblinConfig.Timeframe) v);
			}
		});
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setOpaque(false);
		row.setBorder(new EmptyBorder(2, 0, 2, 0));
		JLabel k = new JLabel(label);
		k.setFont(FontManager.getRunescapeSmallFont());
		k.setForeground(MUTED);
		row.add(k, BorderLayout.WEST);
		row.add(combo, BorderLayout.EAST);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		return row;
	}

	/**
	 * Syncs the pickers with config. The top row shows only while "Show top graph" is
	 * checked in settings. EDT only; the selection guards stop listener write-back loops.
	 */
	public void refreshGraphControls()
	{
		if (!config.geTopGraph().equals(topGraphCombo.getSelectedItem()))
		{
			topGraphCombo.setSelectedItem(config.geTopGraph());
		}
		if (!config.geBottomGraph().equals(bottomGraphCombo.getSelectedItem()))
		{
			bottomGraphCombo.setSelectedItem(config.geBottomGraph());
		}
		boolean show = config.geShowTopGraph();
		if (topGraphRow.isVisible() != show)
		{
			topGraphRow.setVisible(show);
			revalidate();
			repaint();
		}
	}

	/** New session fill data. EDT only. */
	/** Updates the account-link status rows on both tabs. EDT only. */
	public void setLinkStatus(boolean linked, boolean locked, String character)
	{
		characterLockedNow = locked;
		String text;
		Color color;
		String tip = null;
		if (linked && locked)
		{
			// Lapse lock: the server refuses this character. Say so loudly.
			text = character == null || character.isEmpty() ? "LOCKED" : "LOCKED · " + character;
			color = LOSS;
			tip = "This character is past the free one-character limit — syncing and market data "
				+ "are paused. Unlink other characters on the website's Settings, or go Premium.";
		}
		else if (linked)
		{
			text = character == null || character.isEmpty() ? "linked" : "linked · " + character;
			color = PROFIT;
		}
		else
		{
			text = "not linked";
			color = MUTED;
		}
		linkValue.setText(text);
		linkValue.setForeground(color);
		linkValue.setToolTipText(tip != null ? tip
			: "One token per character — link it in the Settings tab above. "
				+ "Guide: flipgoblin.com/plugin");
		settingsLinkValue.setText(text);
		settingsLinkValue.setForeground(color);
		settingsLinkValue.setToolTipText(tip);
		rebuildCharacterRows(); // the current row's lock marker follows
	}

	public void update(List<TradeRecord> records, Map<Integer, String> itemNames)
	{
		this.records = records;
		this.names.putAll(itemNames);
		render();
	}

	/** New positions-board state. EDT only. */
	public void updatePositions(List<GePositions.Position> positions, Map<Integer, String> itemNames)
	{
		this.positions = positions;
		this.names.putAll(itemNames);
		render();
	}

	private void render()
	{
		SessionStats.Result stats = SessionStats.match(records);
		// All-characters scope: the other characters' realized P/L, matched per character
		// and never merged into one FIFO, adds on top of the live character's. The label
		// and tooltip say exactly what is summed.
		CharacterLedger.Totals oc = others;
		boolean allScope = config.panelScopeAll() && oc != null && oc.characters > 0;
		long realized = stats.totalRealized + (allScope ? oc.realized7d : 0);
		plKey.setText(allScope ? "P/L (7d) · all" : "P/L (7d)");
		totalValue.setToolTipText(allScope
			? "This character + " + oc.characters + " other linked: " + oc.names
			: null);
		totalValue.setText(gp(realized));
		totalValue.setForeground(realized > 0 ? PROFIT : realized < 0 ? LOSS : MUTED);

		cards.removeAll();

		if (!positions.isEmpty())
		{
			cards.add(sectionLabel("Active offers"));
			for (GePositions.Position p : positions)
			{
				cards.add(positionRow(p));
				cards.add(Box.createVerticalStrut(3));
			}
			cards.add(Box.createVerticalStrut(5));
		}

		if (records.isEmpty() && positions.isEmpty())
		{
			JLabel none = new JLabel("No offers or trades yet this session.");
			none.setFont(FontManager.getRunescapeSmallFont());
			none.setForeground(MUTED);
			none.setBorder(new EmptyBorder(4, 2, 0, 0));
			none.setAlignmentX(Component.LEFT_ALIGNMENT);
			cards.add(none);
			revalidate();
			repaint();
			return;
		}

		if (!records.isEmpty())
		{
			// Newest fill per item for the card's "last" row.
			Map<Integer, TradeRecord> lastFill = new HashMap<>();
			for (TradeRecord r : records)
			{
				lastFill.put(r.itemId, r);
			}
			cards.add(sectionLabel("Session items"));
			for (SessionStats.ItemPosition p : stats.items)
			{
				cards.add(itemCard(p, lastFill.get(p.itemId)));
				cards.add(Box.createVerticalStrut(6));
			}

			cards.add(sectionLabel("Fills (newest first)"));
			int shown = 0;
			for (int i = records.size() - 1; i >= 0 && shown < MAX_FILL_ROWS; i--, shown++)
			{
				cards.add(fillRow(records.get(i)));
				cards.add(Box.createVerticalStrut(3));
			}
		}

		revalidate();
		repaint();
	}

	/** One standing offer: icon | name + phase tag / fill detail / thin fill-progress bar. */
	private JPanel positionRow(GePositions.Position p)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(CARD_BG);
		row.setBorder(new EmptyBorder(4, 6, 4, 6));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);

		JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(ICON_W, ICON_H));
		// Quantity-aware sprite (113 arrows must not draw the single-arrow variant) — the
		// stackable flag doubles as the quantity overlay for non-stacking items, RuneLite's idiom.
		itemManager.getImage(p.itemId, p.totalQuantity, p.totalQuantity > 1).addTo(icon);
		row.add(icon, BorderLayout.WEST);

		JPanel text = new JPanel();
		text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
		text.setOpaque(false);

		String tag = p.phase == GePositions.Phase.WORKING ? "working"
			: p.phase == GePositions.Phase.COMPLETE ? "done" : "cancelled";
		Color tagColor = p.phase == GePositions.Phase.WORKING ? WORKING
			: p.phase == GePositions.Phase.COMPLETE ? PROFIT : LOSS;
		JPanel top = new JPanel(new BorderLayout(6, 0));
		top.setOpaque(false);
		top.setAlignmentX(Component.LEFT_ALIGNMENT);
		JLabel name = new JLabel((p.side == TradeRecord.Side.BUY ? "Buy " : "Sell ") + nameOf(p.itemId));
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(Color.WHITE);
		top.add(name, BorderLayout.CENTER);
		top.add(value(tag, tagColor), BorderLayout.EAST);
		text.add(top);

		JLabel detail = new JLabel(
			p.quantitySold + "/" + p.totalQuantity + " @ " + Gp.exact(p.price) + " · slot " + (p.slot + 1));
		detail.setFont(FontManager.getRunescapeSmallFont());
		detail.setForeground(MUTED);
		detail.setAlignmentX(Component.LEFT_ALIGNMENT);
		text.add(detail);

		text.add(Box.createVerticalStrut(3));
		float fraction = p.phase == GePositions.Phase.COMPLETE ? 1f
			: p.totalQuantity > 0 ? (float) p.quantitySold / p.totalQuantity : 0f;
		text.add(progressBar(fraction, tagColor));

		row.add(text, BorderLayout.CENTER);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	/** 4px offer-fill bar (the flipping-panel idiom); the track stays visible at 0% fill. */
	private static JPanel progressBar(float fraction, Color fill)
	{
		float f = Math.max(0f, Math.min(1f, fraction));
		JPanel bar = new JPanel()
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				super.paintComponent(g);
				g.setColor(BAR_TRACK);
				g.fillRect(0, 0, getWidth(), getHeight());
				g.setColor(fill);
				g.fillRect(0, 0, Math.round(getWidth() * f), getHeight());
			}
		};
		bar.setOpaque(false);
		bar.setPreferredSize(new Dimension(0, 4));
		bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 4));
		bar.setAlignmentX(Component.LEFT_ALIGNMENT);
		return bar;
	}

	/** One item's card: icon | name | colored P/L header over collapsible key/value rows. */
	private JPanel itemCard(SessionStats.ItemPosition p, TradeRecord last)
	{
		JPanel card = card();

		JPanel header = new JPanel(new BorderLayout(6, 0));
		header.setOpaque(false);
		JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(ICON_W, ICON_H));
		int openQty = (int) Math.max(1, Math.min(Integer.MAX_VALUE, p.openQty));
		itemManager.getImage(p.itemId, openQty, openQty > 1).addTo(icon);
		header.add(icon, BorderLayout.WEST);
		JLabel nameLabel = new JLabel(nameOf(p.itemId));
		nameLabel.setFont(FontManager.getRunescapeSmallFont());
		nameLabel.setForeground(Color.WHITE);
		header.add(nameLabel, BorderLayout.CENTER);
		header.add(value(gp(p.realized), p.realized > 0 ? PROFIT : p.realized < 0 ? LOSS : MUTED),
			BorderLayout.EAST);
		card.add(header);

		JPanel info = new JPanel();
		info.setLayout(new BoxLayout(info, BoxLayout.Y_AXIS));
		info.setOpaque(false);
		info.add(separator());
		info.add(kvRow("Open quantity", value(Long.toString(p.openQty), PRICE)));
		if (p.unmatchedSellQty > 0)
		{
			info.add(kvRow("Untracked sold", value(Long.toString(p.unmatchedSellQty), MUTED)));
		}
		if (last != null)
		{
			// Value and timestamp on separate lines — the combined string is wider than the panel.
			info.add(kvRow("Last fill", value(
				(last.side == TradeRecord.Side.BUY ? "buy @ " : "sell @ ") + Gp.exact(last.price), PRICE)));
			info.add(kvRow("", value(
				HHMM.format(new Date(last.timestamp)) + (last.recovered ? " (offline)" : ""), MUTED)));
		}
		info.setVisible(!collapsed.contains(p.itemId));
		card.add(info);

		// Flipping-panel idiom: clicking the header folds the card's info away (persists per item).
		// Attached to the header too (not just the name) so the whole strip is a click target; Swing
		// dispatches to the deepest component, so the two listeners never double-fire.
		MouseAdapter toggle = new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				boolean nowCollapsed = info.isVisible();
				info.setVisible(!nowCollapsed);
				if (nowCollapsed)
				{
					collapsed.add(p.itemId);
				}
				else
				{
					collapsed.remove(p.itemId);
				}
				cards.revalidate();
			}
		};
		header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		nameLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		header.addMouseListener(toggle);
		nameLabel.addMouseListener(toggle);
		return card;
	}

	/** One fill as a compact two-line row: icon | "Buy 100 Lobster" / "@ 200 gp · 14:32 (offline)". */
	private JPanel fillRow(TradeRecord r)
	{
		boolean buy = r.side == TradeRecord.Side.BUY;
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(CARD_BG);
		row.setBorder(new EmptyBorder(3, 6, 3, 6));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);

		JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(ICON_W, ICON_H));
		itemManager.getImage(r.itemId, r.quantity, r.quantity > 1).addTo(icon);
		row.add(icon, BorderLayout.WEST);

		JPanel text = new JPanel();
		text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
		text.setOpaque(false);
		JLabel top = new JLabel((buy ? "Buy " : "Sell ") + r.quantity + " " + nameOf(r.itemId));
		top.setFont(FontManager.getRunescapeSmallFont());
		top.setForeground(buy ? WORKING : Color.WHITE);
		JLabel bottom = new JLabel(
			"@ " + Gp.exact(r.price) + " · " + HHMM.format(new Date(r.timestamp)) + (r.recovered ? " (offline)" : ""));
		bottom.setFont(FontManager.getRunescapeSmallFont());
		bottom.setForeground(MUTED);
		text.add(top);
		text.add(bottom);
		row.add(text, BorderLayout.CENTER);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	// --- shared building blocks -----------------------------------------------------------------

	private static JPanel card()
	{
		JPanel card = new JPanel();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setBackground(CARD_BG);
		card.setBorder(new EmptyBorder(6, 8, 6, 8));
		card.setAlignmentX(Component.LEFT_ALIGNMENT);
		return card;
	}

	/**
	 * Key/value row — description WEST, right-aligned value in CENTER (the flipping-panel
	 * property-row idiom). CENTER, not EAST: BorderLayout paints an oversized EAST child straight
	 * over WEST, while CENTER gets the leftover width and the label ellipsizes.
	 */
	private static JPanel kvRow(String key, JLabel valueLabel)
	{
		return kvRow(keyLabel(key), valueLabel);
	}

	/** As {@link #kvRow(String, JLabel)} but with a caller-held key label (mutable rows). */
	private static JPanel kvRow(JLabel key, JLabel valueLabel)
	{
		JPanel row = new JPanel(new BorderLayout(8, 0));
		row.setOpaque(false);
		row.setBorder(new EmptyBorder(2, 0, 2, 0));
		row.add(key, BorderLayout.WEST);
		row.add(valueLabel, BorderLayout.CENTER);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		return row;
	}

	private static JLabel keyLabel(String text)
	{
		JLabel k = new JLabel(text);
		k.setFont(FontManager.getRunescapeSmallFont());
		k.setForeground(MUTED);
		return k;
	}

	private static JLabel value(String text, Color color)
	{
		JLabel l = new JLabel(text, SwingConstants.RIGHT);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(color);
		return l;
	}

	private static JPanel separator()
	{
		JPanel sep = new JPanel();
		sep.setOpaque(false);
		sep.setBorder(new MatteBorder(1, 0, 0, 0, ColorScheme.DARK_GRAY_COLOR));
		sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 3));
		sep.setAlignmentX(Component.LEFT_ALIGNMENT);
		return sep;
	}

	private static JLabel sectionLabel(String text)
	{
		JLabel l = new JLabel(text.toUpperCase());
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(MUTED);
		l.setBorder(new EmptyBorder(8, 2, 4, 0));
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		return l;
	}

	private String nameOf(int itemId)
	{
		String n = names.get(itemId);
		return n != null ? n : "#" + itemId;
	}

	/**
	 * Render the composed asset totals (frozen bank + live inventory/equipment + GE-held). The
	 * headline is the DASHBOARD-PARITY estimated total (coins + stacks at live bid net of tax);
	 * estTotal < 0 = no bulk prices yet (unlinked / first fetch), which
	 * falls back to the raw stacks·coins line. `bankFresh` = captured THIS session; `bankTrusted`
	 * = the bank COUNTS in the estimate (fresh, or the custody chain is ACQUITTED-unbroken) —
	 * the split keeps the bank-age display honest when trust comes from custody, not a re-open.
	 * EDT-only.
	 */
	public void updateAssets(AssetSnapshot composite, long bankTimestamp, boolean bankFresh,
		boolean bankTrusted, long estTotal, long unpriced)
	{
		// All-characters scope (display only — the plugin's sync payload stays per-character):
		// add the other linked characters' bank-photo values when both sides are priceable.
		CharacterLedger.Totals oc = others;
		boolean allScope = config.panelScopeAll() && oc != null && oc.characters > 0
			&& oc.bankEst >= 0 && estTotal >= 0;
		if (allScope)
		{
			estTotal += oc.bankEst;
			unpriced += oc.unpriced;
		}
		if (composite == null || composite.totalStacks() == 0)
		{
			assetsValue.setText("open your bank");
			assetsValue.setForeground(MUTED);
			assetsValue.setToolTipText(null);
			assetsDetailRow.setVisible(false);
			revalidate();
			repaint();
			return;
		}
		String bankPart;
		if (bankTimestamp == 0)
		{
			bankPart = "no bank yet";
		}
		else
		{
			long hours = java.time.Duration.ofMillis(
				Math.max(0, System.currentTimeMillis() - bankTimestamp)).toHours();
			bankPart = bankFresh ? "bank fresh"
				: "bank " + (hours < 1 ? "<1" : hours) + "h"
					+ (bankTrusted ? " · custody ✓" : " ⟳");
		}
		String detailPart;
		if (estTotal >= 0)
		{
			// Custody: an untrusted bank is EXCLUDED from the
			// estimate (the caller hands us the partial composite), so label the number for what
			// it is. Trusted-but-not-fresh = the ACQUITTED chain — full number, honest bank age.
			assetsValue.setText((bankTrusted ? "≈ " : "inv+GE ≈ ") + gp(estTotal));
			// Coins are inside the total — the detail stays short so it never clips the row.
			detailPart = String.format("%d stacks%s · %s", composite.totalStacks(),
				unpriced > 0 ? " · " + unpriced + " unpriced" : "", bankPart)
				+ (allScope ? " · +" + oc.characters + " alt" + (oc.characters > 1 ? "s" : "") : "");
		}
		else
		{
			assetsValue.setText(String.format("%d stacks · %s", composite.totalStacks(), gp(composite.coins())));
			detailPart = bankPart;
		}
		assetsValue.setForeground(bankTrusted ? PRICE : MUTED);
		assetsDetail.setText(detailPart);
		assetsDetailRow.setVisible(true);
		String tip = (estTotal >= 0
			? (bankTrusted
				? "Estimated sell-now value after GE tax (bank + inventory + equipment + GE offers)"
					+ (bankFresh ? ""
						: " — the bank part is the stored photo, provably untouched since your "
							+ "last session (login custody acquitted)")
				: "Inventory + equipment + GE only — the bank hasn't been witnessed this session, "
					+ "so it isn't counted. Open your bank for the full net worth.")
			: "Bank + inventory + equipment + GE offers")
			+ (bankTrusted || estTotal >= 0 ? "" : " — open your bank to refresh the bank part")
			+ (allScope
				? ". Includes " + oc.characters + " other linked character"
					+ (oc.characters > 1 ? "s" : "") + " from their last bank photo (banks only): "
					+ oc.names
				: "");
		assetsValue.setToolTipText(tip);
		assetsDetail.setToolTipText(tip);
		revalidate();
		repaint();
	}

	private static String gp(long n)
	{
		return Gp.shortForm(n) + " gp";
	}
}
