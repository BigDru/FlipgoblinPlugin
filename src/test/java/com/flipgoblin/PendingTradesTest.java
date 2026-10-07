package com.flipgoblin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import okhttp3.OkHttpClient;
import org.junit.Test;

public class PendingTradesTest
{
	/** A map in place of ConfigManager. */
	static final class MapStore implements PendingTrades.Store
	{
		final Map<String, String> saved = new HashMap<>();
		final Set<String> linked = new HashSet<>();

		@Override
		public String get(String profile)
		{
			return saved.get(profile);
		}

		@Override
		public void put(String profile, String json)
		{
			if (json == null)
			{
				saved.remove(profile);
			}
			else
			{
				saved.put(profile, json);
			}
		}

		@Override
		public boolean linked(String profile)
		{
			return linked.contains(profile);
		}
	}

	private final Gson gson = new Gson();
	private final MapStore store = new MapStore();
	private final PendingTrades trades = new PendingTrades(store, gson);

	private SyncClient client(String owner)
	{
		SyncClient s = new SyncClient(new OkHttpClient(), gson);
		s.claim(owner);
		return s;
	}

	private static TradeRecord fill(int itemId)
	{
		return new TradeRecord(itemId, TradeRecord.Side.BUY, 100, 1, 100, 0, 1720000000000L);
	}

	@Test
	public void savedFillsComeBackAfterARestart()
	{
		store.linked.add("A");
		SyncClient before = client("A");
		TradeRecord a = fill(1);
		TradeRecord b = fill(2);
		before.enqueue(a);
		before.enqueue(b);
		trades.save(before);

		SyncClient after = client("A");
		trades.load(after);
		List<TradeRecord> back = after.pendingSnapshot();
		assertEquals(2, back.size());
		assertEquals(a.clientId, back.get(0).clientId);
		assertEquals(b.clientId, back.get(1).clientId);
		assertEquals(b.price, back.get(1).price);
	}

	@Test
	public void eachCharacterKeepsItsOwnQueue()
	{
		store.linked.add("A");
		store.linked.add("B");
		SyncClient a = client("A");
		a.enqueue(fill(1));
		trades.save(a);
		SyncClient b = client("B");
		trades.load(b);
		assertEquals(0, b.pendingCount());
		b.enqueue(fill(2));
		trades.save(b);
		SyncClient a2 = client("A");
		trades.load(a2);
		assertEquals(1, a2.pendingCount());
		assertEquals(1, a2.pendingSnapshot().get(0).itemId);
	}

	@Test
	public void unlinkedOrEmptyQueuesAreNotSaved()
	{
		SyncClient s = client("A");
		s.enqueue(fill(1));
		trades.save(s);
		assertNull(store.saved.get("A")); // unlinked: it would never send

		store.linked.add("A");
		trades.save(s);
		assertTrue(store.saved.containsKey("A"));
		trades.save(client("A"));
		assertFalse(store.saved.containsKey("A")); // drained: the saved copy is removed
	}

	@Test
	public void restoreSkipsFillsAlreadyQueued()
	{
		store.linked.add("A");
		SyncClient s = client("A");
		s.enqueue(fill(1));
		trades.save(s);
		trades.load(s);
		assertEquals(1, s.pendingCount());
	}

	@Test
	public void tooManyKeepsTheNewest()
	{
		store.linked.add("A");
		SyncClient s = client("A");
		for (int i = 0; i < PendingTrades.MAX_SAVED + 3; i++)
		{
			s.enqueue(fill(i));
		}
		trades.save(s);
		SyncClient back = client("A");
		trades.load(back);
		assertEquals(PendingTrades.MAX_SAVED, back.pendingCount());
		assertEquals(3, back.pendingSnapshot().get(0).itemId);
	}

	@Test
	public void unreadableSaveIsSkipped()
	{
		store.saved.put("A", "{not json");
		SyncClient s = client("A");
		trades.load(s);
		assertEquals(0, s.pendingCount());
	}
}
