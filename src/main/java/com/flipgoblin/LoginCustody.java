package com.flipgoblin;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Judges whether anyone used the account between our sessions, using the welcome screen's
 * "You last logged in … ago" line. Despite its wording, that line measures from the END of
 * the previous session, not from its login. This was established against client logs.
 * We therefore compare the report against our own recorded logout time, as durations, so
 * clock differences cancel out. Pure logic only; widget reading and state live in the plugin.
 *
 * Verdicts (every uncertainty resolves to the cautious side):
 * - ACQUITTED: the reported session end matches our recorded logout within display
 *   rounding, at minute precision or finer. This rules out a hidden session almost
 *   entirely, because a hidden session ends after our logout and its report would read
 *   newer than ours. The one residual: a hidden session that starts and ends inside the
 *   same rounding step as our logout cannot be detected.
 * - CONVICTED: the report is newer than our recorded logout. Someone was online after us.
 * - AMBIGUOUS: no stored record, unparseable text, hour or day rounding (wide enough to
 *   hide a whole session), or a report older than our own logout, which means our own
 *   record cannot be trusted.
 */
final class LoginCustody
{
	/**
	 * Slack for rounding and timing: the heartbeat can leave lastSeen up to about a minute
	 * stale after a crash, the server records a hard-close logout with its own lag, and the
	 * display rounds down to its finest unit. 90 seconds covers all of these. A hidden
	 * session shorter than the slack could not reach the bank anyway.
	 */
	static final long SLACK_MS = 90_000;

	/**
	 * Acquittal requires the display to round at minute precision or finer. Every observed
	 * format carries minutes even at hour scale ("7 hours, 59 minutes ago"). A coarser
	 * display leaves a window a whole session could hide in, so it judges AMBIGUOUS.
	 */
	static final long MAX_ACQUIT_GRANULARITY_MS = 60_000;

	enum Verdict
	{
		ACQUITTED, CONVICTED, AMBIGUOUS
	}

	/** Our saved per-character session record. */
	static final class SessionRecord
	{
		/** Last fresh login (epoch ms, 0 = unknown). Diagnostics only; the welcome report
		 * measures session end, so the judge never compares logins. */
		long freshLoginMs;
		/** Last login of any kind (fresh, hop, or reconnect). Diagnostics only. */
		long anyLoginMs;
		/** Our recorded logout time: refreshed by a heartbeat about once a minute and stamped
		 * on clean logout. This is what the judge compares against the welcome report. A
		 * crash leaves it up to one heartbeat stale, which SLACK_MS absorbs. */
		long lastSeenMs;
		/** True while the stored bank snapshot can still be trusted: set when the bank is
		 * opened, carried across a logout only by an ACQUITTED verdict, and false the moment
		 * a gap is not provably ours. */
		boolean bankChainTrusted;
	}

	/** A parsed "You last logged in …" line: the rounded-down duration and how finely it was displayed. */
	static final class Report
	{
		/** The reported gap in ms, rounded down; "1 hour, 1 min" parses to 61 minutes. */
		final long agoMs;
		/** The finest unit displayed, in ms. This is the width of the rounding step. */
		final long granularityMs;

		Report(long agoMs, long granularityMs)
		{
			this.agoMs = agoMs;
			this.granularityMs = granularityMs;
		}
	}

	/** The verdict plus the age of our own recorded logout, for the "ours" part of the display. */
	static final class Result
	{
		final Verdict verdict;
		/** How old our recorded session end was when judged. 0 means no record. */
		final long ourAgoMs;

		Result(Verdict verdict, long ourAgoMs)
		{
			this.verdict = verdict;
			this.ourAgoMs = ourAgoMs;
		}
	}

	private static final Pattern REPORT = Pattern.compile("(?i)last logged in\\s+(.+?)\\s+ago");
	private static final Pattern COMPONENT = Pattern.compile(
		"(?i)^(\\d{1,6}|an?|one)\\s+(day|hour|min(?:ute)?|sec(?:ond)?)s?$");

