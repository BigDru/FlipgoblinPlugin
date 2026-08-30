package com.flipgoblin;

import net.runelite.api.GrandExchangeOfferState;

/**
 * A plain-data copy of a {@code GrandExchangeOffer} taken at one event. It decouples
 * {@link GeOfferDiffer} from the RuneLite interface so tests can build fixtures without a
 * client mock. {@code spent} is widened to {@code long} so delta arithmetic cannot overflow.
 */
public final class OfferSnapshot
{
	public final int itemId;
	public final GrandExchangeOfferState state;
	public final int totalQuantity;
	public final int quantitySold;
	public final long spent;
	/** The offer's per-item price. The position tracker and the escrow math both use it. */
	public final long price;

	public OfferSnapshot(int itemId, GrandExchangeOfferState state, int totalQuantity, int quantitySold, long spent,
		long price)
	{
		this.itemId = itemId;
		this.state = state;
		this.totalQuantity = totalQuantity;
		this.quantitySold = quantitySold;
		this.spent = spent;
		this.price = price;
	}
}
