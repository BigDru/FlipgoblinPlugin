package com.flipgoblin;

import java.util.ArrayList;
import java.util.List;

/**
 * Tracks GE buy-limit usage seen this session. The game's rule: an item's 4-hour limit
 * window opens at the first buy after the previous window expired, every buy inside the
 * window counts toward the limit, and the count fully resets when the window ends.
 *
 * Only buys the plugin saw are counted. Buys made before the plugin ran are invisible, so
 * the count is usually a lower bound and the UI shows "≥". Fills recovered at login have
 * uncertain times, so they only count what can be proven; see {@link #usage} for how.
 */
final class GeLimits
{
	static final long WINDOW_MS = 4 * 60 * 60 * 1000L;

	private GeLimits()
	{
	}

	/** One item's provable buy-limit usage. */
	static final class Usage
	{
		/** Units provably bought in a window still active now. */
		final long used;
		/** The latest possible reset time (epoch ms). With hedged set, the real reset may come sooner. */
		final long resetAtMs;
		/** True when the whole window was watched live, so the count is exact. */
		final boolean exact;
		/** True when recovered fills contributed, so the UI hedges the reset time. */
		final boolean hedged;

		Usage(long used, long resetAtMs, boolean exact, boolean hedged)
		{
			this.used = used;
			this.resetAtMs = resetAtMs;
			this.exact = exact;
			this.hedged = hedged;
		}
	}

	/**
	 * Returns the usage of the window active at {@code now}, or null when no usage can be
	 * proven. Records are read in list order, which is event order. {@code buyLimit} is
	 * the item's 4-hour limit, or 0 when unknown.
	 *
	 * The count is exact rather than a lower bound when the whole window was watched: while
	 * this client is logged in the account cannot trade anywhere else, so if the window
	 * opened inside our unbroken observation span and every counted fill was seen live,
	 * nothing was missed. The UI drops the ≥/≤ markers in that case.
	 *
	 * Recovered fills need more care, because we only know each one happened somewhere
	 * between its offlineSince bound and its detection time. Counting them at detection
	 * time would pin the window at login and claim "limit spent" long after the real limit
	 * may have reset. So the count is what survives even when every recovered fill is
	 * placed as early as its bounds and the buy limit allow, which is the timeline that
	 * frees the limit soonest. If even that timeline leaves an active window, the usage is
	 * provable. If not, nothing can be claimed and the result is null. For example, buying
	 * two limits overnight proves the limit is still spent while the offline gap is under
	 * eight hours (the second window cannot have expired), and proves nothing after that.
	 * When recovered fills contributed, the hedged flag is set and the reset time is only
	 * an upper bound (the real reset may come sooner).
	 */
	static Usage usage(List<TradeRecord> records, int itemId, long now, long observedSince, long buyLimit)
	{
		long anchor = 0;
		long used = 0;
		boolean anyRecovered = false;
		List<TradeRecord> buys = new ArrayList<>();
		for (TradeRecord r : records)
		{
			// Only slot-observed fills count. History imports (slot -1) can be arbitrarily
			// old with estimated times, and counting those at detection time would pollute
			// the window with days-old buys.
			if (r.itemId != itemId || r.side != TradeRecord.Side.BUY || r.slot < 0)
			{
				continue;
			}
			buys.add(r);
			if (anchor == 0 || r.timestamp >= anchor + WINDOW_MS)
			{
				anchor = r.timestamp; // first buy opens the window; a post-expiry buy opens the next
				used = 0;
				anyRecovered = false;
			}
			used += r.quantity;
			anyRecovered |= r.recovered;
		}
		if (anchor == 0 || now >= anchor + WINDOW_MS)
		{
			return null;
		}
		if (!anyRecovered)
		{
			boolean exact = observedSince > 0 && observedSince <= anchor;
			return new Usage(used, anchor + WINDOW_MS, exact, false);
		}
		long provable = provableUsed(buys, now, buyLimit);
		if (provable <= 0)
		{
			return null;
		}
		return new Usage(provable, anchor + WINDOW_MS, false, true);
	}

	/**
	 * The buy count that provably still lies in an active window when every recovered fill
	 * is placed as early as its time bounds and the buy limit allow. Live fills sit at
	 * their real times. A recovered fill starts at its offlineSince bound and waits for a
	 * window reset when the current one is full. A recovered fill with no offlineSince
	 * bound could be arbitrarily old and proves nothing, so it is skipped. If this early
	 * placement would force a live fill into an already-full window, the model does not
	 * fit the data, and only the live fills are counted.
	 */
	private static long provableUsed(List<TradeRecord> buys, long now, long buyLimit)
	{
		List<TradeRecord> placeable = new ArrayList<>();
		for (TradeRecord r : buys)
		{
			if (!r.recovered || r.offlineSince > 0)
			{
				placeable.add(r);
			}
		}
		// Sort by earliest possible time; the sort is stable, so same-time fills keep event order.
		placeable.sort((a, b) -> Long.compare(
			a.recovered ? a.offlineSince : a.timestamp,
			b.recovered ? b.offlineSince : b.timestamp));
		long anchor = -1;
		long used = 0;
		long cursor = 0;
		for (TradeRecord r : placeable)
		{
			long q = r.quantity;
			long t = Math.max(r.recovered ? r.offlineSince : r.timestamp, cursor);
			while (q > 0)
			{
				if (anchor < 0 || t >= anchor + WINDOW_MS)
				{
					anchor = t;
					used = 0;
				}
				long room = buyLimit > 0 ? buyLimit - used : q;
				if (room <= 0)
				{
					if (!r.recovered)
					{
						// A live fill cannot wait for a reset; its time is real. The
						// early-placement model is infeasible, so count only live fills.
						return liveOnlyUsed(buys, now);
					}
					t = anchor + WINDOW_MS;
					continue;
				}
				long take = Math.min(q, room);
				used += take;
				q -= take;
				cursor = t;
			}
		}
		return anchor >= 0 && now < anchor + WINDOW_MS ? used : 0;
	}

	/** The active-window count over live fills only, ignoring every recovered fill. */
	private static long liveOnlyUsed(List<TradeRecord> buys, long now)
	{
		long anchor = 0;
		long used = 0;
		for (TradeRecord r : buys)
		{
			if (r.recovered)
			{
				continue;
			}
			if (anchor == 0 || r.timestamp >= anchor + WINDOW_MS)
			{
				anchor = r.timestamp;
				used = 0;
			}
			used += r.quantity;
		}
		return anchor > 0 && now < anchor + WINDOW_MS ? used : 0;
	}

	/** Formats a reset instant as "HH:mm" local time. */
	static String resetTime(long epochMs)
	{
		return java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(
			java.time.Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneId.systemDefault()));
	}
}
