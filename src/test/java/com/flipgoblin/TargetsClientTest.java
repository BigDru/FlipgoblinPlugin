package com.flipgoblin;

import com.google.gson.Gson;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Pins the /plugin/targets wire parse + the overlay's alert vocabulary. */
public class TargetsClientTest
{
	private final Gson gson = new Gson();

	@Test
	public void parsesTargetsWithAlerts()
	{
		String body = "{\"ok\":true,\"targets\":["
			+ "{\"itemId\":4151,\"watched\":true,\"alerts\":["
			+ "{\"metric\":\"bid\",\"op\":\"lte\",\"threshold\":1000000,\"enabled\":true},"
			+ "{\"metric\":\"roi\",\"op\":\"gte\",\"threshold\":250,\"enabled\":false}]},"
			+ "{\"itemId\":2,\"watched\":true,\"alerts\":[]}]}";
		Map<Integer, TargetsClient.Target> parsed = TargetsClient.parse(gson, body);
		assertNotNull(parsed);
		assertEquals(2, parsed.size());
		TargetsClient.Target whip = parsed.get(4151);
		assertTrue(whip.watched);
		assertEquals(2, whip.alerts.size());
		assertEquals("bid", whip.alerts.get(0).metric);
		assertEquals(1_000_000L, whip.alerts.get(0).threshold);
		assertTrue(whip.alerts.get(0).enabled);
		assertFalse(whip.alerts.get(1).enabled);
		assertTrue(parsed.get(2).alerts.isEmpty());
	}

	@Test
	public void malformedBodiesParseToNullNeverThrow()
	{
		assertNull(TargetsClient.parse(gson, "not json at all"));
		assertNull(TargetsClient.parse(gson, "{\"ok\":true}"));
		assertNull(TargetsClient.parse(gson, "{\"targets\":{\"a\":1}}"));
		assertNull(TargetsClient.parse(gson, "{\"targets\":[{\"noItemId\":1}]}"));
	}

	@Test
	public void alertLabelUsesTheSiteVocabulary()
	{
		// Thresholds are exact prices the user typed — never abbreviated back (ruling 2026-08-28).
		assertEquals("Buy ≤ 1,000,000",
			TargetsClient.alertLabel(new TargetsClient.TargetAlert("bid", "lte", 1_000_000, true)));
		assertEquals("Sell ≥ 250",
			TargetsClient.alertLabel(new TargetsClient.TargetAlert("ask", "gte", 250, true)));
		assertEquals("ROI ≥ 2.5%",
			TargetsClient.alertLabel(new TargetsClient.TargetAlert("roi", "gte", 250, true)));
		assertEquals("Margin ≥ 29,000",
			TargetsClient.alertLabel(new TargetsClient.TargetAlert("margin", "gte", 29_000, true)));
	}
}
