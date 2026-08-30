package com.flipgoblin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.runelite.api.GrandExchangeOfferState;

/**
 * Turns RuneLite's {@code GrandExchangeOffer} events into individual fill records. RuneLite
 * reports an offer's cumulative state, not deltas, and replays every non-empty slot on
 * login, so each event is diffed against a per-slot baseline to find what actually changed.
 * Pure and single-threaded (RuneLite events arrive on the client thread).
 *
 * The differ can start empty, which makes the login replay only establish baselines, or it
 * can be seeded with the previous session's saved baselines, in which case fills that
 * happened while offline show up as the first diff. Both cases are the same operation,
 * diffing against a prior baseline; only the seed source differs.
 *
 * Recovered fills are marked. The game exposes no fill timestamps, so a fill's
 * {@code timestamp} is always the time we detected it. For a fill recovered at login that
 * can be long after the real fill, so the record carries {@code recovered=true} and
 * {@code offlineSince}, bounding the true fill time to somewhere between the two.
 */
public final class GeOfferDiffer
{
	/** Last-seen cumulative for a slot's current offer. Public so persistence can round-trip it via Gson. */
	public static final class SlotState
	{
		public final int itemId;
		public final int quantitySold;
		public final long spent;
		/** When this slot state was last seen live (epoch ms). Bounds any recovered fill's time window. */
		public final long lastSeen;
		/**
		 * Offer identity guards: side, total quantity, and set price. Item id and counters
		 * alone are a weak identity. Without these, a stale re-fire of a completed offer's
		 * state, diffed against a fresh baseline of the same item, would book the whole dead
		 * offer as a live fill.
		 */
		public final TradeRecord.Side side;
		public final int totalQuantity;
		public final long price;

		public SlotState(int itemId, int quantitySold, long spent, long lastSeen,
			TradeRecord.Side side, int totalQuantity, long price)
		{
			this.itemId = itemId;
			this.quantitySold = quantitySold;
			this.spent = spent;
			this.lastSeen = lastSeen;
			this.side = side;
			this.totalQuantity = totalQuantity;
			this.price = price;
		}

	}

	private final Map<Integer, SlotState> baseline = new HashMap<>();
	/** Slots whose baseline came from the saved seed and have not yet seen a live event this session. */
	private final Set<Integer> seededSlots = new HashSet<>();

	public GeOfferDiffer()
	{
	}

	/** Seeds the differ with the previous session's baselines so offline fills can be recovered. */
	public GeOfferDiffer(Map<Integer, SlotState> seed)
	{
		if (seed != null)
		{
			baseline.putAll(seed);
			seededSlots.addAll(seed.keySet());
		}
	}

	/** A copy of the current per-slot baselines, for saving. */
	public Map<Integer, SlotState> snapshotBaseline()
	{
		return new HashMap<>(baseline);
	}

	/**
	 * Detects the logout EMPTY storm. When the client leaves the logged-in state it clears
	 * its GE memory and fires an EMPTY event for every slot. Those are client-side resets,
	 * not collections. Folding one in would wipe the slot's baseline, and fills landing
	 * while offline could then never be recovered on the next login. Callers must drop the
	 * event entirely when this returns true, as RuneLite's own GE plugin does.
	 */
	public static boolean isLogoutClear(GrandExchangeOfferState offerState, net.runelite.api.GameState gameState)
	{
		return offerState == GrandExchangeOfferState.EMPTY && gameState != net.runelite.api.GameState.LOGGED_IN;
	}

	/**
	 * Folds one offer event into the baseline. Returns a fill only when the event carries
	 * newly transacted quantity for the same ongoing offer. The first sighting of an offer
	 * only records a baseline, which is what keeps the login replay silent.
	 */
	public Optional<TradeRecord> onOffer(int slot, OfferSnapshot next, long ts)
	{
		// Any live event on a slot ends its seeded status. Remember whether this event was the first.
		boolean wasSeeded = seededSlots.remove(slot);

		if (next.state == GrandExchangeOfferState.EMPTY)
		{
			// The slot was collected or cleared. Forget its baseline so the next offer starts fresh.
			baseline.remove(slot);
			return Optional.empty();
		}

		SlotState prev = baseline.get(slot);
		// Anything that fails the identity check means a different offer now occupies the
		// slot, so record a baseline and emit nothing (see OfferStates.sameOffer).
		TradeRecord.Side nextSide = OfferStates.sideOf(next.state);
		boolean sameOffer = prev != null && OfferStates.sameOffer(prev.itemId, prev.side,
			prev.totalQuantity, prev.price, prev.quantitySold, prev.spent, next);

		baseline.put(slot, new SlotState(next.itemId, next.quantitySold, next.spent, ts,
			nextSide, next.totalQuantity, next.price));

		if (!sameOffer)
		{
			// A new or unknown offer, or a login replay against an empty baseline. Baseline only.
			return Optional.empty();
		}

		int qtyDelta = next.quantitySold - prev.quantitySold;
		if (qtyDelta <= 0)
		{
			// A state-only transition with no new fill, such as BUYING to BOUGHT at the same quantity.
			return Optional.empty();
		}

		long spentDelta = next.spent - prev.spent;
		// A delta can span units filled at different prices, so the per-item price is an average.
		// The exact spent delta is kept on the record so no coins are lost to rounding.
		long price = Math.round((double) spentDelta / qtyDelta);
		// A fill against a still-seeded baseline happened while we were not watching. Mark it
		// recovered and bound its true time instead of letting it read as a fresh trade.
		long offlineSince = wasSeeded ? prev.lastSeen : 0;
		return Optional.of(new TradeRecord(
			next.itemId, nextSide, price, qtyDelta, spentDelta, slot, ts, wasSeeded, offlineSince));
	}
}
