package com.flipgoblin;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * The opt-in trade sync client. Sends captured fills to the user's Flip Goblin account
 * ({@code POST /plugin/flips} with a bearer token) using RuneLite's shared OkHttp.
 *
 * Fills queue locally. Each flush sends the whole pending batch and only clears it on a
 * 2xx. A failure keeps the queue intact for the next attempt, which is safe because every
 * record carries a clientId UUID and the server dedups on (user, clientId), so re-sending
 * changes nothing. A 4xx means the batch itself is bad (a bug, not a passing failure), so
 * it is logged loudly and dropped rather than wedging the queue forever. Batches cap at
 * the server's limit of 500.
 *
 * COMPLIANCE: this only runs when the user has linked their account, and the token config
 * item carries the full disclosure. The payload is exactly the disclosed fields: item,
 * side, price, quantity, time, GE slot, and the dedup id.
 */
@Slf4j
public final class SyncClient
{
	static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	static final int MAX_BATCH = 500;

	private final OkHttpClient http;
	private final Gson gson = new Gson();
	private final Deque<TradeRecord> pending = new ArrayDeque<>();
	// The crowdsource stream keeps its own queue, so trade sync and price contribution
	// never share a fate. Both flow under the one account link. Events age out client-side
	// (the server rejects stale timestamps anyway), so an offline evening never floods
	// rejects on login.
	private final Deque<TradeRecord> crowdPending = new ArrayDeque<>();
	static final int CROWD_MAX_BATCH = 100;
	static final long CROWD_MAX_AGE_MS = 60_000; // server slack is ±90s; stay comfortably inside

	public SyncClient(OkHttpClient http)
	{
		this.http = http;
	}

	/** The account link's server-side verdict (the /plugin/me witness route). */
	public enum LinkCheck
	{
		/** Token valid and this character is active — full operation. */
		OK,
		/** Lapse lock: more linked characters than the account's tier allows. Refuse all operation. */
		LOCKED,
		/** The token is unknown or revoked. Syncs would fail with a 401 anyway. */
		INVALID,
		/** Network or server trouble. Keep the previous state rather than flapping on a blip. */
		UNREACHABLE
	}

	/** True when the 401 body is the lapse lock rather than a bad token. Pure. */
	static boolean lockedBody(int code, String body)
	{
		return code == 401 && body != null && body.contains("character locked");
	}

	/**
	 * Asks the server whether this token operates (GET /plugin/me). Blocking, so call it
	 * from the executor. The lock check is the point: a locked character must show as
	 * locked and go dark, not silently queue forever.
	 */
	public LinkCheck checkLink(String apiBase, String token)
	{
		Request request = new Request.Builder()
			.url(apiBase.replaceAll("/+$", "") + "/plugin/me")
			.header("Authorization", "Bearer " + token)
			.build();
		try (Response res = http.newCall(request).execute())
		{
			if (res.isSuccessful())
			{
				return LinkCheck.OK;
			}
			String body = res.body() == null ? "" : res.body().string();
			if (lockedBody(res.code(), body))
			{
				return LinkCheck.LOCKED;
			}
			return res.code() == 401 ? LinkCheck.INVALID : LinkCheck.UNREACHABLE;
		}
		catch (IOException e)
		{
			log.debug("link check unreachable: {}", e.toString());
			return LinkCheck.UNREACHABLE;
		}
	}

	/** Queues a fill for the next flush. Client thread only, like the differ. */
	public void enqueue(TradeRecord record)
	{
		pending.addLast(record);
	}

	public int pendingCount()
	{
		return pending.size();
	}

	/** Queues a live fill for the crowd stream. Recovered fills never qualify; their times are stale. */
	public void enqueueCrowd(TradeRecord record)
	{
		if (!record.recovered)
		{
			crowdPending.addLast(record);
		}
	}

	public int crowdPendingCount()
	{
		return crowdPending.size();
	}

	/** The exact wire payload for one batch. Pinned by unit tests against the server contract. */
	static JsonObject buildPayload(List<TradeRecord> batch)
	{
		JsonArray arr = new JsonArray();
		for (TradeRecord r : batch)
		{
			JsonObject o = new JsonObject();
			o.addProperty("itemId", r.itemId);
			o.addProperty("side", r.side == TradeRecord.Side.BUY ? "buy" : "sell");
			o.addProperty("price", r.price);
			o.addProperty("qty", r.quantity);
			o.addProperty("ts", r.timestamp);
			if (r.slot >= 0)
			{
				o.addProperty("geSlot", r.slot);
			}
			if (r.recovered)
			{
				// Detected at login rather than observed live: ts is the detection time, and the
				// true fill happened between offlineSince and ts. The dashboard renders these honestly.
				o.addProperty("recovered", true);
				if (r.offlineSince > 0)
				{
					o.addProperty("offlineSince", r.offlineSince);
				}
			}
			o.addProperty("clientId", r.clientId);
			arr.add(o);
		}
		JsonObject body = new JsonObject();
		body.add("flips", arr);
		return body;
	}

