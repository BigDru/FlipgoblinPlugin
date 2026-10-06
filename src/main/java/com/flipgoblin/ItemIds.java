package com.flipgoblin;

/** Item ids the plugin refers to by number. */
final class ItemIds
{
	/** Coins. Coin amounts travel through the item pipelines under this id. */
	static final int COINS = 995;

	/** Platinum token, worth 1,000 coins. The GE can now pay out and take these. */
	static final int PLATINUM_TOKEN = 13204;

	/** The gp one unit of this item is worth if it is cash: 1 for coins, 1,000 for platinum, else 0. */
	static long cashValue(int itemId)
	{
		return itemId == COINS ? 1 : itemId == PLATINUM_TOKEN ? 1_000 : 0;
	}

	/** The client's blank-box placeholder, shown in an empty collect box. Not a real item. */
	static final int BLANK_BOX = 6512;

	private ItemIds()
	{
	}
}
