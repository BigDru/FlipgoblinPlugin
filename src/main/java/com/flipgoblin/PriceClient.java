package com.flipgoblin;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Read-only market data for the GE info panel, fetched from the keyed /plugin routes.
 * Works stale-while-revalidate: {@code get()} returns the cached row instantly (or null)
 * and kicks off one background refresh when stale, so it is safe to call from overlay
 * render every frame. Only the item id and the token leave the client, never any account
 * data (COMPLIANCE.md documents this).
 */
@Slf4j
public final class PriceClient
{
	/** One item's flip metrics as served by the API. A null means that side never traded or cannot be priced. */
	public static final class ItemPrices
	{
		public final int itemId;
		public final String name;
		public final Long ask;
		public final Long bid;
		public final Long margin;
		public final Double roi;
		public final Long tax;
		public final Long buyLimit;
		public final Long askVolume;
		public final Long bidVolume;
		public final long fetchedAt;

		ItemPrices(int itemId, String name, Long ask, Long bid, Long margin, Double roi, Long tax,
			Long buyLimit, Long askVolume, Long bidVolume, long fetchedAt)
		{
			this.itemId = itemId;
			this.name = name;
			this.ask = ask;
			this.bid = bid;
			this.margin = margin;
			this.roi = roi;
			this.tax = tax;
			this.buyLimit = buyLimit;
			this.askVolume = askVolume;
			this.bidVolume = bidVolume;
			this.fetchedAt = fetchedAt;
		}
	}

	/**
	 * One chart series for the offer panel. The arrays are bucket-aligned, and time holds
	 * each bucket's epoch seconds for the hover readout. A side that did not trade in a
	 * bucket is NaN, and the renderer skips those segments, same as the site's lines.
	 *
	 * A series is either TICKS (per-minute trade points, carrying real-fill flags) or
	 * CANDLES (bucketed closes, carrying per-bucket volumes). Build one with
	 * {@link #ticks} or {@link #candles}.
	 */
	public static final class Series
	{
		public enum Kind
		{
			TICKS, CANDLES
		}

		public final Kind kind;
		public final long[] time;
		public final double[] ask;
		public final double[] bid;
		/** Marks slots where a real fill landed. Tick series only; null for candles. */
		public final boolean[] askReal;
		public final boolean[] bidReal;
		/** Traded volume per slot. Candle series only; null for ticks. NaN means no bucket
		 * data. Volumes are per-bucket sums and are never carried forward like prices. */
		public final double[] askVol;
		public final double[] bidVol;
		public final long fetchedAt;

		static Series ticks(long[] time, double[] ask, double[] bid, boolean[] askReal,
			boolean[] bidReal, long fetchedAt)
		{
			return new Series(Kind.TICKS, time, ask, bid, askReal, bidReal, null, null, fetchedAt);
		}

		static Series candles(long[] time, double[] ask, double[] bid, double[] askVol,
			double[] bidVol, long fetchedAt)
		{
			return new Series(Kind.CANDLES, time, ask, bid, null, null, askVol, bidVol, fetchedAt);
		}

		Series(Kind kind, long[] time, double[] ask, double[] bid, boolean[] askReal,
			boolean[] bidReal, double[] askVol, double[] bidVol, long fetchedAt)
		{
			this.kind = kind;
			this.time = time;
			this.ask = ask;
			this.bid = bid;
			this.askReal = askReal;
			this.bidReal = bidReal;
			this.askVol = askVol;
			this.bidVol = bidVol;
			this.fetchedAt = fetchedAt;
		}
	}

	static final long TTL_MS = 60_000; // matches the endpoint's edge-cache window
	static final long SERIES_TTL_MS = 300_000; // coarse buckets — refetching faster is waste
	/** Tick-graph refresh span in minutes. The overlap covers the Wiki's publication lag. */
	static final int TICK_TAIL_SPAN = 3;
	/** Candle tail refreshes resample onto this many grid slots, enough overlap for the merge. */
	static final int CANDLE_TAIL_SLOTS = 3;
	/** Retry delay while the current bucket is unpainted: near-immediate, but no request storm. */
	static final long BOUNDARY_RETRY_MS = 5_000;

