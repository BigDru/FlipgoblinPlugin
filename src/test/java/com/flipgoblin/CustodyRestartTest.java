package com.flipgoblin;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** A plugin restart mid-session keeps bank trust. Only a login may break it. */
public class CustodyRestartTest
{
	private static final long NOW = 1_790_000_000_000L;

	private static LoginCustody.SessionRecord record(boolean trusted, long lastSeenMs)
	{
		LoginCustody.SessionRecord r = new LoginCustody.SessionRecord();
		r.bankChainTrusted = trusted;
		r.lastSeenMs = lastSeenMs;
		return r;
	}

	@Test
	public void quickRestartKeepsTrust()
	{
		assertTrue(CustodyTracker.carriesAcrossRestart(record(true, NOW - 70_000), NOW));
		assertTrue(CustodyTracker.carriesAcrossRestart(record(true, NOW - CustodyTracker.RESTART_CARRY_MS), NOW));
	}

	@Test
	public void untrustedRecordStaysUntrusted()
	{
		assertFalse(CustodyTracker.carriesAcrossRestart(record(false, NOW - 70_000), NOW));
	}

	@Test
	public void staleRecordDoesNotCarry()
	{
		// Saved too long ago: the client may have logged out while the plugin was off.
		assertFalse(CustodyTracker.carriesAcrossRestart(record(true, NOW - CustodyTracker.RESTART_CARRY_MS - 1), NOW));
	}

	@Test
	public void missingOrFutureRecordDoesNotCarry()
	{
		assertFalse(CustodyTracker.carriesAcrossRestart(null, NOW));
		assertFalse(CustodyTracker.carriesAcrossRestart(record(true, 0), NOW));
		assertFalse(CustodyTracker.carriesAcrossRestart(record(true, NOW + 60_000), NOW));
	}
}
