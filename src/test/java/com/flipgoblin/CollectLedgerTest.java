package com.flipgoblin;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Test;

public class CollectLedgerTest
{
	private static final int SLOT = 2;

	private static OfferSnapshot snap(int item, GrandExchangeOfferState st, int total, int sold, long spent,
		long price)
	{
		return new OfferSnapshot(item, st, total, sold, spent, price);
	}

	@Test
	public void buyFills_accumulateItems_andEmptyClears()
	{
		CollectLedger l = new CollectLedger();
		l.onOffer(SLOT, snap(563, GrandExchangeOfferState.BUYING, 6000, 0, 0, 124), 1L);
		l.onOffer(SLOT, snap(563, GrandExchangeOfferState.BUYING, 6000, 400, 49_600, 124), 2L);
		l.onOffer(SLOT, snap(563, GrandExchangeOfferState.BOUGHT, 6000, 6000, 744_000, 124), 3L);
		assertArrayEquals(new int[][]{{563, 6000}}, l.itemPairs());
		assertEquals(0, l.coins());
		// Collected → slot EMPTY → the entry dies.
		assertTrue(l.onOffer(SLOT, snap(0, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0), 4L));
		assertEquals(0, l.itemPairs().length);
	}

	@Test
	public void sellFills_accumulateCoins_NET_ofTax_neverGross()
	{
		// The verified id=4 case shape: 1,800 @ 1,189 — tax 23/unit, net 1,166/unit. Gross would
		// be 2,140,200; the ledger must hold 2,098,800.
		CollectLedger l = new CollectLedger();
		l.onOffer(SLOT, snap(4151, GrandExchangeOfferState.SELLING, 1800, 0, 0, 1189), 1L);
		l.onOffer(SLOT, snap(4151, GrandExchangeOfferState.SOLD, 1800, 1800, 2_140_200, 1189), 2L);
		assertEquals(1800L * 1166, l.coins());
		assertEquals(0, l.itemPairs().length);
	}

	@Test
	public void cancelledBuy_addsUnfilledEscrowRefund_cancelledSell_returnsStock()
	{
		CollectLedger l = new CollectLedger();
		// Buy 100 @ 50, 30 filled, then cancelled: 30 items + 70×50 refund coins in the box.
		l.onOffer(SLOT, snap(100, GrandExchangeOfferState.BUYING, 100, 0, 0, 50), 1L);
		l.onOffer(SLOT, snap(100, GrandExchangeOfferState.BUYING, 100, 30, 1500, 50), 2L);
		l.onOffer(SLOT, snap(100, GrandExchangeOfferState.CANCELLED_BUY, 100, 30, 1500, 50), 3L);
		assertArrayEquals(new int[][]{{100, 30}}, l.itemPairs());
		assertEquals(70L * 50, l.coins());

		// Sell 100 lobsters (379, tax-exempt) @ 200, 40 sold, cancelled: 40×200 coins + 60 stock.
		CollectLedger l2 = new CollectLedger();
		l2.onOffer(SLOT, snap(379, GrandExchangeOfferState.SELLING, 100, 0, 0, 200), 1L);
		l2.onOffer(SLOT, snap(379, GrandExchangeOfferState.SELLING, 100, 40, 8000, 200), 2L);
		l2.onOffer(SLOT, snap(379, GrandExchangeOfferState.CANCELLED_SELL, 100, 40, 8000, 200), 3L);
		assertEquals(40L * 200, l2.coins()); // exempt — no tax
		assertArrayEquals(new int[][]{{379, 60}}, l2.itemPairs());
	}

	@Test
	public void firstSighting_countsNothing_slotReuseWithoutEmpty_assumesCollected()
	{
		CollectLedger l = new CollectLedger();
		// Login replay of a completed offer: baseline only — offline progress arrives via
		// applyFill under acquittal, never through the raw replay (never-inflate).
		assertFalse(l.onOffer(SLOT, snap(563, GrandExchangeOfferState.BOUGHT, 6000, 6000, 744_000, 124), 1L));
		assertEquals(0, l.itemPairs().length);
		// Live fills after the baseline still count.
		CollectLedger l2 = new CollectLedger();
		l2.onOffer(SLOT, snap(100, GrandExchangeOfferState.BUYING, 10, 0, 0, 50), 1L);
		l2.onOffer(SLOT, snap(100, GrandExchangeOfferState.BUYING, 10, 4, 200, 50), 2L);
		assertArrayEquals(new int[][]{{100, 4}}, l2.itemPairs());
		// A different offer appears without a witnessed EMPTY → the old contents were collected
		// while we blinked: entry dies, new offer starts clean.
		assertTrue(l2.onOffer(SLOT, snap(100, GrandExchangeOfferState.BUYING, 20, 0, 0, 60), 3L));
		assertEquals(0, l2.itemPairs().length);
	}