	/** One bucket's span per interval key. Drives the boundary-aware refresh. */
	private static long intervalMs(String interval)
	{
		switch (interval)
		{
			case "5m":
				return 300_000L;
			case "15m":
				return 900_000L;
			case "1h":
				return 3_600_000L;
			case "4h":
				return 14_400_000L;
			case "12h":
				return 43_200_000L;
			case "1d":
				return 86_400_000L;
			default:
				return 60_000L; // the "1m" tick grid
		}
	}

	private final OkHttpClient http;
	private final Gson gson = new Gson();
	private final Map<Integer, ItemPrices> cache = new ConcurrentHashMap<>();
	private final Set<Integer> inflight = ConcurrentHashMap.newKeySet();
	private final Map<String, Series> seriesCache = new ConcurrentHashMap<>();
	private final Set<String> seriesInflight = ConcurrentHashMap.newKeySet();
	/** When each series' newest volume-bearing bucket first reached this client. */
	private final Map<String, VolArrival> volArrivals = new ConcurrentHashMap<>();
	/** Next-fetch instants (epoch ms) learned from the API's cache headers. Keyed by
	 * "d:&lt;itemId&gt;" for the detail fetch, and by the series key otherwise. */
	private final Map<String, Long> nextFetchAt = new ConcurrentHashMap<>();

	/**
	 * How long until the API's edge cache serves fresh data, read from a response's headers
	 * (s-maxage minus age), plus a small margin and a floor. Returns -1 when the headers are
	 * absent, and the caller keeps its fixed TTL.
	 */
	static long cacheCycleDelayMs(String cacheControl, String age)
	{
		if (cacheControl == null || age == null)
		{
			return -1;
		}
		java.util.regex.Matcher m =
			java.util.regex.Pattern.compile("s-maxage=(\\d+)").matcher(cacheControl);
		if (!m.find())
		{
			return -1;
		}
		long ageSec;
		try
		{
			ageSec = Long.parseLong(age.trim());
		}
		catch (NumberFormatException e)
		{
			return -1;
		}
		if (ageSec < 0)
		{
			return -1;
		}
		return Math.max(3_000, (Long.parseLong(m.group(1)) - ageSec) * 1000 + 1_500);
	}

	/** When the next detail fetch is scheduled (epoch ms). Drives the panel's countdown. */
	public long nextDetailFetch(int itemId)
	{
		Long n = nextFetchAt.get("d:" + itemId);
		if (n != null)
		{
			return n;
		}
		ItemPrices p = cache.get(itemId);
		return p == null ? 0 : p.fetchedAt + TTL_MS;
	}

	public PriceClient(OkHttpClient http)
	{
		this.http = http;
	}

	/**
	 * The freshest known prices for the item, or null until the first fetch lands. Starts
	 * one background refresh when missing or stale. Never blocks, so it is safe from render.
	 */
	public ItemPrices get(String apiBase, String token, int itemId)
	{
		long now = System.currentTimeMillis();
		ItemPrices cached = cache.get(itemId);
		Long learned = nextFetchAt.get("d:" + itemId);
		long nextAt = cached == null ? now
			: learned != null ? learned : cached.fetchedAt + TTL_MS;
		if ((cached == null || now >= nextAt) && inflight.add(itemId))
		{
			fetch(apiBase, token, itemId);
		}
		return cached;
	}