	/** Bank and inventory churn is noise. One push a minute is plenty. */
	static final long ASSET_SYNC_WINDOW_MS = 60_000;
	/** Offer-board changes are user actions, and the dashboard should show a placed or
	 * changed order on its next poll, so they fast-path the throttle. 3 seconds still
	 * coalesces the burst of events one placed order fires. */
	static final long OFFER_SYNC_WINDOW_MS = 3_000;

	/** The offers section of an assets body. {@link #assetsJson} emits it last. Pure. */
	static String offersSection(String body)
	{
		if (body == null)
		{
			return "";
		}
		int i = body.indexOf("\"offers\":");
		return i < 0 ? "" : body.substring(i);
	}

	/** The delay before the next assets push. Pure. */
	static long assetDrainDelayMs(boolean offersChanged, long lastPushMs, long now)
	{
		long window = offersChanged ? OFFER_SYNC_WINDOW_MS : ASSET_SYNC_WINDOW_MS;
		long floor = offersChanged ? 1_000 : 2_000; // still let the event burst settle
		return Math.max(floor, lastPushMs + window - now);
	}

	/**
	 * The assets snapshot payload: (item, quantity) pairs with coins riding under the coin
	 * item id, the bank capture time when one exists, and the live GE offers board. The
	 * offers list is always present, and [] means no standing offers. Pure and
	 * unit-testable; it mirrors the server's parser.
	 */
	static String assetsJson(AssetSnapshot snapshot, long bankTs, boolean bankFresh,
		List<GePositions.Position> offers)
	{
		JsonObject body = new JsonObject();
		JsonArray pairs = new JsonArray();
		for (AssetSnapshot.Entry e : snapshot.entries)
		{
			JsonArray pair = new JsonArray();
			pair.add(e.itemId);
			pair.add(e.qty);
			pairs.add(pair);
		}
		body.add("pairs", pairs);
		if (bankTs > 0)
		{
			body.addProperty("bankAt", bankTs);
		}
		// False means the bank part is not trusted this session (neither witnessed nor
		// carried by an ACQUITTED custody chain), so the server must not record a history
		// point from this body.
		body.addProperty("bankFresh", bankFresh);
		JsonArray arr = new JsonArray();
		for (GePositions.Position p : offers)
		{
			JsonObject o = new JsonObject();
			o.addProperty("slot", p.slot);
			o.addProperty("itemId", p.itemId);
			o.addProperty("side", p.side == TradeRecord.Side.BUY ? "buy" : "sell");
			o.addProperty("price", p.price);
			o.addProperty("total", p.totalQuantity);
			o.addProperty("filled", p.quantitySold);
			o.addProperty("phase", p.phase == GePositions.Phase.WORKING ? "working"
				: p.phase == GePositions.Phase.COMPLETE ? "done" : "cancelled");
			arr.add(o);
		}
		body.add("offers", arr);
		return body.toString();
	}

	/**
	 * One-shot assets push (POST /plugin/assets). The server keeps only the latest, so
	 * there is no queue, and a failed push simply waits for the next change. Blocking;
	 * call from the executor.
	 */
	public boolean pushAssets(String apiBase, String token, String character, String bodyJson)
	{
		Request.Builder rb = new Request.Builder()
			.url(apiBase.replaceAll("/+$", "") + "/plugin/assets")
			.header("Authorization", "Bearer " + token)
			.post(RequestBody.create(JSON, bodyJson));
		if (character != null && !character.isEmpty())
		{
			rb.header("X-FlipGoblin-Character", character); // a display label only; the token is the identity
		}
		Request request = rb.build();
		try (Response res = http.newCall(request).execute())
		{
			if (!res.isSuccessful())
			{
				log.debug("assets push failed with HTTP {}", res.code());
			}
			return res.isSuccessful();
		}
		catch (java.io.IOException e)
		{
			log.debug("assets push failed: {}", e.toString());
			return false;
		}
	}

