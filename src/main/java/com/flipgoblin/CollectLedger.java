package com.flipgoblin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.GrandExchangeOfferState;

/**
 * Tracks what the GE collection box is holding for the player, per slot. The game API never
 * exposes this directly. It complements the working-offer escrow in {@link GePositions}.
 * Every uncertainty resolves downward (assume collected), so the ledger can never inflate
 * the total.
 *
 * It is fed from three sources, each covering the others' blind spots:
 * - Fill deltas from the offer stream, diffed here with the same identity rules as the
 *   differ. A buy fill adds bought stock. A sell fill adds proceeds net of tax. A
 *   cancellation adds the refund: unfilled escrow coins for a buy, unsold stock for a
 *   sell.
 * - The collection box widget via {@link #resyncAll}, authoritative while visible.
 * - Collect clicks via {@link #zeroAll}: any witnessed collect empties the ledger, and the
 *   next widget glance restores whatever actually remains.
 *
 * The first sighting of an offer (login replay or slot reuse) only records a baseline and
 * counts nothing. Offline fills enter only through {@link #applyFill}, which the caller
 * gates on an ACQUITTED custody verdict. An EMPTY slot means collected, and its entry dies.
 *
 * Pure and single-threaded (client thread), like the differ and GePositions.
 */
final class CollectLedger
{
	/** One slot's uncollected contents. Plain fields so Gson can round-trip it. */
	static final class Entry
	{
		int itemId;
		int items; // uncollected item qty (bought stock / returned unsold stock)
		long coins; // uncollected coins (net sell proceeds / cancelled-buy refund)

		boolean isEmpty()
		{
			return items <= 0 && coins <= 0;
		}
	}

	/** The previous offer state per slot, for diffing. Session-local and never saved: a fresh
	 *  session must treat the login replay as a first sighting, and offline fills arrive
	 *  through applyFill instead. */
	private static final class Prev
	{
		final int itemId;
		final TradeRecord.Side side;
		final int totalQuantity;
		final long price;
		final int quantitySold;
		final long spent;
		final GePositions.Phase phase;

		Prev(OfferSnapshot s)
		{
			this.itemId = s.itemId;
			this.side = OfferStates.sideOf(s.state);
			this.totalQuantity = s.totalQuantity;
			this.price = s.price;
			this.quantitySold = s.quantitySold;
			this.spent = s.spent;
			this.phase = OfferStates.phaseOf(s.state);
		}
	}

	private final Map<Integer, Entry> entries = new HashMap<>();
	private final Map<Integer, Prev> prevs = new HashMap<>();

	/** Folds in one offer event. Returns true when the ledger changed, so the caller can save. */
	boolean onOffer(int slot, OfferSnapshot next, long ignoredTs)
	{
		if (next.state == GrandExchangeOfferState.EMPTY)
		{
			prevs.remove(slot);
			return entries.remove(slot) != null;
		}
		Prev prev = prevs.get(slot);
		prevs.put(slot, new Prev(next));
		if (prev == null)
		{
			// First sighting this session (login replay or plugin enable): baseline, count
			// nothing. A restored entry for this slot stays, unless the replayed offer is not
			// the one the entry belongs to. Then we missed an EMPTY, so drop the entry.
			Entry e = entries.get(slot);
			if (e != null && e.itemId != next.itemId)
			{
				entries.remove(slot);
				return true;
			}
			return false;
		}
		boolean sameOffer = OfferStates.sameOffer(prev.itemId, prev.side,
			prev.totalQuantity, prev.price, prev.quantitySold, prev.spent, next);
		if (!sameOffer)
		{
			// The slot was reused without a witnessed EMPTY, so whatever the old entry held
			// was collected while we blinked. Assume collected. The new offer starts clean.
			return entries.remove(slot) != null;
		}
		boolean changed = false;
		int qtyDelta = next.quantitySold - prev.quantitySold;
		if (qtyDelta > 0)
		{
			Entry e = entryFor(slot, next.itemId);
			if (prev.side == TradeRecord.Side.BUY)
			{
				e.items += qtyDelta;
			}
			else
			{
				// Net of tax. The offer's spent counter is gross for sells.
				e.coins += SessionStats.netFromSale(next.price, next.itemId) * qtyDelta;
			}
			changed = true;
		}
		if (prev.phase == GePositions.Phase.WORKING && OfferStates.phaseOf(next.state) == GePositions.Phase.CANCELLED)
		{
			// The machine moves the remainder into the collection box on cancel.
			int remainder = next.totalQuantity - next.quantitySold;
			if (remainder > 0)
			{
				Entry e = entryFor(slot, next.itemId);
				if (prev.side == TradeRecord.Side.BUY)
				{
					e.coins += next.price * (long) remainder; // unfilled escrow refund
				}
				else
				{
					e.items += remainder; // unsold stock returns
				}
				changed = true;
			}
		}
		return changed;
	}

