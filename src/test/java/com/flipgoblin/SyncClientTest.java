package com.flipgoblin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.Arrays;
import org.junit.Test;

/**
 * Pins the sync wire payload to the API-8 contract (`parseSyncBody` in apps/api/src/flips.ts):
 * {flips: [{itemId, side: "buy"|"sell", price, qty, ts, geSlot?, clientId}]}. If either side renames a
 * field, this test (or the server e2e) goes red — the cross-system contract guard.
 */
public class SyncClientTest
{
	@Test
	public void lockedBodyDistinguishesTheLapseLockFromABadToken()
	{
		// The lapse lock rides a 401 whose body names it — anything else is not "locked".
		org.junit.Assert.assertTrue(SyncClient.lockedBody(401, "{\"ok\":false,\"error\":\"character locked\"}"));
		org.junit.Assert.assertFalse(SyncClient.lockedBody(401, "{\"ok\":false,\"error\":\"unauthenticated\"}"));
		org.junit.Assert.assertFalse(SyncClient.lockedBody(403, "{\"error\":\"character locked\"}"));
		org.junit.Assert.assertFalse(SyncClient.lockedBody(401, null));
		org.junit.Assert.assertFalse(SyncClient.lockedBody(200, ""));
	}

	@Test
	public void payloadMatchesTheApiContract()
	{
		TradeRecord buy = new TradeRecord(4151, TradeRecord.Side.BUY, 1_000_000, 2, 2_000_000, 3, 1720000000000L);
		TradeRecord sell = new TradeRecord(4151, TradeRecord.Side.SELL, 1_100_000, 1, 1_100_000, 5, 1720000060000L);
		JsonObject body = SyncClient.buildPayload(Arrays.asList(buy, sell));

		JsonArray flips = body.getAsJsonArray("flips");
		assertEquals(2, flips.size());

		JsonObject f0 = flips.get(0).getAsJsonObject();
		assertEquals(4151, f0.get("itemId").getAsInt());
		assertEquals("buy", f0.get("side").getAsString());
		assertEquals(1_000_000, f0.get("price").getAsLong());
		assertEquals(2, f0.get("qty").getAsInt());
		assertEquals(1720000000000L, f0.get("ts").getAsLong());
		assertEquals(3, f0.get("geSlot").getAsInt());
		assertEquals(buy.clientId, f0.get("clientId").getAsString());
		assertEquals(36, f0.get("clientId").getAsString().length()); // UUID — fits the server's 1-64 cap

		assertEquals("sell", flips.get(1).getAsJsonObject().get("side").getAsString());
		assertFalse(f0.get("clientId").getAsString().equals(
			flips.get(1).getAsJsonObject().get("clientId").getAsString()));
	}

	@Test
	public void negativeSlotIsOmitted() // geSlot is optional in the contract (null ⇔ absent)
	{
		TradeRecord r = new TradeRecord(1, TradeRecord.Side.BUY, 10, 1, 10, -1, 1720000000000L);
		JsonObject f0 = SyncClient.buildPayload(Arrays.asList(r)).getAsJsonArray("flips")
			.get(0).getAsJsonObject();
		assertTrue(!f0.has("geSlot"));
	}

	@Test
	public void recoveredFillCarriesFlagAndWindow_liveFillOmitsBoth()
	{
		TradeRecord recovered = new TradeRecord(
			4151, TradeRecord.Side.SELL, 1_000, 2, 2_000, 1, 1720000060000L, true, 1720000000000L);
		TradeRecord live = new TradeRecord(4151, TradeRecord.Side.BUY, 900, 1, 900, 2, 1720000070000L);
		JsonArray flips = SyncClient.buildPayload(Arrays.asList(recovered, live)).getAsJsonArray("flips");

		JsonObject f0 = flips.get(0).getAsJsonObject();
		assertTrue(f0.get("recovered").getAsBoolean());
		assertEquals(1720000000000L, f0.get("offlineSince").getAsLong());

		JsonObject f1 = flips.get(1).getAsJsonObject();
		assertFalse(f1.has("recovered"));
		assertFalse(f1.has("offlineSince"));
	}

	@Test
	public void assetsJsonCarriesPairsBankAtAndOffers() // mirrors parseAssetsBody in apps/api/src/assets.ts
	{
		AssetSnapshot snap = AssetSnapshot.of(0L, 1_000L, new int[][]{{4151, 3}});
		GePositions.Position sell = new GePositions.Position(
			2, 2359, TradeRecord.Side.SELL, 92, 7081, 0, 0, GePositions.Phase.WORKING, 1L, 2L);
		String json = SyncClient.assetsJson(snap, 1720000000000L, true, Arrays.asList(sell));

		assertEquals("{\"pairs\":[[995,1000],[4151,3]],\"bankAt\":1720000000000,\"bankFresh\":true,"
			+ "\"offers\":[{\"slot\":2,\"itemId\":2359,\"side\":\"sell\",\"price\":92,"
			+ "\"total\":7081,\"filled\":0,\"phase\":\"working\"}]}", json);
	}

	@Test
	public void assetsJsonWithNoOffersSendsEmptyArray() // [] = "none standing"; absent = old plugin
	{
		AssetSnapshot snap = AssetSnapshot.of(0L, 500L);
		String json = SyncClient.assetsJson(snap, 0L, false, java.util.Collections.emptyList());
		assertEquals("{\"pairs\":[[995,500]],\"bankFresh\":false,\"offers\":[]}", json);
	}

	/**
	 * PLUG-7: the crowd payload matches parseCrowdBody in apps/api/src/crowd.ts —
	 * {events: [{itemId, side, price, quantity, ts(SECONDS), clientId}]} and NEVER the GE slot.
	 */
	@Test
	public void crowdPayloadMatchesTheApiContractAndOmitsTheSlot()
	{
		TradeRecord buy = new TradeRecord(4151, TradeRecord.Side.BUY, 1_000_000, 2, 2_000_000, 3, 1720000000000L);
		JsonObject body = SyncClient.buildCrowdPayload(Arrays.asList(buy));
		JsonArray events = body.getAsJsonArray("events");
		assertEquals(1, events.size());
		JsonObject e0 = events.get(0).getAsJsonObject();
		assertEquals(4151, e0.get("itemId").getAsInt());
		assertEquals("buy", e0.get("side").getAsString());
		assertEquals(1_000_000, e0.get("price").getAsLong());
		assertEquals(2, e0.get("quantity").getAsInt());
		assertEquals(1720000000L, e0.get("ts").getAsLong()); // unix SECONDS on the crowd wire
		assertEquals(buy.clientId, e0.get("clientId").getAsString());
		assertFalse("the GE slot must never ride the crowd stream", e0.has("geSlot"));
		assertFalse(e0.has("slot"));
	}

	/** Recovered fills never qualify for the crowd stream (their ts is detection, not fill time). */
	@Test
	public void recoveredFillsAreNeverEnqueuedForCrowd()
	{
		SyncClient s = new SyncClient(null, new Gson());
		TradeRecord recovered = new TradeRecord(4151, TradeRecord.Side.BUY, 100, 1, 100, 2,
			1720000000000L, true, 1719990000000L);
		TradeRecord live = new TradeRecord(4151, TradeRecord.Side.BUY, 100, 1, 100, 2, 1720000000000L);
		s.enqueueCrowd(recovered);
		s.enqueueCrowd(live);
		assertEquals(1, s.crowdPendingCount());
	}
}
