package com.flipgoblin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemManager;

/**
 * Reads the GE History tab so buys from before the plugin ran still count as cost basis.
 * While the tab is open, each entry is parsed from its widgets: the icon child carries the
 * item id and quantity, a "Bought"/"Sold" caption sets the side, and the "… coins" text
 * closes the entry with its total. Entries become recovered records stamped at detection
 * time, since the tab shows no trade times. They stay local and are never synced, because
 * the server may already hold those fills from an earlier session. Slot -1 marks the origin.
 *
 * Client thread only. The plugin polls every frame; one open of the tab imports once.
 */
@Slf4j
final class GeHistoryImporter
{
	private final Client client;
	private final ItemManager itemManager;
	private boolean imported;

	GeHistoryImporter(Client client, ItemManager itemManager)
	{
		this.client = client;
		this.itemManager = itemManager;
	}

	/**
	 * Reads the History tab if it is open and not yet imported this open. Returns the new
	 * records to add (reconciled against {@code existing} and shape-deduped), or null when
	 * there is nothing to do. The caller persists, repaints, and schedules time refinement.
	 */
	List<TradeRecord> poll(List<TradeRecord> existing, long now)
	{
		Widget list = client.getWidget(InterfaceID.GeHistory.LIST);
		if (list == null)
		{
			imported = false; // tab closed — the next open re-imports (deduped)
			return null;
		}
		if (imported)
		{
			return null;
		}
		// Entries may hang off ANY child array depending on client build — walk all three (a
		// dynamic-only walk silently imports nothing on the live client).
		List<Widget> kidList = new ArrayList<>();
		for (Widget[] grp : new Widget[][]{
			list.getDynamicChildren(), list.getStaticChildren(), list.getNestedChildren()})
		{
			if (grp != null)
			{
				Collections.addAll(kidList, grp);
			}
		}
		Widget[] kids = kidList.toArray(new Widget[0]);
		if (kids.length == 0)
		{
			return null; // not populated yet — try again next frame
		}
		imported = true;
		log.info("[{}] GE history open: {} children (dyn={}, static={}, nested={})",
			FlipGoblinPlugin.BUILD, kids.length,
			list.getDynamicChildren() == null ? 0 : list.getDynamicChildren().length,
			list.getStaticChildren() == null ? 0 : list.getStaticChildren().length,
			list.getNestedChildren() == null ? 0 : list.getNestedChildren().length);
		int entries = 0;
		String side = null;
		int iconItem = -1;
		int iconQty = 0;
		List<TradeRecord> importedRecs = new ArrayList<>();
		// Live layout, 6 children per entry: "Bought:"/"Sold:" caption →
		// "<name>x <qty>" text → the item ICON (id + qty) → the coins text, which for buys reads
		// "49,000 coins= 7 each" and for sells "1,629,000 coins(1,662,000 - 33,000)= 1,629 each"
		// (NET first, gross − tax in parens — the record wants GROSS so the flip matcher's own tax
		// math doesn't double-count).
		Pattern sidePat = Pattern.compile("^(Bought|Sold):?$");
		Pattern coinsPat = Pattern.compile("^([\\d,]+) coins(?:\\(([\\d,]+) - ([\\d,]+)\\))?.*");
		for (Widget ch : kids)
		{
			if (ch == null)
			{
				continue;
			}
			if (ch.getItemId() > 0)
			{
				iconItem = itemManager.canonicalize(ch.getItemId());
				iconQty = Math.max(1, ch.getItemQuantity());
				continue;
			}
			String plain = ch.getText() == null ? "" : ch.getText().replaceAll("<[^>]*>", "").trim();
			Matcher sm = sidePat.matcher(plain);
			if (sm.matches())
			{
				side = sm.group(1);
				continue;
			}
			Matcher m = coinsPat.matcher(plain);
			if (m.matches() && side != null && iconItem > 0)
			{
				entries++;
				// Gross transacted value: buys report it directly; sells put it in the parens.
				long gross = Long.parseLong(
					(m.group(2) != null ? m.group(2) : m.group(1)).replace(",", ""));
				// Reconcile against slot-observed fill runs: import the whole entry, only its
				// uncovered gap, or nothing — a naive shape-dedup double-counts an offer whose
				// fills were captured piecemeal (see GeHistoryReconcile).
				TradeRecord r = GeHistoryReconcile.importFor(existing, iconItem,
					side.equalsIgnoreCase("Bought") ? TradeRecord.Side.BUY : TradeRecord.Side.SELL,
					iconQty, gross, now);
				// Dedup by shape — NOTE two identical real trades (same item/side/qty/price)
				// collapse to one; the tab carries no ids, so this is the honest floor.
				if (r != null && !hasEquivalent(existing, r) && !hasEquivalent(importedRecs, r))
				{
					importedRecs.add(r);
				}
				side = null;
				iconItem = -1;
				iconQty = 0;
			}
		}
		if (importedRecs.isEmpty() && entries == 0)
		{
			// Nothing parsed from a populated list — dump a compact shape sample for diagnosis.
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < kids.length && i < 36; i++)
			{
				Widget ch = kids[i];
				if (ch == null)
				{
					continue;
				}
				String t = ch.getText() == null ? "" : ch.getText().replaceAll("<[^>]*>", "");
				sb.append(i).append(":item=").append(ch.getItemId())
					.append(",q=").append(ch.getItemQuantity())
					.append(",t=").append(t, 0, Math.min(40, t.length())).append(" | ");
			}
			log.info("[{}] GE history: no entries parsed from {} children: {}",
				FlipGoblinPlugin.BUILD, kids.length, sb);
		}
		return importedRecs;
	}

	/** An already-known record with the same item, side, quantity, and price (import dedup). */
	private static boolean hasEquivalent(List<TradeRecord> records, TradeRecord r)
	{
		for (TradeRecord x : records)
		{
			if (x.itemId == r.itemId && x.side == r.side && x.quantity == r.quantity && x.price == r.price)
			{
				return true;
			}
		}
		return false;
	}
}
