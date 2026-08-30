package com.flipgoblin;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Pins the phase-2 custody parser against every welcome-screen format captured live (2026-07-16,
 * b57/b58 auto-dumps + user reports) and the judge against the spec's verdict scenarios
 * (docs/wealth-ledger-design.md §"Login custody").
 */
public class LoginCustodyTest
{
	private static final long MIN = 60_000L;
	private static final long HOUR = 3_600_000L;
	private static final long DAY = 86_400_000L;
	private static final long NOW = 1_800_000_000_000L;

	// --- parser: live-captured formats ------------------------------------------------------------

	@Test
	public void parsesMinuteForm()
	{
		LoginCustody.Report r = LoginCustody.parse("You last logged in 40 minutes ago.");
		assertEquals(40 * MIN, r.agoMs);
		assertEquals(MIN, r.granularityMs);
	}

	@Test
	public void parsesWordForm()
	{
		// Captured verbatim 2026-07-16: "…a minute ago." — the parser must handle a/an.
		LoginCustody.Report r = LoginCustody.parse("You last logged in a minute ago.");
		assertEquals(MIN, r.agoMs);
		assertEquals(MIN, r.granularityMs);
	}

	@Test
	public void parsesHourMinuteForm()
	{
		// User-reported 2026-07-14: "1 hour, 1 min ago" — note the abbreviated unit.
		LoginCustody.Report r = LoginCustody.parse("You last logged in 1 hour, 1 min ago.");
		assertEquals(HOUR + MIN, r.agoMs);
		assertEquals(MIN, r.granularityMs);
	}

	@Test
	public void parsesLongGapForm()
	{
		LoginCustody.Report r = LoginCustody.parse("You last logged in 7 hours, 59 minutes ago.");
		assertEquals(7 * HOUR + 59 * MIN, r.agoMs);
		assertEquals(MIN, r.granularityMs);
	}

	@Test
	public void parsesDayFormWithCoarserGranularity()
	{
		// Multi-day format unobserved (assume-precise ruling 2026-07-14); if it degrades to
		// day/hour units the granularity widens and the discriminator handles the rest.
		LoginCustody.Report r = LoginCustody.parse("You last logged in 2 days, 3 hours ago.");
		assertEquals(2 * DAY + 3 * HOUR, r.agoMs);
		assertEquals(HOUR, r.granularityMs);
	}

	@Test
	public void parsesThroughColorTags()
	{
		LoginCustody.Report r =
			LoginCustody.parse("<col=ffffff>You last logged in 30 minutes ago.</col>");
		assertEquals(30 * MIN, r.agoMs);
	}

	@Test
	public void unparseableReturnsNull()
	{
		assertNull(LoginCustody.parse(null));
		assertNull(LoginCustody.parse("Welcome to Old School RuneScape"));
		assertNull(LoginCustody.parse("You last logged in earlier today."));
		assertNull(LoginCustody.parse("You last logged in 40 fortnights ago."));
	}

	// --- judge: end-anchored semantics (established live 2026-07-16, b60/b61 client.log) ----------

	private static LoginCustody.SessionRecord record(long loginAgo, long seenAgo)
	{
		LoginCustody.SessionRecord r = new LoginCustody.SessionRecord();
		r.freshLoginMs = NOW - loginAgo;
		r.anyLoginMs = NOW - loginAgo;
		r.lastSeenMs = NOW - seenAgo;
		r.bankChainTrusted = true;
		return r;
	}

	@Test
	public void acquitsWhenReportMatchesOurLogout()
	{
		// The live b61 false-conviction scenario, judged right: 54-minute session, relog a
		// minute after logout. Report "a minute ago" = the session END — matches lastSeen,
		// login instants irrelevant.
		LoginCustody.Report rep = LoginCustody.parse("You last logged in a minute ago.");
		LoginCustody.Result res = LoginCustody.judge(rep, record(54 * MIN, MIN), NOW);
		assertEquals(LoginCustody.Verdict.ACQUITTED, res.verdict);
		assertEquals(MIN, res.ourAgoMs);
	}

