package com.flipgoblin;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.VarPlayer;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.PanelComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * Market info wrapped around the Grand Exchange window: an always-on graph below the
 * window, an opt-in second graph above it (both timeframes configurable), and an info
 * panel to the right. While the player is setting up or viewing an offer, the panel shows
 * that item's market data: ask/bid, after-tax margin, ROI, 24h volume per side, buy limit,
 * and data age. The charts span the GE window's width, each with a readout panel at its
 * right edge, because text drawn over the lines was unreadable. Always on; there is no
 * config toggle.
 *
 * There are two triggers because the client models them differently: composing an offer
 * sets the CURRENT_GE_ITEM varp (reset to -1 on confirm), while viewing a placed offer
 * only sets the GE_SELECTEDSLOT varbit (1-based; 0 means the index screen). In the second
 * case the item comes from that slot's live offer, which also feeds the "Your offer"
 * progress line.
 *
 * Anchors to the GE widget's live bounds. Renders nothing when the GE is closed or no
 * item is selected. Prices come from PriceClient's cache, so render never blocks.
 */
@Slf4j
public class GeInfoOverlay extends Overlay
{
	private static final Color GOLD = new Color(0xd4, 0xaf, 0x37);
	private static final Color PROFIT = new Color(0x3f, 0xb9, 0x50);
	private static final Color LOSS = new Color(0xf8, 0x51, 0x49);
	private static final Color MUTED = Color.LIGHT_GRAY;
	// Chart series colors — the website's exactly (apps/web/lib/candles.ts ASK_COLOR / BID_COLOR).
	private static final Color ASK_LINE = new Color(0xe8, 0xb0, 0x4f);
	private static final Color BID_LINE = new Color(0x38, 0xbd, 0xf8);
	/** The purchase-price reference line while selling a tracked flip. */
	private static final Color REF_LINE = new Color(0xf8, 0x51, 0x49, 190);
	// Trade-dot colors — the site's DOT_MAX_ALPHA (0.6) exactly: softer than the lines they sit on.
	private static final Color ASK_DOT = new Color(0xe8, 0xb0, 0x4f, 153);
	private static final Color BID_DOT = new Color(0x38, 0xbd, 0xf8, 153);
	/** One background for the chart boxes and every panel, at the configured opacity. */
	private Color panelBg()
	{
		int a = Math.max(0, Math.min(100, config.gePanelOpacity())) * 255 / 100;
		return new Color(0, 0, 0, a);
	}

	private final Client client;
	private final FlipGoblinPlugin plugin;
	private final FlipGoblinConfig config;
	private final PanelComponent panel = new PanelComponent();
	// The readout panels persist (one per chart slot) because PanelComponent draws its
	// background from the size cached on the previous render. A fresh instance per frame
	// painted a tiny default square with no visible background.
	private final PanelComponent topReadout = new PanelComponent();
	private final PanelComponent botReadout = new PanelComponent();
	private String lastGate;

	/** Chart box height. */
	private static final int CHART_H = 176;
	private static final int GAP = 6;

	@Inject
	GeInfoOverlay(Client client, FlipGoblinPlugin plugin, FlipGoblinConfig config)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		// Do not use isHidden() here. It walks the ancestor chain, and this interface's root
		// layer reads hidden on the live client even while the GE is plainly on screen.
		// Loaded versus null is the real open/closed signal: the widget goes null when the
		// GE closes.
		Widget ge = client.getWidget(InterfaceID.GeOffers.FRAME);
		if (ge == null)
		{
			trace("FRAME null (GE closed, or child index moved on this client rev)");
			return null;
		}
		Rectangle b = ge.getBounds();
		if (b == null || b.width <= 0 || b.height <= 0)
		{
			trace("degenerate bounds " + b);
			return null; // open/close transition frame — no real geometry to anchor to yet
		}
		int varp = client.getVarpValue(VarPlayer.CURRENT_GE_ITEM);
		GrandExchangeOffer viewed = viewedOffer(varp);
		int itemId = viewed != null ? viewed.getItemId() : varp;
		if (itemId <= 0)
		{
			// Slot price tags belong only on the index screen. "No item selected" is not
			// enough of a check: composing into an empty slot keeps the item varp unset
			// while the setup screen covers the cards, and a stray tag would float over
			// "Choose an item…". GE_SELECTEDSLOT is set the whole time any slot screen is
			// open, so 0 means the index screen, where the cards are visible.
			if (client.getVarbitValue(VarbitID.GE_SELECTEDSLOT) == 0)
			{
				drawSlotPrices(graphics);
			}
			trace("no item: varp=" + varp
				+ " selectedSlot=" + client.getVarbitValue(VarbitID.GE_SELECTEDSLOT));
			return null;
		}
		trace("rendering item=" + itemId + (viewed != null ? " (viewed offer)" : " (setup)")
			+ " at " + b.x + "," + b.y + " " + b.width + "x" + b.height);

		PriceClient.ItemPrices p = plugin.priceFor(itemId);
		// Flip context: selling something this plugin saw us buy. This powers the panel's
		// purchase, break-even, and expected-P/L rows, and the red purchase-price line on
		// the graphs. The setup side comes from the composer varbits, and a placed sell
		// carries its own price. The cost basis is the last tracked buy, the same basis as
		// the Last buy row.
		boolean setupSell = viewed == null && client.getVarbitValue(VarbitID.GE_NEWOFFER_TYPE) == 1;
		boolean viewedSell = viewed != null && sellSide(viewed.getState())
			&& viewed.getTotalQuantity() - viewed.getQuantitySold() > 0;
		TradeRecord[] fills = plugin.lastFills(itemId);
		TradeRecord lastBuy = fills == null ? null : fills[0];
		long purchasePrice = (setupSell || viewedSell) && lastBuy != null ? lastBuy.price : 0;

