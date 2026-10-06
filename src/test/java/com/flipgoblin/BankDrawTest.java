package com.flipgoblin;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class BankDrawTest
{
	@Test
	public void shortfall_isTheCostTheInventoryDidNotPay()
	{
		assertEquals(20_000, BankDraw.shortfall(20_000, 0, 0)); // all from the bank
		assertEquals(0, BankDraw.shortfall(20_000, 50_000, 30_000)); // all from the inventory
		assertEquals(5_000, BankDraw.shortfall(20_000, 15_000, 0)); // split
		assertEquals(0, BankDraw.shortfall(20_000, 50_000, 10_000)); // inventory dropped more: never negative
		assertEquals(20_000, BankDraw.shortfall(20_000, 0, 7)); // inventory grew: bank paid all
	}

	@Test
	public void userCase_bankPaidBuy_noLongerCountsTwice()
	{
		// The b91 field report (2026-10-06): ~50k net worth, all coins banked, a ~20k buy paid
		// from the bank showed ~70k until the bank was reopened.
		AssetSnapshot photo = AssetSnapshot.of(1L, new int[][]{{995, 45_000}, {4151, 1}});
		long escrow = 20_000;
		AssetSnapshot fixed = BankDraw.withdraw(photo, BankDraw.shortfall(escrow, 0, 0));
		assertEquals(25_000, fixed.coins());
		assertEquals(45_000, fixed.coins() + escrow); // cash total unchanged by placing the buy
		assertEquals(1L, fixed.timestamp); // still the time the bank was last seen
		assertEquals(1, fixed.entries.stream().filter(e -> e.itemId == 4151).findFirst().get().qty);
	}

	@Test
	public void withdraw_usesPlatinumWhenCoinsRunOut_andKeepsTheChange()
	{
		AssetSnapshot photo = AssetSnapshot.of(1L, new int[][]{{995, 300}, {13204, 10}});
		AssetSnapshot after = BankDraw.withdraw(photo, 2_500);
		// 300 coins, then 3 tokens (3,000 gp) for the other 2,200: 800 change, 7 tokens left.
		assertEquals(10_300 - 2_500, after.coins());
		assertEquals(7, after.entries.stream().filter(e -> e.itemId == 13204).findFirst().get().qty);
	}

	@Test
	public void withdraw_neverGoesBelowZero()
	{
		AssetSnapshot photo = AssetSnapshot.of(1L, new int[][]{{995, 100}, {4151, 1}});
		AssetSnapshot after = BankDraw.withdraw(photo, 1_000_000);
		assertEquals(0, after.coins());
		assertEquals(1, after.totalStacks()); // only the whip is left
	}
}