	@Test
	public void acquitsLongGapMinutePrecision()
	{
		LoginCustody.Report rep = LoginCustody.parse("You last logged in 7 hours, 59 minutes ago.");
		LoginCustody.Result res =
			LoginCustody.judge(rep, record(9 * HOUR, 7 * HOUR + 59 * MIN + 30_000), NOW);
		assertEquals(LoginCustody.Verdict.ACQUITTED, res.verdict);
	}

	@Test
	public void convictsWhenReportNewerThanOurLogout()
	{
		// We logged out 3h ago but the screen says a session ended 30m ago — someone was on.
		LoginCustody.Report rep = LoginCustody.parse("You last logged in 30 minutes ago.");
		LoginCustody.Result res = LoginCustody.judge(rep, record(4 * HOUR, 3 * HOUR), NOW);
		assertEquals(LoginCustody.Verdict.CONVICTED, res.verdict);
	}

	@Test
	public void doesNotMatchLoginInstants()
	{
		// The b60/b61 bug pinned: a report that matches our LOGIN (not our logout) is a session
		// that ended while we believe we were still online — broken record → AMBIGUOUS, and
		// never a false ACQUITTED off the login anchor.
		LoginCustody.Report rep = LoginCustody.parse("You last logged in 54 minutes ago.");
		LoginCustody.Result res = LoginCustody.judge(rep, record(54 * MIN, MIN), NOW);
		assertEquals(LoginCustody.Verdict.AMBIGUOUS, res.verdict);
	}

	@Test
	public void ambiguousWithoutRecord()
	{
		LoginCustody.Report rep = LoginCustody.parse("You last logged in 40 minutes ago.");
		assertEquals(LoginCustody.Verdict.AMBIGUOUS, LoginCustody.judge(rep, null, NOW).verdict);
	}

	@Test
	public void ambiguousOnUnparseableText()
	{
		LoginCustody.Result res = LoginCustody.judge(null, record(HOUR, 5 * MIN), NOW);
		assertEquals(LoginCustody.Verdict.AMBIGUOUS, res.verdict);
		assertEquals(5 * MIN, res.ourAgoMs);
	}

	@Test
	public void ambiguousOnCoarseGranularityEvenWhenMatching()
	{
		// Hour/day-granularity display: the rounding granule could hide a whole stealth session
		// (spec's degraded-format rule) — matching is not enough to acquit.
		LoginCustody.Report rep = LoginCustody.parse("You last logged in 2 days ago.");
		LoginCustody.Result res =
			LoginCustody.judge(rep, record(3 * DAY, 2 * DAY + 3 * HOUR), NOW);
		assertEquals(LoginCustody.Verdict.AMBIGUOUS, res.verdict);
	}

	@Test
	public void ambiguousWhenLogoutUnknown()
	{
		// Plugin enabled mid-session crash: record exists but lastSeen never heartbeat.
		LoginCustody.SessionRecord prev = new LoginCustody.SessionRecord();
		prev.anyLoginMs = NOW - HOUR;
		LoginCustody.Report rep = LoginCustody.parse("You last logged in 1 hour, 0 minutes ago.");
		assertEquals(LoginCustody.Verdict.AMBIGUOUS, LoginCustody.judge(rep, prev, NOW).verdict);
	}

	@Test
	public void slackAbsorbsCrashStaleHeartbeat()
	{
		// Hard-kill exit: lastSeen is up to a heartbeat (~60s) older than the true disconnect,
		// so our value reads slightly PAST the bucket — SLACK_MS keeps it an acquittal.
		LoginCustody.Report rep = LoginCustody.parse("You last logged in 10 minutes ago.");
		LoginCustody.Result res = LoginCustody.judge(rep, record(HOUR, 11 * MIN + 50_000), NOW);
		assertEquals(LoginCustody.Verdict.ACQUITTED, res.verdict);
	}

	// --- duration formatting (the overlay's "1h01m" form) ------------------------------------------

	@Test
	public void formatsDurations()
	{
		assertEquals("40m", LoginCustody.fmtDuration(40 * MIN));
		assertEquals("1h02m", LoginCustody.fmtDuration(HOUR + 2 * MIN + 30_000));
		assertEquals("2d3h", LoginCustody.fmtDuration(2 * DAY + 3 * HOUR + 7 * MIN));
		assertEquals("0m", LoginCustody.fmtDuration(12_000));
	}
}