	private void fetch(String apiBase, String token, int itemId)
	{
		// Keyed access: market data flows through the Bearer-token /plugin routes.
		Request request = new Request.Builder()
			.url(apiBase.replaceAll("/+$", "") + "/plugin/items/" + itemId)
			.header("Authorization", "Bearer " + token)
			.build();
		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				inflight.remove(itemId);
				log.debug("price fetch failed for {}: {}", itemId, e.toString());
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (Response res = response)
				{
					if (res.isSuccessful() && res.body() != null)
					{
						long fetchedAt = System.currentTimeMillis();
						ItemPrices parsed = parse(itemId, res.body().string(), fetchedAt, gson);
						if (parsed != null)
						{
							cache.put(itemId, parsed);
							long d = cacheCycleDelayMs(res.header("cache-control"), res.header("age"));
							nextFetchAt.put("d:" + itemId, fetchedAt + (d > 0 ? d : TTL_MS));
						}
					}
				}
				catch (RuntimeException e)
				{
					log.debug("price parse failed for {}: {}", itemId, e.toString());
				}
				finally
				{
					inflight.remove(itemId);
				}
			}
		});
	}

	/**
	 * The freshest known series for (item, interval), or null until the first fetch lands.
	 * Fine intervals (1m through 1h) refresh on the endpoint's cache cadence, since their
	 * live bucket moves. Coarser ones wait SERIES_TTL_MS. Safe from render.
	 */
	public Series getSeries(String apiBase, String token, int itemId, String interval, int limit)
	{
		long now = System.currentTimeMillis();
		String key = itemId + ":" + interval;
		boolean coarse = "4h".equals(interval) || "12h".equals(interval) || "1d".equals(interval);
		Series cached = seriesCache.get(key);
		// The next fetch fires when the API's cache entry turns over, learned from each
		// response, not on a blind TTL. Coarse intervals keep their slow cadence, and
		// missing headers fall back to the fixed TTLs.
		Long learned = nextFetchAt.get(key);
		long nextAt = cached == null ? now
			: learned != null ? learned : cached.fetchedAt + (coarse ? SERIES_TTL_MS : TTL_MS);
		// Once the wall clock enters a bucket the cached grid does not show yet, refetch
		// near-immediately. The grids are computed client-side, so that fetch slides the
		// window at the boundary even while the server still serves the older body, and the
		// new bucket's values follow as the data lands.
		if (cached != null && cached.time.length > 0)
		{
			long bucketStartSec = now / intervalMs(interval) * (intervalMs(interval) / 1000);
			if (cached.time[cached.time.length - 1] < bucketStartSec)
			{
				nextAt = Math.min(nextAt, cached.fetchedAt + BOUNDARY_RETRY_MS);
			}
		}
		if ((cached == null || now >= nextAt) && seriesInflight.add(key))
		{
			// "1m" is not a candle interval; it reads raw per-side trade points from /ticks.
			// The first load fetches the full window. Every refresh after that fetches only
			// a small tail and merges it client-side, sliding the window and dropping the
			// oldest entries. All parameters are fixed, so every plugin user shares one
			// server cache entry per item.
			boolean tail = cached != null;
			String base = apiBase.replaceAll("/+$", "");
			String url = "1m".equals(interval)
				? base + "/plugin/items/" + itemId + "/ticks?limit=" + (tail ? TICK_TAIL_SPAN + 3 : limit + 15)
					+ "&span=" + (tail ? TICK_TAIL_SPAN : limit + 1)
				: base + "/plugin/items/" + itemId + "/candles?interval=" + interval
					+ "&limit=" + (tail ? 2 : limit);
			Request request = new Request.Builder().url(url)
				.header("Authorization", "Bearer " + token)
				.build();
			http.newCall(request).enqueue(new Callback()
			{
				@Override
				public void onFailure(Call call, IOException e)
				{
					seriesInflight.remove(key);
					log.debug("series fetch failed for {}: {}", key, e.toString());
				}

				@Override
				public void onResponse(Call call, Response response) throws IOException
				{
					try (Response res = response)
					{
						if (res.isSuccessful() && res.body() != null)
						{
							String body = res.body().string();
							long fetchedAt = System.currentTimeMillis();
							boolean coarse = "4h".equals(interval) || "12h".equals(interval)
								|| "1d".equals(interval);
							long cycle = coarse ? SERIES_TTL_MS
								: cacheCycleDelayMs(res.header("cache-control"), res.header("age"));
							Series parsed = "1m".equals(interval)
								? parseTicks(body, tail ? TICK_TAIL_SPAN : limit, fetchedAt, gson)
								: resample(parseSeries(body, fetchedAt, gson),
									(int) (intervalMs(interval) / 1000),
									tail ? CANDLE_TAIL_SLOTS : limit, fetchedAt);
							if (parsed == null)
							{
								return;
							}
							if (!tail)
							{
								seriesCache.put(key, parsed);
								noteVolArrival(key, parsed);
								nextFetchAt.put(key, fetchedAt + (cycle > 0 ? cycle : TTL_MS));
								return;
							}
							// The tick grid is minutes+1 slots (through the live minute).
							int maxLen = "1m".equals(interval) ? limit + 1 : limit;
							Series merged = mergeTail(seriesCache.get(key), parsed, maxLen);
							if (merged != null)
							{
								seriesCache.put(key, merged);
								noteVolArrival(key, merged);
								nextFetchAt.put(key, fetchedAt + (cycle > 0 ? cycle : TTL_MS));
							}
							else
							{
								// The cache is too old to patch (the client was away). Drop
								// it so the next render does a full-depth fetch.
								seriesCache.remove(key);
								nextFetchAt.remove(key);
							}
						}
					}
					catch (RuntimeException e)
					{
						log.debug("series parse failed for {}: {}", key, e.toString());
					}
					finally
					{
						seriesInflight.remove(key);
					}
				}
			});
		}
		return cached;
	}

	/**
	 * Patches a small tail refresh into a full cached series: overwrite the overlapped
	 * buckets with the fresh truth, append new ones, and trim the head to {@code maxLen} so
	 * the window slides at constant size. Real-fill flags merge the same way when both
	 * sides carry them. Returns null when the tail does not overlap the cached range's last
	 * entries (the client was away longer than the tail covers), and the caller then drops
	 * the cache entry and refetches at full depth.
	 */
	static Series mergeTail(Series cached, Series tail, int maxLen)
	{
		if (cached == null || tail.time.length == 0)
		{
			return null;
		}
		int at = -1;
		int searchBack = tail.time.length + 2;
		for (int i = cached.time.length - 1; i >= 0 && i >= cached.time.length - searchBack; i--)
		{
			if (cached.time[i] == tail.time[0])
			{
				at = i;
				break;
			}
		}
		if (at < 0)
		{
			return null;
		}
		int total = at + tail.time.length;
		int drop = Math.max(0, total - maxLen);
		int len = total - drop;
		boolean flags = cached.askReal != null && tail.askReal != null;
		boolean vols = cached.askVol != null && tail.askVol != null;
		long[] time = new long[len];
		double[] ask = new double[len];
		double[] bid = new double[len];
		boolean[] askReal = flags ? new boolean[len] : null;
		boolean[] bidReal = flags ? new boolean[len] : null;
		double[] askVol = vols ? new double[len] : null;
		double[] bidVol = vols ? new double[len] : null;
		for (int i = drop; i < at; i++)
		{
			time[i - drop] = cached.time[i];
			ask[i - drop] = cached.ask[i];
			bid[i - drop] = cached.bid[i];
			if (flags)
			{
				askReal[i - drop] = cached.askReal[i];
				bidReal[i - drop] = cached.bidReal[i];
			}
			if (vols)
			{
				askVol[i - drop] = cached.askVol[i];
				bidVol[i - drop] = cached.bidVol[i];
			}
		}
		for (int i = 0; i < tail.time.length; i++)
		{
			// A NaN tail slot means the small response just did not include that bucket, so
			// keep the cached truth instead of erasing it. A naive merge erodes volumes
			// bucket by bucket. Flags do come from the tail, since it covers its whole span.
			int idx = at - drop + i;
			int ci = at + i;
			boolean hasCached = ci < cached.time.length;
			time[idx] = tail.time[i];
			ask[idx] = Double.isNaN(tail.ask[i]) && hasCached ? cached.ask[ci] : tail.ask[i];
			bid[idx] = Double.isNaN(tail.bid[i]) && hasCached ? cached.bid[ci] : tail.bid[i];
			if (flags)
			{
				askReal[idx] = tail.askReal[i];
				bidReal[idx] = tail.bidReal[i];
			}
			if (vols)
			{
				askVol[idx] = Double.isNaN(tail.askVol[i]) && hasCached ? cached.askVol[ci] : tail.askVol[i];
				bidVol[idx] = Double.isNaN(tail.bidVol[i]) && hasCached ? cached.bidVol[ci] : tail.bidVol[i];
			}
		}
		return new Series(tail.kind, time, ask, bid, askReal, bidReal, askVol, bidVol, tail.fetchedAt);
	}

	// Bulk latest bids for the panel's asset valuation, matching the dashboard's numbers:
	// one slim fetch of [id, ask, bid] triplets, refreshed at most every BULK_TTL_MS. A
	// bank's estimated value does not need the chart's faster cadence.
	static final long BULK_TTL_MS = 5 * 60_000;
	private volatile java.util.Map<Integer, Long> bulkBids;
	private volatile long bulkBidsAt;

	/** The latest id→bid map, or null before the first fetch. Safe from render. */
	java.util.Map<Integer, Long> bulkBids()
	{
		return bulkBids;
	}

	/** Executor only. Refetches past the TTL. Returns true when a fresh map was installed. */
	boolean refreshBulkBids(String apiBase, String token)
	{
		if (bulkBids != null && System.currentTimeMillis() - bulkBidsAt < BULK_TTL_MS)
		{
			return false;
		}
		Request request = new Request.Builder()
			.url(apiBase.replaceAll("/+$", "") + "/plugin/prices")
			.header("Authorization", "Bearer " + token)
			.build();
		try (Response res = http.newCall(request).execute())
		{
			if (!res.isSuccessful() || res.body() == null)
			{
				return false;
			}
			java.util.Map<Integer, Long> parsed = parseBulkBids(res.body().string(), gson);
			if (parsed == null)
			{
				return false;
			}
			bulkBids = parsed;
			bulkBidsAt = System.currentTimeMillis();
			return true;
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("bulk prices fetch failed: {}", e.toString());
			return false;
		}
	}

	/** Parses the /plugin/prices body into an id→bid map. Rows with no bid are dropped. Pure. */
	static java.util.Map<Integer, Long> parseBulkBids(String json, Gson gson)
	{
		try
		{
			JsonObject o = gson.fromJson(json, JsonObject.class);
			if (o == null || !o.has("prices") || !o.get("prices").isJsonArray())
			{
				return null;
			}
			java.util.Map<Integer, Long> out = new java.util.HashMap<>();
			for (JsonElement el : o.getAsJsonArray("prices"))
			{
				if (!el.isJsonArray())
				{
					continue;
				}
				com.google.gson.JsonArray t = el.getAsJsonArray();
				if (t.size() < 3 || t.get(0).isJsonNull() || t.get(2).isJsonNull())
				{
					continue;
				}
				out.put(t.get(0).getAsInt(), t.get(2).getAsLong());
			}
			return out;
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	/**
	 * Blocking one-shot ticks fetch for refining a recovered fill's time. Executor threads
	 * only, never the client thread. Returns null on any failure, and the refinement then
	 * just keeps the detection time.
	 */
	Series fetchTicksBlocking(String apiBase, String token, int itemId, int minutes)
	{
		Request request = new Request.Builder()
			.url(apiBase.replaceAll("/+$", "") + "/plugin/items/" + itemId
				+ "/ticks?limit=" + Math.min(10_080, minutes + 15) + "&span=" + Math.min(10_080, minutes))
			.header("Authorization", "Bearer " + token)
			.build();
		try (Response res = http.newCall(request).execute())
		{
			if (!res.isSuccessful() || res.body() == null)
			{
				return null;
			}
			return parseTicks(res.body().string(), Math.min(10_080, minutes), System.currentTimeMillis(), gson);
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("refine ticks fetch failed for {}: {}", itemId, e.toString());
			return null;
		}
	}

	/**
	 * The latest minute inside [fromMs, toMs] where the fill's side traded at exactly
	 * {@code price}, or 0 when none did. My buy fill is someone insta-selling (the bid
	 * series), and my sell fill is someone insta-buying (the ask series). Only real trade
	 * minutes count; carried plateaus never match.
	 */
	static long latestPriceMatchMs(Series s, TradeRecord.Side side, long price, long fromMs, long toMs)
	{
		double[] vals = side == TradeRecord.Side.BUY ? s.bid : s.ask;
		boolean[] real = side == TradeRecord.Side.BUY ? s.bidReal : s.askReal;
		if (real == null)
		{
			return 0;
		}
		long best = 0;
		for (int i = 0; i < s.time.length; i++)
		{
			long tMs = s.time[i] * 1000;
			if (real[i] && !Double.isNaN(vals[i]) && (long) vals[i] == price
				&& tMs >= fromMs && tMs <= toMs)
			{
				best = Math.max(best, tMs);
			}
		}
		return best;
	}

	/** When a series' newest published volume first reached this client. */
	public static final class VolArrival
	{
		/** The volume-bearing bucket (epoch seconds). */
		public final long bucketSec;
		/** When it was first observed here (epoch ms). */
		public final long observedMs;

		VolArrival(long bucketSec, long observedMs)
		{
			this.bucketSec = bucketSec;
			this.observedMs = observedMs;
		}
	}

	/**
	 * When the given interval's newest published volume first reached this client, or
	 * null before any volume data. Safe from render.
	 */
	public VolArrival volArrival(int itemId, String interval)
	{
		return volArrivals.get(itemId + ":" + interval);
	}

	/** Records the moment a newer volume-bearing bucket first shows up in the cached series. */
	private void noteVolArrival(String key, Series s)
	{
		if (s.askVol == null)
		{
			return;
		}
		long newest = 0;
		for (int i = s.time.length - 1; i >= 0; i--)
		{
			if (!Double.isNaN(s.askVol[i]) || !Double.isNaN(s.bidVol[i]))
			{
				newest = s.time[i];
				break;
			}
		}
		if (newest == 0)
		{
			return;
		}
		final long bucket = newest;
		volArrivals.compute(key, (k, prev) ->
			prev != null && prev.bucketSec >= bucket ? prev : new VolArrival(bucket, System.currentTimeMillis()));
	}

	/**
	 * Snaps a candle series onto a uniform bucket grid ending at the current bucket. The
	 * endpoint only returns buckets that exist, so a rarely traded item would otherwise
	 * render by event index, the window could not slide when nothing traded, and hover
	 * times would go stale. Rows land in their bucket's slot. Quiet slots carry the
	 * previous values, matching the site's plateau lines, and rows older than the window
	 * seed that carry. Slots before the item's first-ever data stay NaN.
	 */
	static Series resample(Series s, int intervalSec, int points, long nowMs)
	{
		if (s == null)
		{
			return null;
		}
		long start = (nowMs / 1000 / intervalSec - (points - 1)) * intervalSec;
		boolean vols = s.askVol != null;
		long[] time = new long[points];
		double[] ask = new double[points];
		double[] bid = new double[points];
		double[] askVol = vols ? new double[points] : null;
		double[] bidVol = vols ? new double[points] : null;
		for (int i = 0; i < points; i++)
		{
			time[i] = start + (long) i * intervalSec;
			ask[i] = Double.NaN;
			bid[i] = Double.NaN;
			if (vols)
			{
				askVol[i] = Double.NaN;
				bidVol[i] = Double.NaN;
			}
		}
		double curAsk = Double.NaN;
		double curBid = Double.NaN;
		int filled = 0; // slots [0, filled) already hold the carried plateau
		for (int i = 0; i < s.time.length; i++)
		{
			int slot = (int) Math.min(points - 1L, Math.floorDiv(s.time[i] - start, intervalSec));
			while (filled < slot)
			{
				ask[filled] = curAsk;
				bid[filled] = curBid;
				filled++;
			}
			if (!Double.isNaN(s.ask[i]))
			{
				curAsk = s.ask[i];
			}
			if (!Double.isNaN(s.bid[i]))
			{
				curBid = s.bid[i];
			}
			if (slot >= 0)
			{
				ask[slot] = curAsk;
				bid[slot] = curBid;
				if (vols)
				{
					// Volumes are per-bucket sums: placed, never carried. A quiet bucket is zero.
					askVol[slot] = s.askVol[i];
					bidVol[slot] = s.bidVol[i];
				}
				filled = slot + 1;
			}
		}
		while (filled < points)
		{
			ask[filled] = curAsk;
			bid[filled] = curBid;
			filled++;
		}
		return Series.candles(time, ask, bid, askVol, bidVol, s.fetchedAt);
	}

	/**
	 * Parses a ticks response into the last {@code minutes} as a per-minute grid. The wire
	 * rows are one-sided trade points, so each side carries forward between trades: a
	 * plateau is the price between trades, exactly the site's 1m step lines, and the final
	 * carry extends through the current minute. Slots before the item's first-ever known
	 * price stay NaN. Pure.
	 */
	static Series parseTicks(String body, int minutes, long now, Gson gson)
	{
		JsonObject root;
		try
		{
			root = gson.fromJson(body, JsonObject.class);
		}
		catch (RuntimeException e)
		{
			return null;
		}
		if (root == null || !root.has("ticks") || !root.get("ticks").isJsonArray())
		{
			return null;
		}
		com.google.gson.JsonArray ticks = root.getAsJsonArray("ticks");
		long startMin = now / 60_000L - minutes;
		int slots = minutes + 1; // ..through the current (live) minute
		long[] time = new long[slots];
		double[] ask = new double[slots];
		double[] bid = new double[slots];
		boolean[] askReal = new boolean[slots];
		boolean[] bidReal = new boolean[slots];
		for (int i = 0; i < slots; i++)
		{
			time[i] = (startMin + i) * 60;
			ask[i] = Double.NaN;
			bid[i] = Double.NaN;
		}
		double curAsk = Double.NaN;
		double curBid = Double.NaN;
		int filled = 0; // slots [0, filled) already hold the carried plateau
		for (int i = 0; i < ticks.size(); i++)
		{
			JsonObject t = ticks.get(i).getAsJsonObject();
			JsonElement te = t.get("time");
			long ts = te == null || te.isJsonNull() ? 0 : te.getAsLong();
			int slot = (int) Math.min(slots - 1L, ts / 60 - startMin);
			while (filled < slot) // plateau up to this trade's minute
			{
				ask[filled] = curAsk;
				bid[filled] = curBid;
				filled++;
			}
			double a = firstFinite(t, "ask");
			double b = firstFinite(t, "bid");
			if (!Double.isNaN(a))
			{
				curAsk = a;
			}
			if (!Double.isNaN(b))
			{
				curBid = b;
			}
			// A derived pre-tracking fill paints like any plateau but never gets a trade-dot
			// flag. The site follows the same rule: synthetic averages never get dots.
			boolean derived = t.has("src") && !t.get("src").isJsonNull();
			if (slot >= 0)
			{
				ask[slot] = curAsk;
				bid[slot] = curBid;
				askReal[slot] |= !derived && !Double.isNaN(a);
				bidReal[slot] |= !derived && !Double.isNaN(b);
				filled = slot + 1;
			}
		}
		while (filled < slots) // plateau through "now"
		{
			ask[filled] = curAsk;
			bid[filled] = curBid;
			filled++;
		}
		return Series.ticks(time, ask, bid, askReal, bidReal, now);
	}

	/**
	 * Parses a candles response. Per bucket, the side's close falls back to the window
	 * average (synthetic historical buckets carry only averages), else NaN. Pure.
	 */
	static Series parseSeries(String body, long now, Gson gson)
	{
		JsonObject root;
		try
		{
			root = gson.fromJson(body, JsonObject.class);
		}
		catch (RuntimeException e)
		{
			return null;
		}
		if (root == null || !root.has("candles") || !root.get("candles").isJsonArray())
		{
			return null;
		}
		com.google.gson.JsonArray candles = root.getAsJsonArray("candles");
		long[] time = new long[candles.size()];
		double[] ask = new double[candles.size()];
		double[] bid = new double[candles.size()];
		double[] askVol = new double[candles.size()];
		double[] bidVol = new double[candles.size()];
		for (int i = 0; i < candles.size(); i++)
		{
			JsonObject c = candles.get(i).getAsJsonObject();
			JsonElement t = c.get("time");
			time[i] = t == null || t.isJsonNull() ? 0 : t.getAsLong();
			ask[i] = firstFinite(c, "askClose", "avgAsk");
			bid[i] = firstFinite(c, "bidClose", "avgBid");
			askVol[i] = firstFinite(c, "askVolume");
			bidVol[i] = firstFinite(c, "bidVolume");
		}
		return Series.candles(time, ask, bid, askVol, bidVol, now);
	}

	private static double firstFinite(JsonObject o, String... keys)
	{
		for (String key : keys)
		{
			JsonElement e = o.get(key);
			if (e != null && !e.isJsonNull())
			{
				return e.getAsDouble();
			}
		}
		return Double.NaN;
	}

	/** Parses an item detail body. Returns null when the shape does not match. Pure. */
	static ItemPrices parse(int itemId, String body, long now, Gson gson)
	{
		JsonObject root;
		try
		{
			root = gson.fromJson(body, JsonObject.class);
		}
		catch (RuntimeException e)
		{
			return null; // not even JSON-object-shaped — same contract as a missing "item"
		}
		if (root == null || !root.has("item") || !root.get("item").isJsonObject())
		{
			return null;
		}
		JsonObject item = root.getAsJsonObject("item");
		return new ItemPrices(
			itemId,
			str(item, "name"),
			lng(item, "ask"),
			lng(item, "bid"),
			lng(item, "margin"),
			dbl(item, "roi"),
			lng(item, "tax"),
			lng(item, "buyLimit"),
			lng(item, "askVolume"),
			lng(item, "bidVolume"),
			now);
	}

	private static Long lng(JsonObject o, String key)
	{
		JsonElement e = o.get(key);
		return e == null || e.isJsonNull() ? null : e.getAsLong();
	}

	private static Double dbl(JsonObject o, String key)
	{
		JsonElement e = o.get(key);
		return e == null || e.isJsonNull() ? null : e.getAsDouble();
	}

	private static String str(JsonObject o, String key)
	{
		JsonElement e = o.get(key);
		return e == null || e.isJsonNull() ? null : e.getAsString();
	}
}
