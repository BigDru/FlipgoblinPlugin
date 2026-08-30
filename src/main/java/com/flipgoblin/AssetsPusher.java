package com.flipgoblin;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/**
 * Schedules and sends assets-snapshot pushes. Never push eagerly, never drop: placing an
 * offer emits inventory-shrank and offer-escrow events milliseconds apart, and each
 * recomposes the snapshot. Pushing the first would publish a dipped total, and a plain
 * throttle would drop the corrected body, so the dip would sit on the dashboard until the
 * next game event. Instead every recompose lands in {@link #submit} and a single deferred
 * drain pushes whatever is newest once the burst settles. An offers change cancels a
 * far-out drain and reschedules it inside the fast window.
 */
@Slf4j
final class AssetsPusher
{
	/** Failed pushes retry on this cadence until the state converges. */
	static final long RETRY_MS = 10_000;

	/** The plugin-side sender. It owns the token and lock gates. */
	interface Sender
	{
		enum Result
		{
			OK, FAIL, DISABLED
		}

		Result send(String body);
	}

	private final ScheduledExecutorService executor;
	private final Sender sender;

	private volatile long lastPushMs;
	private volatile String lastBody;
	/** Newest composed snapshot not yet pushed. Latest wins; overwritten freely on every recompose. */
	private volatile String pendingBody;
	/** The scheduled drain, if any. Replaced when a faster window wants an earlier push. */
	private ScheduledFuture<?> drain;
	private final Object drainLock = new Object();

	AssetsPusher(ScheduledExecutorService executor, Sender sender)
	{
		this.executor = executor;
		this.sender = sender;
	}

	/** Takes the newest composed body and schedules a drain for it. */
	void submit(String body)
	{
		pendingBody = body;
		boolean offersChanged =
			!SyncClient.offersSection(body).equals(SyncClient.offersSection(lastBody));
		schedule(SyncClient.assetDrainDelayMs(offersChanged, lastPushMs, System.currentTimeMillis()));
	}

	/**
	 * Executor-side half: pushes the newest settled snapshot, change-detected. Bookkeeping
	 * is success-gated: recording the body as pushed before the HTTP call would let one
	 * failed push make the change detector drop that state forever. A failure leaves the
	 * state pending and retries.
	 */
	private void drainPending()
	{
		String body = pendingBody;
		if (body == null || body.equals(lastBody))
		{
			return;
		}
		long started = System.currentTimeMillis();
		Sender.Result result = sender.send(body);
		long took = System.currentTimeMillis() - started;
		if (result == Sender.Result.DISABLED)
		{
			return;
		}
		if (result == Sender.Result.OK)
		{
			lastPushMs = System.currentTimeMillis();
			lastBody = body;
			// Debug, not info: this fires up to once a minute for a whole linked session.
			log.debug("[{}] assets push ok in {}ms ({} chars)", FlipGoblinPlugin.BUILD, took, body.length());
			// The body may have moved on while the push was in flight. Drain the newer
			// state on the fast floor instead of waiting for the next game event.
			if (!body.equals(pendingBody))
			{
				schedule(1_000);
			}
			return;
		}
		log.info("[{}] assets push FAILED after {}ms — retrying in {}s", FlipGoblinPlugin.BUILD, took,
			RETRY_MS / 1000);
		schedule(RETRY_MS);
	}

	/** A best-effort synchronous drain, for shutdown. */
	void flushNow()
	{
		drainPending();
	}

	/** Schedules a drain {@code delay} ms out, unless an earlier one is already on its way. */
	private void schedule(long delay)
	{
		synchronized (drainLock)
		{
			if (drain != null && !drain.isDone()
				&& drain.getDelay(TimeUnit.MILLISECONDS) <= delay)
			{
				return;
			}
			if (drain != null)
			{
				drain.cancel(false);
			}
			drain = executor.schedule(this::drainPending, delay, TimeUnit.MILLISECONDS);
		}
	}
}