	/**
	 * Counts an offline fill discovered at login. The caller gates this on an ACQUITTED
	 * custody verdict: nothing can touch the collection box while logged out, so acquittal
	 * proves the fill is still held by the GE.
	 */
	void applyFill(TradeRecord r)
	{
		Entry e = entryFor(r.slot, r.itemId);
		if (r.side == TradeRecord.Side.BUY)
		{
			e.items += r.quantity;
		}
		else
		{
			e.coins += SessionStats.netFromSale(r.price, r.itemId) * (long) r.quantity;
		}
	}

	/**
	 * Replaces the whole ledger with exactly what the open collection box shows, one
	 * {itemId, itemQty, coins} triple per slot-shaped widget, keyed by detection order.
	 * This heals drift in both directions. Detection-order keys are fine because every
	 * later consumer either matches by item, zeroes everything, or is this same poll on
	 * the next tick. Returns true when the ledger changed.
	 */
	boolean resyncAll(List<long[]> boxes)
	{
		Map<Integer, Entry> next = new HashMap<>();
		int key = 0;
		for (long[] b : boxes)
		{
			if (b[1] <= 0 && b[2] <= 0)
			{
				continue;
			}
			Entry e = new Entry();
			e.itemId = (int) b[0];
			e.items = (int) b[1];
			e.coins = b[2];
			next.put(key++, e);
		}
		if (sameEntries(entries, next))
		{
			return false;
		}
		entries.clear();
		entries.putAll(next);
		return true;
	}

	/**
	 * Witnesses one viewed slot's pending boxes from the GE offer-detail view. Before the
	 * witnessed state lands, any entry holding the same item under a different key is
	 * dropped, so a duplicate can never survive a glance. The rare twin-offer case deflates
	 * and heals at the next collection-box open. Returns true when the ledger changed.
	 */
	boolean resyncViewed(int slot, int itemId, int itemQty, long coins)
	{
		boolean changed = false;
		if (itemId > 0)
		{
			java.util.Iterator<Map.Entry<Integer, Entry>> it = entries.entrySet().iterator();
			while (it.hasNext())
			{
				Map.Entry<Integer, Entry> me = it.next();
				if (me.getKey() != slot && me.getValue().itemId == itemId)
				{
					it.remove();
					changed = true;
				}
			}
		}
		Entry cur = entries.get(slot);
		if (itemQty <= 0 && coins <= 0)
		{
			return (entries.remove(slot) != null) || changed;
		}
		if (cur != null && cur.itemId == itemId && cur.items == itemQty && cur.coins == coins)
		{
			return changed;
		}
		Entry e = new Entry();
		e.itemId = itemId;
		e.items = itemQty;
		e.coins = coins;
		entries.put(slot, e);
		return true;
	}

	private static boolean sameEntries(Map<Integer, Entry> a, Map<Integer, Entry> b)
	{
		if (a.size() != b.size())
		{
			return false;
		}
		for (Map.Entry<Integer, Entry> me : a.entrySet())
		{
			Entry o = b.get(me.getKey());
			if (o == null || o.itemId != me.getValue().itemId || o.items != me.getValue().items
				|| o.coins != me.getValue().coins)
			{
				return false;
			}
		}
		return true;
	}

