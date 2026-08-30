package com.flipgoblin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Pins the history-import reconciliation (live b43 finding, 2026-07-11: a 1000-bar buy captured as
 * partial fills summing 747 double-imported whole from the History tab — overlay read "1747B").
 */
public class GeHistoryReconcileTest
{
	private static TradeRecord fill(int item, TradeRecord.Side side, long price, int qty, int slot)
	{
		return new TradeRecord(item, side, price, qty, price * qty, slot, 1000L);
	}

	private static TradeRecord importRec(int item, TradeRecord.Side side, long price, int qty)
	{
		return new TradeRecord(item, side, price, qty, price * qty, -1, 2000L, true, 0);
	}

	/** The live mithril ledger: six partial buy fills (747 @951, slot 1) + interleaved sells. */
	private static List<TradeRecord> mithrilFills()
	{
		List<TradeRecord> recs = new ArrayList<>();
		for (int q : new int[]{22, 360, 45})
		{
			recs.add(fill(2359, TradeRecord.Side.BUY, 951, q, 1));
		}
		recs.add(fill(2359, TradeRecord.Side.SELL, 974, 10, 2));
		for (int q : new int[]{90, 27})
		{
			recs.add(fill(2359, TradeRecord.Side.BUY, 951, q, 1));
		}
		for (int q : new int[]{501, 259, 27})
		{
			recs.add(fill(2359, TradeRecord.Side.SELL, 974, q, 2));
		}
		recs.add(fill(2359, TradeRecord.Side.BUY, 951, 203, 1));
		recs.add(fill(2359, TradeRecord.Side.SELL, 974, 203, 2));
		return recs;
	}

	@Test
	public void partialCoverage_importsOnlyTheGap()
	{
		// History entry: "Bought 1000 for 951,000" — fills cover 747 → import exactly the 253 gap.
		TradeRecord r = GeHistoryReconcile.importFor(
			mithrilFills(), 2359, TradeRecord.Side.BUY, 1000, 951_000L, 5000L);
		assertEquals(253, r.quantity);
		assertEquals(951, r.price);
		assertEquals(951_000L - 747L * 951L, r.spent);
		assertEquals(-1, r.slot);
		assertTrue(r.recovered);
	}

	@Test
	public void fullCoverage_importsNothing()
	{
		// "Sold 1000 for 974,000" — the sell fills cover it all.
		assertNull(GeHistoryReconcile.importFor(
			mithrilFills(), 2359, TradeRecord.Side.SELL, 1000, 974_000L, 5000L));
	}

	@Test
	public void noMatchingRun_importsWhole()
	{
		// A pre-plugin offer at a different price: no run agrees — import the whole entry.
		TradeRecord r = GeHistoryReconcile.importFor(
			mithrilFills(), 2359, TradeRecord.Side.BUY, 500, 430_000L, 5000L); // @860
		assertEquals(500, r.quantity);
		assertEquals(860, r.price);
	}

	@Test
	public void newerRunAtAnotherPrice_doesNotMaskTheOlderOne()
	{
		// A second buy offer @944 starts filling on another slot AFTER the 951 offer completed —
		// the 951 entry must still reconcile against ITS run, not the newest one.
		List<TradeRecord> recs = mithrilFills();
		recs.add(fill(2359, TradeRecord.Side.BUY, 944, 400, 2));
		TradeRecord r = GeHistoryReconcile.importFor(
			recs, 2359, TradeRecord.Side.BUY, 1000, 951_000L, 5000L);
		assertEquals(253, r.quantity);
		assertEquals(951, r.price);
	}


	@Test
	public void newerSamePriceRun_doesNotReopenAnOldEntry()
	{
		// A fresh 951 offer starts filling (300 on slot 2) AFTER the old 951 offer's run (747).
		// Re-parsing the old "Bought 1000" entry must reconcile against the LARGEST agreeing run
		// (747), yielding the same 253 gap the shape-dedup already holds — never a 700 re-import.
		List<TradeRecord> recs = mithrilFills();
		recs.add(fill(2359, TradeRecord.Side.BUY, 951, 300, 2));
		TradeRecord r = GeHistoryReconcile.importFor(
			recs, 2359, TradeRecord.Side.BUY, 1000, 951_000L, 5000L);
		assertEquals(253, r.quantity);
	}


	@Test
	public void runsSegmentBySlotWithinTheItemSideView()
	{
		// Same item+side on two slots = two runs, newest first; other items interleave freely.
		List<TradeRecord> recs = new ArrayList<>();
		recs.add(fill(2359, TradeRecord.Side.BUY, 951, 100, 1));
		recs.add(fill(444, TradeRecord.Side.BUY, 150, 999, 3)); // unrelated item mid-run
		recs.add(fill(2359, TradeRecord.Side.BUY, 951, 200, 1));
		recs.add(fill(2359, TradeRecord.Side.BUY, 944, 50, 2));
		List<long[]> runs = GeHistoryReconcile.runs(recs, 2359, TradeRecord.Side.BUY);
		assertEquals(2, runs.size());
		assertEquals(50, runs.get(0)[0]); // newest run: slot 2
		assertEquals(300, runs.get(1)[0]); // older run: slot 1, uninterrupted by item 444
	}
}