	@Test
	public void widgetResync_isAuthoritative_bothDirections()
	{
		CollectLedger l = new CollectLedger();
		l.onOffer(SLOT, snap(100, GrandExchangeOfferState.BUYING, 10, 0, 0, 50), 1L);
		l.onOffer(SLOT, snap(100, GrandExchangeOfferState.BUYING, 10, 10, 500, 50), 2L);
		assertArrayEquals(new int[][]{{100, 10}}, l.itemPairs());
		// Box shows only 3 left (mobile collected 7): resync heals DOWN.
		assertTrue(l.resyncAll(java.util.Arrays.asList(new long[]{100, 3, 0})));
		assertArrayEquals(new int[][]{{100, 3}}, l.itemPairs());
		// Box shows pre-ledger stock we never witnessed filling: resync heals UP (the b72 ruby
		// bootstrap case — authoritative-while-visible).
		assertTrue(l.resyncAll(java.util.Arrays.asList(new long[]{100, 3, 0}, new long[]{1603, 4078, 250})));
		assertEquals(250, l.coins());
		boolean rubies = false;
		for (int[] p : l.itemPairs())
		{
			rubies |= p[0] == 1603 && p[1] == 4078;
		}
		assertTrue(rubies);
		// Identical state → no change (no persist churn per tick).
		assertFalse(l.resyncAll(java.util.Arrays.asList(new long[]{100, 3, 0}, new long[]{1603, 4078, 250})));
		// Emptied box → the whole ledger dies with it.
		assertTrue(l.resyncAll(java.util.Collections.emptyList()));
		assertEquals(0, l.itemPairs().length);
		assertEquals(0, l.coins());
	}

	@Test
	public void zeroAll_empties_and_persistRoundTrip_restoresUnderAcquittal()
	{
		CollectLedger l = new CollectLedger();
		l.onOffer(SLOT, snap(100, GrandExchangeOfferState.BUYING, 10, 0, 0, 50), 1L);
		l.onOffer(SLOT, snap(100, GrandExchangeOfferState.BUYING, 10, 10, 500, 50), 2L);
		Map<Integer, CollectLedger.Entry> saved = l.snapshotEntries();
		assertTrue(l.zeroAll());
		assertFalse(l.zeroAll()); // already empty — no churn
		assertEquals(0, l.itemPairs().length);
		// The acquitted-login path: persisted entries seed a fresh ledger and count again.
		CollectLedger next = new CollectLedger();
		next.seedEntries(saved);
		assertArrayEquals(new int[][]{{100, 10}}, next.itemPairs());
		// MERGE semantics: a slot the live session already touched keeps its live entry — the
		// seed must never eat a pre-judge witnessed mutation.
		CollectLedger merged = new CollectLedger();
		merged.applyFill(new TradeRecord(200, TradeRecord.Side.BUY, 10, 5, 50, SLOT, 1L, true, 1L));
		merged.seedEntries(saved); // saved holds SLOT too (item 100) — live entry wins
		assertArrayEquals(new int[][]{{200, 5}}, merged.itemPairs());
		// A seeded entry whose slot replays a DIFFERENT item = missed EMPTY → dropped low.
		assertTrue(next.onOffer(SLOT, snap(200, GrandExchangeOfferState.BUYING, 5, 0, 0, 10), 3L)
			|| next.itemPairs().length == 0);
		assertEquals(0, next.itemPairs().length);
	}

	@Test
	public void applyFill_recoveredRecords_countUnderAcquittal_netOfTax()
	{
		CollectLedger l = new CollectLedger();
		// Overnight 20k-bar sell @ 93 (the −1.72m dip case): net 92/unit (tax floor(1.86)=1).
		l.applyFill(new TradeRecord(2357, TradeRecord.Side.SELL, 93, 20_000, 1_860_000, SLOT, 5L, true, 1L));
		assertEquals(20_000L * SessionStats.netFromSale(93, 2357), l.coins());
		// A recovered buy counts as stock.
		l.applyFill(new TradeRecord(563, TradeRecord.Side.BUY, 124, 7000, 868_000, 3, 6L, true, 1L));
		boolean found = false;
		for (int[] p : l.itemPairs())
		{
			found |= p[0] == 563 && p[1] == 7000;
		}
		assertTrue(found);
	}

