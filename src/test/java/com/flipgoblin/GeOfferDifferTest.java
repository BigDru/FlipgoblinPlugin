package com.flipgoblin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Test;

public class GeOfferDifferTest
{
	private static final int SLOT = 0;

	private static OfferSnapshot snap(int item, GrandExchangeOfferState st, int total, int sold, long spent)
	{
		return new OfferSnapshot(item, st, total, sold, spent, 100); // one shared set price (identity field)
	}

	private static OfferSnapshot snapAt(int item, GrandExchangeOfferState st, int total, int sold, long spent,
		long price)
	{
		return new OfferSnapshot(item, st, total, sold, spent, price);
	}

	@Test
	public void staleRefireOfDeadOffer_doesNotAliasAFreshSameItemBaseline()
	{
		// The law-rune phantom (2026-08-10): a completed 6000@124 buy's state refires AFTER a fresh
		// 0-progress offer of the same item occupies the slot. Different totalQuantity/price → a
		// DIFFERENT offer: the refire must baseline, never emit a 6000 fill.
		GeOfferDiffer d = new GeOfferDiffer();
		assertFalse(d.onOffer(SLOT, snapAt(563, GrandExchangeOfferState.BUYING, 12_000, 0, 0, 123), 1L)
			.isPresent());
		assertFalse(d.onOffer(SLOT, snapAt(563, GrandExchangeOfferState.BOUGHT, 6_000, 6_000, 744_000, 124), 2L)
			.isPresent());
	}

	@Test
	public void sameItemOppositeSide_neverReadsAsTheSameOffer()
	{
		// Side is part of the offer identity: a SELL baseline followed by a BUY of the same item whose
		// counters happen to overtake it must reset, not emit the overlap as a fill.
		GeOfferDiffer d = new GeOfferDiffer();
		assertFalse(d.onOffer(SLOT, snap(563, GrandExchangeOfferState.SELLING, 6_000, 300, 37_200), 1L)
			.isPresent());
		assertFalse(d.onOffer(SLOT, snap(563, GrandExchangeOfferState.BUYING, 6_000, 5_000, 500_000), 2L)
			.isPresent());
	}

	@Test
	public void seededBaseline_recoversOfflineFills()
	{
		// A persisted seed carries the full offer identity; the login replay diffs against it and
		// the offline delta comes back as a RECOVERED fill with the honest time window.
		Map<Integer, GeOfferDiffer.SlotState> seed = new HashMap<>();
		seed.put(SLOT, new GeOfferDiffer.SlotState(100, 2, 200, 50L, TradeRecord.Side.BUY, 10, 100));
		GeOfferDiffer d = new GeOfferDiffer(seed);
		TradeRecord r = d.onOffer(SLOT, snap(100, GrandExchangeOfferState.BOUGHT, 10, 10, 1000), 100L).get();
		assertEquals(8, r.quantity);
		assertTrue(r.recovered);
		assertEquals(50L, r.offlineSince);
	}

	@Test
	public void freshBuy_partialThenComplete_emitsSummingDeltas()
	{
		GeOfferDiffer d = new GeOfferDiffer();
		// Placement: first sighting at 0 → baseline, no fill.
		assertFalse(d.onOffer(SLOT, snap(100, GrandExchangeOfferState.BUYING, 10, 0, 0), 1L).isPresent());
		// Partial fill of 4 @100.
		TradeRecord f1 = d.onOffer(SLOT, snap(100, GrandExchangeOfferState.BUYING, 10, 4, 400), 2L).get();
		assertEquals(TradeRecord.Side.BUY, f1.side);
		assertEquals(4, f1.quantity);
		assertEquals(100, f1.price);
		assertEquals(400, f1.spent);
		assertEquals(100, f1.itemId);
		assertEquals(SLOT, f1.slot);
		assertEquals(2L, f1.timestamp);
		// Remaining 6 fill and the offer completes.
		TradeRecord f2 = d.onOffer(SLOT, snap(100, GrandExchangeOfferState.BUYING, 10, 10, 1000), 3L).get();
		assertEquals(6, f2.quantity);
		assertEquals(600, f2.spent);
		// BOUGHT with no new quantity → no fill.
		assertFalse(d.onOffer(SLOT, snap(100, GrandExchangeOfferState.BOUGHT, 10, 10, 1000), 4L).isPresent());
		assertEquals(10, f1.quantity + f2.quantity); // deltas sum to the total
	}

