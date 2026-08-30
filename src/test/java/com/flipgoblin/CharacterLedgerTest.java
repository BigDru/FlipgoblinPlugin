package com.flipgoblin;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/**
 * Pins the all-characters aggregation (panel scope, 2026-07-23): per-character FIFO isolation,
 * the read-time 7d horizon, and bank-photo valuation against the shared bulk-bid map.
 */
public class CharacterLedgerTest
{
	private static final int WHIP = 4151;
	private static final long NOW = 1_760_000_000_000L;
	private static final long HORIZON = 7L * 24 * 3_600_000;

	private static TradeRecord rec(TradeRecord.Side side, long price, int qty, long ts)
	{
		return new TradeRecord(WHIP, side, price, qty, price * qty, 0, ts);
	}

	private static CharacterLedger.Character chr(String name, java.util.List<TradeRecord> recs,
		AssetSnapshot bank)
	{
		return new CharacterLedger.Character(name, recs, bank);
	}

	@Test
	public void realizedIsMatchedPerCharacter_neverMergedFifo()
	{
		// A bought, B sold — merged into one FIFO this would "realize" profit; per character the
		// buy stays open and the sell is untracked, so the honest aggregate realizes ZERO.
		CharacterLedger.Totals t = CharacterLedger.aggregate(Arrays.asList(
			chr("A", Collections.singletonList(rec(TradeRecord.Side.BUY, 100, 1, NOW - 1000)), null),
			chr("B", Collections.singletonList(rec(TradeRecord.Side.SELL, 200, 1, NOW - 500)), null)),
			null, NOW, HORIZON);
		assertEquals(0, t.realized7d);
		assertEquals(2, t.characters);
		assertEquals("A · B", t.names);
	}

	@Test
	public void realizedSumsCompletedFlipsAcrossCharacters()
	{
		// Each character flips independently: buy 100 → sell 200 nets 200 - tax(4) - 100 = 96.
		java.util.List<TradeRecord> flip = Arrays.asList(
			rec(TradeRecord.Side.BUY, 100, 1, NOW - 2000),
			rec(TradeRecord.Side.SELL, 200, 1, NOW - 1000));
		CharacterLedger.Totals t = CharacterLedger.aggregate(
			Arrays.asList(chr("A", flip, null), chr("B", flip, null)), null, NOW, HORIZON);
		assertEquals(192, t.realized7d);
	}

	@Test
	public void horizonReappliesAtReadTime()
	{
		// The stored history was trimmed at the character's LAST persist — a fill now older than
		// 7d must fall out of the sum even though it survived in storage.
		CharacterLedger.Totals t = CharacterLedger.aggregate(Collections.singletonList(
			chr("A", Arrays.asList(
				rec(TradeRecord.Side.BUY, 100, 1, NOW - HORIZON - 2000),
				rec(TradeRecord.Side.SELL, 200, 1, NOW - HORIZON - 1000)),
				null)),
			null, NOW, HORIZON);
		assertEquals(0, t.realized7d);
	}

	@Test
	public void bankPhotosValueAtBidsNetOfTax_andSum()
	{
		Map<Integer, Long> bids = new HashMap<>();
		bids.put(WHIP, 200L);
		AssetSnapshot bankA = AssetSnapshot.of(NOW, new int[][]{{WHIP, 2}, {995, 50}});
		AssetSnapshot bankB = AssetSnapshot.of(NOW, new int[][]{{WHIP, 1}, {9999, 3}});
		CharacterLedger.Totals t = CharacterLedger.aggregate(
			Arrays.asList(chr("A", null, bankA), chr("B", null, bankB)), bids, NOW, HORIZON);
		// A: 2 × net(200)=196 → 392, +50 coins = 442; B: 196, id 9999 unpriced.
		assertEquals(442 + 196, t.bankEst);
		assertEquals(1, t.unpriced);
	}

	@Test
	public void noBidMapMeansNoValuation()
	{
		AssetSnapshot bank = AssetSnapshot.of(NOW, new int[][]{{WHIP, 2}});
		CharacterLedger.Totals t = CharacterLedger.aggregate(
			Collections.singletonList(chr("A", null, bank)), null, NOW, HORIZON);
		assertEquals(-1, t.bankEst);
		assertEquals(0, t.unpriced);
	}
}
