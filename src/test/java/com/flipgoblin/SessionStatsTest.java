package com.flipgoblin;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

/**
 * Pins the Java P/L math to the SAME fixture values as packages/shared/src/flips.test.ts, the
 * cross-implementation consistency guard (client panel vs website must agree).
 */
public class SessionStatsTest
{
	private static TradeRecord rec(int item, TradeRecord.Side side, long price, int qty, long ts)
	{
		return new TradeRecord(item, side, price, qty, price * qty, 0, ts);
	}

	/** A known-taxable id for the generic cases (Abyssal whip; mirrors tax.test.ts WHIP). */
	private static final int WHIP = 4151;

	@Test
	public void taxMirrorsShared()
	{
		assertEquals(3, SessionStats.geSellTax(150, WHIP)); // floor(3.0)
		assertEquals(2, SessionStats.geSellTax(149, WHIP)); // floor(2.98)
		assertEquals(0, SessionStats.geSellTax(49, WHIP)); // floor(0.98)
		assertEquals(5_000_000, SessionStats.geSellTax(300_000_000, WHIP)); // cap
		assertEquals(5_000_000, SessionStats.geSellTax(5_000_000_000L, WHIP)); // cap holds above max cash
		assertEquals(4_995_000_000L, SessionStats.netFromSale(5_000_000_000L, WHIP));
		assertEquals(0, SessionStats.geSellTax(0, WHIP));
	}

	@Test
	public void exemptItems_zeroTax() // mirrors tax.test.ts "exempt items pay ZERO tax" (FND-5)
	{
		assertEquals(0, SessionStats.geSellTax(10_000_000, 13190)); // Old school bond, 200k if taxable
		assertEquals(10_000_000, SessionStats.netFromSale(10_000_000, 13190));
		assertEquals(0, SessionStats.geSellTax(250, 379)); // Lobster, 5 if taxable
	}

	@Test
	public void breakevenMirrorsShared() // mirrors tax.test.ts "breakevenAsk: smallest ask that nets the cost basis"
	{
		assertEquals(1_020_408, SessionStats.breakevenAsk(1_000_000, WHIP));
		// The solved ask nets the basis; one gp lower does not.
		long be = SessionStats.breakevenAsk(1_000_000, WHIP);
		org.junit.Assert.assertTrue(SessionStats.netFromSale(be, WHIP) >= 1_000_000);
		org.junit.Assert.assertTrue(SessionStats.netFromSale(be - 1, WHIP) < 1_000_000);
		assertEquals(180, SessionStats.breakevenAsk(180, 379)); // exempt (Lobster): breakeven = cost
		assertEquals(305_000_000, SessionStats.breakevenAsk(300_000_000, WHIP)); // capped territory
		assertEquals(0, SessionStats.breakevenAsk(0, WHIP));
	}

	@Test
	public void exemptFlip_realizesRawSpread() // mirrors flips.test.ts "exempt items realize the raw spread"
	{
		SessionStats.Result r = SessionStats.match(Arrays.asList(
			rec(379, TradeRecord.Side.BUY, 200, 10, 1),
			rec(379, TradeRecord.Side.SELL, 250, 10, 2)));
		assertEquals(500, r.totalRealized);
	}

	@Test
	public void simpleFlip_realizes470() // mirrors flips.test.ts "simple flip"
	{
		SessionStats.Result r = SessionStats.match(Arrays.asList(
			rec(1, TradeRecord.Side.BUY, 100, 10, 1),
			rec(1, TradeRecord.Side.SELL, 150, 10, 2)));
		assertEquals(470, r.totalRealized);
		assertEquals(10, r.items.get(0).matchedQty);
		assertEquals(0, r.items.get(0).openQty);
	}

	@Test
	public void fifoOrder_oldestLotsFirst() // mirrors "FIFO order"
	{
		SessionStats.Result r = SessionStats.match(Arrays.asList(
			rec(1, TradeRecord.Side.BUY, 100, 10, 1),
			rec(1, TradeRecord.Side.BUY, 200, 10, 2),
			rec(1, TradeRecord.Side.SELL, 300, 15, 3)));
		assertEquals(10 * (294 - 100) + 5 * (294 - 200), r.totalRealized);
		assertEquals(5, r.items.get(0).openQty);
		assertEquals(5 * 200, r.items.get(0).openCost);
	}

	@Test
	public void unmatchedSells_surfacedNotPriced() // mirrors "unmatched sells"
	{
		SessionStats.Result r = SessionStats.match(
			Collections.singletonList(rec(1, TradeRecord.Side.SELL, 150, 4, 1)));
		assertEquals(0, r.totalRealized);
		assertEquals(4, r.items.get(0).unmatchedSellQty);
	}

	@Test
	public void taxCap_perUnit_hugeSell() // mirrors "tax cap applies per item unit"
	{
		SessionStats.Result r = SessionStats.match(Arrays.asList(
			rec(9, TradeRecord.Side.BUY, 250_000_000, 2, 1),
			rec(9, TradeRecord.Side.SELL, 300_000_000, 2, 2)));
		assertEquals(2L * 45_000_000, r.totalRealized);
	}