	@Test
	public void cancelAfterPartial_emitsOnlyThePartial()
	{
		GeOfferDiffer d = new GeOfferDiffer();
		d.onOffer(SLOT, snap(100, GrandExchangeOfferState.BUYING, 10, 0, 0), 1L);
		TradeRecord f = d.onOffer(SLOT, snap(100, GrandExchangeOfferState.BUYING, 10, 3, 297), 2L).get();
		assertEquals(3, f.quantity);
		assertEquals(99, f.price);
		// Cancel with no additional fill → nothing.
		assertFalse(d.onOffer(SLOT, snap(100, GrandExchangeOfferState.CANCELLED_BUY, 10, 3, 297), 3L).isPresent());
		// Collected → slot cleared.
		assertFalse(d.onOffer(SLOT, snap(0, GrandExchangeOfferState.EMPTY, 0, 0, 0), 4L).isPresent());
	}

	@Test
	public void cancelCarryingAFinalFill_emitsIt()
	{
		GeOfferDiffer d = new GeOfferDiffer();
		d.onOffer(SLOT, snap(100, GrandExchangeOfferState.BUYING, 10, 0, 0), 1L);
		TradeRecord f = d.onOffer(SLOT, snap(100, GrandExchangeOfferState.CANCELLED_BUY, 10, 2, 200), 2L).get();
		assertEquals(TradeRecord.Side.BUY, f.side);
		assertEquals(2, f.quantity);
	}

	@Test
	public void loginReplay_emptySeed_isSuppressed()
	{
		GeOfferDiffer d = new GeOfferDiffer();
		// Login refires a pre-existing, already-partly-filled offer. With no baseline we must NOT emit it.
		assertFalse(d.onOffer(SLOT, snap(100, GrandExchangeOfferState.BUYING, 10, 5, 500), 1L).isPresent());
		// Only genuinely-new fills after the replay are captured.
		TradeRecord f = d.onOffer(SLOT, snap(100, GrandExchangeOfferState.BUYING, 10, 7, 700), 2L).get();
		assertEquals(2, f.quantity);
	}

	@Test
	public void offlineFills_recoveredFromPersistedSeed()
	{
		Map<Integer, GeOfferDiffer.SlotState> seed = new HashMap<>();
		// Last session left it at 3 filled, last seen at t=1000.
		seed.put(SLOT, new GeOfferDiffer.SlotState(100, 3, 300, 1000L, TradeRecord.Side.BUY, 10, 100));
		GeOfferDiffer d = new GeOfferDiffer(seed);
		// Login at t=5000: the offer progressed to 5 while offline → the 2-unit delta is recovered,
		// MARKED as such, and bounded to its offline window [1000, 5000].
		TradeRecord f = d.onOffer(SLOT, snap(100, GrandExchangeOfferState.BUYING, 10, 5, 520), 5000L).get();
		assertEquals(2, f.quantity);
		assertEquals(220, f.spent);
		assertEquals(110, f.price);
		assertTrue(f.recovered);
		assertEquals(1000L, f.offlineSince);
		assertEquals(5000L, f.timestamp);
		// The NEXT fill on the same slot is live again — never marked recovered.
		TradeRecord live = d.onOffer(SLOT, snap(100, GrandExchangeOfferState.BUYING, 10, 7, 740), 6000L).get();
		assertFalse(live.recovered);
		assertEquals(0L, live.offlineSince);
	}

