package com.flipgoblin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import org.junit.Test;

/** Pins the GE hover tooltip markup (RS col tags + </br> — no client classes, so it stays pure). */
public class GeTooltipTest
{
	private static PriceClient.ItemPrices prices(String json)
	{
		return PriceClient.parse(4151, json, 1000L, new Gson());
	}

	@Test
	public void fullDataRendersAllLines()
	{
		String tip = GeTooltip.build(prices(
			"{\"ok\":true,\"item\":{\"name\":\"Abyssal whip\",\"ask\":1002947,\"bid\":976344,"
				+ "\"margin\":6545,\"roi\":0.0067,\"tax\":20058,\"buyLimit\":70,"
				+ "\"askVolume\":1705,\"bidVolume\":1993}}"));
		assertTrue(tip.contains("Abyssal whip"));
		assertTrue(tip.contains("Buy 1.0m · Sell 976.3k"));
		assertTrue(tip.contains("Margin"));
		assertTrue(tip.contains("(0.7%)"));
		assertTrue(tip.contains("after tax (20.1k)")); // the tax gp rides the margin line
		assertTrue(tip.contains("Limit 70"));
		assertTrue(tip.contains("<col=3fb950>")); // positive margin = profit green
		assertEquals(3, tip.split("</br>", -1).length - 1);
	}

	@Test
	public void nullSidesRenderDashes_negativeMarginRendersLossColor()
	{
		String tip = GeTooltip.build(prices(
			"{\"ok\":true,\"item\":{\"name\":\"Cannonball\",\"ask\":null,\"bid\":180,"
				+ "\"margin\":-5,\"roi\":null,\"tax\":null,\"buyLimit\":null,"
				+ "\"askVolume\":null,\"bidVolume\":9000}}"));
		assertTrue(tip.contains("Buy — · Sell 180"));
		assertTrue(tip.contains("<col=f85149>")); // loss red
		assertTrue(tip.contains("Limit —"));
		assertFalse(tip.contains("%")); // no ROI when null
	}

	@Test
	public void limitUsageLine_appearsOnlyWithAnActiveWindow_redWhenCapped()
	{
		String json = "{\"ok\":true,\"item\":{\"name\":\"Diamond\",\"ask\":1650,\"bid\":1620,"
			+ "\"margin\":10,\"roi\":0.006,\"tax\":16,\"buyLimit\":11000,"
			+ "\"askVolume\":1,\"bidVolume\":1}}";
		assertFalse(GeTooltip.build(prices(json)).contains("Bought"));

		String open = GeTooltip.build(prices(json), new GeLimits.Usage(250, 0, false, false), "15:42");
		assertTrue(open.contains("Bought ≥250/11000 · left ≤10750 · limit resets 15:42"));
		assertFalse(open.contains("<col=f85149>Bought")); // under the cap — muted, not red

		String capped = GeTooltip.build(prices(json), new GeLimits.Usage(11000, 0, false, false), "15:42");
		assertTrue(capped.contains("<col=f85149>Bought ≥11000/11000 · left ≤0"));
	}

	@Test
	public void lastFillLine_rendersPresentSidesOnly()
	{
		String json = "{\"ok\":true,\"item\":{\"name\":\"Diamond\",\"ask\":1650,\"bid\":1620,"
			+ "\"margin\":10,\"roi\":0.006,\"tax\":16,\"buyLimit\":11000,"
			+ "\"askVolume\":1,\"bidVolume\":1}}";
		TradeRecord buy = new TradeRecord(4151, TradeRecord.Side.BUY, 1_180_000, 2, 2_360_000, 0, 1000L);
		TradeRecord sell = new TradeRecord(4151, TradeRecord.Side.SELL, 1_220_000, 2, 2_440_000, 0, 2000L);

		String buyOnly = GeTooltip.build(prices(json), null, null, buy, "13:42", null, null);
		assertTrue(buyOnly.contains("Last buy 1.2m ×2 13:42"));
		assertFalse(buyOnly.contains("sell")); // capital-S "Sell" line is fine; no last-sell segment

		String both = GeTooltip.build(prices(json), null, null, buy, "13:42", sell, "14:05");
		assertTrue(both.contains("Last buy 1.2m ×2 13:42 · sell 1.2m ×2 14:05"));

		String none = GeTooltip.build(prices(json), null, null, null, null, null, null);
		assertFalse(none.contains("Last"));
	}

	@Test
	public void flipLine_signColored()
	{
		// Per-ea is an exact price figure (ruling 2026-08-28); the unrealized TOTAL stays short.
		assertEquals("<col=3fb950>Flip 12,500/ea · 1.2m unrealized</col>", GeTooltip.flipLine(12_500, 1_200_000));
		assertTrue(GeTooltip.flipLine(-50, -5_000).contains("<col=f85149>"));
	}
}
