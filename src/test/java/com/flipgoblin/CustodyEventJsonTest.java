package com.flipgoblin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.google.gson.JsonObject;
import com.google.gson.Gson;
import org.junit.Test;

/** Pins the custody log body to what POST /plugin/custody accepts. */
public class CustodyEventJsonTest
{
	@Test
	public void loginRowCarriesEveryField()
	{
		JsonObject o = new Gson().fromJson(CustodyTracker.eventJson("login",
			LoginCustody.Verdict.ACQUITTED, false, false, 3_600_000L, 3_590_000L, 1_790_000_000_000L),
			JsonObject.class);
		assertEquals("login", o.get("cause").getAsString());
		assertEquals("acquitted", o.get("verdict").getAsString());
		assertFalse(o.get("trusted").getAsBoolean());
		assertFalse(o.get("prevChainTrusted").getAsBoolean());
		assertEquals(3_600_000L, o.get("gameAgoMs").getAsLong());
		assertEquals(3_590_000L, o.get("ourAgoMs").getAsLong());
		assertEquals(1_790_000_000_000L, o.get("clientTs").getAsLong());
		assertEquals(FlipGoblinPlugin.BUILD, o.get("build").getAsString());
	}

	@Test
	public void unknownFieldsAreLeftOut()
	{
		JsonObject o = new Gson().fromJson(CustodyTracker.eventJson("plugin_start",
			null, false, null, null, null, 5L), JsonObject.class);
		assertEquals(4, o.size()); // cause, trusted, clientTs, build
		assertFalse(o.has("verdict"));
		assertFalse(o.has("gameAgoMs"));
	}
}
