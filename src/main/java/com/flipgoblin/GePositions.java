package com.flipgoblin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.GrandExchangeOfferState;

/**
 * The 8 GE slots as live positions: every ask and bid the player has standing, with its
 * fill progress and lifecycle phase. Rebuilt for free on every login because RuneLite
 * replays all slot states, so nothing needs persisting.
 *
 * Lifecycle: WORKING (offer live and filling), then COMPLETE (fully bought or sold) or
 * CANCELLED (aborted), then collected, at which point an EMPTY event removes the slot.
 * OSRS has no in-place "modify offer". A modification is a cancel plus a re-place, and
 * this model shows it as exactly that.
 *
 * This is also the source of GE-side assets for the ledger, but only what the GE machine
 * is provably holding: a WORKING buy's unfilled coin escrow and a WORKING sell's unsold
 * stock. Everything waiting in the collection box counts zero here, because any stack can
 * be collected at any moment without an event firing, and counting it would double-count.
 * {@link CollectLedger} adds the collectable value back from witnessed signals.
 *
 * Pure and single-threaded (client thread), like the differ.
 */
public final class GePositions
{
	public enum Phase
	{
		WORKING, COMPLETE, CANCELLED
	}

	public static final class Position
	{
		public final int slot;
		public final int itemId;
		public final TradeRecord.Side side;
		public final long price;
		public final int totalQuantity;
		public final int quantitySold;
		public final long spent;
		public final Phase phase;
		/** When we first saw this offer in the slot. Survives across events, resets when the slot is reused. */
		public final long firstSeen;
		public final long lastUpdate;

		Position(int slot, int itemId, TradeRecord.Side side, long price, int totalQuantity, int quantitySold,
			long spent, Phase phase, long firstSeen, long lastUpdate)
		{
			this.slot = slot;
			this.itemId = itemId;
			this.side = side;
			this.price = price;
			this.totalQuantity = totalQuantity;
			this.quantitySold = quantitySold;
			this.spent = spent;
			this.phase = phase;
			this.firstSeen = firstSeen;
			this.lastUpdate = lastUpdate;
		}
	}

	private final Map<Integer, Position> bySlot = new HashMap<>();

	/** Folds in one offer event. EMPTY means collected or cleared, and the position leaves the board. */
	public void onOffer(int slot, OfferSnapshot next, long ts)
	{
		if (next.state == GrandExchangeOfferState.EMPTY)
		{
			bySlot.remove(slot);
			return;
		}
		Position prev = bySlot.get(slot);
		// Anything that fails the identity check means the slot was reused, and the
		// position's clock restarts (see OfferStates.sameOffer).
		boolean sameOffer = prev != null && OfferStates.sameOffer(prev.itemId, prev.side,
			prev.totalQuantity, prev.price, prev.quantitySold, prev.spent, next);
		long firstSeen = sameOffer ? prev.firstSeen : ts;
		bySlot.put(slot, new Position(slot, next.itemId, OfferStates.sideOf(next.state), next.price,
			next.totalQuantity, next.quantitySold, next.spent, OfferStates.phaseOf(next.state), firstSeen, ts));
	}

	/** Current positions, slot-ordered. */
	public List<Position> active()
	{
		List<Position> out = new ArrayList<>(bySlot.values());
		out.sort((a, b) -> Integer.compare(a.slot, b.slot));
		return out;
	}

	/**
	 * Items the GE machine is provably holding: only a WORKING sell's unsold remainder (see
	 * the class doc for why everything else counts zero). Zero-quantity pairs are omitted.
	 */
	public int[][] heldItemPairs()
	{
		List<int[]> pairs = new ArrayList<>();
		for (Position p : bySlot.values())
		{
			// Only a WORKING sell's unsold remainder is still on the GE machine; all else counts zero.
			int qty = (p.side == TradeRecord.Side.SELL && p.phase == Phase.WORKING)
				? p.totalQuantity - p.quantitySold
				: 0;
			if (qty > 0)
			{
				pairs.add(new int[]{p.itemId, qty});
			}
		}
		return pairs.toArray(new int[0][]);
	}

	/**
	 * Coins the GE machine is provably holding: only a WORKING buy's unfilled escrow,
	 * {@code price × (total − sold)} (see the class doc for why everything else counts zero).
	 */
	public long heldCoins()
	{
		long coins = 0;
		for (Position p : bySlot.values())
		{
			// Only a WORKING buy's unfilled escrow is committed to the machine; all else counts zero.
			if (p.side == TradeRecord.Side.BUY && p.phase == Phase.WORKING)
			{
				coins += p.price * (long) (p.totalQuantity - p.quantitySold);
			}
		}
		return coins;
	}

}
