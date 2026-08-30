package com.flipgoblin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import org.junit.Test;

/** Pins PriceClient's parse to the `GET /items/:id` contract (apps/api items.ts detail shape). */
public class PriceClientTest
{
	private static final Gson GSON = new Gson();

	@Test
	public void parsesTheDetailShape()
	{
		String body = "{\"ok\":true,\"item\":{\"id\":4151,\"name\":\"Abyssal whip\",\"buyLimit\":70,"
			+ "\"ask\":1734000,\"bid\":1731000,\"askTime\":1783600000,\"bidTime\":1783600050,"
			+ "\"margin\":-37680,\"roi\":-0.0218,\"askVolume\":4800,\"bidVolume\":5100,\"tax\":34680}}";
		PriceClient.ItemPrices p = PriceClient.parse(4151, body, 1000L, GSON);
		assertEquals("Abyssal whip", p.name);
		assertEquals((Long) 1734000L, p.ask);
		assertEquals((Long) 1731000L, p.bid);
		assertEquals((Long) (-37680L), p.margin);
		assertEquals(-0.0218, p.roi, 1e-9);
		assertEquals((Long) 34680L, p.tax);
		assertEquals((Long) 70L, p.buyLimit);
		assertEquals((Long) 4800L, p.askVolume);
		assertEquals((Long) 5100L, p.bidVolume);
		assertEquals(1000L, p.fetchedAt);
	}

	@Test
	public void nullSidesStayNull_neverZero()
	{
		String body = "{\"ok\":true,\"item\":{\"id\":2,\"name\":\"Cannonball\",\"buyLimit\":null,"
			+ "\"ask\":null,\"bid\":180,\"margin\":null,\"roi\":null,\"askVolume\":null,"
			+ "\"bidVolume\":9000,\"tax\":null}}";
		PriceClient.ItemPrices p = PriceClient.parse(2, body, 1L, GSON);
		assertNull(p.ask);
		assertNull(p.margin);
		assertNull(p.roi);
		assertNull(p.buyLimit);
		assertEquals((Long) 180L, p.bid);
		assertEquals((Long) 9000L, p.bidVolume);
	}

	@Test
	public void malformedBodyReturnsNull()
	{
		assertNull(PriceClient.parse(2, "{\"ok\":false,\"error\":\"item not found\"}", 1L, GSON));
		assertNull(PriceClient.parse(2, "[]", 1L, GSON));
	}

	@Test
	public void seriesParse_closeFallsBackToAvg_nullSideIsNaN()
	{
		String body = "{\"ok\":true,\"candles\":["
			+ "{\"time\":1,\"askClose\":100,\"bidClose\":95,\"avgAsk\":99,\"avgBid\":94,"
			+ "\"askVolume\":40,\"bidVolume\":50},"
			+ "{\"time\":2,\"askClose\":null,\"bidClose\":null,\"avgAsk\":101,\"avgBid\":96},"
			+ "{\"time\":3,\"askClose\":null,\"bidClose\":97,\"avgAsk\":null,\"avgBid\":null}]}";
		PriceClient.Series s = PriceClient.parseSeries(body, 5L, GSON);
		assertEquals(3, s.ask.length);
		assertEquals(1L, s.time[0]);
		assertEquals(3L, s.time[2]);
		assertEquals(100.0, s.ask[0], 0);
		assertEquals(101.0, s.ask[1], 0); // synthetic bucket — falls back to the window avg
		assertTrue(Double.isNaN(s.ask[2])); // side truly absent
		assertEquals(97.0, s.bid[2], 0);
		assertNull(s.askReal); // candle series carry no fill flags — only the 1m tick series do
		assertEquals(40.0, s.askVol[0], 0); // per-bucket volumes ride along for the volume pane
		assertEquals(50.0, s.bidVol[0], 0);
		assertTrue(Double.isNaN(s.askVol[1])); // absent volume stays NaN, never zero-filled
		assertEquals(5L, s.fetchedAt);
	}

	@Test
	public void seriesParse_malformedReturnsNull()
	{
		assertNull(PriceClient.parseSeries("{\"ok\":false}", 1L, GSON));
		assertNull(PriceClient.parseSeries("not json", 1L, GSON));
	}

	@Test
	public void ticksParse_minuteGridCarriesPlateaus()
	{
		// now = minute 100 (epoch 6000s); 5-minute window ⇒ slots at minutes 95..100.
		long now = 6_000_000L;
		String body = "{\"ok\":true,\"itemId\":2,\"ticks\":["
			+ "{\"time\":5580,\"ask\":100,\"bid\":null}," // minute 93 — pre-window, feeds the carry
			+ "{\"time\":5760,\"ask\":null,\"bid\":90},"  // minute 96
			+ "{\"time\":5880,\"ask\":102,\"bid\":null}]}"; // minute 98
		PriceClient.Series s = PriceClient.parseTicks(body, 5, now, GSON);
		assertEquals(6, s.ask.length);
		assertEquals(5700L, s.time[0]); // minute 95
		assertEquals(6000L, s.time[5]); // the live minute
		assertEquals(100.0, s.ask[0], 0); // carried plateau from the pre-window trade
		assertTrue(Double.isNaN(s.bid[0])); // bid never traded before the window
		assertEquals(90.0, s.bid[1], 0);
		assertEquals(100.0, s.ask[1], 0);
		assertEquals(102.0, s.ask[3], 0);
		assertEquals(102.0, s.ask[5], 0); // plateau extends through "now"
		assertEquals(90.0, s.bid[5], 0);
		// Real-fill flags mark ONLY the slots where a trade landed — carried plateaus stay false.
		assertTrue(s.bidReal[1]);
		assertFalse(s.askReal[1]); // ask was carried at minute 96, not traded
		assertTrue(s.askReal[3]);
		assertFalse(s.askReal[0]);
		assertFalse(s.bidReal[5]);
	}

