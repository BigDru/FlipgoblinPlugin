package com.flipgoblin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** Pins the 4h buy-limit window model: first-buy anchor, in-window accumulation, full reset. */
public class GeLimitsTest
{
	private static final long H = 60 * 60 * 1000L;

	private static void assertUsage(long used, long resetAtMs, boolean exact, boolean hedged,
		GeLimits.Usage actual)
	{
		assertEquals(used, actual.used);
		assertEquals(resetAtMs, actual.resetAtMs);
		assertEquals(exact, actual.exact);
		assertEquals(hedged, actual.hedged);
	}

	private static TradeRecord buy(int itemId, int qty, long ts)
	{
		return new TradeRecord(itemId, TradeRecord.Side.BUY, 100, qty, 100L * qty, 0, ts);
	}

	private static TradeRecord sell(int itemId, int qty, long ts)
	{
		return new TradeRecord(itemId, TradeRecord.Side.SELL, 100, qty, 100L * qty, 0, ts);
	}

	@Test
	public void noBuysMeansNoWindow()
	{
		assertNull(GeLimits.usage(Arrays.asList(sell(4151, 5, 1000)), 4151, 2000, 0, 0));
	}

	@Test
	public void buysAccumulateInsideTheWindow_anchoredOnFirstBuy()
	{
		List<TradeRecord> recs = Arrays.asList(
			buy(4151, 10, 1 * H),
			sell(4151, 3, 2 * H), // sells never count
			buy(4151, 25, 3 * H),
			buy(999, 40, 3 * H)); // other items never count
		assertUsage(35, 5 * H, false, false, GeLimits.usage(recs, 4151, 4 * H, 0, 0));
	}

	@Test
	public void historyImports_slotMinusOne_neverCountTowardTheWindow()
	{
		TradeRecord imported = new TradeRecord(4151, TradeRecord.Side.BUY, 100, 500, 50_000, -1, 3 * H);
		assertNull(GeLimits.usage(Arrays.asList(imported), 4151, 4 * H, 0, 0));
		// ...and they don't pollute a window opened by a slot-observed buy either.
		List<TradeRecord> recs = Arrays.asList(buy(4151, 10, 3 * H), imported);
		assertUsage(10, 7 * H, false, false, GeLimits.usage(recs, 4151, 4 * H, 0, 0));
	}

	@Test
	public void windowExpiryReturnsNull_andNextBuyOpensAFreshWindow()
	{
		List<TradeRecord> recs = Arrays.asList(buy(4151, 10, 1 * H));
		assertNull(GeLimits.usage(recs, 4151, 5 * H, 0, 0)); // 1h + 4h window has ended

		List<TradeRecord> recs2 = Arrays.asList(
			buy(4151, 10, 1 * H),
			buy(4151, 7, 6 * H)); // past the first window: new anchor, count restarts
		assertUsage(7, 10 * H, false, false, GeLimits.usage(recs2, 4151, 7 * H, 0, 0));
	}

	@Test
	public void buyExactlyAtWindowEndStartsTheNextWindow()
	{
		List<TradeRecord> recs = Arrays.asList(
			buy(4151, 10, 1 * H),
			buy(4151, 5, 5 * H)); // ts == anchor + 4h, outside the old window
		assertUsage(5, 9 * H, false, false, GeLimits.usage(recs, 4151, 6 * H, 0, 0));
	}

	@Test
	public void exactWhenTheWholeWindowWasObservedLive()
	{
		List<TradeRecord> recs = Arrays.asList(buy(4151, 10, 3 * H), buy(4151, 5, 4 * H));
		// Observation started before the anchor and no fill is recovered → exact.
		assertUsage(15, 7 * H, true, false, GeLimits.usage(recs, 4151, 5 * H, 2 * H, 0));
		// Observation started AFTER the anchor → still a lower bound.
		assertUsage(15, 7 * H, false, false, GeLimits.usage(recs, 4151, 5 * H, 3 * H + 1, 0));
		// A recovered fill in the window has an estimated time → never exact, and hedged.
		TradeRecord rec = new TradeRecord(4151, TradeRecord.Side.BUY, 100, 5, 500, 0, 4 * H, true, 3 * H);
		List<TradeRecord> withRecovered = Arrays.asList(buy(4151, 10, 3 * H), rec);
		assertUsage(15, 7 * H, false, true, GeLimits.usage(withRecovered, 4151, 5 * H, 2 * H, 0));
	}

