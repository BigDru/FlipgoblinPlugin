package com.flipgoblin;

/**
 * One buy or sell fill. {@code spent} keeps the exact gp of this fill alongside the derived
 * per-item {@code price} so accounting never loses coins to rounding.
 */
public final class TradeRecord
{
	public enum Side
	{
		BUY, SELL
	}

	/** Random dedup id. The server upserts on (user, clientId), so a re-sent batch changes nothing. */
	public final String clientId;
	public final int itemId;
	public final Side side;
	public final long price;
	public final int quantity;
	public final long spent;
	public final int slot;
	public final long timestamp;
	/**
	 * True when this fill was found at login rather than seen live. It happened while we were
	 * not watching, so {@code timestamp} is when we detected it, not when it filled. The game
	 * exposes no fill timestamps, so the true time is only known to lie somewhere between
	 * {@code offlineSince} and {@code timestamp}.
	 */
	public final boolean recovered;
	/** Earliest possible true time of a recovered fill (epoch ms). 0 means unknown or not recovered. */
	public final long offlineSince;

	/** A live fill, observed as it happened. */
	public TradeRecord(int itemId, Side side, long price, int quantity, long spent, int slot, long timestamp)
	{
		this(itemId, side, price, quantity, spent, slot, timestamp, false, 0);
	}

	public TradeRecord(int itemId, Side side, long price, int quantity, long spent, int slot, long timestamp,
		boolean recovered, long offlineSince)
	{
		this(itemId, side, price, quantity, spent, slot, timestamp, recovered, offlineSince,
			java.util.UUID.randomUUID().toString());
	}

	/**
	 * Full form including {@code clientId}. Used to rewrite an existing record, for example
	 * to refine a recovered fill's time, without minting a new dedup id, so the server never
	 * sees a duplicate.
	 */
	public TradeRecord(int itemId, Side side, long price, int quantity, long spent, int slot, long timestamp,
		boolean recovered, long offlineSince, String clientId)
	{
		this.itemId = itemId;
		this.side = side;
		this.price = price;
		this.quantity = quantity;
		this.spent = spent;
		this.slot = slot;
		this.timestamp = timestamp;
		this.recovered = recovered;
		this.offlineSince = offlineSince;
		this.clientId = clientId;
	}

	@Override
	public String toString()
	{
		return String.format("TradeRecord{item=%d side=%s price=%d qty=%d spent=%d slot=%d ts=%d%s}",
			itemId, side, price, quantity, spent, slot, timestamp,
			recovered ? " recovered(since=" + offlineSince + ")" : "");
	}
}
