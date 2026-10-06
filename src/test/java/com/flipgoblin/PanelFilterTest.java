package com.flipgoblin;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** Which Session items cards the two hide settings hide. */
public class PanelFilterTest
{
	private static final int ITEM = 329; // Salmon

	private static TradeRecord rec(TradeRecord.Side side, long price, int qty, long ts)
	{
		return new TradeRecord(ITEM, side, price, qty, price * qty, 0, ts);
	}

	private static SessionStats.ItemPosition position(List<TradeRecord> fills, List<Dismissal> ignores)
	{
		return SessionStats.match(fills, ignores).items.get(0);
	}

	private static final List<Dismissal> NONE = Collections.emptyList();

	@Test
	public void untrackedSaleOnly_followsHideUntracked() // sold, never bought
	{
		SessionStats.ItemPosition p = position(Arrays.asList(rec(TradeRecord.Side.SELL, 189, 1, 1)), NONE);
		assertTrue(SessionStats.hiddenCard(p, true, false));
		assertFalse(SessionStats.hiddenCard(p, false, true));
	}

	@Test
	public void ignoredOnly_followsHideIgnored() // food bought and eaten
	{
		SessionStats.ItemPosition p = position(Arrays.asList(rec(TradeRecord.Side.BUY, 29, 10, 1)),
			Arrays.asList(new Dismissal(ITEM, 2)));
		assertTrue(SessionStats.hiddenCard(p, false, true));
		assertFalse(SessionStats.hiddenCard(p, true, false));
	}

	@Test
	public void flipThenRestIgnored_alwaysShown() // bought 10, sold 4, ignored 6
	{
		SessionStats.ItemPosition p = position(
			Arrays.asList(rec(TradeRecord.Side.BUY, 29, 10, 1), rec(TradeRecord.Side.SELL, 29, 4, 2)),
			Arrays.asList(new Dismissal(ITEM, 3)));
		assertFalse(SessionStats.hiddenCard(p, true, true));
	}

	@Test
	public void flipWithExtraUntrackedSales_alwaysShown()
	{
		SessionStats.ItemPosition p = position(
			Arrays.asList(rec(TradeRecord.Side.BUY, 29, 5, 1), rec(TradeRecord.Side.SELL, 35, 8, 2)), NONE);
		assertFalse(SessionStats.hiddenCard(p, true, true));
	}

	@Test
	public void ignoredThenSoldUntracked_needsBothSettings()
	{
		SessionStats.ItemPosition p = position(
			Arrays.asList(rec(TradeRecord.Side.BUY, 29, 10, 1), rec(TradeRecord.Side.SELL, 29, 3, 3)),
			Arrays.asList(new Dismissal(ITEM, 2)));
		assertTrue(SessionStats.hiddenCard(p, true, true));
		assertFalse(SessionStats.hiddenCard(p, true, false));
		assertFalse(SessionStats.hiddenCard(p, false, true));
	}

	@Test
	public void openUnitsAfterAnIgnore_alwaysShown()
	{
		SessionStats.ItemPosition p = position(
			Arrays.asList(rec(TradeRecord.Side.BUY, 29, 10, 1), rec(TradeRecord.Side.BUY, 30, 5, 3)),
			Arrays.asList(new Dismissal(ITEM, 2)));
		assertFalse(SessionStats.hiddenCard(p, true, true));
	}

	@Test
	public void plainFlip_alwaysShown()
	{
		SessionStats.ItemPosition p = position(
			Arrays.asList(rec(TradeRecord.Side.BUY, 29, 10, 1), rec(TradeRecord.Side.SELL, 35, 10, 2)), NONE);
		assertFalse(SessionStats.hiddenCard(p, true, true));
	}
}