	@Test
	public void seededSlot_replayIdentical_thenLiveFill_notRecovered()
	{
		Map<Integer, GeOfferDiffer.SlotState> seed = new HashMap<>();
		seed.put(SLOT, new GeOfferDiffer.SlotState(100, 3, 300, 1000L, TradeRecord.Side.BUY, 10, 100));
		GeOfferDiffer d = new GeOfferDiffer(seed);
		// Login replay with UNCHANGED values (nothing happened offline) → no emit, seeded status consumed.
		assertFalse(d.onOffer(SLOT, snap(100, GrandExchangeOfferState.BUYING, 10, 3, 300), 5000L).isPresent());
		// A later fill this session is a normal live fill.
		TradeRecord f = d.onOffer(SLOT, snap(100, GrandExchangeOfferState.BUYING, 10, 6, 600), 6000L).get();
		assertEquals(3, f.quantity);
		assertFalse(f.recovered);
	}

	@Test
	public void seededSlot_differentItem_baselinesOnly_noPhantomRecovery()
	{
		Map<Integer, GeOfferDiffer.SlotState> seed = new HashMap<>();
		seed.put(SLOT, new GeOfferDiffer.SlotState(100, 3, 300, 1000L, TradeRecord.Side.BUY, 10, 100));
		GeOfferDiffer d = new GeOfferDiffer(seed);
		// The slot was collected + reused for another item while offline — first sighting only baselines.
		assertFalse(d.onOffer(SLOT, snap(200, GrandExchangeOfferState.SELLING, 5, 2, 400), 5000L).isPresent());
	}

	@Test
	public void slotReuse_afterEmpty_startsFresh_noPhantom()
	{
		GeOfferDiffer d = new GeOfferDiffer();
		d.onOffer(SLOT, snap(100, GrandExchangeOfferState.BUYING, 5, 0, 0), 1L);
		assertEquals(5, d.onOffer(SLOT, snap(100, GrandExchangeOfferState.BUYING, 5, 5, 500), 2L).get().quantity);
		d.onOffer(SLOT, snap(0, GrandExchangeOfferState.EMPTY, 0, 0, 0), 3L); // collected
		// A different item now occupies the slot — first sighting only baselines.
		assertFalse(d.onOffer(SLOT, snap(200, GrandExchangeOfferState.SELLING, 3, 0, 0), 4L).isPresent());
		TradeRecord sell = d.onOffer(SLOT, snap(200, GrandExchangeOfferState.SELLING, 3, 3, 900), 5L).get();
		assertEquals(TradeRecord.Side.SELL, sell.side);
		assertEquals(200, sell.itemId);
		assertEquals(3, sell.quantity);
	}

	@Test
	public void slotReuse_withoutEmpty_itemChange_reBaselines()
	{
		GeOfferDiffer d = new GeOfferDiffer();
		d.onOffer(SLOT, snap(100, GrandExchangeOfferState.BOUGHT, 10, 10, 1000), 1L); // seen, baselined
		// New item appears in the slot without an EMPTY in between → treat as a new offer, don't emit.
		assertFalse(d.onOffer(SLOT, snap(200, GrandExchangeOfferState.SELLING, 4, 0, 0), 2L).isPresent());
	}

	@Test
	public void sellMirror_soldEmitsSellSide()
	{
		GeOfferDiffer d = new GeOfferDiffer();
		d.onOffer(SLOT, snap(200, GrandExchangeOfferState.SELLING, 10, 0, 0), 1L);
		TradeRecord f = d.onOffer(SLOT, snap(200, GrandExchangeOfferState.SOLD, 10, 10, 2000), 2L).get();
		assertEquals(TradeRecord.Side.SELL, f.side);
		assertEquals(10, f.quantity);
		assertEquals(200, f.price);
	}

