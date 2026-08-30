package com.flipgoblin;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;

@Slf4j
@PluginDescriptor(
	name = "Flip Goblin",
	description = "Grand Exchange flip tracker — informational only. Zero network calls until you link your Flip Goblin account; settings live in the Flip Goblin side panel.",
	tags = { "grand exchange", "flipping", "prices", "margin" }
)
public class FlipGoblinPlugin extends Plugin
{
	// Build/version tag, visible in logs and the settings panel so support reports identify the
	// running build. Dev jars carry b##; the publish pipeline stamps the dated release version.
	static final String BUILD = "2026.08.30.2";

	/** The Flip Goblin API base URL, baked in. One constant, one server. */
	static final String API_BASE = "https://flipgoblin-api.druex.workers.dev";

	// GE-capture state. Single-threaded: RuneLite events arrive on the client thread. Records
	// accumulate in memory; the panel renders them and the sync client (opt-in) sends them.
	private GeOfferDiffer differ;
	private List<TradeRecord> records;

	// Offline-fill recovery: per-slot baselines are saved per RuneScape profile (per
	// character), because an account switch must never diff against another character's
	// slots. Seeded once per login. Every event re-saves, so the stored baseline always
	// mirrors the live one.
	@Inject
	private ConfigManager configManager;
	@Inject
	private Gson gson;
	private boolean seededThisLogin;
	static final String CONFIG_GROUP = "flipgoblin";
	private static final String BASELINE_KEY = "slotBaselines";
	/** Persisted fill history (per character): cross-session cost basis, capped by age and count. */
	private static final String RECORDS_KEY = "tradeRecords";
	/** Per-character token store (RS-profile-scoped) — one token per character.
	 *  The literals live in LinkedCharacters, which enumerates/unlinks these stores for the panel. */
	private static final String TOKEN_PROFILE_KEY = LinkedCharacters.TOKEN_PROFILE_KEY;
	/** Which character's profile the visible token field currently mirrors (global scope). */
	private static final String TOKEN_OWNER_KEY = LinkedCharacters.TOKEN_OWNER_KEY;
	/** True while syncTokenForProfile writes the field itself — stops the ConfigChanged echo. */
	private volatile boolean mirroringToken;
	/** Current character name (client thread via GameTick) — the display label sent with syncs. */
	private volatile String rsn;
	/**
	 * Lapse lock: the server says this character is past the tier's cap.
	 * While true the plugin REFUSES all operation (no syncs, no market data — local tracking only)
	 * and the panel says "locked". Refreshed from /plugin/me at login, on token changes, and each
	 * targets cycle; an unreachable check keeps the previous verdict.
	 */
	private volatile boolean characterLocked;
	private static final long RECORDS_MAX_AGE_MS = 7L * 24 * 3_600_000;
	private static final int RECORDS_MAX = 1_000;
	private static final java.lang.reflect.Type RECORDS_TYPE =
		new TypeToken<List<TradeRecord>>() {}.getType();
	private static final java.lang.reflect.Type COLLECT_LEDGER_TYPE =
		new TypeToken<java.util.Map<Integer, CollectLedger.Entry>>() {}.getType();
	private static final java.lang.reflect.Type BASELINE_TYPE =
		new TypeToken<Map<Integer, GeOfferDiffer.SlotState>>()
		{
		}.getType();

	// The asset ledger. The bank is only readable while open, so each fresh login (not a
	// hop) prompts the user to open it; we never open it ourselves. The saved snapshot is
	// bank-only. Inventory and equipment are tracked live from container events, and
	// GE-held items and coins come from the positions board. The view is composed on every
	// change, so totals are always current and never double-count: each container is
	// authoritative for itself, and transfers require the bank open, so its frozen state
	// stays consistent. Known undercount: deposit boxes, until the next bank-open.
	@Inject
	private Client client;
	@Inject
	private net.runelite.client.chat.ChatMessageManager chatMessageManager;
	private static final String ASSETS_KEY = "assetSnapshot";
	private AssetSnapshot bankSnapshot;
	private boolean bankFreshThisSession;
	private boolean hopping;
	private int[][] liveInventory;
	private int[][] liveEquipment;

	// GE positions: the 8 slots as live positions; replayed at login, so no persistence.
	// Read by the overlay on the client thread (all mutation is client-thread too — no sync needed).
	private GePositions positions;
	private long sessionRealized;

	// The "All characters" panel scope: the other linked characters' parsed local stores,
	// reloaded on login, link changes, and the scope toggle. Their data only moves when
	// they play, so reparsing on every recompute would be waste. Volatile: written on the
	// client thread and the executor, read wherever recomputeAssets runs.
	private volatile List<CharacterLedger.Character> otherCharacters = java.util.Collections.emptyList();

	// Login custody: our per-character session record
	// vs the welcome screen's "You last logged in …" report. All mutation on the client thread;
	// volatiles are for the overlay/EDT readers. The verdict + chain gate the bank-trust below.
	/** The witnessed-collect ledger: the per-slot uncollected state, RS-profile-scoped. */
	private static final String COLLECT_LEDGER_KEY = "collectLedger";
	/** The standalone collection-box interface (per slot: dynamic child [3] = the pending items
	 *  box, [4] = the pending coins box). */
	private static final int COLLECTION_BOX_GROUP = 402;
	private final CollectLedger collectLedger = new CollectLedger();
	/** Offline fills found at login replay — counted only once this session judges ACQUITTED
	 *  (nothing can touch the collection box while logged out). */
	private final List<TradeRecord> pendingRecoveredFills = new ArrayList<>();
	private boolean collectionBoxOpen;
	/** >0 = a collect-to-inventory click awaits its inventory delta (ticks left in the window). */
	private int collectArmedTicks;
	/**
	 * False from a fresh login's ledger reset until the custody judge completes. While
	 * false, no save may run, heartbeat included: the store holds the carry the judge
	 * needs, and an early save of the just-reset empty ledger would wipe it before an
	 * ACQUITTED login could restore from it. True by default, because a plugin enabled
	 * mid-session never judges, and there the heartbeat save is the staleness guard.
	 */
	private boolean ledgerAuthoritative = true;
	/** The main GE interface — the offer-detail view renders INSIDE it as dynamic children. */
	private static final int GE_GROUP = 465;
	private boolean geOpen;
	/** Witness settle: ticks since the viewed slot changed (boxes populate a beat after the card). */
	private int viewedSlotVar = -1;
	private int viewedSettleTicks;
	/** The login-custody state machine. See {@link CustodyTracker}; the plugin reacts via its Host. */
	private CustodyTracker custody;

	// The over-the-game overlay rendering P/L + the positions board.
	@Inject
	private net.runelite.client.ui.overlay.OverlayManager overlayManager;
	@Inject
	private FlipGoblinOverlay overlay;

	// The GE-anchored info panel + its read-only price feed (public data, item id only).
	@Inject
	private GeInfoOverlay geInfoOverlay;
	// The login-screen custody indicator. Renders only while the welcome screen is up.
	@Inject
	private CustodyOverlay custodyOverlay;
	@Inject
	private net.runelite.client.ui.overlay.tooltip.TooltipManager tooltipManager;
	private PriceClient prices;

	// The session panel (Swing — EDT-only; updates are marshalled via invokeLater).
	@Inject
	private ClientToolbar clientToolbar;
	@Inject
	private ItemManager itemManager;
	private FlipGoblinPanel panel;
	private NavigationButton navButton;
	// id → name, resolved on the CLIENT thread as items are first seen (ItemComposition is
	// client-thread-only; the panel must never touch it). Read by the EDT via defensive copies.
	private final Map<Integer, String> itemNames = new java.util.concurrent.ConcurrentHashMap<>();

	// Opt-in trade sync (config-gated; the flusher no-ops unless enabled + configured).
	@Inject
	private FlipGoblinConfig config;
	@Inject
	private OkHttpClient okHttpClient;
	@Inject
	private ScheduledExecutorService executor;
	@Inject
	private net.runelite.client.callback.ClientThread clientThread;
	private SyncClient sync;
	private ScheduledFuture<?> flusher;
	// Read-only website targets (watchlist + alert thresholds) for the GE overlay.
	private TargetsClient targets;
	private ScheduledFuture<?> targetsRefresher;