	private LoginCustody()
	{
	}

	/**
	 * Parses the welcome-screen line into a rounded-down duration. Formats seen live include
	 * "You last logged in 40 minutes ago.", "…a minute ago.", and "1 hour, 1 min ago". So:
	 * comma-separated (value, unit) parts, where a/an/one mean 1 and the units are
	 * day/hour/min(ute)/sec(ond) with an optional plural. Returns null for anything else,
	 * and the caller must display that failure as AMBIGUOUS rather than hide it.
	 */
	static Report parse(String rawText)
	{
		if (rawText == null)
		{
			return null;
		}
		String plain = rawText.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
		Matcher m = REPORT.matcher(plain);
		if (!m.find())
		{
			return null;
		}
		long total = 0;
		long gran = 0;
		for (String part : m.group(1).split(",\\s*|\\s+and\\s+"))
		{
			Matcher pm = COMPONENT.matcher(part.trim());
			if (!pm.matches())
			{
				return null;
			}
			String q = pm.group(1).toLowerCase();
			long n = q.equals("a") || q.equals("an") || q.equals("one") ? 1 : Long.parseLong(q);
			String unit = pm.group(2).toLowerCase();
			long unitMs = unit.startsWith("day") ? 86_400_000L
				: unit.startsWith("hour") ? 3_600_000L
				: unit.startsWith("min") ? 60_000L : 1_000L;
			total += n * unitMs;
			gran = gran == 0 ? unitMs : Math.min(gran, unitMs);
		}
		return gran == 0 ? null : new Report(total, gran);
	}

	/**
	 * The judgement. The report says when the most recent session ended, and a rounded-down
	 * display means the true instant lies somewhere in [agoMs, agoMs + granularity).
	 * Compare our recorded logout:
	 *
	 * - Inside that window (with slack) at minute precision or finer: ACQUITTED. A hidden
	 *   session would end after our logout, so its report would read newer than our record.
	 * - Report newer than our logout: CONVICTED. Someone was online after us.
	 * - Report older than our logout: AMBIGUOUS, not convicted. The server says the last
	 *   session ended before we think we were online, so our own record is what cannot be
	 *   trusted (a clock jump or a lost config write).
	 * - Inside the window but with hour or day rounding: AMBIGUOUS. The step is wide
	 *   enough to hide an entire session.
	 *
	 * The recorded login instants are never compared. Doing so would falsely convict every
	 * reconnect, because the report measures session end. They stay for diagnostics only.
	 */
	static Result judge(Report report, SessionRecord prev, long nowMs)
	{
		if (prev == null || prev.lastSeenMs <= 0)
		{
			return new Result(Verdict.AMBIGUOUS, 0);
		}
		long seenAgo = nowMs - prev.lastSeenMs;
		if (report == null)
		{
			return new Result(Verdict.AMBIGUOUS, seenAgo);
		}
		if (seenAgo >= report.agoMs + report.granularityMs + SLACK_MS)
		{
			return new Result(Verdict.CONVICTED, seenAgo);
		}
		if (seenAgo < report.agoMs - SLACK_MS)
		{
			return new Result(Verdict.AMBIGUOUS, seenAgo);
		}
		return new Result(report.granularityMs <= MAX_ACQUIT_GRANULARITY_MS
			? Verdict.ACQUITTED : Verdict.AMBIGUOUS, seenAgo);
	}

	/** Formats a compact duration such as "1h01m", "40m", or "2d3h". */
	static String fmtDuration(long ms)
	{
		long min = Math.max(0, ms) / 60_000;
		long d = min / 1_440;
		long h = min % 1_440 / 60;
		long m = min % 60;
		if (d > 0)
		{
			return d + "d" + (h > 0 ? h + "h" : "");
		}
		if (h > 0)
		{
			return h + "h" + String.format("%02dm", m);
		}
		return m + "m";
	}
}
