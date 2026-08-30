package com.flipgoblin;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Pins the b51 offer-board fast-path (dashboard-lag report 2026-07-12). */
public class AssetSyncThrottleTest
{
	@Test
	public void offersSectionExtractsTheTailAndHandlesNull()
	{
		String body = "{\"pairs\":[[995,100]],\"bankAt\":5,\"offers\":[{\"slot\":0}]}";
		assertEquals("\"offers\":[{\"slot\":0}]}", SyncClient.offersSection(body));
		assertEquals("", SyncClient.offersSection(null));
		assertEquals("", SyncClient.offersSection("{\"pairs\":[]}"));
	}

	@Test
	public void offerChangesFastPathThePush()
	{
		long now = 1_000_000L;
		// offers changed, last push long ago → the 1s settle floor
		assertEquals(1_000, SyncClient.assetDrainDelayMs(true, now - 100_000, now));
		// offers changed, pushed 1s ago → the remaining 2s of the 3s window
		assertEquals(2_000, SyncClient.assetDrainDelayMs(true, now - 1_000, now));
		// bank-only change, pushed 10s ago → waits out the minute window
		assertEquals(50_000, SyncClient.assetDrainDelayMs(false, now - 10_000, now));
		// bank-only change, never pushed → the 2s settle floor
		assertTrue(SyncClient.assetDrainDelayMs(false, 0, now) == 2_000);
	}
}