	private static TradeRecord recovered(int itemId, int qty, long detectedTs, long offlineSince)
	{
		return new TradeRecord(itemId, TradeRecord.Side.BUY, 100, qty, 100L * qty, 0, detectedTs,
			true, offlineSince);
	}

	// Two limits bought overnight: while the offline gap is under 8h, even the earliest
	// feasible placement leaves the second window active, so one limit's worth is provable.
	@Test
	public void twoLimitsOffline_shortGap_provesOneLimitStillSpent()
	{
		List<TradeRecord> recs = Arrays.asList(
			recovered(2357, 10_000, 7 * H, 0 * H + 1),
			recovered(2357, 10_000, 7 * H, 0 * H + 1));
		// Earliest placement: window 1 at ~0h holds 10k, window 2 at ~4h holds 10k and is
		// still active at 7h. Reset time stays the latest bound (detection + 4h). Hedged.
		assertUsage(10_000, 11 * H, false, true,
			GeLimits.usage(recs, 2357, 7 * H, 0, 10_000));
	}

	// Past 8h the second window may already have reset, so nothing is provable.
	@Test
	public void twoLimitsOffline_longGap_provesNothing()
	{
		List<TradeRecord> recs = Arrays.asList(
			recovered(2357, 10_000, 9 * H, 0 * H + 1),
			recovered(2357, 10_000, 9 * H, 0 * H + 1));
		assertNull(GeLimits.usage(recs, 2357, 9 * H, 0, 10_000));
	}

	// With no known buy limit, only a single window can be assumed: provable inside the
	// first 4h of the offline bound, nothing after.
	@Test
	public void unknownLimit_singleWindowBound()
	{
		List<TradeRecord> inside = Arrays.asList(recovered(2357, 500, 3 * H, 1));
		assertUsage(500, 7 * H, false, true, GeLimits.usage(inside, 2357, 3 * H + 1800_000, 0, 0));
		List<TradeRecord> past = Arrays.asList(recovered(2357, 500, 5 * H, 1));
		assertNull(GeLimits.usage(past, 2357, 5 * H, 0, 0));
	}

	// A recovered fill with no offlineSince bound could be arbitrarily old and proves nothing.
	@Test
	public void recoveredWithoutBound_provesNothing()
	{
		List<TradeRecord> recs = Arrays.asList(recovered(2357, 500, 1 * H, 0));
		assertNull(GeLimits.usage(recs, 2357, 2 * H, 0, 10_000));
	}

	// Live buys after login stay provable even when the offline buys are not.
	@Test
	public void liveBuysSurviveWhenOfflineBuysAge_out()
	{
		List<TradeRecord> recs = Arrays.asList(
			recovered(2357, 10_000, 6 * H, 1),  // earliest window [0,4h), expired by 7h
			buy(2357, 500, 6 * H + 1800_000));  // live at 6.5h opens a fresh window
		assertUsage(500, 10 * H, false, true, GeLimits.usage(recs, 2357, 7 * H, 0, 10_000));
	}

	// If early placement would force a live fill into a full window, the model does not fit
	// the data; fall back to counting live fills only.
	@Test
	public void infeasibleEarlyPlacement_fallsBackToLiveOnly()
	{
		List<TradeRecord> recs = Arrays.asList(
			recovered(2357, 10_000, 6 * H + 1800_000, 6 * H), // [6h, 6.5h]: fills the window
			buy(2357, 500, 7 * H)); // live inside that same window
		assertUsage(500, 10 * H + 1800_000, false, true,
			GeLimits.usage(recs, 2357, 7 * H, 0, 10_000));
	}

	private static TradeRecord fill(TradeRecord.Side side, long price, int qty, int slot, long ts)
	{
		return new TradeRecord(563, side, price, qty, price * qty, slot, ts);
	}

