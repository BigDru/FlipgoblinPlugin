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
 * heals fills lost to a capture hole. Two same-price offers on the same slot merge into
 * one run and read as covered. That bias is accepted because it never inflates the ledger.
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
	 * Groups slot-observed fills of one (item, side) into contiguous same-slot runs, newest
	 * first, each as {quantity, spent}. Fills of other items or sides may interleave freely.
	 * A slot change within the filtered view starts the next, older run.
	 */
	static List<long[]> runs(List<TradeRecord> records, int itemId, TradeRecord.Side side)
	{
		List<long[]> out = new ArrayList<>();
		long[] run = null;
		int runSlot = Integer.MIN_VALUE;
		for (int i = records.size() - 1; i >= 0; i--)
		{
			TradeRecord r = records.get(i);
			if (r.itemId != itemId || r.side != side || r.slot < 0)
			{
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
			return null; // the run holds everything the entry claims — nothing to import
		}
		int gapQty = qty - (int) run[0];
		long gapSpent = Math.max(0, gross - run[1]);
		return new TradeRecord(itemId, side, gapSpent / gapQty, gapQty, gapSpent, -1, now, true, 0);
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
