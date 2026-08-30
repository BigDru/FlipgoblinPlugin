package com.flipgoblin;

import java.util.Locale;

/**
 * Formats exact gp amounts for real price records such as fills, standing offers, live
 * ask/bid, margins, and tax. A real price always shows every digit ("14,000"), never an
 * abbreviation ("14.0k"), because flipping is won in the last few coins. Aggregate totals
 * like session P/L still use the short k/m form.
 */
final class Gp
{
	private Gp()
	{
	}

	static String exact(long n)
	{
		return String.format(Locale.US, "%,d", n);
	}

	static String exactOrDash(Long n)
	{
		return n == null ? "—" : exact(n);
	}

	/** The short k/m form for aggregate totals: "1.4m", "23.5k", "950". */
	static String shortForm(long n)
	{
		long abs = Math.abs(n);
		String s;
		if (abs >= 1_000_000)
		{
			s = String.format(Locale.US, "%.1fm", abs / 1_000_000.0);
		}
		else if (abs >= 10_000)
		{
			s = String.format(Locale.US, "%.1fk", abs / 1_000.0);
		}
		else
		{
			s = Long.toString(abs);
		}
		return (n < 0 ? "-" : "") + s;
	}

	static String shortOrDash(Long n)
	{
		return n == null ? "—" : shortForm(n);
	}
}