	@Test
	public void buyFilledByYourOwnSell_doesNotCountTowardTheLimit()
	{
		// The live law rune case: the window was full at 18000, then a 1 rune buy at 125
		// filled against the player's own 125 sell 0.6s later, and the game allowed it.
		List<TradeRecord> recs = Arrays.asList(
			fill(TradeRecord.Side.BUY, 121, 18_000, 0, 1 * H),
			fill(TradeRecord.Side.BUY, 125, 1, 1, 2 * H),
			fill(TradeRecord.Side.SELL, 125, 1, 0, 2 * H + 600));
		assertUsage(18_000, 5 * H, true, false, GeLimits.usage(recs, 563, 3 * H, 1, 18_000));
	}

	@Test
	public void selfMatch_onlyTakesOutTheMatchedUnits()
	{
		List<TradeRecord> recs = Arrays.asList(
			fill(TradeRecord.Side.SELL, 125, 3, 0, 1 * H),
			fill(TradeRecord.Side.BUY, 125, 10, 1, 1 * H + 600)); // 3 from yourself, 7 from others
		assertUsage(7, 5 * H + 600, false, false, GeLimits.usage(recs, 563, 2 * H, 0, 0));
	}

	@Test
	public void selfMatch_needsTheSamePriceAndCloseTimes()
	{
		List<TradeRecord> recs = Arrays.asList(
			fill(TradeRecord.Side.BUY, 121, 5, 0, 1 * H),
			fill(TradeRecord.Side.SELL, 125, 5, 1, 1 * H + 600), // different price
			fill(TradeRecord.Side.BUY, 125, 4, 0, 2 * H),
			fill(TradeRecord.Side.SELL, 125, 4, 1, 2 * H + GeLimits.SELF_MATCH_MS + 1)); // too late
		assertUsage(9, 5 * H, false, false, GeLimits.usage(recs, 563, 3 * H, 0, 0));
	}

	@Test
	public void selfMatch_eachSellUnitPairsOnlyOnce()
	{
		List<TradeRecord> recs = Arrays.asList(
			fill(TradeRecord.Side.BUY, 125, 2, 0, 1 * H),
			fill(TradeRecord.Side.SELL, 125, 2, 1, 1 * H + 600),
			fill(TradeRecord.Side.BUY, 125, 2, 2, 1 * H + 1200));
		assertUsage(2, 5 * H + 1200, false, false, GeLimits.usage(recs, 563, 2 * H, 0, 0));
	}

	@Test
	public void selfMatch_neverPairsRecoveredFills()
	{
		List<TradeRecord> recs = Arrays.asList(
			fill(TradeRecord.Side.BUY, 125, 5, 0, 1 * H),
			new TradeRecord(563, TradeRecord.Side.SELL, 125, 5, 625, 1, 1 * H + 600, true, 0));
		assertUsage(5, 5 * H, false, false, GeLimits.usage(recs, 563, 2 * H, 0, 0));
	}

	@Test
	public void fullySelfMatchedBuy_doesNotOpenAWindow()
	{
		List<TradeRecord> recs = Arrays.asList(
			fill(TradeRecord.Side.BUY, 125, 1, 0, 1 * H),
			fill(TradeRecord.Side.SELL, 125, 1, 1, 1 * H + 600));
		assertNull(GeLimits.usage(recs, 563, 2 * H, 0, 0));
	}

	@Test
	public void maxBuyIsTheSmallerOfCashAndLimitLeft()
	{
		// Jug: 13,000 limit left at 2 gp each. 39 gp buys 19; 1m gp is capped by the limit.
		assertEquals(19, GeLimits.maxBuy(13_000, 39, 2));
		assertEquals(13_000, GeLimits.maxBuy(13_000, 1_000_000, 2));
		// No known limit: the cash alone decides.
		assertEquals(500_000, GeLimits.maxBuy(-1, 1_000_000, 2));
		// No cash, or the limit used up: nothing.
		assertEquals(0, GeLimits.maxBuy(13_000, 0, 2));
		assertEquals(0, GeLimits.maxBuy(0, 1_000_000, 2));
	}
}
