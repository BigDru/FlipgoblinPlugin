package com.flipgoblin;

import net.runelite.api.GrandExchangeOfferState;

/**
 * The one place offer states are interpreted. The differ, the positions board, and the
 * collect ledger all read the same events; keeping side, phase, and offer identity here
 * stops the copies from drifting apart.
 */
final class OfferStates
{
	private OfferStates()
	{
	}

	/** The trade side of a non-EMPTY offer state. */
	static TradeRecord.Side sideOf(GrandExchangeOfferState state)
	{
		switch (state)
		{
			case BUYING:
			case BOUGHT:
			case CANCELLED_BUY:
				return TradeRecord.Side.BUY;
			case SELLING:
			case SOLD:
			case CANCELLED_SELL:
				return TradeRecord.Side.SELL;
			default:
				throw new IllegalArgumentException("no side for state " + state);
		}
	}

	/** The lifecycle phase of a non-EMPTY offer state. */
	static GePositions.Phase phaseOf(GrandExchangeOfferState state)
	{
		switch (state)
		{
			case BUYING:
			case SELLING:
				return GePositions.Phase.WORKING;
			case BOUGHT:
			case SOLD:
				return GePositions.Phase.COMPLETE;
			case CANCELLED_BUY:
			case CANCELLED_SELL:
				return GePositions.Phase.CANCELLED;
			default:
				throw new IllegalArgumentException("no phase for state " + state);
		}
	}

	/**
	 * Whether {@code next} is the same ongoing offer as a previously seen state. The full
	 * identity must match (item, side, total quantity, set price) and the cumulative
	 * counters must not go backwards. Item and counters alone are a weak identity: a stale
	 * re-fire of a completed offer's state, diffed against a fresh baseline of the same
	 * item, would pass it and read as a giant live fill.
	 */
	static boolean sameOffer(int prevItemId, TradeRecord.Side prevSide, int prevTotalQuantity,
		long prevPrice, int prevQuantitySold, long prevSpent, OfferSnapshot next)
	{
		return prevItemId == next.itemId
			&& prevSide == sideOf(next.state)
			&& prevTotalQuantity == next.totalQuantity
			&& prevPrice == next.price
			&& next.quantitySold >= prevQuantitySold
			&& next.spent >= prevSpent;
	}
}
