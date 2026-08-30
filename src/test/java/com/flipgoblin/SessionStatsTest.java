package com.flipgoblin;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

/**
 * Pins the Java P/L math to the SAME fixture values as packages/shared/src/flips.test.ts — the
 * cross-implementation consistency guard (client panel vs website must agree).
 */
public class SessionStatsTest
{
	private static TradeRecord rec(int item, TradeRecord.Side side, long price, int qty, long ts)
	{
		return new TradeRecord(item, side, price, qty, price * qty, 0, ts);
	}

	/** A known-taxable id for the generic cases (Abyssal whip — mirrors tax.test.ts WHIP). */
	private static final int WHIP = 4151;

	@Test
	public void taxMirrorsShared()
	{
		assertEquals(3, SessionStats.geSellTax(150, WHIP)); // floor(3.0)
		assertEquals(2, SessionStats.geSellTax(149, WHIP)); // floor(2.98)
		assertEquals(0, SessionStats.geSellTax(49, WHIP)); // floor(0.98)
		assertEquals(5_000_000, SessionStats.geSellTax(300_000_000, WHIP)); // cap
		assertEquals(0, SessionStats.geSellTax(0, WHIP));
	}

	@Test
	public void exemptItems_zeroTax() // mirrors tax.test.ts "exempt items pay ZERO tax" (FND-5)
	{
		assertEquals(0, SessionStats.geSellTax(10_000_000, 13190)); // Old school bond — 200k if taxable
		assertEquals(10_000_000, SessionStats.netFromSale(10_000_000, 13190));
		assertEquals(0, SessionStats.geSellTax(250, 379)); // Lobster — 5 if taxable
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
}