	@Override
	protected void startUp() throws Exception
	{
		differ = new GeOfferDiffer();
		records = new ArrayList<>();
		positions = new GePositions();
		prices = new PriceClient(okHttpClient, gson);
		targets = new TargetsClient(okHttpClient);
		overlayManager.add(overlay);
		overlayManager.add(geInfoOverlay);
		overlayManager.add(custodyOverlay);
		panel = new FlipGoblinPanel(itemManager, configManager, config);
		navButton = NavigationButton.builder()
			.tooltip("Flip Goblin")
			.icon(navIcon())
			.priority(6)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
		sync = new SyncClient(okHttpClient, gson);
		assetsPusher = new AssetsPusher(executor, this::sendAssets);
		historyImporter = new GeHistoryImporter(client, itemManager);
		// Custody: plugin enabled while ALREADY at the login screen sees no LOGIN_SCREEN
		// transition — seed the flag so the coming login still judges (vs. reading as a reconnect).
		if (custody == null)
		{
			custody = new CustodyTracker(configManager, gson, chatMessageManager, custodyHost());
		}
		custody.seedAtStartUp(client.getGameState() == GameState.LOGIN_SCREEN);
		// "All characters" scope: parse the other linked characters' stores once now so the panel
		// can aggregate before the first login (and refresh the Settings tab's character list).
		loadOtherCharacters();
		clientThread.invokeLater(this::recomputeAssets);
		// Retry loop for the offline queue: a no-op when sync is off, unconfigured, or the queue is empty.
		flusher = executor.scheduleWithFixedDelay(this::flushIfEnabled, 30, 30, TimeUnit.SECONDS);
		// Slow-cadence targets refresh — only ever calls out when the account link is set.
		targetsRefresher = executor.scheduleWithFixedDelay(this::refreshTargetsIfLinked, 5, 300, TimeUnit.SECONDS);
		log.info("Flip Goblin started (build {})", BUILD);
	}

	@Override
	protected void shutDown() throws Exception
	{
		overlayManager.remove(overlay);
		overlayManager.remove(geInfoOverlay);
		overlayManager.remove(custodyOverlay);
		clientToolbar.removeNavigation(navButton);
		if (flusher != null)
		{
			flusher.cancel(false);
			flusher = null;
		}
		if (targetsRefresher != null)
		{
			targetsRefresher.cancel(false);
			targetsRefresher = null;
		}
		targets = null;
		// Best-effort final drains, submitted to the executor: blocking network on this
		// thread would stall whatever thread the plugin system shuts us down on. The sync
		// task captures its client and token, so nulling the fields below cannot race it.
		// The assets drain may see the closed state and skip; the next login re-pushes the
		// complete snapshot anyway (latest wins server-side).
		SyncClient closingSync = sync;
		AssetsPusher closingPusher = assetsPusher;
		String closingToken = config.apiToken().trim();
		String closingCharacter = rsn;
		if (closingSync != null && !closingToken.isEmpty() && !characterLocked)
		{
			executor.submit(() ->
			{
				closingSync.flush(API_BASE, closingToken, closingCharacter);
				closingSync.flushCrowd(API_BASE, closingToken);
			});
		}
		if (closingPusher != null)
		{
			executor.submit(closingPusher::flushNow);
		}
		assetsPusher = null;
		sync = null;
		navButton = null;
		panel = null;
		differ = null;
		records = null;
		log.debug("Flip Goblin stopped");
	}

