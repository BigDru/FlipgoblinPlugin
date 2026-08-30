package com.flipgoblin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A snapshot of what the player owns at one moment: a list of (itemId, quantity) entries
 * merged together from container contents.
 */
public final class AssetSnapshot
{
	public static final class Entry
	{
		public final int itemId;
		public final long qty;

		public Entry(int itemId, long qty)
		{
			this.itemId = itemId;
			this.qty = qty;
		}
	}

	/** Epoch ms the snapshot was taken. */
	public final long timestamp;
	public final List<Entry> entries;

	public AssetSnapshot(long timestamp, List<Entry> entries)
	{
		this.timestamp = timestamp;
		this.entries = entries;
	}

	/**
	 * Builds a snapshot from raw (id, quantity) container contents. Entries with an id or
	 * quantity of zero or less are skipped. When the same item appears more than once, its
	 * quantities are added together. Items keep the order they first appeared in.
	 */
	public static AssetSnapshot of(long timestamp, int[][]... containers)
	{
		return of(timestamp, 0, containers);
	}

	/**
	 * Same as {@link #of(long, int[][]...)}, but also counts coins that no container shows,
	 * such as coins held by the GE for an open buy offer. Takes a long because eight slots
	 * of escrow can add up to more than the max cash stack.
	 */
	public static AssetSnapshot of(long timestamp, long extraCoins, int[][]... containers)
	{
		Map<Integer, Long> merged = new LinkedHashMap<>();
		if (extraCoins > 0)
		{
			merged.put(ItemIds.COINS, extraCoins);
		}
		for (int[][] container : containers)
		{
			if (container == null)
			{
				continue;
			}
			for (int[] pair : container)
			{
				int id = pair[0];
				long qty = pair[1];
				if (id <= 0 || qty <= 0)
				{
					continue;
				}
				merged.merge(id, qty, Long::sum);
			}
		}
		List<Entry> entries = new ArrayList<>(merged.size());
		for (Map.Entry<Integer, Long> e : merged.entrySet())
		{
			entries.add(new Entry(e.getKey(), e.getValue()));
		}
		return new AssetSnapshot(timestamp, entries);
	}

	/** Converts the entries back to raw (id, quantity) pairs so a saved snapshot can be merged again. */
	public int[][] pairs()
	{
		int[][] out = new int[entries.size()][2];
		for (int i = 0; i < entries.size(); i++)
		{
			Entry e = entries.get(i);
			out[i][0] = e.itemId;
			out[i][1] = (int) Math.min(Integer.MAX_VALUE, e.qty);
		}
		return out;
	}

	public long totalStacks()
	{
		return entries.size();
	}

	/** The number of coins in the snapshot, or 0 if none are visible. */
	public long coins()
	{
		for (Entry e : entries)
		{
			if (e.itemId == ItemIds.COINS)
			{
				return e.qty;
			}
		}
		return 0;
	}

	/**
	 * Estimates the snapshot's total value in gp. Coins count at face value. Every other
	 * stack is valued at its live bid price minus GE tax. Stacks with no known bid price
	 * are left out of the total and counted instead. Returns {total gp, unpriced count}.
	 */
	public long[] estimateValue(Map<Integer, Long> bids)
	{
		long total = 0;
		long unpriced = 0;
		for (Entry e : entries)
		{
			if (e.itemId == ItemIds.COINS)
			{
				total += e.qty;
				continue;
			}
			Long bid = bids == null ? null : bids.get(e.itemId);
			if (bid == null)
			{
				unpriced++;
				continue;
			}
			total += SessionStats.netFromSale(bid, e.itemId) * e.qty;
		}
		return new long[]{total, unpriced};
	}
}
