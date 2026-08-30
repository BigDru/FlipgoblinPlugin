package com.flipgoblin;

import static org.junit.Assert.assertEquals;

import com.google.gson.Gson;
import org.junit.Test;

/** Pins the B1 asset-snapshot merge semantics + the Gson persistence round-trip. */
public class AssetSnapshotTest
{
	@Test
	public void mergesContainers_sumsDuplicates_dropsEmptyAndPlaceholders()
	{
		int[][] bank = {
			{995, 1_000_000}, // coins
			{4151, 3},
			{560, 0}, // bank placeholder (qty 0) — dropped
			{-1, 5}, // empty slot sentinel — dropped
		};
		int[][] inventory = {
			{995, 25_000}, // more coins in the backpack — sums with the bank stack
			{4151, 1},
			{2, 100},
		};
		AssetSnapshot snap = AssetSnapshot.of(1720000000000L, bank, inventory);

		assertEquals(3, snap.totalStacks()); // 995, 4151, 2
		assertEquals(1_025_000, snap.coins());
		assertEquals(1720000000000L, snap.timestamp);
		long whips = snap.entries.stream().filter(e -> e.itemId == 4151).findFirst().get().qty;
		assertEquals(4, whips);
	}

	@Test
	public void nullContainerIsSkipped_notFatal()
	{
		AssetSnapshot snap = AssetSnapshot.of(1L, new int[][]{{995, 10}}, null);
		assertEquals(1, snap.totalStacks());
		assertEquals(10, snap.coins());
	}

	@Test
	public void noCoinsMeansZero()
	{
		AssetSnapshot snap = AssetSnapshot.of(1L, new int[][]{{4151, 1}});
		assertEquals(0, snap.coins());
	}

	@Test
	public void extraCoins_mergeWithContainerCoins_beyondIntRange()
	{
		// GE escrow can exceed int max-cash — the long path must survive composition.
		long escrow = 3_000_000_000L;
		AssetSnapshot snap = AssetSnapshot.of(1L, escrow, new int[][]{{995, 500}, {4151, 1}});
		assertEquals(escrow + 500, snap.coins());
		assertEquals(2, snap.totalStacks());
	}

	@Test
	public void pairsRoundTrip_feedsRecomposition()
	{
		AssetSnapshot bank = AssetSnapshot.of(1L, new int[][]{{995, 1000}, {4151, 2}});
		// Frozen bank pairs + live inventory compose without duplication (distinct containers).
		AssetSnapshot composite = AssetSnapshot.of(2L, 0, bank.pairs(), new int[][]{{560, 50}});
		assertEquals(3, composite.totalStacks());
		assertEquals(1000, composite.coins());
	}

	@Test
	public void gsonRoundTrip_forProfilePersistence()
	{
		AssetSnapshot before = AssetSnapshot.of(1720000000000L,
			new int[][]{{995, 5_000}, {4151, 2}});
		Gson gson = new Gson();
		AssetSnapshot after = gson.fromJson(gson.toJson(before), AssetSnapshot.class);
		assertEquals(before.timestamp, after.timestamp);
		assertEquals(before.totalStacks(), after.totalStacks());
		assertEquals(before.coins(), after.coins());
		assertEquals(2, after.entries.stream().filter(e -> e.itemId == 4151).findFirst().get().qty);
	}
	@Test
	public void estimateValue_dashboardParity()
	{
		// coins face value + whip at bid net of 2% tax + exempt lobster at full bid; ruby unpriced.
		AssetSnapshot snap = AssetSnapshot.of(0L, 1_000L,
			new int[][]{{4151, 2}, {379, 10}, {1603, 5}});
		java.util.Map<Integer, Long> bids = new java.util.HashMap<>();
		bids.put(4151, 150L); // tax floor(3.0) => net 147/unit
		bids.put(379, 250L); // exempt => 250/unit
		long[] est = snap.estimateValue(bids);
		assertEquals(1_000 + 2 * 147 + 10 * 250, est[0]);
		assertEquals(1, est[1]); // the ruby stack has no live bid
		// No price map at all: coins still count, every non-coin stack is unpriced.
		long[] blind = snap.estimateValue(null);
		assertEquals(1_000, blind[0]);
		assertEquals(3, blind[1]);
	}
}