	/** A tiny generated icon (no binary asset needed): a gold square with a dark F. */
	private static BufferedImage navIcon()
	{
		BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D g = img.createGraphics();
		g.setColor(new java.awt.Color(212, 175, 55));
		g.fillRoundRect(0, 0, 16, 16, 4, 4);
		g.setColor(new java.awt.Color(40, 30, 0));
		g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 12));
		g.drawString("F", 5, 12);
		g.dispose();
		return img;
	}

	/** Tracks the current character name — the local player resolves a few ticks after LOGGED_IN. */
	@Subscribe
	public void onGameTick(GameTick tick)
	{
		net.runelite.api.Player p = client.getLocalPlayer();
		if (p != null && p.getName() != null && !p.getName().equals(rsn))
		{
			rsn = p.getName();
			pushLinkStatus(); // name just resolved/changed — refresh the panel's Account row
		}
		if (custody != null && custody.heartbeat(Instant.now().toEpochMilli()))
		{
			// Staleness guard: keep the stored uncollected ledger tracking the live one, so a
			// plugin enabled mid-session (which never fresh-login-resets) can't leave an old
			// store to be wrongly restored by a later acquittal. ≤8 tiny entries — cheap.
			persistCollectLedger();
		}
		if (collectArmedTicks > 0)
		{
			collectArmedTicks--; // window expired = nothing arrived = nothing was collected
		}
		pollCollectionBox();
		pollOfferDetail();
	}

	/**
	 * Widget ground truth, authoritative while visible: while the collection box
	 * (group 402) is open, resync each slot's uncollected ledger to exactly what it renders —
	 * children 4..11 are the GE slots; a slot's dynamic child [3] is the pending items box,
	 * [4] the pending coins box (item 995 × gp). Heals every drift on first glance. 8 slots ×
	 * 2 child reads per tick, only while the interface is open — negligible.
	 */
	private void pollCollectionBox()
	{
		if (!collectionBoxOpen)
		{
			return;
		}
		// Slot widgets live DEEP in group 402 (per slot: dynamic child [3] = pending items,
		// [4] = pending coins; the offer's icon sits at [21] — excluded by reading only 3/4).
		// Fixed top-level indices miss them, so scan the whole tree structurally instead: any
		// widget whose DYNAMIC children carry an item at index 3 or 4 is a slot. Runs only
		// while the box is open; the tree is a few dozen nodes.
		List<long[]> boxes = new ArrayList<>();
		for (int child = 0; child < 64; child++)
		{
			net.runelite.api.widgets.Widget w = client.getWidget(COLLECTION_BOX_GROUP, child);
			if (w != null)
			{
				scanForPendingBoxes(w, boxes, 0);
			}
		}
		if (collectLedger.resyncAll(boxes))
		{
			persistCollectLedger();
			recomputeAssets();
		}
	}

	/** Walk static+nested children looking for slot-shaped widgets (dynamic child 3 = pending
	 *  items, 4 = pending coins). Appends one {itemId, itemQty, coins} triple per hit. */
	private static void scanForPendingBoxes(net.runelite.api.widgets.Widget w, List<long[]> out,
		int depth)
	{
		if (depth > 6)
		{
			return;
		}
		net.runelite.api.widgets.Widget[] dyn = w.getDynamicChildren();
		if (dyn != null && dyn.length > 4)
		{
			long itemId = 0;
			long itemQty = 0;
			long coins = 0;
			net.runelite.api.widgets.Widget items = dyn[3];
			if (items != null && items.getItemId() > 0 && items.getItemId() != ItemIds.BLANK_BOX
				&& items.getItemQuantity() > 0)
			{
				if (items.getItemId() == ItemIds.COINS)
				{
					coins += items.getItemQuantity(); // a coins-only offer renders in the items box
				}
				else
				{
					itemId = items.getItemId();
					itemQty = items.getItemQuantity();
				}
			}
			net.runelite.api.widgets.Widget coinBox = dyn[4];
			if (coinBox != null && coinBox.getItemId() == ItemIds.COINS && coinBox.getItemQuantity() > 0)
			{
				coins += coinBox.getItemQuantity();
			}
			if (itemQty > 0 || coins > 0)
			{
				out.add(new long[]{itemId, itemQty, coins});
			}
		}
		scanChildren(w.getStaticChildren(), out, depth);
		scanChildren(w.getNestedChildren(), out, depth);
	}

	private static void scanChildren(net.runelite.api.widgets.Widget[] children, List<long[]> out,
		int depth)
	{
		if (children == null)
		{
			return;
		}
		for (net.runelite.api.widgets.Widget c : children)
		{
			if (c != null)
			{
				scanForPendingBoxes(c, out, depth + 1);
			}
		}
	}

	/**
	 * The GE offer-detail witness. While an offer is being viewed, its two pending collect
	 * boxes are on screen, so read them and resync that slot's ledger entry. The paths are
	 * pinned from a live widget dump: the card is 465.0/s[0]/s[4], hidden unless the
	 * detail view is up; the boxes are dynamic children of the card's static children
	 * s[5..12]; the offer icon sits on the card directly and is never inside those
	 * containers. Every box item must be coins or the viewed offer's item. Anything else
	 * means a stale or foreign render, and the whole read is skipped rather than guessed at.
	 */
	private void pollOfferDetail()
	{
		if (!geOpen)
		{
			return;
		}
		int sel = client.getVarbitValue(net.runelite.api.gameval.VarbitID.GE_SELECTEDSLOT);
		if (sel != viewedSlotVar)
		{
			viewedSlotVar = sel;
			viewedSettleTicks = 0;
		}
		if (sel <= 0 || ++viewedSettleTicks < 2)
		{
			return; // boxes render a beat after the card — an all-zeros first read would wipe truth
		}
		int slot = sel - 1; // the varbit is 1-based (0 = no offer selected)
		GePositions.Position pos = null;
		for (GePositions.Position p : positions.active())
		{
			if (p.slot == slot)
			{
				pos = p;
				break;
			}
		}
		if (pos == null)
		{
			return; // nothing on the board for this slot — nothing witnessable
		}
		net.runelite.api.widgets.Widget root = client.getWidget(GE_GROUP, 0);
		net.runelite.api.widgets.Widget[] rootKids = root == null ? null : root.getStaticChildren();
		net.runelite.api.widgets.Widget s0 = rootKids != null && rootKids.length > 0 ? rootKids[0] : null;
		net.runelite.api.widgets.Widget[] s0Kids = s0 == null ? null : s0.getStaticChildren();
		net.runelite.api.widgets.Widget card = s0Kids != null && s0Kids.length > 4 ? s0Kids[4] : null;
		if (card == null || card.isHidden())
		{
			return;
		}
		net.runelite.api.widgets.Widget[] cardKids = card.getStaticChildren();
		if (cardKids == null)
		{
			return;
		}
		int itemQty = 0;
		long coins = 0;
		for (int i = 5; i < Math.min(13, cardKids.length); i++)
		{
			net.runelite.api.widgets.Widget[] dyn = cardKids[i] == null ? null : cardKids[i].getDynamicChildren();
			if (dyn == null)
			{
				continue;
			}
			for (net.runelite.api.widgets.Widget d : dyn)
			{
				if (d == null || d.isHidden() || d.getItemId() <= 0 || d.getItemId() == ItemIds.BLANK_BOX
					|| d.getItemQuantity() <= 0)
				{
					continue;
				}
				if (d.getItemId() == ItemIds.COINS)
				{
					coins += d.getItemQuantity();
				}
				else if (d.getItemId() == pos.itemId)
				{
					itemQty += d.getItemQuantity();
				}
				else
				{
					return; // an item that is neither coins nor the viewed offer — don't witness
				}
			}
		}
		if (collectLedger.resyncViewed(slot, pos.itemId, itemQty, coins))
		{
			persistCollectLedger();
			recomputeAssets();
		}
	}

	/**
	 * Collect detection: every collect variant fires a menu click (per-slot boxes,
	 * main-screen Collect / Collect-to-bank, the bank's collection box). Scope is ambiguous from
	 * the option string alone, so err LOW — zero the whole ledger; the 402 poll (or the next
	 * glance) restores whatever actually remains. Collect-to-bank lands in the frozen-bank blind
	 * spot by design — no inventory delta expected. A stray non-GE "Collect…" option zeroing the
	 * ledger only ever deflates, and heals the same way.
	 */
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		String option = event.getMenuOption();
		if (option == null || !option.startsWith("Collect"))
		{
			return;
		}
		if (option.toLowerCase(java.util.Locale.ROOT).contains("bank"))
		{
			// Collect-to-bank: no inventory delta by design (coins land behind the frozen bank
			// photo) — the only variant the delta signal can't follow. Err low, zero everything.
			if (collectLedger.zeroAll())
			{
				persistCollectLedger();
				recomputeAssets();
			}
			return;
		}
		// Collect-to-inventory (any variant, any scope): arm the delta window instead of zeroing.
		// The next inventory event's ARRIVALS decrement the ledger exactly (signal 3 — the closed
		// system: with the GE open, positive inventory deltas can only be collects). If nothing
		// arrives (full inventory, misclick), nothing moved and the ledger correctly stands.
		collectArmedTicks = 3;
	}

	/** Saves the uncollected ledger to the profile (entries only; baselines are
	 *  session-local). Does nothing while a fresh login is still unjudged: the store holds
	 *  the carry the judge needs, and overwriting it then would lose it. */
	private void persistCollectLedger()
	{
		if (!ledgerAuthoritative)
		{
			return;
		}
		configManager.setRSProfileConfiguration(CONFIG_GROUP, COLLECT_LEDGER_KEY,
			gson.toJson(collectLedger.snapshotEntries()));
	}

	/** The persisted uncollected ledger, or null when absent/corrupt (both mean: start clean). */
	private java.util.Map<Integer, CollectLedger.Entry> loadCollectLedger()
	{
		String json = configManager.getRSProfileConfiguration(CONFIG_GROUP, COLLECT_LEDGER_KEY);
		if (json == null || json.isEmpty())
		{
			return null;
		}
		try
		{
			return gson.fromJson(json, COLLECT_LEDGER_TYPE);
		}
		catch (RuntimeException e)
		{
			log.warn("corrupt collect ledger in profile — starting clean", e);
			return null;
		}
	}

	/**
	 * Re-parse the OTHER linked characters' local stores (7d fill history + bank photo) for the
	 * panel's "All characters" scope. Cheap (a few small JSON strings), but only re-run when the
	 * inputs can actually have changed: login (profile switch), link/unlink, scope toggle.
	 */
	private void loadOtherCharacters()
	{
		List<CharacterLedger.Character> out = new ArrayList<>();
		String currentKey = configManager.getRSProfileKey();
		for (net.runelite.client.config.RuneScapeProfile p : configManager.getRSProfiles())
		{
			String key = p.getKey();
			if (key == null || key.equals(currentKey))
			{
				continue; // the live character renders from its in-memory state, never a stale store
			}
			String token = configManager.getConfiguration(CONFIG_GROUP, key, TOKEN_PROFILE_KEY);
			if (token == null || token.trim().isEmpty())
			{
				continue; // linked characters only — matches the website's "keys they linked" list
			}
			List<TradeRecord> recs = null;
			AssetSnapshot bank = null;
			try
			{
				String rj = configManager.getConfiguration(CONFIG_GROUP, key, RECORDS_KEY);
				if (rj != null)
				{
					recs = gson.fromJson(rj, RECORDS_TYPE);
				}
				String aj = configManager.getConfiguration(CONFIG_GROUP, key, ASSETS_KEY);
				if (aj != null)
				{
					bank = gson.fromJson(aj, AssetSnapshot.class);
				}
			}
			catch (RuntimeException e)
			{
				log.warn("corrupt store for linked character {} — counting what parsed", p.getDisplayName(), e);
			}
			out.add(new CharacterLedger.Character(
				LinkedCharacters.displayName(p.getDisplayName(), p.getType()), recs, bank));
		}
		otherCharacters = out;
	}

	/** Mirror the link state (token present for THIS character + its name + the lapse-lock
	 *  verdict) to the side panel. */
	private void pushLinkStatus()
	{
		FlipGoblinPanel target = panel;
		if (target == null)
		{
			return;
		}
		boolean linked = isLinked();
		boolean locked = characterLocked;
		String name = rsn;
		SwingUtilities.invokeLater(() -> target.setLinkStatus(linked, locked, name));
	}

	/**
	 * Seed the differ from this character's persisted baseline exactly once per login. LOGGED_IN fires
	 * before the GE offer replay, and the persisted map always mirrors the live baseline (re-persisted on
	 * every event), so re-seeding on hops/re-logins is value-identical — only truly-offline deltas emit,
	 * and they emit as recovered fills with their offline window.
	 */
	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			syncTokenForProfile(); // per-character token BEFORE anything that might sync
			executor.submit(this::refreshLockStatus); // is THIS character allowed to operate?
			loadOtherCharacters(); // the profile switch changes who "the others" are
			seedFromProfile();
			if (hopping)
			{
				hopping = false; // world hop, not a fresh login — assets can't have drifted
				custody.recordLogin(false); // the server may count a hop as a login — record it
			}
			else
			{
				onFreshLogin();
			}
		}
		else if (event.getGameState() == GameState.LOGIN_SCREEN || event.getGameState() == GameState.HOPPING)
		{
			seededThisLogin = false; // next LOGGED_IN re-seeds (possibly a different character)
			hopping = event.getGameState() == GameState.HOPPING;
			// Custody: stamp the clean logout instant and drop the welcome stash. The verdict
			// and chain stay in memory across a hop (same session); a real logout's next
			// fresh login resets them.
			if (custody != null)
			{
				custody.onLeaveWorld(event.getGameState() == GameState.LOGIN_SCREEN);
			}
		}
	}

	/**
	 * Replace the in-memory fill history with this character's persisted records (7d horizon) —
	 * cross-session cost basis, so a sell placed today matches yesterday's buy. Client thread
	 * (names resolve via ItemComposition).
	 */
	private void loadRecords()
	{
		if (records == null)
		{
			return;
		}
		String json = configManager.getRSProfileConfiguration(CONFIG_GROUP, RECORDS_KEY);
		if (json == null || json.isEmpty())
		{
			return;
		}
		try
		{
			List<TradeRecord> loaded = gson.fromJson(json, RECORDS_TYPE);
			if (loaded == null)
			{
				return;
			}
			records.clear();
			records.addAll(loaded);
			for (TradeRecord r : loaded)
			{
				itemNames.computeIfAbsent(r.itemId, id -> itemManager.getItemComposition(id).getName());
			}
			// Re-date any history imports still carrying detection stamps (e.g. imported by a
			// build without feed refinement) — refinement is idempotent: already-dated records
			// match the same minute again and apply as no-ops.
			List<TradeRecord> unrefined = new ArrayList<>();
			for (TradeRecord r : loaded)
			{
				if (r.slot < 0 && r.recovered)
				{
					unrefined.add(r);
				}
			}
			if (!unrefined.isEmpty())
			{
				executor.submit(() -> refineImports(unrefined));
			}
			sessionRealized = SessionStats.match(records).totalRealized;
			List<TradeRecord> copy = new ArrayList<>(records);
			Map<Integer, String> names = new java.util.HashMap<>(itemNames);
			FlipGoblinPanel target = panel;
			if (target != null)
			{
				SwingUtilities.invokeLater(() -> target.update(copy, names));
			}
			log.debug("loaded {} persisted trade records", loaded.size());
		}
		catch (RuntimeException e)
		{
			log.warn("corrupt trade records in profile — ignoring", e);
		}
	}

	/** Mirror the fill history to the profile, trimmed to the horizon (the in-memory list keeps all). */
	private void saveRecords()
	{
		long cutoff = Instant.now().toEpochMilli() - RECORDS_MAX_AGE_MS;
		List<TradeRecord> keep = new ArrayList<>();
		for (TradeRecord r : records)
		{
			if (r.timestamp >= cutoff)
			{
				keep.add(r);
			}
		}
		if (keep.size() > RECORDS_MAX)
		{
			keep = new ArrayList<>(keep.subList(keep.size() - RECORDS_MAX, keep.size()));
		}
		configManager.setRSProfileConfiguration(CONFIG_GROUP, RECORDS_KEY, gson.toJson(keep));
	}

	/** Fresh login: load this character's last BANK snapshot and ask for a bank-open to refresh it. */
	private void onFreshLogin()
	{
		observedSince = Instant.now().toEpochMilli();
		bankFreshThisSession = false;
		bankSnapshot = null;
		custody.onLogin();
		loadRecords();
		String json = configManager.getRSProfileConfiguration(CONFIG_GROUP, ASSETS_KEY);
		if (json != null && !json.isEmpty())
		{
			try
			{
				bankSnapshot = gson.fromJson(json, AssetSnapshot.class);
			}
			catch (RuntimeException e)
			{
				log.warn("corrupt asset snapshot in profile — ignoring", e);
			}
		}
		recomputeAssets();
		// CONSOLE via ChatMessageManager — a raw client.addChatMessage(GAMEMESSAGE, …) here NPEs
		// OTHER plugins' chat handlers (injected game messages re-enter every subscriber).
		// CONSOLE is the plugin-notice type, and the manager queues onto the client thread
		// properly.
		chatMessageManager.queue(net.runelite.client.chat.QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.value("Flip Goblin: open your bank to refresh your asset snapshot"
				+ (bankSnapshot == null ? "." : " (assets may have changed since your last visit)."))
			.build());
	}

	/** The plugin's side of the custody handshake: the ledger consequences of each outcome. */
	private CustodyTracker.Host custodyHost()
	{
		return new CustodyTracker.Host()
		{
			@Override
			public void onNewCustodyWindow()
			{
				// A new custody window starts the uncollected ledger EMPTY (never-inflate
				// floor); the judge restores the saved entries only if this login acquits.
				// Saves are FROZEN until then — the store is the carry the judge needs intact.
				collectLedger.reset();
				pendingRecoveredFills.clear();
				ledgerAuthoritative = false;
			}

			@Override
			public void onJudged(boolean acquitted)
			{
				// The acquittal payoff: uncollected value carried across the logout counts
				// again (saved entries plus offline fills found at replay). Anything else
				// assumes collected, and the store is overwritten with the empty live state
				// so staleness cannot survive either.
				if (acquitted)
				{
					collectLedger.seedEntries(loadCollectLedger());
					for (TradeRecord r : pendingRecoveredFills)
					{
						collectLedger.applyFill(r);
					}
				}
				pendingRecoveredFills.clear();
				ledgerAuthoritative = true; // judged — from here the live ledger IS the truth to save
				persistCollectLedger();
				recomputeAssets(); // an acquitted chain changes the estimate + the sync's bankFresh flag
			}
		};
	}

	// --- custody overlay reads (render thread) ---

	String custodyOverlayDetail()
	{
		return custody == null ? null : custody.overlayDetail();
	}

	LoginCustody.Verdict custodyOverlayVerdict()
	{
		return custody == null ? null : custody.overlayVerdict();
	}

	boolean welcomeScreenVisible()
	{
		return custody != null && custody.welcomeScreenVisible();
	}

	/** True while the main overlay should carry the custody banner (first minute in-world). */
	boolean custodyBannerActive()
	{
		return custody != null && custody.bannerActive();
	}

	/**
	 * The welcome screen (group 378) feeds the custody judge — the text is provably populated at
	 * WidgetLoaded time, so it is captured here.
	 */
	@Subscribe
	public void onWidgetLoaded(net.runelite.api.events.WidgetLoaded event)
	{
		if (event.getGroupId() == net.runelite.api.gameval.InterfaceID.WELCOME_SCREEN && custody != null)
		{
			custody.onWelcomeLoaded(WidgetCatalog.findText(client, event.getGroupId(), "last logged in"));
		}
		if (event.getGroupId() == COLLECTION_BOX_GROUP)
		{
			collectionBoxOpen = true; // the tick poll resyncs the ledger while it stays open
		}
		if (event.getGroupId() == GE_GROUP)
		{
			geOpen = true; // the tick poll witnesses the viewed offer's pending boxes
		}
	}

	/** Click-through: the overlay stops with the welcome screen; the console mirror fires once. */
	@Subscribe
	public void onWidgetClosed(net.runelite.api.events.WidgetClosed event)
	{
		if (event.getGroupId() == COLLECTION_BOX_GROUP)
		{
			collectionBoxOpen = false;
		}
		if (event.getGroupId() == GE_GROUP)
		{
			geOpen = false;
		}
		if (event.getGroupId() != net.runelite.api.gameval.InterfaceID.WELCOME_SCREEN)
		{
			return;
		}
		if (custody != null)
		{
			custody.onWelcomeClosed();
		}
	}

	/**
	 * Container updates keep the ledger current: BANK (only readable while open) refreshes the frozen
	 * bank snapshot; INVENTORY/EQUIPMENT are tracked live. Any of them recomposes the totals.
	 */
	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() == InventoryID.BANK.getId())
		{
			// BANK-ONLY on purpose: inventory/equipment are live below — merging them here would
			// double-count the moment they change while the bank stays frozen.
			bankSnapshot = AssetSnapshot.of(Instant.now().toEpochMilli(), pairsOf(event.getItemContainer()));
			bankFreshThisSession = true;
			configManager.setRSProfileConfiguration(CONFIG_GROUP, ASSETS_KEY, gson.toJson(bankSnapshot));
			// Witnessing the bank restarts its custody chain: from here the photo carries across
			// logouts for as long as every gap keeps getting ACQUITTED.
			custody.trustBankChain();
		}
		else if (event.getContainerId() == InventoryID.INVENTORY.getId())
		{
			int[][] before = liveInventory;
			liveInventory = pairsOf(event.getItemContainer());
			if (collectArmedTicks > 0)
			{
				// The armed collect's arrivals: coins ride under the coin item id, the rest is stock.
				java.util.Map<Integer, Integer> arrived = CollectLedger.arrivals(before, liveInventory);
				Integer coins = arrived.remove(ItemIds.COINS);
				if (!arrived.isEmpty() || coins != null)
				{
					collectArmedTicks = 0;
					if (collectLedger.applyCollectDelta(arrived, coins == null ? 0 : coins))
					{
						persistCollectLedger();
					}
				}
			}
		}
		else if (event.getContainerId() == InventoryID.EQUIPMENT.getId())
		{
			liveEquipment = pairsOf(event.getItemContainer());
		}
		else
		{
			return;
		}
		recomputeAssets();
	}

	/**
	 * Dump a container to (id, qty) pairs. Two cache-variant hazards, handled in ORDER:
	 * (1) bank PLACEHOLDERS are DROPPED — they arrive with qty 1, not 0, and canonicalize()
	 * would resolve them to the REAL item, minting a phantom 1×item at full bid; a placeholder
	 * is not an asset. (2) survivors are CANONICALIZED so noted stacks fold to their real id —
	 * withdraw-as-note otherwise reports ids the price feed has never heard of and the stack
	 * silently counts ZERO. Client thread only (composition lookups).
	 */
	private int[][] pairsOf(ItemContainer container)
	{
		if (container == null)
		{
			return null;
		}
		Item[] items = container.getItems();
		int[][] pairs = new int[items.length][2];
		for (int i = 0; i < items.length; i++)
		{
			int id = items[i].getId();
			if (id > 0)
			{
				// A placeholder def carries the placeholder template; the real item's def does not.
				boolean placeholder =
					itemManager.getItemComposition(id).getPlaceholderTemplateId() != -1;
				id = placeholder ? 0 : itemManager.canonicalize(id);
			}
			pairs[i][0] = id;
			pairs[i][1] = items[i].getQuantity();
		}
		return pairs;
	}

	/**
	 * Compose the always-current asset view: frozen bank + live inventory/equipment + GE-held items
	 * and coins. No duplicates by construction — every source is authoritative for its own container.
	 */
	private void recomputeAssets()
	{
		FlipGoblinPanel target = panel;
		if (target == null || positions == null)
		{
			return;
		}
		AssetSnapshot bank = bankSnapshot;
		boolean fresh = bankFreshThisSession;
		// Custody: an ACQUITTED chain proves the stored photo was never out of our custody, so
		// it counts WITHOUT a re-open (estimate + history immediately). Otherwise the gated
		// regime holds — the estimate excludes the unwitnessed bank and syncs carry
		// bankFresh=false (no history row) until the first bank-open; the full composite still
		// syncs either way (the site keeps its last-known bank table).
		boolean trusted = fresh || (custody != null && custody.bankChainTrusted() && bank != null);
		// GE-held = working-offer escrow + the witnessed uncollected ledger (both branches).
		long geCoins = positions.heldCoins() + collectLedger.coins();
		int[][] uncollected = collectLedger.itemPairs();
		AssetSnapshot composite = AssetSnapshot.of(
			Instant.now().toEpochMilli(),
			geCoins,
			bank == null ? null : bank.pairs(),
			liveInventory,
			liveEquipment,
			positions.heldItemPairs(),
			uncollected);
		AssetSnapshot estimated = trusted || bank == null
			? composite
			: AssetSnapshot.of(
				Instant.now().toEpochMilli(),
				geCoins,
				liveInventory,
				liveEquipment,
				positions.heldItemPairs(),
				uncollected);
		long bankTs = bank == null ? 0 : bank.timestamp;
		// Dashboard-parity total: value the composite against the bulk bid map when we have one;
		// -1 = unknown (unlinked, or the first fetch hasn't landed). The refresh is TTL-guarded
		// inside PriceClient, and a successful refresh re-runs this recompute exactly once.
		PriceClient p = prices;
		java.util.Map<Integer, Long> bids = p == null ? null : p.bulkBids();
		long[] est = bids == null ? null : estimated.estimateValue(bids);
		long estTotal = est == null ? -1 : est[0];
		long unpriced = est == null ? 0 : est[1];
		if (p != null && isOperational())
		{
			String token = config.apiToken().trim();
			executor.submit(() ->
			{
				if (p.refreshBulkBids(API_BASE, token))
				{
					clientThread.invokeLater(this::recomputeAssets);
				}
			});
		}
		// "All characters" scope: the other linked characters' summed contribution rides the same
		// panel push (DISPLAY ONLY — the sync below stays strictly this character's composite).
		CharacterLedger.Totals others = config.panelScopeAll()
			? CharacterLedger.aggregate(otherCharacters, bids, Instant.now().toEpochMilli(), RECORDS_MAX_AGE_MS)
			: null;
		SwingUtilities.invokeLater(() ->
		{
			target.setOtherCharacters(others);
			target.updateAssets(estimated, bankTs, fresh, trusted, estTotal, unpriced);
		});
		maybeSyncAssets(composite, bankTs, trusted, positions.active());
	}

	/** The push scheduler. The token is the opt-in; see {@link AssetsPusher} for the burst rules. */
	private volatile AssetsPusher assetsPusher;

	/**
	 * Assets sync, on whenever the account token is configured. Builds the wire body and
	 * hands it to {@link AssetsPusher}, which settles event bursts and throttles pushes.
	 */
	private void maybeSyncAssets(AssetSnapshot composite, long bankTs, boolean bankFresh,
		List<GePositions.Position> offers)
	{
		AssetsPusher pusher = assetsPusher;
		if (composite == null || composite.totalStacks() == 0 || pusher == null)
		{
			return;
		}
		String token = config.apiToken().trim();
		if (token.isEmpty() || characterLocked)
		{
			return;
		}
		pusher.submit(SyncClient.assetsJson(composite, bankTs, bankFresh, offers));
	}

	/** The pusher's sender: one push through the gates the plugin owns. Executor thread. */
	private AssetsPusher.Sender.Result sendAssets(String body)
	{
		String token = config.apiToken().trim();
		SyncClient s = sync;
		if (token.isEmpty() || s == null || characterLocked)
		{
			return AssetsPusher.Sender.Result.DISABLED;
		}
		return s.pushAssets(API_BASE, token, rsn, body)
			? AssetsPusher.Sender.Result.OK : AssetsPusher.Sender.Result.FAIL;
	}

	private void pushPositionsToPanel()
	{
		FlipGoblinPanel target = panel;
		if (target == null)
		{
			return;
		}
		List<GePositions.Position> active = positions.active();
		Map<Integer, String> names = new java.util.HashMap<>(itemNames);
		SwingUtilities.invokeLater(() -> target.updatePositions(active, names));
	}

	private void seedFromProfile()
	{
		if (seededThisLogin || differ == null)
		{
			return;
		}
		seededThisLogin = true;
		String json = configManager.getRSProfileConfiguration(CONFIG_GROUP, BASELINE_KEY);
		if (json == null || json.isEmpty())
		{
			differ = new GeOfferDiffer();
			return;
		}
		try
		{
			Map<Integer, GeOfferDiffer.SlotState> seed = gson.fromJson(json, BASELINE_TYPE);
			differ = new GeOfferDiffer(seed);
			log.debug("seeded {} GE slot baselines from profile", seed == null ? 0 : seed.size());
		}
		catch (RuntimeException e)
		{
			log.warn("corrupt slot baseline in profile — starting fresh", e);
			differ = new GeOfferDiffer();
		}
	}

	/**
	 * Fold each cumulative GE offer event into the differ; a returned fill delta is a captured trade record.
	 * The GE exposes no server fill-time, so we stamp fills at event-arrival time (recovered fills carry
	 * their offline window — see GeOfferDiffer).
	 */
	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
	{
		seedFromProfile(); // ordering guard: no-op when already seeded this login
		GrandExchangeOffer offer = event.getOffer();
		if (GeOfferDiffer.isLogoutClear(offer.getState(), client.getGameState()))
		{
			// Client-side reset on logout/hop, not a collection — folding it would wipe the differ
			// baseline + positions board and orphan every fill that lands before re-login.
			return;
		}
		long ts = Instant.now().toEpochMilli();
		OfferSnapshot snapshot = new OfferSnapshot(
			offer.getItemId(),
			offer.getState(),
			offer.getTotalQuantity(),
			offer.getQuantitySold(),
			offer.getSpent(),
			offer.getPrice());
		if (offer.getItemId() > 0)
		{
			// Client thread — the only place ItemComposition may be read; UI layers get plain strings.
			itemNames.computeIfAbsent(offer.getItemId(), id -> itemManager.getItemComposition(id).getName());
		}
		positions.onOffer(event.getSlot(), snapshot, ts);
		// The uncollected ledger diffs the same stream (fills in, EMPTY = collected out).
		if (collectLedger.onOffer(event.getSlot(), snapshot, ts))
		{
			persistCollectLedger();
		}
		differ.onOffer(event.getSlot(), snapshot, ts)
			.ifPresent(r -> {
				// Forensic tripwire: an entire offer materializing as ONE live fill is either a
				// real instant fill or a stale-state refire that slipped the identity guards —
				// log the full event so any anomaly is diagnosable from client.log.
				if (!r.recovered && r.quantity == snapshot.totalQuantity)
				{
					log.info("[{}] full-quantity single-event fill: slot {} item {} {} {}x @ {} state {}",
						BUILD, event.getSlot(), r.itemId, r.side, r.quantity, r.price, snapshot.state);
				}
				records.add(r);
				saveRecords(); // fills survive restarts — cross-session cost basis
				if (r.recovered)
				{
					// Offline fills count in the uncollected ledger only under ACQUITTED (a stealth
					// session could have collected them). Buffer until judged; apply-or-drop there.
					if (custody != null && custody.overlayVerdict() == LoginCustody.Verdict.ACQUITTED)
					{
						collectLedger.applyFill(r);
						persistCollectLedger();
					}
					else if (custody == null || !custody.judged())
					{
						pendingRecoveredFills.add(r);
					}
				}
				if (r.recovered && r.offlineSince > 0)
				{
					executor.submit(() -> refineRecoveredFill(r)); // borrow the WHEN from the feed
				}
				sessionRealized = SessionStats.match(records).totalRealized;
				List<TradeRecord> copy = new ArrayList<>(records); // snapshot for the EDT
				Map<Integer, String> names = new java.util.HashMap<>(itemNames);
				FlipGoblinPanel target = panel;
				if (target != null)
				{
					SwingUtilities.invokeLater(() -> target.update(copy, names));
				}
				SyncClient s = sync;
				if (s != null)
				{
					s.enqueue(r);
					// Contributions ride the account link. The flush gate below is the single
					// token check; unlinked queues simply never send.
					s.enqueueCrowd(r);
					executor.submit(this::flushIfEnabled); // prompt first attempt; the 30s loop retries
				}
			});
		pushPositionsToPanel();
		recomputeAssets();
		// Every event moves the baseline (fills AND first-sightings/clears) — mirror it to the profile so
		// the next login recovers offline fills from exactly what this session last saw.
		configManager.setRSProfileConfiguration(CONFIG_GROUP, BASELINE_KEY, gson.toJson(differ.snapshotBaseline()));
	}

	// --- overlay reads (client thread only, same thread as all mutation — no sync needed) ---------------

	List<GePositions.Position> overlayPositions()
	{
		return positions == null ? java.util.Collections.emptyList() : positions.active();
	}

	long overlayRealized()
	{
		return sessionRealized;
	}

	String overlayName(int itemId)
	{
		String n = itemNames.get(itemId);
		return n != null ? n : "#" + itemId;
	}

	/** Server data flows only when a token is set. The token is the plugin's one data switch. */
	boolean isLinked()
	{
		return !config.apiToken().trim().isEmpty();
	}

	/** Linked AND not lapse-locked — the gate every network feature runs behind. */
	boolean isOperational()
	{
		return isLinked() && !characterLocked;
	}

	/** The lapse-lock verdict for display surfaces (panel Account row, GE panel message). */
	boolean isCharacterLocked()
	{
		return characterLocked;
	}

	/** Executor-side: re-ask the server whether this character operates; repaint on a change. */
	private void refreshLockStatus()
	{
		SyncClient s = sync;
		if (s == null)
		{
			return;
		}
		String token = config.apiToken().trim();
		boolean locked;
		if (token.isEmpty())
		{
			locked = false;
		}
		else
		{
			SyncClient.LinkCheck check = s.checkLink(API_BASE, token);
			if (check == SyncClient.LinkCheck.UNREACHABLE)
			{
				return; // keep the previous verdict — never flap on a network blip
			}
			locked = check == SyncClient.LinkCheck.LOCKED;
		}
		if (locked != characterLocked)
		{
			characterLocked = locked;
			log.info("[{}] character lock state → {}", BUILD, locked ? "LOCKED" : "active");
			pushLinkStatus();
			clientThread.invokeLater(this::recomputeAssets); // the estimate loses/regains its bids
		}
	}

	/** Cached market data (null until the first fetch lands; always null unlinked). Render-safe. */
	PriceClient.ItemPrices priceFor(int itemId)
	{
		PriceClient p = prices;
		if (p == null || !isOperational())
		{
			return null;
		}
		return p.get(API_BASE, config.apiToken().trim(), itemId);
	}

	/** When the interval's newest volume bucket reached this client; null before first data. */
	PriceClient.VolArrival volArrival(int itemId, String interval)
	{
		PriceClient p = prices;
		return p == null ? null : p.volArrival(itemId, interval);
	}

	/** When the next detail fetch is scheduled (epoch ms; 0 = unknown). Drives the panel countdown. */
	long nextDetailFetch(int itemId)
	{
		PriceClient p = prices;
		return p == null ? 0 : p.nextDetailFetch(itemId);
	}

	/** Cached candle series for the offer-panel charts (null unlinked). Render-safe. */
	PriceClient.Series seriesFor(int itemId, String interval, int limit)
	{
		PriceClient p = prices;
		if (p == null || !isOperational())
		{
			return null;
		}
		return p.getSeries(API_BASE, config.apiToken().trim(), itemId, interval, limit);
	}

	/**
	 * The active 4h limit window's provable usage, or null when nothing is provable.
	 * {@code buyLimit} may be null when the item's limit is unknown. Client thread.
	 */
	GeLimits.Usage limitUsage(int itemId, Long buyLimit)
	{
		List<TradeRecord> recs = records;
		return recs == null ? null : GeLimits.usage(recs, itemId, Instant.now().toEpochMilli(),
			observedSince, buyLimit == null ? 0 : buyLimit);
	}

	/** This item's session totals, or null when untraded this session. */
	SessionStats.ItemTotals sessionItemStats(int itemId)
	{
		List<TradeRecord> recs = records;
		if (recs == null || recs.isEmpty())
		{
			return null;
		}
		long buys = 0;
		long sells = 0;
		for (TradeRecord r : recs)
		{
			if (r.itemId != itemId)
			{
				continue;
			}
			if (r.side == TradeRecord.Side.BUY)
			{
				buys += r.quantity;
			}
			else
			{
				sells += r.quantity;
			}
		}
		if (buys == 0 && sells == 0)
		{
			return null;
		}
		long realized = 0;
		for (SessionStats.ItemPosition p : SessionStats.match(recs).items)
		{
			if (p.itemId == itemId)
			{
				realized = p.realized;
				break;
			}
		}
		return new SessionStats.ItemTotals(buys, sells, realized);
	}

	/**
	 * Refines a recovered fill's time. The fill's existence, price, and quantity are
	 * certain from the slot delta; only its time is a detection stamp. The public trade
	 * feed supplies candidate instants: minutes inside the offline window where the fill's
	 * side traded at exactly its price. The latest match wins, which is conservative for
	 * the 4h limit window, and no match keeps the detection time. The recovered flag and
	 * offlineSince stay, since the time remains an estimate either way, and the clientId
	 * is preserved so the server never sees a duplicate. Executor thread; the record swap
	 * hops back to the client thread.
	 */
	private void refineRecoveredFill(TradeRecord r)
	{
		PriceClient p = prices;
		if (p == null || !isOperational())
		{
			return;
		}
		int spanMin = (int) Math.min(10_080, (r.timestamp - r.offlineSince) / 60_000 + 3);
		if (spanMin < 2)
		{
			return;
		}
		PriceClient.Series s = p.fetchTicksBlocking(
			API_BASE, config.apiToken().trim(), r.itemId, spanMin);
		if (s == null)
		{
			return;
		}
		long refined = PriceClient.latestPriceMatchMs(s, r.side, r.price, r.offlineSince, r.timestamp);
		if (refined == 0 || refined == r.timestamp)
		{
			return;
		}
		clientThread.invokeLater(() ->
			applyRefinedTimes(java.util.Collections.singletonMap(r, refined)));
	}

	/**
	 * Batch time refinement for GE History imports, whose detection stamps pollute the
	 * record chronology: one 7-day tick fetch per distinct item, then the same
	 * latest-price-match rule per record. Executor thread; one client-thread hop applies
	 * everything.
	 */
	private void refineImports(List<TradeRecord> imports)
	{
		PriceClient p = prices;
		if (p == null || !isOperational())
		{
			return;
		}
		String base = API_BASE;
		String tok = config.apiToken().trim();
		Map<Integer, List<TradeRecord>> byItem = new java.util.HashMap<>();
		for (TradeRecord r : imports)
		{
			byItem.computeIfAbsent(r.itemId, k -> new ArrayList<>()).add(r);
		}
		Map<TradeRecord, Long> refinements = new java.util.HashMap<>();
		for (Map.Entry<Integer, List<TradeRecord>> e : byItem.entrySet())
		{
			PriceClient.Series s = p.fetchTicksBlocking(base, tok, e.getKey(), 10_080);
			if (s == null)
			{
				continue;
			}
			for (TradeRecord r : e.getValue())
			{
				long m = PriceClient.latestPriceMatchMs(s, r.side, r.price,
					r.timestamp - RECORDS_MAX_AGE_MS, r.timestamp);
				if (m > 0 && m != r.timestamp)
				{
					refinements.put(r, m);
				}
			}
		}
		if (!refinements.isEmpty())
		{
			clientThread.invokeLater(() -> applyRefinedTimes(refinements));
		}
	}

	/**
	 * Swaps each record for a copy at its refined time, keeping the clientId (so the
	 * server sees no duplicates) and the recovered semantics. Then re-sorts the list back
	 * to nondecreasing timestamps, since GeLimits reads in order, saves, and repaints.
	 * Client thread only.
	 */
	private void applyRefinedTimes(Map<TradeRecord, Long> refinements)
	{
		List<TradeRecord> recs = records;
		if (recs == null || refinements.isEmpty())
		{
			return;
		}
		int applied = 0;
		for (Map.Entry<TradeRecord, Long> e : refinements.entrySet())
		{
			int idx = recs.indexOf(e.getKey());
			if (idx < 0)
			{
				continue; // list was replaced (relog) — the persisted copy keeps its old time
			}
			TradeRecord r = e.getKey();
			recs.set(idx, new TradeRecord(r.itemId, r.side, r.price, r.quantity, r.spent,
				r.slot, e.getValue(), true, r.offlineSince, r.clientId));
			applied++;
		}
		if (applied == 0)
		{
			return;
		}
		recs.sort(java.util.Comparator.comparingLong((TradeRecord x) -> x.timestamp));
		saveRecords();
		sessionRealized = SessionStats.match(recs).totalRealized;
		List<TradeRecord> copy = new ArrayList<>(recs);
		Map<Integer, String> names = new java.util.HashMap<>(itemNames);
		FlipGoblinPanel target = panel;
		if (target != null)
		{
			SwingUtilities.invokeLater(() -> target.update(copy, names));
		}
		log.info("[{}] refined {} fill time(s) from the trade feed", BUILD, applied);
	}

	/** The session's most recent fill per side for this item: {last buy, last sell}; null when untraded. */
	TradeRecord[] lastFills(int itemId)
	{
		List<TradeRecord> recs = records;
		if (recs == null || recs.isEmpty())
		{
			return null;
		}
		TradeRecord buy = null;
		TradeRecord sell = null;
		for (TradeRecord r : recs)
		{
			if (r.itemId != itemId)
			{
				continue;
			}
			if (r.side == TradeRecord.Side.BUY)
			{
				buy = buy == null || r.timestamp >= buy.timestamp ? r : buy;
			}
			else
			{
				sell = sell == null || r.timestamp >= sell.timestamp ? r : sell;
			}
		}
		return buy == null && sell == null ? null : new TradeRecord[]{buy, sell};
	}

	/** The GE index screen's 8 slot cards, slot-ordered — hover → live-offer mapping + price tags. */
	static final int[] GE_INDEX_SLOTS = {
		net.runelite.api.gameval.InterfaceID.GeOffers.INDEX_0,
		net.runelite.api.gameval.InterfaceID.GeOffers.INDEX_1,
		net.runelite.api.gameval.InterfaceID.GeOffers.INDEX_2,
		net.runelite.api.gameval.InterfaceID.GeOffers.INDEX_3,
		net.runelite.api.gameval.InterfaceID.GeOffers.INDEX_4,
		net.runelite.api.gameval.InterfaceID.GeOffers.INDEX_5,
		net.runelite.api.gameval.InterfaceID.GeOffers.INDEX_6,
		net.runelite.api.gameval.InterfaceID.GeOffers.INDEX_7,
	};

	/**
	 * The placed offer whose index-slot card the hovered widget sits in, or null. The slot's layers
	 * carry no item id of their own (the icon is a separate child), so hovering a locked buy/sell
	 * slot needs this mapping: walk up to the INDEX_n ancestor, read that slot's live offer.
	 */
	private GrandExchangeOffer slotOffer(net.runelite.api.widgets.Widget w)
	{
		for (net.runelite.api.widgets.Widget a = w; a != null; a = a.getParent())
		{
			for (int slot = 0; slot < GE_INDEX_SLOTS.length; slot++)
			{
				if (a.getId() != GE_INDEX_SLOTS[slot])
				{
					continue;
				}
				GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
				GrandExchangeOffer o = offers == null || slot >= offers.length ? null : offers[slot];
				return o == null || o.getItemId() <= 0
					|| o.getState() == net.runelite.api.GrandExchangeOfferState.EMPTY ? null : o;
			}
		}
		return null;
	}

	/** Sell-side offer states (mirrors GePositions.sideOf's SELL arm). */
	private static boolean isSellState(net.runelite.api.GrandExchangeOfferState s)
	{
		return s == net.runelite.api.GrandExchangeOfferState.SELLING
			|| s == net.runelite.api.GrandExchangeOfferState.SOLD
			|| s == net.runelite.api.GrandExchangeOfferState.CANCELLED_SELL;
	}

	private GeHistoryImporter historyImporter;
	/** Start of the current unbroken login (epoch ms; 0 = not logged in yet this run).
	 * Hops keep it, since their gap fills arrive as recovered and drop exactness on their
	 * own; a fresh login resets it. Drives GeLimits' exact-count proof. */
	private volatile long observedSince;

	/**
	 * Polls the GE History tab through {@link GeHistoryImporter} and folds any new
	 * cost-basis records into the ledger: persist, recompute, repaint, and schedule the
	 * trade-feed time refinement.
	 */
	private void importGeHistory()
	{
		if (records == null || historyImporter == null)
		{
			return;
		}
		List<TradeRecord> importedRecs = historyImporter.poll(records, Instant.now().toEpochMilli());
		if (importedRecs == null || importedRecs.isEmpty())
		{
			return;
		}
		records.addAll(importedRecs);
		saveRecords();
		sessionRealized = SessionStats.match(records).totalRealized;
		for (TradeRecord r : records)
		{
			itemNames.computeIfAbsent(r.itemId, id -> itemManager.getItemComposition(id).getName());
		}
		List<TradeRecord> copy = new ArrayList<>(records);
		Map<Integer, String> names = new java.util.HashMap<>(itemNames);
		FlipGoblinPanel target = panel;
		if (target != null)
		{
			SwingUtilities.invokeLater(() -> target.update(copy, names));
		}
		log.info("[{}] GE history: imported {} cost-basis records", BUILD, importedRecs.size());
		executor.submit(() -> refineImports(importedRecs)); // date them from the trade feed
	}

	/**
	 * Hover tooltips (complementing the always-on anchored panel). The canonical
	 * item-prices pattern: per-frame, read the LAST menu entry (what the cursor is on),
	 * canonicalize (noted → tradeable id), tooltip. With the GE open (always on) the gate is the
	 * GE window + its side inventory; outside the GE the inventoryHover toggle extends it to the
	 * inventory (standalone or bank-side — the bank widget swaps the inventory's interface id).
	 * Widgets without an item id of their own fall back to the index-slot mapping, so hovering a
	 * locked buy/sell slot reads out that offer's item too. Untradeables are skipped — the price
	 * API has nothing for them, and a null-parse never caches, so they'd refetch every frame.
	 */
	@Subscribe
	public void onBeforeRender(net.runelite.api.events.BeforeRender event)
	{
		importGeHistory();
		boolean geOpen = client.getWidget(net.runelite.api.gameval.InterfaceID.GeOffers.FRAME) != null;
		if (!geOpen && !config.inventoryHover())
		{
			return;
		}
		net.runelite.api.MenuEntry[] entries = client.getMenuEntries();
		if (entries.length == 0)
		{
			return;
		}
		net.runelite.api.MenuEntry last = entries[entries.length - 1];
		int group = net.runelite.api.widgets.WidgetUtil.componentToInterface(last.getParam1());
		boolean onGe = group == net.runelite.api.gameval.InterfaceID.GE_OFFERS
			|| group == net.runelite.api.gameval.InterfaceID.GE_OFFERS_SIDE;
		boolean onInventory = group == net.runelite.api.gameval.InterfaceID.INVENTORY
			|| group == net.runelite.api.gameval.InterfaceID.BANKSIDE;
		if (geOpen ? !onGe : !onInventory)
		{
			return;
		}
		net.runelite.api.widgets.Widget w = last.getWidget();
		if (w == null)
		{
			return;
		}
		int rawId = w.getItemId();
		GrandExchangeOffer slotOffer = rawId > 0 ? null : slotOffer(w);
		int itemId = rawId > 0 ? itemManager.canonicalize(rawId)
			: slotOffer == null ? -1 : itemManager.canonicalize(slotOffer.getItemId());
		if (itemId <= 0 || !itemManager.getItemComposition(itemId).isTradeable())
		{
			return;
		}
		PriceClient.ItemPrices p = priceFor(itemId);
		GeLimits.Usage usage = limitUsage(itemId, p == null ? null : p.buyLimit);
		TradeRecord[] fills = lastFills(itemId);
		TradeRecord lastBuy = fills == null ? null : fills[0];
		TradeRecord lastSell = fills == null ? null : fills[1];
		String markup = p == null ? "Flip Goblin: fetching prices…"
			: GeTooltip.build(p, usage, usage == null ? null
					: (usage.hedged ? "by " : "") + GeLimits.resetTime(usage.resetAtMs),
				lastBuy, lastBuy == null ? null : GeLimits.resetTime(lastBuy.timestamp),
				lastSell, lastSell == null ? null : GeLimits.resetTime(lastSell.timestamp));
		// A hovered SELL slot with a known buy-in shows the flipper's unrealized margin: what the
		// unsold remainder nets (tax at the OFFER's price) over the last buy fill.
		if (p != null && slotOffer != null && lastBuy != null && isSellState(slotOffer.getState()))
		{
			int remaining = slotOffer.getTotalQuantity() - slotOffer.getQuantitySold();
			if (remaining > 0)
			{
				long each = SessionStats.netFromSale(slotOffer.getPrice(), itemId) - lastBuy.price;
				markup += "</br>" + GeTooltip.flipLine(each, each * remaining);
			}
		}
		// Own TooltipComponent (not the string form) so the background can be darker than
		// RuneLite's default brown — same darkness as the GE panels,
		// at the user's configured opacity.
		net.runelite.client.ui.overlay.components.TooltipComponent tip =
			new net.runelite.client.ui.overlay.components.TooltipComponent();
		tip.setText(markup);
		tip.setModIcons(client.getModIcons());
		int alpha = Math.max(0, Math.min(100, config.gePanelOpacity())) * 255 / 100;
		tip.setBackgroundColor(new java.awt.Color(0, 0, 0, alpha));
		tooltipManager.add(new net.runelite.client.ui.overlay.tooltip.Tooltip(tip));
	}

	/** Keeps the panel's Settings tab in step with config (writes from any surface live-apply). */
	@Subscribe
	public void onConfigChanged(net.runelite.client.events.ConfigChanged event)
	{
		FlipGoblinPanel pn = panel;
		if (pn != null && CONFIG_GROUP.equals(event.getGroup()))
		{
			SwingUtilities.invokeLater(pn::refreshSettings);
		}
		// The config page's "→ Open FlipGoblin settings" pseudo-button: EVERY toggle of the box
		// (tick or untick) opens the panel on its Settings tab. No write-back — nothing else
		// reads the value, and resetting it isn't repainted by the open config page anyway.
		if (CONFIG_GROUP.equals(event.getGroup()) && "openPanel".equals(event.getKey()))
		{
			NavigationButton nav = navButton;
			if (nav != null && pn != null)
			{
				SwingUtilities.invokeLater(() ->
				{
					clientToolbar.openPanel(nav);
					pn.showSettingsTab();
				});
			}
		}
		// "All characters" scope inputs changed: a link/unlink (any profile's token store) or the
		// scope toggle itself. Reload the parsed stores off-thread, then recompute on the client
		// thread (recomputeAssets touches client-thread state).
		if (CONFIG_GROUP.equals(event.getGroup())
			&& (TOKEN_PROFILE_KEY.equals(event.getKey()) || "panelScopeAll".equals(event.getKey())))
		{
			executor.submit(() ->
			{
				loadOtherCharacters();
				clientThread.invokeLater(this::recomputeAssets);
			});
		}
		// Per-character token memory: a paste while logged in writes
		// through to THIS character's profile store; clearing the field unlinks this character.
		// While logged out the paste stays in the field only — the next character to log in
		// adopts it (see syncTokenForProfile).
		if (CONFIG_GROUP.equals(event.getGroup()) && "apiToken".equals(event.getKey()))
		{
			// A different token may carry a different lock verdict — re-ask (also clears the
			// stale LOCKED state instantly when the token is removed).
			executor.submit(this::refreshLockStatus);
		}
		if (CONFIG_GROUP.equals(event.getGroup()) && "apiToken".equals(event.getKey()) && !mirroringToken)
		{
			String profileKey = configManager.getRSProfileKey();
			if (profileKey != null)
			{
				String v = config.apiToken().trim();
				if (v.isEmpty())
				{
					configManager.unsetRSProfileConfiguration(CONFIG_GROUP, TOKEN_PROFILE_KEY);
					configManager.unsetConfiguration(CONFIG_GROUP, TOKEN_OWNER_KEY);
				}
				else
				{
					configManager.setRSProfileConfiguration(CONFIG_GROUP, TOKEN_PROFILE_KEY, v);
					configManager.setConfiguration(CONFIG_GROUP, TOKEN_OWNER_KEY, profileKey);
				}
			}
		}
	}

	/**
	 * Per-character token memory, login half. The visible config field always shows the CURRENT
	 * character's token: a stored profile token mirrors into the field; a non-empty field with no
	 * profile entry is a fresh paste that this character ADOPTS; a field
	 * still mirroring ANOTHER character's token clears instead — each character links its own.
	 * Client thread (LOGGED_IN).
	 */
	private void syncTokenForProfile()
	{
		String profileKey = configManager.getRSProfileKey();
		if (profileKey == null)
		{
			return;
		}
		mirroringToken = true;
		try
		{
			String stored = configManager.getRSProfileConfiguration(CONFIG_GROUP, TOKEN_PROFILE_KEY);
			String field = config.apiToken().trim();
			if (stored != null && !stored.isEmpty())
			{
				if (!stored.equals(field))
				{
					configManager.setConfiguration(CONFIG_GROUP, "apiToken", stored);
				}
				configManager.setConfiguration(CONFIG_GROUP, TOKEN_OWNER_KEY, profileKey);
			}
			else if (!field.isEmpty())
			{
				String owner = configManager.getConfiguration(CONFIG_GROUP, TOKEN_OWNER_KEY);
				if (owner == null || owner.isEmpty() || owner.equals(profileKey))
				{
					configManager.setRSProfileConfiguration(CONFIG_GROUP, TOKEN_PROFILE_KEY, field);
					configManager.setConfiguration(CONFIG_GROUP, TOKEN_OWNER_KEY, profileKey);
				}
				else
				{
					configManager.setConfiguration(CONFIG_GROUP, "apiToken", "");
					configManager.unsetConfiguration(CONFIG_GROUP, TOKEN_OWNER_KEY);
				}
			}
		}
		finally
		{
			mirroringToken = false;
		}
		pushLinkStatus();
	}

	/** Off the client thread (executor). Only ever sends when the user opted in AND configured both fields. */
	private void flushIfEnabled()
	{
		SyncClient s = sync;
		if (s == null)
		{
			return;
		}
		String base = API_BASE;
		String token = config.apiToken().trim();
		if (token.isEmpty() || characterLocked)
		{
			return; // locked: queues HOLD (idempotent client ids) and drain the moment we unlock
		}
		// The token is the one data switch: trades and the crowd stream both flow whenever
		// linked, disclosed together on the token config item.
		if (s.pendingCount() > 0)
		{
			s.flush(base, token, rsn);
		}
		if (s.crowdPendingCount() > 0)
		{
			s.flushCrowd(base, token);
		}
	}

	/** Refresh website targets (executor) — inert until the account link is configured.
	 *  Doubles as the lapse-lock heartbeat (every 5 min): re-verdicts before deciding to fetch. */
	private void refreshTargetsIfLinked()
	{
		refreshLockStatus();
		TargetsClient t = targets;
		if (t == null)
		{
			return;
		}
		String base = API_BASE;
		String token = config.apiToken().trim();
		if (token.isEmpty() || characterLocked)
		{
			return;
		}
		t.refresh(gson, base, token);
	}

	/** The website target (watchlist/alerts) for an item, or null. Render-thread safe. */
	TargetsClient.Target targetFor(int itemId)
	{
		TargetsClient t = targets;
		return t == null ? null : t.get(itemId);
	}

	@Provides
	FlipGoblinConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(FlipGoblinConfig.class);
	}
}
