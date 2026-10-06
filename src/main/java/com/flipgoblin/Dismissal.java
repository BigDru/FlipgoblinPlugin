package com.flipgoblin;

/**
 * One Ignore click: the item's units open at {@code timestamp} stop counting as a flip.
 * Same as the website's "not a flip" button.
 */
public final class Dismissal
{
	public final int itemId;
	/** Unix ms of the click. Fills at the same ms are written off too. */
	public final long timestamp;

	public Dismissal(int itemId, long timestamp)
	{
		this.itemId = itemId;
		this.timestamp = timestamp;
	}
}