	/** The crowd wire payload: exactly the disclosed fields. The GE slot is deliberately absent. */
	static JsonObject buildCrowdPayload(List<TradeRecord> batch)
	{
		JsonArray arr = new JsonArray();
		for (TradeRecord r : batch)
		{
			JsonObject o = new JsonObject();
			o.addProperty("itemId", r.itemId);
			o.addProperty("side", r.side == TradeRecord.Side.BUY ? "buy" : "sell");
			o.addProperty("price", r.price);
			o.addProperty("quantity", r.quantity);
			o.addProperty("ts", r.timestamp / 1000); // wire is unix SECONDS
			o.addProperty("clientId", r.clientId);
			arr.add(o);
		}
		JsonObject body = new JsonObject();
		body.add("events", arr);
		return body;
	}

	/**
	 * Flushes the crowd queue (POST /crowd/prices). Best effort: stale events are dropped
	 * client-side, and server-side skips are the server's call and never retried. Blocking;
	 * call from the executor.
	 */
	public boolean flushCrowd(String apiBase, String token)
	{
		long cutoff = System.currentTimeMillis() - CROWD_MAX_AGE_MS;
		while (!crowdPending.isEmpty() && crowdPending.peekFirst().timestamp < cutoff)
		{
			crowdPending.pollFirst(); // too old to pass the server's timestamp window; not worth a request
		}
		if (crowdPending.isEmpty())
		{
			return true;
		}
		List<TradeRecord> batch = new ArrayList<>();
		int n = Math.min(crowdPending.size(), CROWD_MAX_BATCH);
		java.util.Iterator<TradeRecord> it = crowdPending.iterator();
		for (int i = 0; i < n; i++)
		{
			batch.add(it.next());
		}
		Request request = new Request.Builder()
			.url(apiBase.replaceAll("/+$", "") + "/crowd/prices")
			.header("Authorization", "Bearer " + token)
			.post(RequestBody.create(JSON, gson.toJson(buildCrowdPayload(batch))))
			.build();
		try (Response res = http.newCall(request).execute())
		{
			if (res.isSuccessful())
			{
				for (int i = 0; i < n; i++)
				{
					crowdPending.pollFirst();
				}
				return crowdPending.isEmpty();
			}
			if (res.code() >= 400 && res.code() < 500 && res.code() != 401 && res.code() != 429)
			{
				log.warn("crowd submit rejected with HTTP {} — dropping {} events", res.code(), n);
				for (int i = 0; i < n; i++)
				{
					crowdPending.pollFirst();
				}
			}
			return false;
		}
		catch (IOException e)
		{
			log.debug("crowd submit unreachable — will retry: {}", e.toString());
			return false;
		}
	}

	/**
	 * Sends everything pending, up to MAX_BATCH; callers loop if needed. Blocking, so call
	 * it from the executor. Returns true when the queue drained.
	 */
	public boolean flush(String apiBase, String token, String character)
	{
		if (pending.isEmpty())
		{
			return true;
		}
		List<TradeRecord> batch = new ArrayList<>();
		int n = Math.min(pending.size(), MAX_BATCH);
		java.util.Iterator<TradeRecord> it = pending.iterator();
		for (int i = 0; i < n; i++)
		{
			batch.add(it.next());
		}

		Request.Builder rb = new Request.Builder()
			.url(apiBase.replaceAll("/+$", "") + "/plugin/flips")
			.header("Authorization", "Bearer " + token)
			.post(RequestBody.create(JSON, gson.toJson(buildPayload(batch))));
		if (character != null && !character.isEmpty())
		{
			rb.header("X-FlipGoblin-Character", character); // a display label only; the token is the identity
		}
		Request request = rb.build();
		try (Response res = http.newCall(request).execute())
		{
			if (res.isSuccessful())
			{
				for (int i = 0; i < n; i++)
				{
					pending.pollFirst();
				}
				log.debug("synced {} fills ({} still pending)", n, pending.size());
				return pending.isEmpty();
			}
			if (res.code() >= 400 && res.code() < 500 && res.code() != 401 && res.code() != 429)
			{
				// The batch itself is malformed (a bug). Drop it rather than wedge the queue forever.
				log.warn("sync rejected with HTTP {} — dropping {} records", res.code(), n);
				for (int i = 0; i < n; i++)
				{
					pending.pollFirst();
				}
				return false;
			}
			log.debug("sync failed with HTTP {} — will retry ({} pending)", res.code(), pending.size());
			return false;
		}
		catch (IOException e)
		{
			log.debug("sync unreachable — will retry ({} pending): {}", pending.size(), e.toString());
			return false;
		}
	}
}