	@Test
	public void cacheCycleDelay_readsEdgeHeaders_fallsBackOnGarbage()
	{
		// s-maxage 60, served copy 52s old → fresh in 8s (+1.5s margin).
		assertEquals(9_500L, PriceClient.cacheCycleDelayMs("public, max-age=15, s-maxage=60", "52"));
		assertEquals(61_500L, PriceClient.cacheCycleDelayMs("s-maxage=60", "0"));
		assertEquals(3_000L, PriceClient.cacheCycleDelayMs("s-maxage=60", "59")); // floor — no hammering
		assertEquals(-1L, PriceClient.cacheCycleDelayMs("public, max-age=15", "10")); // no s-maxage
		assertEquals(-1L, PriceClient.cacheCycleDelayMs("s-maxage=60", "abc"));
		assertEquals(-1L, PriceClient.cacheCycleDelayMs(null, "10"));
		assertEquals(-1L, PriceClient.cacheCycleDelayMs("s-maxage=60", null));
	}

	@Test
	public void resample_uniformGridEndsAtCurrentBucket_carriesGaps()
	{
		// 900s buckets, 4 slots, now = 9900s → grid [7200, 8100, 9000, 9900].
		PriceClient.Series in = PriceClient.Series.candles(
			new long[]{6300, 8100}, new double[]{10, 12}, new double[]{9, 11}, null, null, 77L);
		PriceClient.Series out = PriceClient.resample(in, 900, 4, 9_900_000L);
		assertEquals(4, out.time.length);
		assertEquals(7200L, out.time[0]);
		assertEquals(9900L, out.time[3]); // always ends at the wall clock's current bucket
		assertEquals(10.0, out.ask[0], 0); // pre-window row seeds the carry
		assertEquals(12.0, out.ask[1], 0);
		assertEquals(12.0, out.ask[3], 0); // quiet buckets carry the plateau
		assertEquals(11.0, out.bid[3], 0);
		assertNull(out.askReal); // candle grids stay flagless
		assertEquals(77L, out.fetchedAt);
	}

	@Test
	public void ticksParse_derivedFillPaintsButNeverFlags()
	{
		String body = "{\"ok\":true,\"itemId\":2,\"ticks\":["
			+ "{\"time\":5760,\"ask\":100,\"bid\":null,\"src\":\"5m\"}]}"; // minute 96, derived
		PriceClient.Series s = PriceClient.parseTicks(body, 5, 6_000_000L, GSON);
		assertEquals(100.0, s.ask[1], 0); // the plateau is real information — it renders
		assertFalse(s.askReal[1]); // ...but synth averages never get a trade dot
	}

	@Test
	public void mergeTail_overwritesLiveBucket_appendsAndTrims_nullOnNoOverlap()
	{
		PriceClient.Series cached = PriceClient.Series.candles(
			new long[]{100, 200, 300}, new double[]{1, 2, 3}, new double[]{10, 20, 30}, null, null, 1000L);

		// Same live bucket refreshed — values overwritten in place, length unchanged.
		PriceClient.Series sameBucket = PriceClient.Series.candles(
			new long[]{300}, new double[]{3.5}, new double[]{35}, null, null, 2000L);
		PriceClient.Series m1 = PriceClient.mergeTail(cached, sameBucket, 3);
		assertEquals(3, m1.time.length);
		assertEquals(3.5, m1.ask[2], 0);
		assertEquals(2000L, m1.fetchedAt);

		// Boundary crossed — old live closes, new live appends, head trims to maxLen.
		PriceClient.Series crossed = PriceClient.Series.candles(
			new long[]{300, 400}, new double[]{3.9, 4}, new double[]{39, 40}, null, null, 3000L);
		PriceClient.Series m2 = PriceClient.mergeTail(cached, crossed, 3);
		assertEquals(3, m2.time.length);
		assertEquals(200L, m2.time[0]); // oldest bucket dropped
		assertEquals(400L, m2.time[2]);
		assertEquals(4.0, m2.ask[2], 0);
		assertEquals(3.9, m2.ask[1], 0);

		// Tail from far in the future (client away) — no overlap, caller must refetch full depth.
		PriceClient.Series away = PriceClient.Series.candles(
			new long[]{900, 1000}, new double[]{9, 10}, new double[]{90, 100}, null, null, 4000L);
		assertNull(PriceClient.mergeTail(cached, away, 3));
		assertNull(PriceClient.mergeTail(null, sameBucket, 3));
	}