	@Test
	public void arrivals_positiveDeltasOnly_coinsRideAs995()
	{
		int[][] before = {{995, 1000}, {563, 20}, {4151, 1}};
		int[][] after = {{995, 5000}, {563, 6020}, {2357, 30}}; // whip left (sold), bars arrived
		Map<Integer, Integer> d = CollectLedger.arrivals(before, after);
		assertEquals(Integer.valueOf(4000), d.get(995));
		assertEquals(Integer.valueOf(6000), d.get(563));
		assertEquals(Integer.valueOf(30), d.get(2357));
		assertFalse(d.containsKey(4151)); // losses are not arrivals
		assertTrue(CollectLedger.arrivals(after, after).isEmpty());
	}

	@Test
	public void applyCollectDelta_attributesExactly_slotOrder_multiSlotSameItem()
	{
		CollectLedger l = new CollectLedger();
		// Slot 1 and slot 5 both hold uncollected law runes; slot 3 holds net sell coins.
		l.applyFill(new TradeRecord(563, TradeRecord.Side.BUY, 124, 4000, 496_000, 1, 1L, true, 1L));
		l.applyFill(new TradeRecord(563, TradeRecord.Side.BUY, 124, 2000, 248_000, 5, 2L, true, 1L));
		l.applyFill(new TradeRecord(379, TradeRecord.Side.SELL, 200, 100, 20_000, 3, 3L, true, 1L));
		// Collect delivered 5,000 runes (partial fit) + all the coins.
		Map<Integer, Integer> arrived = new HashMap<>();
		arrived.put(563, 5000);
		assertTrue(l.applyCollectDelta(arrived, 20_000)); // lobster (379) is exempt — net = gross
		// Slot order: slot 1 fully consumed (4000), slot 5 keeps the 1,000 remainder.
		assertArrayEquals(new int[][]{{563, 1000}}, l.itemPairs());
		assertEquals(0, l.coins());
	}

	@Test
	public void applyCollectDelta_clampsAtZero_ignoresUnmatchedArrivals()
	{
		CollectLedger l = new CollectLedger();
		l.applyFill(new TradeRecord(563, TradeRecord.Side.BUY, 124, 100, 12_400, 0, 1L, true, 1L));
		Map<Integer, Integer> arrived = new HashMap<>();
		arrived.put(563, 500); // more than the ledger holds (e.g. an unrelated same-item source)
		arrived.put(1613, 40); // not ours at all
		assertTrue(l.applyCollectDelta(arrived, 999_999)); // stray coins with no coin entries
		assertEquals(0, l.itemPairs().length); // consumed to zero, never negative
		assertEquals(0, l.coins());
		// Nothing armed, nothing held → a delta application is a no-op.
		assertFalse(l.applyCollectDelta(new HashMap<>(), 0));
	}

	@Test
	public void resyncViewed_witnessesOneSlot_andSweepsSameItemDuplicates()
	{
		CollectLedger l = new CollectLedger();
		// A whole-box resync stored the rubies under detection-order key 0…
		l.resyncAll(java.util.Arrays.asList(new long[]{1603, 4078, 0}));
		// …then the offer-detail view witnesses the SAME offer under its real slot key 1.
		assertTrue(l.resyncViewed(1, 1603, 4078, 0));
		// One entry, never two — a glance can never double-count.
		assertArrayEquals(new int[][]{{1603, 4078}}, l.itemPairs());
		// Re-witnessing the identical state is a no-op (no persist churn per tick).
		assertFalse(l.resyncViewed(1, 1603, 4078, 0));
		// Empty boxes witnessed = collected: the slot's entry dies.
		assertTrue(l.resyncViewed(1, 1603, 0, 0));
		assertEquals(0, l.itemPairs().length);
	}

	@Test
	public void seedEntries_dropsEmptyAndNull_gsonShapeRoundTrips()
	{
		Map<Integer, CollectLedger.Entry> seed = new HashMap<>();
		CollectLedger.Entry empty = new CollectLedger.Entry();
		seed.put(1, empty);
		seed.put(2, null);
		CollectLedger l = new CollectLedger();
		l.seedEntries(seed);
		assertEquals(0, l.itemPairs().length);
		assertEquals(0, l.coins());
	}
}
