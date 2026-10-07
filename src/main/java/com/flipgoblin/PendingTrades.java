package com.flipgoblin;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Saves each character's unsent fills in its RuneLite profile, so a restart or a plugin
 * update never loses them. Only linked characters are saved: an unlinked queue never sends.
 */
@Slf4j
final class PendingTrades
{
	/** Where the saved queue lives. The plugin backs this with ConfigManager. */
	interface Store
	{
		String get(String profile);

		/** Saves the queue, or removes it when {@code json} is null. */
		void put(String profile, String json);

		boolean linked(String profile);
	}

	static final int MAX_SAVED = 5000;
	private static final Type TYPE = new TypeToken<List<TradeRecord>>() { }.getType();

	private final Store store;
	private final Gson gson;

	PendingTrades(Store store, Gson gson)
	{
		this.store = store;
		this.gson = gson;
	}

	/** Saves the queue under its character. Keeps the newest fills if there are too many. */
	void save(SyncClient sync)
	{
		String owner = sync.owner();
		if (owner == null)
		{
			return;
		}
		List<TradeRecord> queue = sync.pendingSnapshot();
		if (queue.isEmpty() || !store.linked(owner))
		{
			store.put(owner, null);
			return;
		}
		if (queue.size() > MAX_SAVED)
		{
			queue = queue.subList(queue.size() - MAX_SAVED, queue.size());
		}
		store.put(owner, gson.toJson(queue, TYPE));
	}

	/** Adds the character's saved fills back to its queue. */
	void load(SyncClient sync)
	{
		String owner = sync.owner();
		String json = owner == null ? null : store.get(owner);
		if (json == null || json.isEmpty())
		{
			return;
		}
		try
		{
			List<TradeRecord> saved = gson.fromJson(json, TYPE);
			if (saved != null)
			{
				sync.restore(saved);
				log.debug("restored {} unsent fills", saved.size());
			}
		}
		catch (JsonParseException e)
		{
			log.warn("saved unsent fills are unreadable, skipping them", e);
		}
	}
}
