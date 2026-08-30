package com.flipgoblin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Session P/L. FIFO-matches the session's {@link TradeRecord}s into realized profit and
 * open positions.
 *
 * The matcher and the tax math mirror the website's, so the in-client numbers agree with
 * the dashboard. Pure and single-threaded (client thread). Rebuilt from scratch on every
 * update, since a session holds tens of records at most.
 */
public final class SessionStats
{
	public static final double GE_TAX_RATE = 0.02;
	public static final long GE_TAX_CAP = 5_000_000L;

	/**
	 * Items exempt from GE tax, per the OSRS Wiki's "Grand Exchange" page. Mirrors the
	 * website's list; keep the two identical.
	 */
	private static final java.util.Set<Integer> GE_TAX_EXEMPT_IDS = new java.util.HashSet<>(java.util.Arrays.asList(
		13190, // Old school bond
		3008, 3010, 3012, 3014, // Energy potion (4/3/2/1)
		882, 806, 884, 807, 558, 886, 808, // bronze/iron/steel arrows+darts, mind rune
		365, 2309, 1891, 2140, 2142, 347, 379, 355, 2327, 351, 329, 315, 361, // low-level food
		8011, 8010, 28824, 8009, 3853, 28790, 8008, 2552, 8013, 8007, // teleport items
		1755, 5325, 1785, 2347, 1733, 233, 5341, 8794, 5329, 5343, 1735, 952, 5331 // tools
	));

	/** GE sell tax: 2% rounded down, capped at 5m, and zero for exempt items. */
	public static long geSellTax(long price, int itemId)
	{
		if (price <= 0 || GE_TAX_EXEMPT_IDS.contains(itemId))
		{
			return 0;
		}
		return Math.min((long) Math.floor(price * GE_TAX_RATE), GE_TAX_CAP);
	}

	/** Sale proceeds net of GE tax. */
	public static long netFromSale(long price, int itemId)
	{
		return price - geSellTax(price, itemId);
	}

	/**
	 * The smallest ask that nets at least {@code costBasis} after tax. Exempt items break
	 * even at cost. The small ±2 scan absorbs the tax rounding, where a plain divide can
	 * land 1 gp short.
	 */
	public static long breakevenAsk(long costBasis, int itemId)
	{
		if (costBasis <= 0)
		{
			return 0;
		}
		if (GE_TAX_EXEMPT_IDS.contains(itemId))
		{
			return costBasis;
		}
		long[] candidates = {(long) Math.ceil(costBasis / (1 - GE_TAX_RATE)), costBasis + GE_TAX_CAP};
		long best = Long.MAX_VALUE;
		for (long c : candidates)
		{
			for (long s = c - 2; s <= c + 2; s++)
			{
				if (s >= costBasis && s < best && netFromSale(s, itemId) >= costBasis)
				{
					best = s;
				}
			}
		}
		return best;
	}

	/** One item's session totals: units bought, units sold, and realized P/L. */
	public static final class ItemTotals
	{
		public final long bought;
		public final long sold;
		public final long realized;

		public ItemTotals(long bought, long sold, long realized)
		{
			this.bought = bought;
			this.sold = sold;
			this.realized = realized;
		}
	}

	/** Per-item session position (the panel's row model). */
	public static final class ItemPosition
	{
		public final int itemId;
		public int matchedQty;
		public long realized;
		public int openQty;
		public long openCost;
		public int unmatchedSellQty;

		ItemPosition(int itemId)
		{
			this.itemId = itemId;
		}
	}

	private static final class BuyLot
	{
		int qty;
		final long price;

		BuyLot(int qty, long price)
		{
			this.qty = qty;
			this.price = price;
		}
	}

	/** FIFO-matches records, which arrive in chronological order. Pure. */
	public static Result match(List<TradeRecord> records)
	{
		Map<Integer, Deque<BuyLot>> lots = new HashMap<>();
		Map<Integer, ItemPosition> positions = new HashMap<>();

		for (TradeRecord r : records)
		{
			ItemPosition p = positions.computeIfAbsent(r.itemId, ItemPosition::new);
			if (r.side == TradeRecord.Side.BUY)
			{
				lots.computeIfAbsent(r.itemId, k -> new ArrayDeque<>()).addLast(new BuyLot(r.quantity, r.price));
				p.openQty += r.quantity;
				p.openCost += (long) r.quantity * r.price;
				continue;
			}
			int remaining = r.quantity;
			long netPerUnit = netFromSale(r.price, r.itemId);
			Deque<BuyLot> q = lots.getOrDefault(r.itemId, new ArrayDeque<>());
			while (remaining > 0 && !q.isEmpty())
			{
				BuyLot lot = q.peekFirst();
				int take = Math.min(remaining, lot.qty);
				p.realized += (long) take * (netPerUnit - lot.price);
				p.matchedQty += take;
				p.openQty -= take;
				p.openCost -= (long) take * lot.price;
				lot.qty -= take;
				remaining -= take;
				if (lot.qty == 0)
				{
					q.pollFirst();
				}
			}
			if (remaining > 0)
			{
				p.unmatchedSellQty += remaining;
			}
		}

		long total = 0;
		List<ItemPosition> out = new ArrayList<>(positions.values());
		out.sort((a, b) -> Integer.compare(a.itemId, b.itemId));
		for (ItemPosition p : out)
		{
			total += p.realized;
		}
		return new Result(total, out);
	}

	public static final class Result
	{
		public final long totalRealized;
		public final List<ItemPosition> items;

		Result(long totalRealized, List<ItemPosition> items)
		{
			this.totalRealized = totalRealized;
			this.items = items;
		}
	}

	private SessionStats()
	{
	}
}