	/** Handles a witnessed collect the inventory delta cannot follow, such as Collect-to-bank.
	 *  Err low and zero everything. The next widget glance restores what actually remains.
	 *  Returns true when the ledger changed. */
	boolean zeroAll()
	{
		boolean changed = !entries.isEmpty();
		entries.clear();
		return changed;
	}

	/**
	 * Consumes a witnessed collect-to-inventory by decrementing entries by what actually
	 * arrived, in slot order. This can only move the ledger down and never below zero, so a
	 * partial collect (a full inventory, or the max cash stack) leaves exactly the remainder
	 * the box still holds. Arrivals that match no entry are ignored, since they are not ours
	 * to explain. Returns true when the ledger changed.
	 */
	boolean applyCollectDelta(Map<Integer, Integer> arrivedItems, long arrivedCoins)
	{
		boolean changed = false;
		List<Integer> slots = new ArrayList<>(entries.keySet());
		java.util.Collections.sort(slots); // the client collects in slot order
		for (int slot : slots)
		{
			Entry e = entries.get(slot);
			Integer arrived = arrivedItems.get(e.itemId);
			if (arrived != null && arrived > 0 && e.items > 0)
			{
				int take = Math.min(e.items, arrived);
				e.items -= take;
				arrivedItems.put(e.itemId, arrived - take);
				changed = true;
			}
			if (arrivedCoins > 0 && e.coins > 0)
			{
				long take = Math.min(e.coins, arrivedCoins);
				e.coins -= take;
				arrivedCoins -= take;
				changed = true;
			}
			if (e.isEmpty())
			{
				entries.remove(slot);
			}
		}
		return changed;
	}

	/**
	 * The positive (id → quantity gained) differences between two inventory dumps, which is
	 * what a collect delivered. Coins ride under the coin item id like everywhere else.
	 * Pure; null containers read as empty.
	 */
	static Map<Integer, Integer> arrivals(int[][] before, int[][] after)
	{
		Map<Integer, Integer> delta = new HashMap<>();
		if (after != null)
		{
			for (int[] p : after)
			{
				delta.merge(p[0], p[1], Integer::sum);
			}
		}
		if (before != null)
		{
			for (int[] p : before)
			{
				delta.merge(p[0], -p[1], Integer::sum);
			}
		}
		delta.values().removeIf(v -> v <= 0);
		return delta;
	}

	/** Wipes everything, baselines included. Used when a new login starts a fresh custody window. */
	void reset()
	{
		entries.clear();
		prevs.clear();
	}

	/** Uncollected coins across all slots: net-of-tax proceeds plus refunds. */
	long coins()
	{
		long sum = 0;
		for (Entry e : entries.values())
		{
			sum += e.coins;
		}
		return sum;
	}

	/** Uncollected item stacks as (itemId, quantity) pairs, for the asset total. */
	int[][] itemPairs()
	{
		List<int[]> pairs = new ArrayList<>();
		for (Entry e : entries.values())
		{
			if (e.items > 0)
			{
				pairs.add(new int[]{e.itemId, e.items});
			}
		}
		return pairs.toArray(new int[0][]);
	}

	/** The saveable uncollected state. Entries only; baselines are session-local by design. */
	Map<Integer, Entry> snapshotEntries()
	{
		return new HashMap<>(entries);
	}

	/**
	 * Restores saved entries. The caller gates this on an ACQUITTED custody verdict. This is
	 * a merge: a slot the live session already touched keeps its live entry, and the seed
	 * only fills untouched slots, so restoring can never overwrite something witnessed this
	 * session. A null or empty seed restores nothing.
	 */
	void seedEntries(Map<Integer, Entry> seed)
	{
		if (seed == null)
		{
			return;
		}
		for (Map.Entry<Integer, Entry> me : seed.entrySet())
		{
			if (me.getValue() != null && !me.getValue().isEmpty() && !entries.containsKey(me.getKey()))
			{
				entries.put(me.getKey(), me.getValue());
			}
		}
	}

	private Entry entryFor(int slot, int itemId)
	{
		Entry e = entries.get(slot);
		if (e == null || e.itemId != itemId)
		{
			e = new Entry();
			e.itemId = itemId;
			entries.put(slot, e);
		}
		return e;
	}

}