		panel.getChildren().clear();
		panel.getChildren().add(TitleComponent.builder()
			.text(p != null && p.name != null ? p.name : plugin.overlayName(itemId)).color(GOLD).build());
		if (viewed != null)
		{
			addYourOfferRow(viewed);
		}
		if (p == null)
		{
			addNoDataRows();
		}
		else
		{
			addMarketRows(p);
			addFlipRows(p, itemId, setupSell || viewedSell, viewedSell, viewed, lastBuy, purchasePrice);
			addLimitRows(p, itemId);
			addActivityRows(p, itemId, fills);
		}
		return layoutPanelAndCharts(graphics, b, p, itemId, purchasePrice);
	}

	/** The "Your offer" progress row for a viewed placed offer. */
	private void addYourOfferRow(GrandExchangeOffer viewed)
	{
		boolean buy = viewed.getState() == GrandExchangeOfferState.BUYING
			|| viewed.getState() == GrandExchangeOfferState.BOUGHT
			|| viewed.getState() == GrandExchangeOfferState.CANCELLED_BUY;
		panel.getChildren().add(LineComponent.builder()
			.left("Your offer")
			.right((buy ? "Buy " : "Sell ") + viewed.getQuantitySold() + "/" + viewed.getTotalQuantity()
				+ " @ " + Gp.exact(viewed.getPrice()))
			.rightColor(Color.WHITE)
			.build());
	}

	/** What to show while no market data is available: why, not a spinner forever. */
	private void addNoDataRows()
	{
		if (plugin.isLinked() && plugin.isCharacterLocked())
		{
			// Lapse lock: the server refuses this character. Say why instead of
			// spinning on "fetching" forever.
			panel.getChildren().add(LineComponent.builder()
				.left("Character locked").leftColor(Color.RED).build());
			panel.getChildren().add(LineComponent.builder()
				.left("another linked character holds the slot —").leftColor(MUTED).build());
			panel.getChildren().add(LineComponent.builder()
				.left("unlink it at:").leftColor(MUTED).build());
			panel.getChildren().add(LineComponent.builder()
				.left("flipgoblin.com/settings").leftColor(MUTED).build());
		}
		else if (!plugin.isLinked())
		{
			// Market data needs a linked account. Say so instead of spinning forever
			// on "fetching".
			panel.getChildren().add(LineComponent.builder()
				.left("Link your Flip Goblin account (free)").leftColor(GOLD).build());
			panel.getChildren().add(LineComponent.builder()
				.left("for live prices, margins + charts:").leftColor(MUTED).build());
			panel.getChildren().add(LineComponent.builder()
				.left("flipgoblin.com/settings").leftColor(MUTED).build());
		}
		else
		{
			panel.getChildren().add(LineComponent.builder().left("fetching prices…").leftColor(MUTED).build());
		}
	}

	/** The live market rows: ask, bid, tax and limit, market margin. */
	private void addMarketRows(PriceClient.ItemPrices p)
	{
		// Real prices are exact; undercutting is decided in the last coins.
		panel.getChildren().add(LineComponent.builder()
			.left("Buy (ask)").right(Gp.exactOrDash(p.ask)).rightColor(Color.WHITE).build());
		panel.getChildren().add(LineComponent.builder()
			.left("Sell (bid)").right(Gp.exactOrDash(p.bid)).rightColor(Color.WHITE).build());
		panel.getChildren().add(LineComponent.builder()
			.left("Tax / limit")
			.right(Gp.exactOrDash(p.tax) + " / " + (p.buyLimit == null ? "—" : String.valueOf(p.buyLimit)))
			.rightColor(MUTED)
			.build());
		Color marginColor = p.margin == null ? MUTED : p.margin > 0 ? PROFIT : LOSS;
		panel.getChildren().add(LineComponent.builder()
			.left("Market Margin")
			.right(Gp.exactOrDash(p.margin) + (p.roi == null ? "" : String.format(" · %.1f%%", p.roi * 100)))
			.rightColor(marginColor)
			.build());
	}

	/**
	 * The flip rows. While selling (composing a sell offer or viewing a placed one), show
	 * the tracked purchase price, the after-tax break-even ask, and, once a price is set,
	 * the expected P/L at that price. With no tracked buy the section still shows a dash
	 * instead of hiding, because a vanished row reads as "where's my margin?".
	 */
	private void addFlipRows(PriceClient.ItemPrices p, int itemId, boolean selling,
		boolean viewedSell, GrandExchangeOffer viewed, TradeRecord lastBuy, long purchasePrice)
	{
		if (!selling)
		{
			return;
		}
		panel.getChildren().add(LineComponent.builder().left(" ").build()); // spacer
		if (lastBuy == null)
		{
			panel.getChildren().add(LineComponent.builder()
				.left("Flip")
				.right("— (no tracked buy)")
				.rightColor(MUTED)
				.build());
			return;
		}
		panel.getChildren().add(LineComponent.builder()
			.left("Purchase price")
			.right(Gp.exact(purchasePrice))
			.rightColor(Color.WHITE)
			.build());
		panel.getChildren().add(LineComponent.builder()
			.left("Break-even")
			.right(Gp.exact(SessionStats.breakevenAsk(purchasePrice, itemId)))
			.rightColor(GOLD)
			.build());
		long setPrice = viewedSell ? viewed.getPrice()
			: client.getVarbitValue(VarbitID.GE_NEWOFFER_PRICE);
		long qty = viewedSell ? viewed.getTotalQuantity() - viewed.getQuantitySold()
			: Math.max(1, client.getVarbitValue(VarbitID.GE_NEWOFFER_QUANTITY));
		if (setPrice > 0)
		{
			long each = SessionStats.netFromSale(setPrice, itemId) - purchasePrice;
			panel.getChildren().add(LineComponent.builder()
				.left("Expected P/L")
				.right(Gp.exact(each) + "/ea · " + Gp.shortForm(each * qty))
				.rightColor(each > 0 ? PROFIT : each < 0 ? LOSS : MUTED)
				.build());
		}
	}

	/** The 4h buy-limit rows: bought, limit left, and the reset countdown. */
	private void addLimitRows(PriceClient.ItemPrices p, int itemId)
	{
		// The 4h buy-limit usage seen this session. "≥" because buys before the plugin
		// ran are invisible. Red once the known count reaches the limit.
		GeLimits.Usage limit = plugin.limitUsage(itemId, p.buyLimit);
		// exact means the whole window was watched live, so no ≥/≤ hedging.
		boolean exact = limit != null && limit.exact;
		if (limit != null)
		{
			boolean capped = p.buyLimit != null && limit.used >= p.buyLimit;
			panel.getChildren().add(LineComponent.builder()
				.left("Bought (4h)")
				.right((exact ? "" : "≥") + limit.used + (p.buyLimit == null ? "" : "/" + p.buyLimit))
				.rightColor(capped ? LOSS : MUTED)
				.build());
		}
		// The useful complement: what can still be bought this window. "≤" because
		// unseen pre-session buys can only shrink it.
		if (p.buyLimit != null)
		{
			long left = Math.max(0, p.buyLimit - (limit == null ? 0 : limit.used));
			panel.getChildren().add(LineComponent.builder()
				.left("Limit left")
				.right((exact ? "" : "≤") + left)
				.rightColor(left == 0 ? LOSS : Color.WHITE)
				.build());
		}
		// The window's reset moment on its own row, with a Next-Update-style countdown
		// beside the wall-clock time.
		if (limit != null)
		{
			// When recovered fills are involved, the reset time is only an upper bound.
			String by = limit.hedged ? "by " : "";
			long resetIn = Math.max(0, (limit.resetAtMs - System.currentTimeMillis()) / 1000);
			panel.getChildren().add(LineComponent.builder()
				.left("Limit resets")
				.right(by + GeLimits.resetTime(limit.resetAtMs) + (resetIn == 0 ? " · now" : " · in " + dur(resetIn)))
				.rightColor(MUTED)
				.build());
		}
	}

	/** Volume, watchlist targets, the session's own trades, and the refresh countdown. */
	private void addActivityRows(PriceClient.ItemPrices p, int itemId, TradeRecord[] fills)
	{
		panel.getChildren().add(LineComponent.builder()
			.left("24h vol (ask/bid)")
			.right(numOrDash(p.askVolume) + " / " + numOrDash(p.bidVolume))
			.rightColor(MUTED)
			.build());
		// The user's website targets for this item: the watched flag and alert
		// thresholds, managed on the site. Absent silently when not linked.
		TargetsClient.Target target = plugin.targetFor(itemId);
		if (target != null)
		{
			if (target.watched)
			{
				panel.getChildren().add(LineComponent.builder()
					.left("★ on your watchlist")
					.leftColor(GOLD)
					.build());
			}
			for (TargetsClient.TargetAlert a : target.alerts)
			{
				if (a.enabled)
				{
					panel.getChildren().add(LineComponent.builder()
						.left("alert")
						.leftColor(MUTED)
						.right(TargetsClient.alertLabel(a))
						.rightColor(GOLD)
						.build());
				}
			}
		}
		// The user's own trades for this item, from this session only.
		SessionStats.ItemTotals session = plugin.sessionItemStats(itemId);
		if (session != null)
		{
			panel.getChildren().add(LineComponent.builder()
				.left("You (7d)")
				.right(session.bought + "B/" + session.sold + "S · " + Gp.shortForm(session.realized))
				.rightColor(session.realized > 0 ? PROFIT : session.realized < 0 ? LOSS : MUTED)
				.build());
		}
		// The session's most recent fill per side: the entry and exit prices this offer is priced against.
		if (fills != null)
		{
			if (fills[0] != null)
			{
				panel.getChildren().add(LineComponent.builder()
					.left("Last buy")
					.right(Gp.exact(fills[0].price) + " ×" + fills[0].quantity
						+ " · " + GeLimits.resetTime(fills[0].timestamp))
					.rightColor(Color.WHITE)
					.build());
			}
			if (fills[1] != null)
			{
				panel.getChildren().add(LineComponent.builder()
					.left("Last sell")
					.right(Gp.exact(fills[1].price) + " ×" + fills[1].quantity
						+ " · " + GeLimits.resetTime(fills[1].timestamp))
					.rightColor(Color.WHITE)
					.build());
			}
		}
		// Countdown to the next detail refresh. The fetch fires when the API's cache
		// entry turns over, so hitting zero coincides with fresh data actually arriving.
		long nextS = Math.max(0,
			(plugin.nextDetailFetch(itemId) - System.currentTimeMillis()) / 1000);
		panel.getChildren().add(LineComponent.builder()
			.left("Next Update").right(nextS == 0 ? "now" : "in " + nextS + "s")
			.rightColor(MUTED).build());
	}

	/**
	 * Lays out the info panel to the right of the GE window (clamped on-canvas) and the
	 * charts above and below it, then draws them. Returns the overlay's total size.
	 */
	private Dimension layoutPanelAndCharts(Graphics2D graphics, Rectangle b,
		PriceClient.ItemPrices p, int itemId, long purchasePrice)
	{
		Color bg = panelBg();
		panel.setBackgroundColor(bg);
		topReadout.setBackgroundColor(bg);
		botReadout.setBackgroundColor(bg);
		int panelWidth = Math.max(230, b.width / 2);
		int px = Math.min(b.x + b.width + GAP, client.getCanvasWidth() - panelWidth - 4);
		int py = b.y;
		panel.setPreferredSize(new Dimension(panelWidth, 0));
		graphics.translate(px, py);
		Dimension d = panel.render(graphics);
		graphics.translate(-px, -py);

		int chartW = Math.max(460, b.width);
		net.runelite.api.Point mouse = client.getMouseCanvasPosition();
		int top = b.y;
		int bottom = Math.max(b.y + b.height, py + (d == null ? 0 : d.height));
		int right = px + panelWidth;
		FlipGoblinConfig.Timeframe topTf = config.geTopGraph();
		FlipGoblinConfig.Timeframe botTf = config.geBottomGraph();
		PriceClient.Series topS = !config.geShowTopGraph() || p == null
			? null : plugin.seriesFor(itemId, topTf.interval, topTf.points);
		PriceClient.Series botS = p == null
			? null : plugin.seriesFor(itemId, botTf.interval, botTf.points);
		if (topS != null && topS.ask.length >= 2)
		{
			int cy = Math.max(4, b.y - CHART_H - GAP);
			drawChartAt(graphics, topS, volumeSource(topTf, topS, itemId), volArrival(topTf, itemId),
				bg, chartW, panelWidth, b.x, cy, mouse, topTf, topReadout, itemId, purchasePrice);
			top = Math.min(top, cy);
			right = Math.max(right, b.x + chartW + GAP + panelWidth);
		}
		if (botS != null && botS.ask.length >= 2)
		{
			int cy = b.y + b.height + GAP;
			drawChartAt(graphics, botS, volumeSource(botTf, botS, itemId), volArrival(botTf, itemId),
				bg, chartW, panelWidth, b.x, cy, mouse, botTf, botReadout, itemId, purchasePrice);
			bottom = Math.max(bottom, cy + CHART_H);
			right = Math.max(right, b.x + chartW + GAP + panelWidth);
		}

		return new Dimension(right - b.x, bottom - top);
	}

	/**
	 * Where a graph's volume pane reads from. Candle graphs carry their own volumes. The 1m
	 * tick graph borrows the 5m grid, since ticks carry no volume and the site renders
	 * 5m-window blocks there. The 48-slot depth matches the 4 Hours timeframe so both share
	 * one cache entry.
	 */
	private PriceClient.Series volumeSource(FlipGoblinConfig.Timeframe tf, PriceClient.Series s, int itemId)
	{
		return "1m".equals(tf.interval) ? plugin.seriesFor(itemId, "5m", 48) : s;
	}

	/** The observed volume-arrival marker for the 1m graph's 5m companion; null for candle graphs. */
	private PriceClient.VolArrival volArrival(FlipGoblinConfig.Timeframe tf, int itemId)
	{
		return "1m".equals(tf.interval) ? plugin.volArrival(itemId, "5m") : null;
	}

	/** Translates to (x, y), draws one chart box + readout panel with the mouse mapped into that space. */
	private static void drawChartAt(Graphics2D g, PriceClient.Series s, PriceClient.Series volSrc,
		PriceClient.VolArrival volArrival, Color bg, int width, int sideWidth, int x, int y,
		net.runelite.api.Point mouse, FlipGoblinConfig.Timeframe tf, PanelComponent side, int itemId,
		long refPrice)
	{
		int mx = mouse == null ? -1 : mouse.getX() - x;
		int my = mouse == null ? -1 : mouse.getY() - y;
		g.translate(x, y);
		drawChart(g, s, volSrc, volArrival, bg, width, sideWidth, mx, my, tf, side, itemId, refPrice);
		g.translate(-x, -y);
	}

	/**
	 * The slot-card price tags. Each active offer's card gets its side's live market price:
	 * the ask for sell offers, the bid for buy offers. Green when our offer is priced to
	 * fill (a sell at or below the ask, a buy at or above the bid), red otherwise. Drawn
	 * centered on the card's progress bar over a dark translucent backing sized to the
	 * text, since placements in the name area collided with two-line item names. Only
	 * called on the index screen; the details and setup screens cover the cards.
	 */
	private void drawSlotPrices(Graphics2D g)
	{
		GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (offers == null)
		{
			return;
		}
		java.awt.Font prevFont = g.getFont();
		g.setFont(net.runelite.client.ui.FontManager.getRunescapeSmallFont());
		java.awt.FontMetrics fm = g.getFontMetrics();
		for (int i = 0; i < FlipGoblinPlugin.GE_INDEX_SLOTS.length && i < offers.length; i++)
		{
			GrandExchangeOffer o = offers[i];
			if (o == null || o.getItemId() <= 0 || o.getState() == GrandExchangeOfferState.EMPTY)
			{
				continue;
			}
			Widget slot = client.getWidget(FlipGoblinPlugin.GE_INDEX_SLOTS[i]);
			Rectangle sb = slot == null ? null : slot.getBounds();
			if (sb == null || sb.width <= 0 || sb.height <= 0)
			{
				continue;
			}
			boolean buyOffer = o.getState() == GrandExchangeOfferState.BUYING
				|| o.getState() == GrandExchangeOfferState.BOUGHT
				|| o.getState() == GrandExchangeOfferState.CANCELLED_BUY;
			PriceClient.ItemPrices p = plugin.priceFor(o.getItemId());
			Long market = p == null ? null : buyOffer ? p.bid : p.ask;
			if (market == null)
			{
				continue;
			}
			Rectangle bar = barBounds(slot, sb);
			if (bar == null)
			{
				bar = new Rectangle(sb.x + 4, sb.y + sb.height - 18, sb.width - 8, 15);
			}
			String txt = Gp.exact(market);
			boolean fills = buyOffer ? o.getPrice() >= market : o.getPrice() <= market;
			int tw = fm.stringWidth(txt);
			int tx = bar.x + (bar.width - tw) / 2;
			int baseline = bar.y + (bar.height + fm.getAscent() - fm.getDescent()) / 2;
			g.setColor(panelBg());
			g.fillRect(tx - 3, bar.y + 1, tw + 6, bar.height - 2);
			g.setColor(Color.BLACK);
			g.drawString(txt, tx + 1, baseline + 1);
			g.setColor(fills ? PROFIT : LOSS);
			g.drawString(txt, tx, baseline);
		}
		g.setFont(prevFont);
	}

	/**
	 * The slot card's offer-progress bar: the widest short (≤20px) NON-TEXT child in the card's
	 * bottom half; null when not found (caller falls back to the card's bottom strip). Text-bearing
	 * children are excluded because the offer-PRICE label sits directly under the bar with near-
	 * identical geometry and would win the width contest.
	 */
	private static Rectangle barBounds(Widget slot, Rectangle sb)
	{
		Rectangle best = null;
		Widget[][] groups = {slot.getStaticChildren(), slot.getDynamicChildren(), slot.getNestedChildren()};
		for (Widget[] grp : groups)
		{
			if (grp == null)
			{
				continue;
			}
			for (Widget ch : grp)
			{
				String t = ch == null ? null : ch.getText();
				Rectangle b = ch == null || (t != null && !t.isEmpty()) ? null : ch.getBounds();
				if (b == null || b.height <= 0 || b.height > 20 || b.y < sb.y + sb.height / 2)
				{
					continue;
				}
				if (best == null || b.width > best.width)
				{
					best = b;
				}
			}
		}
		return best;
	}

	/**
	 * Logs the render verdict whenever it changes: a handful of lines per GE visit, silent
	 * otherwise. Kept at info level on purpose, so a silently blank panel names which gate
	 * tripped.
	 */
	private void trace(String gate)
	{
		if (!gate.equals(lastGate))
		{
			lastGate = gate;
			log.info("[{}] GE panel: {}", FlipGoblinPlugin.BUILD, gate);
		}
	}

	/**
	 * The placed offer whose details screen is open, or null. Only consulted when no offer is being
	 * composed (composing wins — its varp carries the item before any offer exists). GE_SELECTEDSLOT
	 * stays set while composing INTO an empty slot, so the EMPTY guard keeps pre-selection blank.
	 */
	private GrandExchangeOffer viewedOffer(int composingItemId)
	{
		if (composingItemId > 0)
		{
			return null;
		}
		int slot = client.getVarbitValue(VarbitID.GE_SELECTEDSLOT) - 1;
		GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (slot < 0 || offers == null || slot >= offers.length)
		{
			return null;
		}
		GrandExchangeOffer o = offers[slot];
		return o == null || o.getState() == GrandExchangeOfferState.EMPTY || o.getItemId() <= 0 ? null : o;
	}

	/** Sell-side offer states (mirrors GePositions.sideOf's SELL arm). */
	private static boolean sellSide(GrandExchangeOfferState s)
	{
		return s == GrandExchangeOfferState.SELLING
			|| s == GrandExchangeOfferState.SOLD
			|| s == GrandExchangeOfferState.CANCELLED_SELL;
	}

	private static final java.time.format.DateTimeFormatter HOVER_TIME =
		java.time.format.DateTimeFormatter.ofPattern("MMM d HH:mm");

	/**
	 * Draws one series in a translucent box at (0, 0) plus its readout panel to the right,
	 * because text over the lines was unreadable. The panel always shows high/low. Inside
	 * the box a crosshair snaps to the nearest bucket, and the panel reads out time, ask,
	 * bid, and spread (raw, before tax). The step lines, series colors, and the mirrored
	 * volume pane in the bottom fifth (ask up, bid down) match the website's lines view.
	 * {@code volSrc} supplies the volumes: the series itself for candle graphs, or the 5m
	 * companion grid for the 1m graph.
	 */
	private static void drawChart(Graphics2D g, PriceClient.Series s, PriceClient.Series volSrc,
		PriceClient.VolArrival volArrival, Color bg, int width, int sideWidth, int mx, int my,
		FlipGoblinConfig.Timeframe tf, PanelComponent side, int itemId, long refPrice)
	{
		final int h = CHART_H;
		final int pad = 3;
		g.setColor(bg);
		g.fillRect(0, 0, width, h);

		double min = Double.POSITIVE_INFINITY;
		double max = Double.NEGATIVE_INFINITY;
		for (int i = 0; i < s.ask.length; i++)
		{
			for (double v : new double[]{s.ask[i], s.bid[i]})
			{
				if (!Double.isNaN(v))
				{
					min = Math.min(min, v);
					max = Math.max(max, v);
				}
			}
		}
		if (!(max > min))
		{
			return; // flat/empty series — box only
		}

		int n = s.ask.length;
		double xStep = (double) (width - 2 * pad) / (n - 1);
		// Prices live in the top ~4/5 of the box; the bottom fifth is the volume pane (site parity).
		final int volH = h / 5;
		final int priceH = h - volH;

		// The website's step lines carry the last price across quiet buckets: a NaN side
		// means no trade that bucket, not price unknown. So carry each side forward before
		// drawing, and rarely traded items render plateaus instead of holes. Leading NaNs,
		// before the item's first data, stay blank. The 1m tick series arrives pre-carried.
		double[] ask = locf(s.ask);
		double[] bid = locf(s.bid);

		double[][] vols = mapVolumes(s, volSrc, n);
		double[] askVol = vols[0];
		double[] bidVol = vols[1];
		drawVolumePane(g, askVol, bidVol, volSrc != s, width, h, volH, pad, xStep);

		java.awt.Stroke prevStroke = g.getStroke();
		g.setStroke(new java.awt.BasicStroke(2f, java.awt.BasicStroke.CAP_BUTT, java.awt.BasicStroke.JOIN_MITER));
		drawSeriesLine(g, bid, BID_LINE, width, priceH, pad, min, max);
		drawSeriesLine(g, ask, ASK_LINE, width, priceH, pad, min, max);
		g.setStroke(prevStroke);
		// The purchase-price reference line while selling a tracked flip, drawn only when
		// it falls inside the plotted range. The panel's Purchase price row always has it.
		if (refPrice > 0 && refPrice >= min && refPrice <= max)
		{
			int ry = (int) (pad + (priceH - 2 * pad - 1) * (1 - (refPrice - min) / (max - min)));
			g.setColor(REF_LINE);
			g.drawLine(pad, ry, width - pad, ry);
		}
		// Dots only where the database actually has a fill, matching the website's 1m trade
		// markers. Candle series carry no flags and get none.
		drawTradeDots(g, bid, s.bidReal, BID_DOT, width, priceH, pad, min, max);
		drawTradeDots(g, ask, s.askReal, ASK_DOT, width, priceH, pad, min, max);

		addReadoutHeader(side, tf, sideWidth, min, max);
		if (volSrc != s)
		{
			addVolumeCountdown(side, volSrc, volArrival);
		}

		if (mx >= pad && mx <= width - pad && my >= 0 && my < h)
		{
			int i = Math.max(0, Math.min(n - 1, (int) Math.round((mx - pad) / xStep)));
			int cx = pad + (int) Math.round(i * xStep);
			g.setColor(new Color(255, 255, 255, 80));
			g.drawLine(cx, 2, cx, h - 3);
			markPoint(g, bid[i], BID_LINE, cx, priceH, pad, min, max);
			markPoint(g, ask[i], ASK_LINE, cx, priceH, pad, min, max);

			boolean hasAsk = !Double.isNaN(ask[i]);
			boolean hasBid = !Double.isNaN(bid[i]);
			if (s.time[i] > 0)
			{
				side.getChildren().add(LineComponent.builder()
					.left("Time")
					.right(HOVER_TIME.format(java.time.Instant.ofEpochSecond(s.time[i])
						.atZone(java.time.ZoneId.systemDefault())) + " (" + ago(s.time[i]) + ")")
					.rightColor(MUTED)
					.build());
			}
			side.getChildren().add(LineComponent.builder()
				.left("Ask").right(hasAsk ? Gp.exact((long) ask[i]) : "—").rightColor(ASK_LINE).build());
			side.getChildren().add(LineComponent.builder()
				.left("Bid").right(hasBid ? Gp.exact((long) bid[i]) : "—").rightColor(BID_LINE).build());
			if (hasAsk && hasBid)
			{
				side.getChildren().add(LineComponent.builder()
					.left("Spread").right(Gp.exact((long) (ask[i] - bid[i]))).rightColor(Color.WHITE).build());
			}
			// The flip math at the hovered bucket's prices: the sell tax at that ask, and
			// the after-tax margin of buying its bid and selling its ask.
			if (hasAsk)
			{
				long tax = SessionStats.geSellTax((long) ask[i], itemId);
				side.getChildren().add(LineComponent.builder()
					.left("Tax (ask)").right(Gp.exact(tax)).rightColor(MUTED).build());
				if (hasBid)
				{
					long margin = (long) ask[i] - tax - (long) bid[i];
					side.getChildren().add(LineComponent.builder()
						.left("Margin (after tax)").right(Gp.exact(margin))
						.rightColor(margin > 0 ? PROFIT : margin < 0 ? LOSS : MUTED)
						.build());
				}
			}
			if (!Double.isNaN(askVol[i]) || !Double.isNaN(bidVol[i]))
			{
				side.getChildren().add(LineComponent.builder()
					.left(volSrc != s ? "Vol (5m win)" : "Vol (ask/bid)")
					.right(vol(askVol[i]) + " / " + vol(bidVol[i]))
					.rightColor(MUTED)
					.build());
			}
		}

		g.translate(width + GAP, 0);
		side.render(g);
		g.translate(-(width + GAP), 0);
	}

	/**
	 * Maps each chart slot to its volumes from {@code volSrc} by bucket time. For candle
	 * graphs the source is the series itself. For the 1m graph the 5m grid resolves each
	 * minute to its window, drawing contiguous blocks like the site. NaN means no data.
	 * Volumes are never carried forward. Returns {askVol, bidVol}.
	 */
	private static double[][] mapVolumes(PriceClient.Series s, PriceClient.Series volSrc, int n)
	{
		double[] askVol = new double[n];
		double[] bidVol = new double[n];
		java.util.Arrays.fill(askVol, Double.NaN);
		java.util.Arrays.fill(bidVol, Double.NaN);
		if (volSrc != null && volSrc.askVol != null && volSrc.time.length > 1)
		{
			long vStart = volSrc.time[0];
			long vStep = volSrc.time[1] - volSrc.time[0];
			for (int i = 0; i < n; i++)
			{
				int vi = (int) Math.floorDiv(s.time[i] - vStart, vStep);
				if (vi >= 0 && vi < volSrc.time.length)
				{
					askVol[i] = volSrc.askVol[vi];
					bidVol[i] = volSrc.bidVol[vi];
				}
			}
		}
		return new double[][]{askVol, bidVol};
	}

	/** The readout panel's fixed rows: title, high, low, and the refresh countdown. */
	private static void addReadoutHeader(PanelComponent side, FlipGoblinConfig.Timeframe tf,
		int sideWidth, double min, double max)
	{
		side.getChildren().clear();
		side.setPreferredSize(new Dimension(sideWidth, 0));
		side.getChildren().add(TitleComponent.builder().text(tf.label).color(GOLD).build());
		side.getChildren().add(LineComponent.builder()
			.left(tf.shortName + " High").right(Gp.exact((long) max)).rightColor(Color.WHITE).build());
		side.getChildren().add(LineComponent.builder()
			.left(tf.shortName + " Low").right(Gp.exact((long) min)).rightColor(Color.WHITE).build());
		// Freshness. A plateau makes the refresh loop invisible, so count down to the next
		// bucket boundary, the moment the picture can actually shift. Once it crosses zero,
		// PriceClient retries on the fast cadence until the new bucket's data lands, so the
		// paint follows the data rather than the coarse TTL.
		long nextIn = tf.intervalSec - (System.currentTimeMillis() / 1000) % tf.intervalSec;
		side.getChildren().add(LineComponent.builder()
			.left("Next Update").right("in " + dur(nextIn)).rightColor(MUTED).build());
	}

	/**
	 * The 1m graph's volume rides the 5m windows, since ticks carry none, so it moves on a
	 * slower cadence than the price line. Count that down too, or the quiet pane reads as
	 * missing data. The Wiki publishes a window's volumes only after it closes, so the
	 * countdown is observation-driven: the next rollup is expected one window after the
	 * last one actually reached this client. Polling keeps retrying past zero ("due"), and
	 * the estimate self-corrects every cycle. Before the first observation, fall back to
	 * the boundary plus a typical publication lag.
	 */
	private static void addVolumeCountdown(PanelComponent side, PriceClient.Series volSrc,
		PriceClient.VolArrival volArrival)
	{
		long volStep = volSrc != null && volSrc.time.length > 1
			? volSrc.time[1] - volSrc.time[0] : 300;
		long nowSec = System.currentTimeMillis() / 1000;
		long nextVolIn;
		if (volArrival != null)
		{
			nextVolIn = volArrival.observedMs / 1000 + volStep - nowSec;
		}
		else
		{
			final long publishLag = 90;
			long since = nowSec % volStep;
			nextVolIn = since < publishLag ? publishLag - since : volStep + publishLag - since;
		}
		side.getChildren().add(LineComponent.builder()
			.left("Next Volume Update")
			.right(nextVolIn <= 0 ? "due" : "in " + dur(nextVolIn))
			.rightColor(MUTED)
			.build());
	}

	/** Formats a compact duration such as "1h 5m", "4m", or "32s". */
	private static String dur(long s)
	{
		long h = s / 3_600;
		long m = (s % 3_600) / 60;
		if (h > 0)
		{
			return h + "h" + (m > 0 ? " " + m + "m" : "");
		}
		return m > 0 ? m + "m" : s + "s";
	}

	/** Formats a relative age such as "2d 4h ago", "1h 23m ago", or "12s ago". */
	private static String ago(long epochSec)
	{
		long s = Math.max(0, System.currentTimeMillis() / 1000 - epochSec);
		long d = s / 86_400;
		long h = (s % 86_400) / 3_600;
		long m = (s % 3_600) / 60;
		if (d > 0)
		{
			return d + "d" + (h > 0 ? " " + h + "h" : "") + " ago";
		}
		if (h > 0)
		{
			return h + "h" + (m > 0 ? " " + m + "m" : "") + " ago";
		}
		return m > 0 ? m + "m ago" : s + "s ago";
	}

	/** A filled dot on the hovered bucket's value. Skipped when the side is NaN there. */
	private static void markPoint(Graphics2D g, double v, Color color, int cx, int h, int pad,
		double min, double max)
	{
		if (Double.isNaN(v))
		{
			return;
		}
		int yy = pad + (int) Math.round((h - 2 * pad - 1) * (1 - (v - min) / (max - min)));
		g.setColor(color);
		g.fillOval(cx - 2, yy - 2, 5, 5);
	}

	/**
	 * The website lines view's step interpolation: hold each bucket's value flat to the
	 * next x. One Path2D per gap-free run, stroked with butt caps, because per-segment
	 * drawLine left overlapping square caps at every vertex, which the client's
	 * stretched-mode upscale rendered as a dot at every point.
	 */
	private static void drawSeriesLine(Graphics2D g, double[] v, Color color, int width, int h,
		int pad, double min, double max)
	{
		g.setColor(color);
		int n = v.length;
		double xStep = (double) (width - 2 * pad) / (n - 1);
		java.awt.geom.Path2D.Double path = null;
		double prevY = 0;
		for (int i = 0; i < n; i++)
		{
			if (Double.isNaN(v[i]))
			{
				if (path != null)
				{
					g.draw(path);
					path = null;
				}
				continue;
			}
			double x = pad + i * xStep;
			double yy = pad + (h - 2 * pad - 1) * (1 - (v[i] - min) / (max - min));
			if (path == null)
			{
				path = new java.awt.geom.Path2D.Double();
				path.moveTo(x, yy);
			}
			else
			{
				path.lineTo(x, prevY); // hold flat
				path.lineTo(x, yy); // then step
			}
			prevY = yy;
		}
		if (path != null)
		{
			g.draw(path);
		}
	}

	/** Carries the last value forward: quiet buckets hold the previous price, and leading NaNs stay. */
	private static double[] locf(double[] v)
	{
		double[] out = v.clone();
		double cur = Double.NaN;
		for (int i = 0; i < out.length; i++)
		{
			if (Double.isNaN(out[i]))
			{
				out[i] = cur;
			}
			else
			{
				cur = out[i];
			}
		}
		return out;
	}

	// Volume bar colors — the website's ASK_VOL_COLOR / BID_VOL_COLOR exactly (0.5 alpha).
	private static final Color ASK_VOL = new Color(0xe8, 0xb0, 0x4f, 128);
	private static final Color BID_VOL = new Color(0x38, 0xbd, 0xf8, 128);

	/**
	 * The mirrored volume pane in the box's bottom fifth, matching the site: ask bars up
	 * from the midline, bid bars down, scaled to the window's max. Candle graphs draw
	 * per-bucket bars; {@code blocks}, the 1m graph's 5m-window volumes, draw contiguous,
	 * one block per window.
	 */
	private static void drawVolumePane(Graphics2D g, double[] askVol, double[] bidVol,
		boolean blocks, int width, int h, int volH, int pad, double xStep)
	{
		double maxVol = 0;
		for (int i = 0; i < askVol.length; i++)
		{
			for (double v : new double[]{askVol[i], bidVol[i]})
			{
				if (!Double.isNaN(v))
				{
					maxVol = Math.max(maxVol, v);
				}
			}
		}
		if (maxVol <= 0)
		{
			return;
		}
		int mid = h - volH / 2;
		int half = volH / 2 - 2;
		int bw = blocks ? (int) Math.ceil(xStep) : Math.max(2, (int) (xStep * 0.7));
		for (int i = 0; i < askVol.length; i++)
		{
			int x = pad + (int) Math.round(i * xStep) - bw / 2;
			if (!Double.isNaN(askVol[i]) && askVol[i] > 0)
			{
				int bh = Math.max(1, (int) Math.round(half * askVol[i] / maxVol));
				g.setColor(ASK_VOL);
				g.fillRect(x, mid - bh, bw, bh);
			}
			if (!Double.isNaN(bidVol[i]) && bidVol[i] > 0)
			{
				int bh = Math.max(1, (int) Math.round(half * bidVol[i] / maxVol));
				g.setColor(BID_VOL);
				g.fillRect(x, mid, bw, bh);
			}
		}
	}

	private static String vol(double v)
	{
		return Double.isNaN(v) ? "—" : Gp.shortForm((long) v);
	}

	/** A small dot on each slot where a real fill landed, like the site's 1m markers. No-op without flags. */
	private static void drawTradeDots(Graphics2D g, double[] v, boolean[] real, Color color,
		int width, int h, int pad, double min, double max)
	{
		if (real == null)
		{
			return;
		}
		g.setColor(color);
		int n = v.length;
		double xStep = (double) (width - 2 * pad) / (n - 1);
		for (int i = 0; i < n; i++)
		{
			if (!real[i] || Double.isNaN(v[i]))
			{
				continue;
			}
			int x = pad + (int) Math.round(i * xStep);
			int yy = pad + (int) Math.round((h - 2 * pad - 1) * (1 - (v[i] - min) / (max - min)));
			g.fillOval(x - 2, yy - 2, 4, 4);
		}
	}

	private static String numOrDash(Long n)
	{
		return Gp.shortOrDash(n);
	}
}
