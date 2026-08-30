package com.flipgoblin;

import java.util.List;
import java.util.Map;

/**
 * Sums the other linked characters' stored data (recent fill history and last bank
 * snapshot) so the panel's "All characters" scope can show account-wide numbers beside
 * the live character's. Pure math; profile enumeration and JSON parsing stay in the plugin.
 *
 * Realized P/L is FIFO-matched per character and then summed. Record lists are never
 * merged into one match, because one character's buys must not price another's sells.
 * An offline character's inventory, equipment, and GE escrow are invisible here, so
 * callers label the aggregate "banks only".
 */
final class CharacterLedger
{
	/** One other character's parsed stores. Either part may be null or empty. */
	static final class Character
	{
		final String name;
		final List<TradeRecord> records;
		final AssetSnapshot bank;

		Character(String name, List<TradeRecord> records, AssetSnapshot bank)
		{
			this.name = name;
			this.records = records;
			this.bank = bank;
		}
	}

	/** The other characters' summed contribution. The live character renders separately. */
	static final class Totals
	{
		final int characters;
		/** Summed per-character realized P/L over fills inside the horizon. */
		final long realized7d;
		/** Summed bank snapshot values at the given bids. -1 means no bid map yet. */
		final long bankEst;
		/** Stacks the bid map could not price. These are left out of bankEst. */
		final long unpriced;
		/** Display names joined with " · ", for tooltips. */
		final String names;

		Totals(int characters, long realized7d, long bankEst, long unpriced, String names)
		{
			this.characters = characters;
			this.realized7d = realized7d;
			this.bankEst = bankEst;
			this.unpriced = unpriced;
			this.names = names;
		}
	}

	private CharacterLedger()
	{
	}

	/**
	 * Sums the other characters. The horizon filter is applied again at read time, because a
	 * stored history was trimmed when that character last saved (possibly days ago) and the
	 * panel's label promises 7 days from now. Pure.
	 */
	static Totals aggregate(List<Character> others, Map<Integer, Long> bids, long nowMs, long horizonMs)
	{
		long realized = 0;
		long bankEst = bids == null ? -1 : 0;
		long unpriced = 0;
		StringBuilder names = new StringBuilder();
		for (Character c : others)
		{
			if (names.length() > 0)
			{
				names.append(" · ");
			}
			names.append(c.name);
			if (c.records != null && !c.records.isEmpty())
			{
				List<TradeRecord> recent = new java.util.ArrayList<>(c.records.size());
				for (TradeRecord r : c.records)
				{
					if (r != null && r.timestamp >= nowMs - horizonMs)
					{
						recent.add(r);
					}
				}
				realized += SessionStats.match(recent).totalRealized;
			}
			if (c.bank != null && bids != null)
			{
				long[] v = c.bank.estimateValue(bids);
				bankEst += v[0];
				unpriced += v[1];
			}
		}
		return new Totals(others.size(), realized, bankEst, unpriced, names.toString());
	}
}
