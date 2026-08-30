package com.flipgoblin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Test;

/** Pins the slice-D positions board: lifecycle phases, cancel-as-reuse, and the escrow math. */
public class GePositionsTest
{
	private static OfferSnapshot snap(int item, GrandExchangeOfferState st, int total, int sold, long spent,
		long price)
	{
		return new OfferSnapshot(item, st, total, sold, spent, price);
	}

	@Test
	public void lifecycle_place_partial_complete_collect()
	{
		GePositions g = new GePositions();
		g.onOffer(2, snap(100, GrandExchangeOfferState.BUYING, 10, 0, 0, 200), 1000L);
		GePositions.Position p = g.active().get(0);
		assertEquals(GePositions.Phase.WORKING, p.phase);
		assertEquals(1000L, p.firstSeen);

		g.onOffer(2, snap(100, GrandExchangeOfferState.BUYING, 10, 4, 800, 200), 2000L);
		p = g.active().get(0);
		assertEquals(4, p.quantitySold);
		assertEquals(1000L, p.firstSeen); // same offer — the position's clock does not reset
		assertEquals(GePositions.Phase.WORKING, p.phase);

		g.onOffer(2, snap(100, GrandExchangeOfferState.BOUGHT, 10, 10, 2000, 200), 3000L);
		assertEquals(GePositions.Phase.COMPLETE, g.active().get(0).phase);

		g.onOffer(2, snap(0, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0), 4000L); // collected
		assertTrue(g.active().isEmpty());
	}

	@Test
	public void cancelShowsCancelled_thenReplaceRestartsTheClock()
	{
		GePositions g = new GePositions();
		g.onOffer(1, snap(100, GrandExchangeOfferState.BUYING, 10, 2, 400, 200), 1000L);
		g.onOffer(1, snap(100, GrandExchangeOfferState.CANCELLED_BUY, 10, 2, 400, 200), 2000L);
		GePositions.Position p = g.active().get(0);
		assertEquals(GePositions.Phase.CANCELLED, p.phase);
		assertEquals(1000L, p.firstSeen);

		// OSRS has no in-place modification: cancel → collect → re-place. The board shows it faithfully.
		g.onOffer(1, snap(0, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0), 3000L);
		g.onOffer(1, snap(100, GrandExchangeOfferState.BUYING, 10, 0, 0, 210), 4000L);
		p = g.active().get(0);
		assertEquals(GePositions.Phase.WORKING, p.phase);
		assertEquals(210, p.price);
		assertEquals(4000L, p.firstSeen);
	}

	@Test
	public void escrow_countsWorkingBuyCoinsAndWorkingSellStockOnly()
	{
		GePositions g = new GePositions();
		// WORKING buy 10 @ 200, 4 filled: GE holds 6×200 = 1200 escrowed coins, but NO items —
		// the 4 bought sit in the collection box, independently collectable.
		g.onOffer(0, snap(100, GrandExchangeOfferState.BUYING, 10, 4, 800, 200), 1L);
		// WORKING sell 5 whips @ 1000, 2 sold: GE holds 3 unsold whips, but NO coins — the 2000
		// proceeds sit in the collection box, so counting them would double-count on collect.
		g.onOffer(1, snap(4151, GrandExchangeOfferState.SELLING, 5, 2, 2000, 1000), 2L);

		assertEquals(1200, g.heldCoins()); // working-buy escrow only (200×6); the sell adds nothing
		int[][] items = g.heldItemPairs();
		assertEquals(1, items.length); // only the working sell's unsold remainder
		assertEquals(4151, items[0][0]);
		assertEquals(3, items[0][1]);
	}

	/**
	 * COMPLETE and CANCELLED positions contribute ZERO to both coins and items: everything they hold
	 * sits in the collection box (each stack independently collectable, counters unchanged on partial
	 * collect), so no component is deterministic and counting any of it risks the double-count on
	 * collect. Never inflate. See docs/wealth-ledger-design.md.
	 */
	@Test
	public void completeAndCancelled_contributeZero()
	{
		GePositions g = new GePositions();
		g.onOffer(0, snap(100, GrandExchangeOfferState.BOUGHT, 10, 10, 2000, 200), 1L); // COMPLETE buy
		g.onOffer(1, snap(4151, GrandExchangeOfferState.SOLD, 5, 5, 5000, 1000), 1L); // COMPLETE sell
		g.onOffer(2, snap(200, GrandExchangeOfferState.CANCELLED_BUY, 10, 2, 400, 200), 1L); // refund collectable
		g.onOffer(3, snap(300, GrandExchangeOfferState.CANCELLED_SELL, 5, 3, 3000, 1000), 1L); // remainder collectable

		assertEquals(0, g.heldCoins());
		assertEquals(0, g.heldItemPairs().length);
	}

	@Test
	public void loginReplay_reconstructsTheBoard_slotOrdered()
	{
		GePositions g = new GePositions();
		g.onOffer(5, snap(2, GrandExchangeOfferState.SELLING, 100, 10, 50, 5), 1L);
		g.onOffer(1, snap(100, GrandExchangeOfferState.BUYING, 10, 0, 0, 200), 1L);
		List<GePositions.Position> active = g.active();
		assertEquals(2, active.size());
		assertEquals(1, active.get(0).slot);
		assertEquals(5, active.get(1).slot);
	}

	@Test
	public void zeroQuantityPairsAreOmitted()
	{
		GePositions g = new GePositions();
		// Fresh buy with nothing filled: no items held yet — only escrowed coins.
		g.onOffer(0, snap(100, GrandExchangeOfferState.BUYING, 10, 0, 0, 200), 1L);
		assertEquals(0, g.heldItemPairs().length);
		assertEquals(2000, g.heldCoins());
	}

	/**
	 * The 3.62m phantom peak (2026-07-13, docs/wealth-ledger-design.md): a WORKING sell's cumulative
	 * {@code spent} was counted as uncollected coins, but a mid-offer collect moves those coins to
	 * inventory with NO offer event fired — so the same gp was counted twice until the slot went
	 * EMPTY. A working sell now contributes ZERO coins; only its unsold remainder (provably still on
	 * the GE machine, not collectable) is counted as items.
	 */
	@Test
	public void workingSell_countsUnsoldItemsButZeroCoins_phantomPeakRegression()
	{
		GePositions g = new GePositions();
		// 1936 rubies listed @ 1205, 1119 sold — spent ≈ 1.32m cumulative (the poisoning shape).
		g.onOffer(3, snap(1603, GrandExchangeOfferState.SELLING, 1936, 1119, 1_348_395, 1205), 1L);
		assertEquals(0, g.heldCoins()); // the phantom coins — collectable, so counted zero
		int[][] items = g.heldItemPairs();
		assertEquals(1, items.length);
		assertEquals(1603, items[0][0]);
		assertEquals(1936 - 1119, items[0][1]); // 817 still listed
	}
}
