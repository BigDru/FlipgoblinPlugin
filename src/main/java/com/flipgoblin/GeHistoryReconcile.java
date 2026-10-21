package com.flipgoblin;

import java.util.ArrayList;
import java.util.List;

/**
 * Reconciles GE History imports against fills the plugin already saw live, so an import can
 * never double-count an offer that was partly captured as it happened.
 *
 * Model: a History entry is one completed offer (side, item, total quantity, gross coins).
 * Its live counterpart is a contiguous run of fills for the same item and side on one slot.
 * An entry is matched against the run whose average price agrees. That gives three
 * outcomes: no matching run means the offer predates the plugin and imports whole; full
 * coverage means skip it; partial coverage means import only the missing gap, which also
 * heals fills lost to a capture hole. Fills since b101 carry their offer id, so each offer is
 * its own run. Older fills group by slot instead, and two offers on one slot merge there.
 *
 * Pure and single-threaded (client thread), like the differ.
 */
final class GeHistoryReconcile
{
	/** Average prices within 1 gp count as the same offer, allowing rounding on partial fills. */
	private static final long PRICE_TOLERANCE = 1;

	private GeHistoryReconcile()
	{
	}

	/**
	 * Groups slot-observed fills of one (item, side) into runs, newest first, each as
	 * {quantity, spent}. A fill that knows its offer joins that offer's run exactly. Older fills
	 * without an offer id group into contiguous same-slot runs, so back-to-back offers in one
	 * slot merge there; fills of other items or sides may interleave freely.
	 */
	static List<long[]> runs(List<TradeRecord> records, int itemId, TradeRecord.Side side)
	{
		List<long[]> out = new ArrayList<>();
		java.util.Map<String, long[]> byOffer = new java.util.HashMap<>();
		long[] run = null;
		int runSlot = Integer.MIN_VALUE;
		for (int i = records.size() - 1; i >= 0; i--)
		{
			TradeRecord r = records.get(i);
			if (r.itemId != itemId || r.side != side || r.slot < 0)
			{
				continue;
			}
			if (r.offerId != 0)
			{
				long[] offer = byOffer.get(r.slot + ":" + r.offerId);
				if (offer == null)
				{
					offer = new long[]{0, 0};
					byOffer.put(r.slot + ":" + r.offerId, offer);
					out.add(offer);
				}
				offer[0] += r.quantity;
				offer[1] += r.spent;
				continue;
			}
			if (run == null || r.slot != runSlot)
			{
				run = new long[]{0, 0};
				out.add(run);
				runSlot = r.slot;
			}
			run[0] += r.quantity;
			run[1] += r.spent;
		}
		return out;
	}

	/**
	 * Decides what a History entry ({@code qty} units for {@code gross} coins) should add to
	 * the ledger: the whole entry, just the uncovered gap, or null when it is fully covered.
	 * Imports carry slot -1 and the recovered flag, like all history imports. Callers still
	 * shape-dedup so reopening the tab stays a no-op.
	 */
	static TradeRecord importFor(List<TradeRecord> records, int itemId, TradeRecord.Side side,
		int qty, long gross, long now)
	{
		if (qty <= 0)
		{
			return null;
		}
		long[] run = matchingRun(records, itemId, side, qty, gross);
		if (run == null)
		{
			return new TradeRecord(itemId, side, gross / qty, qty, gross, -1, now, true, 0);
		}
		if (run[0] >= qty)
		{
			return null; // the run holds everything the entry claims; nothing to import
		}
		int gapQty = qty - (int) run[0];
		long gapSpent = Math.max(0, gross - run[1]);
		return new TradeRecord(itemId, side, gapSpent / gapQty, gapQty, gapSpent, -1, now, true, 0);
	}

	/**
	 * Drops History imports that a tracked offer already holds: one offer with an id, the same
	 * quantity and a price within the tolerance. Before b114 back-to-back offers in one slot
	 * merged into a single run, so such an offer could import a second time. Gap imports have
	 * a different quantity from their offer and stay. Returns a new list. Pure.
	 */
	static List<TradeRecord> dropDuplicateImports(List<TradeRecord> records)
	{
		List<TradeRecord> out = new ArrayList<>(records.size());
		for (TradeRecord r : records)
		{
			if (r.slot >= 0 || !r.recovered || r.quantity <= 0 || !duplicatesAnOffer(records, r))
			{
				out.add(r);
			}
		}
		return out;
	}

	private static boolean duplicatesAnOffer(List<TradeRecord> records, TradeRecord imp)
	{
		java.util.Map<String, long[]> offers = new java.util.HashMap<>();
		for (TradeRecord r : records)
		{
			if (r.itemId == imp.itemId && r.side == imp.side && r.slot >= 0 && r.offerId != 0)
			{
				long[] o = offers.computeIfAbsent(r.slot + ":" + r.offerId, k -> new long[]{0, 0});
				o[0] += r.quantity;
				o[1] += r.spent;
			}
		}
		long impAvg = Math.round((double) imp.spent / imp.quantity);
		for (long[] o : offers.values())
		{
			if (o[0] == imp.quantity && Math.abs(Math.round((double) o[1] / o[0]) - impAvg) <= PRICE_TOLERANCE)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Finds the price-agreeing run with the largest quantity, or null when none agrees.
	 * Largest, not newest, keeps the comparison conservative when several offers ran at the
	 * same price: a newer half-filled offer must not shrink an older entry's coverage and
	 * re-import quantity the older run already accounts for.
	 */
	private static long[] matchingRun(List<TradeRecord> records, int itemId, TradeRecord.Side side,
		long qty, long gross)
	{
		long entryAvg = Math.round((double) gross / qty);
		long[] best = null;
		for (long[] run : runs(records, itemId, side))
		{
			long runAvg = Math.round((double) run[1] / run[0]);
			if (Math.abs(entryAvg - runAvg) <= PRICE_TOLERANCE && (best == null || run[0] > best[0]))
			{
				best = run;
			}
		}
		return best;
	}
}
