package com.flipgoblin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Drives the gate through a real OkHttp client against a local HTTP server. */
public class UpdateGateTest
{
	private HttpServer server;
	private String base;
	private final AtomicInteger hits = new AtomicInteger();
	private final AtomicInteger status = new AtomicInteger(200);
	private final AtomicReference<String> seenVersion = new AtomicReference<>();

	@Before
	public void start() throws Exception
	{
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", ex ->
		{
			hits.incrementAndGet();
			seenVersion.set(ex.getRequestHeaders().getFirst(UpdateGate.HEADER));
			byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
			ex.sendResponseHeaders(status.get(), body.length);
			ex.getResponseBody().write(body);
			ex.close();
		});
		server.start();
		base = "http://127.0.0.1:" + server.getAddress().getPort();
	}

	@After
	public void stop()
	{
		server.stop(0);
	}

	private int get(OkHttpClient http, String url) throws Exception
	{
		try (Response r = http.newCall(new Request.Builder().url(url).build()).execute())
		{
			return r.code();
		}
	}

	@Test
	public void sendsTheVersionToOurApiOnly() throws Exception
	{
		UpdateGate gate = new UpdateGate("2026.10.06", base + "/", () -> { });
		OkHttpClient http = new OkHttpClient.Builder().addInterceptor(gate).build();
		assertEquals(200, get(http, base + "/plugin/me"));
		assertEquals("2026.10.06", seenVersion.get());

		UpdateGate other = new UpdateGate("2026.10.06", "https://flipgoblin-api.example", () -> { });
		OkHttpClient otherHttp = new OkHttpClient.Builder().addInterceptor(other).build();
		assertEquals(200, get(otherHttp, base + "/anything"));
		assertNull(seenVersion.get()); // not our API: no header
	}

	@Test
	public void a426PausesEveryLaterCallWithoutTheNetwork() throws Exception
	{
		AtomicInteger fired = new AtomicInteger();
		UpdateGate gate = new UpdateGate("2026.10.06", base, fired::incrementAndGet);
		OkHttpClient http = new OkHttpClient.Builder().addInterceptor(gate).build();
		status.set(426);
		assertEquals(426, get(http, base + "/plugin/flips"));
		assertTrue(gate.required());
		assertEquals(1, fired.get());
		int before = hits.get();
		status.set(200); // even if the server changed its mind, the session stays paused
		assertEquals(426, get(http, base + "/plugin/flips"));
		assertEquals(426, get(http, base + "/prices"));
		assertEquals(before, hits.get());
		assertEquals(1, fired.get());
	}

	@Test
	public void syncKeepsItsQueueOn426() throws Exception
	{
		UpdateGate gate = new UpdateGate("2026.10.06", base, () -> { });
		OkHttpClient http = new OkHttpClient.Builder().addInterceptor(gate).build();
		SyncClient sync = new SyncClient(http, new Gson());
		sync.enqueue(new TradeRecord(4151, TradeRecord.Side.BUY, 1_000_000, 1, 1_000_000, 0, 1720000000000L));
		status.set(426);
		assertFalse(sync.flush(base, "flipgoblin_test", "Char"));
		assertEquals(1, sync.pendingCount()); // held for after the update, never dropped
		status.set(400);
		UpdateGate fresh = new UpdateGate("2026.10.06", base, () -> { });
		SyncClient plain = new SyncClient(new OkHttpClient.Builder().addInterceptor(fresh).build(), new Gson());
		plain.enqueue(new TradeRecord(4151, TradeRecord.Side.BUY, 1_000_000, 1, 1_000_000, 0, 1720000000000L));
		plain.flush(base, "flipgoblin_test", "Char");
		assertEquals(0, plain.pendingCount()); // a real 400 still drops the bad batch
	}

	@Test
	public void rejectsForGood_onlyForBadRequests()
	{
		assertTrue(UpdateGate.rejectsForGood(400));
		assertTrue(UpdateGate.rejectsForGood(404));
		assertFalse(UpdateGate.rejectsForGood(401)); // token problem: hold
		assertFalse(UpdateGate.rejectsForGood(426)); // update needed: hold
		assertFalse(UpdateGate.rejectsForGood(429)); // rate limited: hold
		assertFalse(UpdateGate.rejectsForGood(503)); // server trouble: hold
		assertFalse(UpdateGate.rejectsForGood(200));
	}
}