	@Test
	public void multiPriceDelta_roundsPrice_retainsExactSpent()
	{
		GeOfferDiffer d = new GeOfferDiffer();
		d.onOffer(SLOT, snap(100, GrandExchangeOfferState.BUYING, 5, 0, 0), 1L);
		// 5 units for 503gp (units filled at mixed prices) → avg 100.6 → round 101, but spent stays exact.
		TradeRecord f = d.onOffer(SLOT, snap(100, GrandExchangeOfferState.BUYING, 5, 5, 503), 2L).get();
		assertEquals(101, f.price);
		assertEquals(503, f.spent);
		assertEquals(5, f.quantity);
	}

	@Test
	public void snapshotBaseline_roundTrips_forPersistence()
	{
		GeOfferDiffer d1 = new GeOfferDiffer();
		d1.onOffer(SLOT, snap(100, GrandExchangeOfferState.BUYING, 10, 3, 300), 1L);
		Map<Integer, GeOfferDiffer.SlotState> saved = d1.snapshotBaseline();
		assertEquals(3, saved.get(SLOT).quantitySold);
		assertEquals(300, saved.get(SLOT).spent);
		assertEquals(1L, saved.get(SLOT).lastSeen); // the recovered-fill window's lower bound persists too
		// A fresh differ seeded from the snapshot continues diffing as if uninterrupted.
		GeOfferDiffer d2 = new GeOfferDiffer(saved);
		Optional<TradeRecord> f = d2.onOffer(SLOT, snap(100, GrandExchangeOfferState.BUYING, 10, 5, 500), 2L);
		assertTrue(f.isPresent());
		assertEquals(2, f.get().quantity);
	}

	@Test
	public void logoutClearPredicate_flagsOnlyTheStorm()
	{
		// The logout/hop storm: EMPTY while not LOGGED_IN → drop.
		assertTrue(GeOfferDiffer.isLogoutClear(
			GrandExchangeOfferState.EMPTY, net.runelite.api.GameState.LOGIN_SCREEN));
		assertTrue(GeOfferDiffer.isLogoutClear(
			GrandExchangeOfferState.EMPTY, net.runelite.api.GameState.HOPPING));
		// A genuine collection arrives while LOGGED_IN → fold it.
		assertFalse(GeOfferDiffer.isLogoutClear(
			GrandExchangeOfferState.EMPTY, net.runelite.api.GameState.LOGGED_IN));
		// Non-EMPTY states are never the storm, whatever the game state.
		assertFalse(GeOfferDiffer.isLogoutClear(
			GrandExchangeOfferState.BUYING, net.runelite.api.GameState.LOGIN_SCREEN));
	}

	@Test
	public void disconnectRoundTrip_stormDropped_offlineFillRecovered()
	{
		// Live: buy filling, observed to 747/1000 (the live b42 mithril-bar scenario).
		GeOfferDiffer d = new GeOfferDiffer();
		d.onOffer(SLOT, snap(2359, GrandExchangeOfferState.BUYING, 1000, 0, 0), 1L);
		d.onOffer(SLOT, snap(2359, GrandExchangeOfferState.BUYING, 1000, 747, 705_168), 2L);

		// Disconnect: the client fires EMPTY per slot while NOT logged in — the subscriber drops it
		// via isLogoutClear, so the differ never sees it (nothing to fold here). The baseline the
		// profile persists at that moment must still hold slot 747.
		assertTrue(GeOfferDiffer.isLogoutClear(
			GrandExchangeOfferState.EMPTY, net.runelite.api.GameState.LOGIN_SCREEN));
		assertEquals(747, d.snapshotBaseline().get(SLOT).quantitySold);

		// Re-login (same or next session): replay shows BOUGHT 1000/1000 → the 253 offline units
		// come out as ONE recovered fill instead of vanishing.
		GeOfferDiffer relogged = new GeOfferDiffer(d.snapshotBaseline());
		TradeRecord f = relogged.onOffer(
			SLOT, snap(2359, GrandExchangeOfferState.BOUGHT, 1000, 1000, 944_000), 10_000L).get();
		assertEquals(253, f.quantity);
		assertTrue(f.recovered);
		assertEquals(2L, f.offlineSince);
	}
}
