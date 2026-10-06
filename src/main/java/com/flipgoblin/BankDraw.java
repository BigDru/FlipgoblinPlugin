package com.flipgoblin;

import java.util.ArrayList;
import java.util.List;

/**
 * Corrects the stored bank snapshot when the GE pays for a buy offer out of the bank.
 * The client only sends the bank while it is open, so without this the snapshot keeps
 * coins the GE already took, and they count twice: in the bank and as the offer's escrow.
 */
final class BankDraw
{
	/** Ticks to wait after a buy is placed before reading the inventory, so its update has landed. */
	static final int SETTLE_TICKS = 2;

	private BankDraw()
	{
	}

	/**
	 * The part of a buy's cost that the inventory did not pay, so the bank paid it.
	 * Cash means coins plus platinum tokens in gp.
	 */
	static long shortfall(long cost, long invCashBefore, long invCashAfter)
	{
		long paidFromInventory = Math.max(0, invCashBefore - invCashAfter);
		return Math.max(0, cost - paidFromInventory);
	}

	/**
	 * Returns the snapshot with {@code gp} taken out of its cash: coins first, then platinum
	 * tokens, with the change from a token kept as coins. Takes no more than the snapshot holds.
	 */
	static AssetSnapshot withdraw(AssetSnapshot photo, long gp)
	{
		long coins = 0;
		long platinum = 0;
		List<AssetSnapshot.Entry> rest = new ArrayList<>();
		for (AssetSnapshot.Entry e : photo.entries)
		{
			if (e.itemId == ItemIds.COINS)
			{
				coins += e.qty;
			}
			else if (e.itemId == ItemIds.PLATINUM_TOKEN)
			{
				platinum += e.qty;
			}
			else
			{
				rest.add(e);
			}
		}
		long fromCoins = Math.min(coins, gp);
		coins -= fromCoins;
		long left = gp - fromCoins;
		if (left > 0 && platinum > 0)
		{
			long tokenGp = ItemIds.cashValue(ItemIds.PLATINUM_TOKEN);
			long tokens = Math.min(platinum, (left + tokenGp - 1) / tokenGp);
			platinum -= tokens;
			coins += Math.max(0, tokens * tokenGp - left);
		}
		List<AssetSnapshot.Entry> out = new ArrayList<>();
		if (coins > 0)
		{
			out.add(new AssetSnapshot.Entry(ItemIds.COINS, coins));
		}
		if (platinum > 0)
		{
			out.add(new AssetSnapshot.Entry(ItemIds.PLATINUM_TOKEN, platinum));
		}
		out.addAll(rest);
		return new AssetSnapshot(photo.timestamp, out);
	}
}