	@Test
	public void latestPriceMatch_findsLatestRealTradeAtExactPrice_insideWindow()
	{
		// Minute grid via parseTicks: trades at minutes 96 (bid 90) and 98 (ask 102), now = min 100.
		String body = "{\"ok\":true,\"itemId\":2,\"ticks\":["
			+ "{\"time\":5760,\"ask\":null,\"bid\":90},"
			+ "{\"time\":5820,\"ask\":null,\"bid\":90},"  // minute 97 — later matching bid trade
			+ "{\"time\":5880,\"ask\":102,\"bid\":null}]}";
		PriceClient.Series s = PriceClient.parseTicks(body, 5, 6_000_000L, GSON);

		// My BUY fill at 90 → the bid series; latest real match is minute 97 (5820s).
		assertEquals(5_820_000L,
			PriceClient.latestPriceMatchMs(s, TradeRecord.Side.BUY, 90, 5_700_000L, 6_000_000L));
		// My SELL fill at 102 → the ask series, minute 98.
		assertEquals(5_880_000L,
			PriceClient.latestPriceMatchMs(s, TradeRecord.Side.SELL, 102, 5_700_000L, 6_000_000L));
		// Carried plateau minutes never match (real flags gate), and prices must be exact.
		assertEquals(0L,
			PriceClient.latestPriceMatchMs(s, TradeRecord.Side.BUY, 91, 5_700_000L, 6_000_000L));
		// Window bounds are honored: nothing before minute 98 for the ask.
		assertEquals(0L,
			PriceClient.latestPriceMatchMs(s, TradeRecord.Side.SELL, 102, 5_700_000L, 5_820_000L));
	}

	@Test
	public void mergeTail_nanTailSlotsKeepCachedTruth()
	{
		// Cached grid with real volumes; the tail's oldest slot has no row (NaN everywhere).
		PriceClient.Series cached = PriceClient.Series.candles(
			new long[]{100, 200, 300}, new double[]{1, 2, 3}, new double[]{10, 20, 30},
			new double[]{5, 6, 7}, new double[]{50, 60, 70}, 1000L);
		PriceClient.Series tail = PriceClient.Series.candles(
			new long[]{200, 300, 400},
			new double[]{Double.NaN, 3.5, 4}, new double[]{Double.NaN, 35, 40},
			new double[]{Double.NaN, 7.5, 8}, new double[]{Double.NaN, 75, 80}, 2000L);
		PriceClient.Series m = PriceClient.mergeTail(cached, tail, 4);
		assertEquals(4, m.time.length);
		assertEquals(6.0, m.askVol[1], 0); // NaN tail slot kept the cached volume (no erosion)
		assertEquals(2.0, m.ask[1], 0); // same for prices
		assertEquals(7.5, m.askVol[2], 0); // real tail values still overwrite
		assertEquals(8.0, m.askVol[3], 0);
	}

	@Test
	public void mergeTail_slidesTickGridAndMergesFlags()
	{
		long now = 6_000_000L; // minute 100
		String full = "{\"ok\":true,\"itemId\":2,\"ticks\":["
			+ "{\"time\":5580,\"ask\":100,\"bid\":90}," // pre-window anchor (minute 93)
			+ "{\"time\":5880,\"ask\":102,\"bid\":null}]}"; // minute 98 trade
		PriceClient.Series cached = PriceClient.parseTicks(full, 5, now, GSON); // minutes 95..100

		long later = 6_060_000L; // one refresh on (minute 101)
		String tailBody = "{\"ok\":true,\"itemId\":2,\"ticks\":["
			+ "{\"time\":5880,\"ask\":102,\"bid\":null}," // the tail fetch's own anchor
			+ "{\"time\":6060,\"ask\":null,\"bid\":95}]}"; // minute 101 trade
		PriceClient.Series tail = PriceClient.parseTicks(tailBody, 2, later, GSON); // minutes 99..101

		PriceClient.Series m = PriceClient.mergeTail(cached, tail, 6);
		assertEquals(6, m.time.length); // window size held constant
		assertEquals(5760L, m.time[0]); // minute 95 dropped off the left edge
		assertEquals(6060L, m.time[5]); // ...through the new live minute
		assertEquals(102.0, m.ask[5], 0); // plateau carried through the tail
		assertEquals(95.0, m.bid[5], 0);
		assertTrue(m.askReal[2]); // minute-98 fill flag survives from the cached region
		assertTrue(m.bidReal[5]);
		assertFalse(m.askReal[5]);
	}

	@Test
	public void ticksParse_noTradesEverIsAllNaN_malformedReturnsNull()
	{
		PriceClient.Series s = PriceClient.parseTicks("{\"ok\":true,\"itemId\":2,\"ticks\":[]}", 5, 6_000_000L, GSON);
		assertEquals(6, s.ask.length);
		assertTrue(Double.isNaN(s.ask[0]));
		assertTrue(Double.isNaN(s.bid[5]));
		assertNull(PriceClient.parseTicks("{\"ok\":false}", 5, 6_000_000L, GSON));
	}
}