	@Test
	public void losingFlip_goesNegative() // mirrors "a losing flip"
	{
		SessionStats.Result r = SessionStats.match(Arrays.asList(
			rec(1, TradeRecord.Side.BUY, 100, 3, 1),
			rec(1, TradeRecord.Side.SELL, 90, 3, 2)));
		assertEquals(-33, r.totalRealized);
	}

	// --- Ignore ("not a flip"), same cases as the website's tests ---

	private static SessionStats.ItemPosition only(SessionStats.Result r)
	{
		assertEquals(1, r.items.size());
		return r.items.get(0);
	}

	@Test
	public void dismissal_writesOffOpenLots_realizedUntouched()
	{
		SessionStats.Result r = SessionStats.match(
			Arrays.asList(rec(WHIP, TradeRecord.Side.BUY, 100, 10, 1)),
			Arrays.asList(new Dismissal(WHIP, 2)));
		assertEquals(0, r.totalRealized);
		SessionStats.ItemPosition p = only(r);
		assertEquals(0, p.openQty);
		assertEquals(0, p.openCost);
		assertEquals(10, p.ignoredQty);
	}

	@Test
	public void dismissal_midEpisode_soldPartStaysRealized()
	{
		// Buy 10@100, sell 4@150 (net 147 → +47/unit), ignore the open 6.
		SessionStats.Result r = SessionStats.match(
			Arrays.asList(rec(WHIP, TradeRecord.Side.BUY, 100, 10, 1), rec(WHIP, TradeRecord.Side.SELL, 150, 4, 2)),
			Arrays.asList(new Dismissal(WHIP, 3)));
		assertEquals(4 * 47, r.totalRealized);
		SessionStats.ItemPosition p = only(r);
		assertEquals(0, p.openQty);
		assertEquals(6, p.ignoredQty);
	}

	@Test
	public void dismissal_laterSellsUntracked_rebuyOpensFresh()
	{
		SessionStats.Result r = SessionStats.match(
			Arrays.asList(
				rec(WHIP, TradeRecord.Side.BUY, 100, 10, 1),
				rec(WHIP, TradeRecord.Side.SELL, 150, 3, 5), // after the ignore, no cost basis
				rec(WHIP, TradeRecord.Side.BUY, 120, 5, 6), // fresh position
				rec(WHIP, TradeRecord.Side.SELL, 200, 5, 7)), // net 196 → +76/unit
			Arrays.asList(new Dismissal(WHIP, 2)));
		SessionStats.ItemPosition p = only(r);
		assertEquals(3, p.unmatchedSellQty);
		assertEquals(5 * 76, r.totalRealized);
		assertEquals(10, p.ignoredQty);
		assertEquals(0, p.openQty);
	}

	@Test
	public void dismissal_staleIsNoop_sameTsFillWrittenOff()
	{
		SessionStats.Result stale = SessionStats.match(
			Arrays.asList(rec(WHIP, TradeRecord.Side.BUY, 100, 5, 1), rec(WHIP, TradeRecord.Side.SELL, 150, 5, 2)),
			Arrays.asList(new Dismissal(WHIP, 3)));
		assertEquals(0, only(stale).ignoredQty);
		assertEquals(5 * 47, stale.totalRealized);
		SessionStats.Result tied = SessionStats.match(
			Arrays.asList(rec(WHIP, TradeRecord.Side.BUY, 100, 5, 3)),
			Arrays.asList(new Dismissal(WHIP, 3)));
		assertEquals(0, only(tied).openQty); // the fill sorts before the ignore on a tie
		assertEquals(5, only(tied).ignoredQty);
		// Another item's ignore touches nothing here.
		SessionStats.Result other = SessionStats.match(
			Arrays.asList(rec(WHIP, TradeRecord.Side.BUY, 100, 5, 1)),
			Arrays.asList(new Dismissal(379, 2)));
		assertEquals(5, only(other).openQty);
	}

	@Test
	public void sideTotals_countEveryFillPerSide()
	{
		SessionStats.ItemPosition p = only(SessionStats.match(Arrays.asList(
			rec(WHIP, TradeRecord.Side.BUY, 100, 10, 1),
			rec(WHIP, TradeRecord.Side.BUY, 110, 10, 2),
			rec(WHIP, TradeRecord.Side.SELL, 150, 25, 3)))); // 5 of these have no cost basis
		assertEquals(20, p.boughtQty);
		assertEquals(2_100, p.boughtValue);
		assertEquals(100, p.boughtMinPrice);
		assertEquals(110, p.boughtMaxPrice);
		assertEquals(25, p.soldQty);
		assertEquals(3_750, p.soldValue);
		assertEquals(150, p.soldMinPrice);
		assertEquals(150, p.soldMaxPrice);
	}
}
