package com.flipgoblin;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;

/**
 * The login-custody state machine. It records when our sessions end, compares each fresh
 * login's welcome-screen report against that record ({@link LoginCustody} holds the pure
 * parse and judge), and carries the verdict, the banner state, and the bank-trust chain
 * that the overlays and the asset ledger read.
 *
 * All mutation happens on the client thread. The volatile fields feed render threads.
 * The plugin forwards the raw events and reacts to the two {@link Host} callbacks; the
 * consequences for the collect ledger live there, not here.
 */
@Slf4j
final class CustodyTracker
{
	/** What the plugin does with custody outcomes. All run on the client thread. */
	interface Host
	{
		/** A real fresh login started a new custody window. Reset the un-judged state. */
		void onNewCustodyWindow();

		/** The judge ran. Apply the acquittal payoff, or the assume-collected floor. */
		void onJudged(boolean acquitted);

		/** Trust was checked at login or changed. Send this body to the server's trust log. */
		void onCustodyEvent(String bodyJson);

		/** A mid-session restart kept the saved trust. Reload what a login would have restored. */
		void onRestartCarried();
	}

	private static final String SESSION_KEY = "sessionRecord";

	private final ConfigManager configManager;
	private final Gson gson;
	private final ChatMessageManager chatMessageManager;
	private final Host host;

	/** This session's record (mutable; heartbeat-saved). Null until first tick/login. */
	private LoginCustody.SessionRecord sessionRecord;
	/** The previous session's record, captured at fresh login before the store is overwritten. */
	private LoginCustody.SessionRecord prevSession;
	private boolean prevSessionLoaded;
	/** True only when the login came through the login screen. A CONNECTION_LOST reconnect
	 * skips it, shows no welcome screen, and must keep the session's custody state. */
	private boolean wasAtLoginScreen;
	private long lastHeartbeatMs;
	/** Wall-clock of the last GameTick, the only trustworthy "session was alive" instant. A
	 * frozen or disconnected client can sit logged in for an hour without ticks, so logout
	 * witnesses must anchor here, never on when handlers ran. */
	private long lastGameTickMs;
	/** Welcome-screen capture. WidgetLoaded and LOGGED_IN can arrive in either order, so the
	 * text is stashed and the judge runs when both halves are in (see maybeJudge). */
	private boolean welcomeLoaded;
	private String welcomeText;
	/** The instant the welcome text was read, and the judge's time anchor. The screen's
	 * "…ago" string is static while the user sits at the play button, so a judge that runs
	 * later must compare durations at capture time, not at its own clock. Every idle minute
	 * would otherwise read as phantom skew on our side only. */
	private long welcomeCapturedMs;
	private boolean judged;
	/** Ticks since a fresh login while no welcome screen has shown, or -1 when not watching. */
	private int noWelcomeTicks = -1;
	private boolean consoleQueued;
	private volatile boolean welcomeVisible;
	private volatile String detail;
	private volatile LoginCustody.Verdict verdict;
	/** The main-overlay banner window. Overlays cannot draw before the LOGGED_IN state, so a
	 * welcome screen shown before it never gets the CustodyOverlay. The verdict therefore
	 * also rides the main overlay for the first minute in-world, which is always drawable. */
	private volatile long bannerUntilMs;
	/** The bank snapshot's custody chain: set at bank-open, and it survives a logout only
	 * through an ACQUITTED verdict. While true, the stored bank counts in the estimate and
	 * syncs carry bankFresh=true, so history flows immediately with no bank-open gate. */
	private volatile boolean bankChainTrusted;
	/** Why the bank copy is not trusted, in plain words for the panel. Null when trusted or unknown. */
	private volatile String untrustedReason;

	CustodyTracker(ConfigManager configManager, Gson gson, ChatMessageManager chatMessageManager, Host host)
	{
		this.configManager = configManager;
		this.gson = gson;
		this.chatMessageManager = chatMessageManager;
		this.host = host;
	}

	/** Seeds the login-screen flag when the plugin comes up already at the login screen. */
	void seedAtStartUp(boolean atLoginScreen)
	{
		wasAtLoginScreen = atLoginScreen;
	}

	/**
	 * The per-tick heartbeat. lastSeen is our logout witness for crash exits; a clean
	 * logout stamps it exactly. Staleness only makes the next login's check stricter,
	 * which is the safe direction. Returns true when the 60-second save just fired, so
	 * the caller can piggyback its own staleness saves.
	 */
	boolean heartbeat(long now)
	{
		lastGameTickMs = now;
		watchForWelcome(now);
		if (now - lastHeartbeatMs < 60_000)
		{
			return false;
		}
		lastHeartbeatMs = now;
		if (sessionRecord == null)
		{
			// Plugin enabled mid-session. A quick restart (a Hub update, a toggle) keeps the
			// saved record and its bank trust, because only a login should break trust.
			LoginCustody.SessionRecord stored = loadSessionRecord();
			if (carriesAcrossRestart(stored, now))
			{
				sessionRecord = stored;
				bankChainTrusted = true;
				untrustedReason = null;
				host.onRestartCarried();
			}
			else
			{
				// Login instants unknown (0). The next login judges off this record.
				sessionRecord = new LoginCustody.SessionRecord();
				if (!bankChainTrusted)
				{
					untrustedReason = "the plugin started while you were logged in";
				}
			}
			host.onCustodyEvent(eventJson("plugin_start", null, bankChainTrusted, null, null, null, now));
		}
		sessionRecord.lastSeenMs = now;
		persistSessionRecord();
		return true;
	}

	/** Ticks a fresh login waits for the welcome screen before calling the login unchecked. */
	static final int NO_WELCOME_TICKS = 10;

	/**
	 * A fresh login whose welcome screen never shows cannot be checked, and trust stays off.
	 * Say so in the log, the chat, and the server's trust log, so the gap is never silent.
	 * A welcome screen that shows up later still runs the check as normal.
	 */
	private void watchForWelcome(long now)
	{
		if (noWelcomeTicks < 0 || judged)
		{
			return;
		}
		if (welcomeLoaded)
		{
			noWelcomeTicks = -1;
			return;
		}
		if (++noWelcomeTicks < NO_WELCOME_TICKS)
		{
			return;
		}
		noWelcomeTicks = -1;
		Boolean prevChain = prevSession == null ? null : prevSession.bankChainTrusted;
		Long gapMs = prevSession == null || prevSession.lastSeenMs <= 0 ? null : now - prevSession.lastSeenMs;
		untrustedReason = "the game skipped the welcome screen, so this login couldn't be checked";
		log.warn("[{}] custody: no welcome screen after a fresh login, check skipped (prevChain={}, gap={}s)",
			FlipGoblinPlugin.BUILD, prevChain, gapMs == null ? "?" : gapMs / 1000);
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.value("Flip Goblin custody: the game skipped the welcome screen, so this login couldn't be checked. "
				+ "<col=ff0000>Open your bank once to restore full net worth.</col>")
			.build());
		host.onCustodyEvent(eventJson("login_unchecked", null, false, prevChain, null, gapMs, now));
	}

	/** How old the saved record may be for a mid-session start to keep its bank trust. */
	static final long RESTART_CARRY_MS = 5 * 60_000;

	/**
	 * True when a plugin started mid-session can keep the saved record's bank trust: the
	 * record was trusted and was saved moments ago, so the client stayed logged in through
	 * the restart. The heartbeat saves every minute, so a plain restart is always inside.
	 */
	static boolean carriesAcrossRestart(LoginCustody.SessionRecord stored, long now)
	{
		return stored != null && stored.bankChainTrusted && stored.lastSeenMs > 0
			&& now >= stored.lastSeenMs && now - stored.lastSeenMs <= RESTART_CARRY_MS;
	}

	/**
	 * Leaving the world (login screen or hop): stamp the logout instant and drop the
	 * welcome stash. The verdict and chain stay in memory across a hop; a real logout's
	 * next fresh login resets them in {@link #onLogin}.
	 */
	void onLeaveWorld(boolean toLoginScreen)
	{
		wasAtLoginScreen |= toLoginScreen;
		welcomeVisible = false;
		welcomeLoaded = false;
		welcomeText = null;
		welcomeCapturedMs = 0;
		if (sessionRecord != null)
		{
			// Logout witness = the last real tick, never "now": the login screen can appear
			// an hour after a silent connection death (frozen client, machine sleep), and
			// stamping wall-clock then destroys the witness the judge needs. Clean logouts
			// tick until the last moment, so anchoring on the tick costs under a second of
			// precision there. If no tick was ever seen this process, keep whatever the
			// record already holds.
			if (lastGameTickMs > 0)
			{
				sessionRecord.lastSeenMs = lastGameTickMs;
			}
			persistSessionRecord();
		}
	}

	/**
	 * A login landed. Only a real fresh login (through the login screen) starts a new
	 * custody window: capture the previous session's record before overwriting it, reset
	 * the verdict, and judge once the welcome text is in (either event order). A
	 * CONNECTION_LOST reconnect also lands here but shows no welcome screen; it keeps the
	 * session's verdict and chain and just stamps the re-login.
	 */
	void onLogin()
	{
		if (!wasAtLoginScreen)
		{
			log.info("[{}] custody: reconnect, trust kept ({})", FlipGoblinPlugin.BUILD, bankChainTrusted);
			recordLogin(false);
			return;
		}
		wasAtLoginScreen = false;
		prevSession = loadSessionRecord();
		prevSessionLoaded = true;
		judged = false;
		consoleQueued = false;
		verdict = null;
		detail = null;
		bankChainTrusted = false; // gated until the judge (or a bank-open) says otherwise
		untrustedReason = "the login check hasn't finished yet";
		host.onNewCustodyWindow();
		sessionRecord = new LoginCustody.SessionRecord();
		noWelcomeTicks = 0;
		log.info("[{}] custody: fresh login, check pending", FlipGoblinPlugin.BUILD);
		recordLogin(true);
		maybeJudge();
	}

	/** Stamps a login instant. Hops and reconnects stamp any-login; a fresh login also sets fresh. */
	void recordLogin(boolean freshLogin)
	{
		long now = Instant.now().toEpochMilli();
		if (sessionRecord == null)
		{
			sessionRecord = new LoginCustody.SessionRecord();
		}
		sessionRecord.anyLoginMs = now;
		sessionRecord.lastSeenMs = now;
		if (freshLogin)
		{
			sessionRecord.freshLoginMs = now;
		}
		persistSessionRecord();
	}

	/** The welcome screen loaded; its text is provably populated at WidgetLoaded time. */
	void onWelcomeLoaded(String text)
	{
		welcomeVisible = true;
		welcomeLoaded = true;
		welcomeText = text;
		welcomeCapturedMs = Instant.now().toEpochMilli();
		maybeJudge();
	}

	/** Click-through: the login-screen overlay stops with the screen; the console mirror fires once. */
	void onWelcomeClosed()
	{
		welcomeVisible = false;
		if (judged)
		{
			// Re-arm the main-overlay banner from click-through: the judge fires when the
			// welcome screen loads, so idling at the play button would otherwise burn the
			// banner window before the player ever enters the world.
			bannerUntilMs = System.currentTimeMillis() + 60_000;
			queueConsole();
		}
	}

	/** Witnessing the bank restarts its custody chain from this moment. */
	void trustBankChain()
	{
		if (!bankChainTrusted)
		{
			host.onCustodyEvent(eventJson("bank_open", null, true, null, null, null,
				Instant.now().toEpochMilli()));
		}
		bankChainTrusted = true;
		untrustedReason = null;
		persistSessionRecord();
	}

	boolean bankChainTrusted()
	{
		return bankChainTrusted;
	}

	String untrustedReason()
	{
		return untrustedReason;
	}

	/** True once this login's judge has run (or a reconnect kept the previous run). */
	boolean judged()
	{
		return judged;
	}

	// --- overlay reads (render thread; the fields are volatile, written on the client thread) ---

	String overlayDetail()
	{
		return detail;
	}

	LoginCustody.Verdict overlayVerdict()
	{
		return verdict;
	}

	boolean welcomeScreenVisible()
	{
		return welcomeVisible;
	}

	/** True while the main overlay should carry the custody banner (first minute in-world). */
	boolean bannerActive()
	{
		return verdict != null && System.currentTimeMillis() < bannerUntilMs;
	}

	/**
	 * The judgement, run exactly once per fresh login when both halves are in: the
	 * previous session record (loaded at login) and the welcome screen text. The two
	 * events' order is not guaranteed, so each caller retries the join. ACQUITTED carries
	 * the bank chain across the logout; anything else keeps the gated regime until the
	 * first bank-open. Client thread.
	 */
	private void maybeJudge()
	{
		if (judged || !prevSessionLoaded || !welcomeLoaded)
		{
			return;
		}
		judged = true;
		noWelcomeTicks = -1;
		long now = Instant.now().toEpochMilli();
		// Anchor at text-capture time, never judge time: the "…ago" string froze when the
		// screen rendered (see welcomeCapturedMs).
		long anchor = welcomeCapturedMs > 0 ? welcomeCapturedMs : now;
		LoginCustody.Report report = LoginCustody.parse(welcomeText);
		LoginCustody.Result result = LoginCustody.judge(report, prevSession, anchor);
		boolean acquitted = result.verdict == LoginCustody.Verdict.ACQUITTED;
		bankChainTrusted = acquitted && prevSession != null && prevSession.bankChainTrusted;
		untrustedReason = bankChainTrusted ? null
			: result.verdict == LoginCustody.Verdict.CONVICTED
				? "someone was online after your last session"
				: !acquitted
					? "the login check couldn't confirm your last session"
					: "your last session's bank copy wasn't trusted either";
		persistSessionRecord();
		String ours = result.ourAgoMs <= 0 ? "?" : LoginCustody.fmtDuration(result.ourAgoMs);
		// "ended", not "last login": the report measures the previous session's END (see
		// LoginCustody), so label both sides for what they are.
		detail = report == null
			? (welcomeText == null ? "no last-login line" : "unparsed") + " · our logout " + ours
			: "ended " + LoginCustody.fmtDuration(report.agoMs) + " ago · our logout " + ours;
		verdict = result.verdict;
		bannerUntilMs = System.currentTimeMillis() + 60_000;
		log.info("[{}] custody: {} → {} (raw=\"{}\", judged {}s after capture, prevAny={}, prevFresh={}, prevSeen={}, chain={})",
			FlipGoblinPlugin.BUILD, detail, result.verdict, welcomeText, (now - anchor) / 1000,
			prevSession == null ? 0 : prevSession.anyLoginMs,
			prevSession == null ? 0 : prevSession.freshLoginMs,
			prevSession == null ? 0 : prevSession.lastSeenMs, bankChainTrusted);
		if (!welcomeVisible)
		{
			queueConsole(); // click-through beat the judge; don't lose the mirror line
		}
		host.onJudged(acquitted);
		host.onCustodyEvent(eventJson("login", result.verdict, bankChainTrusted,
			prevSession == null ? null : prevSession.bankChainTrusted,
			report == null ? null : report.agoMs,
			result.ourAgoMs <= 0 ? null : result.ourAgoMs, now));
	}

	/**
	 * The body for the server's trust log: trust is now {@code trusted} because of
	 * {@code cause}. Null fields are left out. The welcome text itself is never sent, only
	 * the gap parsed from it.
	 */
	static String eventJson(String cause, LoginCustody.Verdict verdict, boolean trusted,
		Boolean prevChainTrusted, Long gameAgoMs, Long ourAgoMs, long clientTs)
	{
		JsonObject o = new JsonObject();
		o.addProperty("cause", cause);
		if (verdict != null)
		{
			o.addProperty("verdict", verdict.name().toLowerCase(java.util.Locale.ROOT));
		}
		o.addProperty("trusted", trusted);
		if (prevChainTrusted != null)
		{
			o.addProperty("prevChainTrusted", prevChainTrusted);
		}
		if (gameAgoMs != null)
		{
			o.addProperty("gameAgoMs", gameAgoMs);
		}
		if (ourAgoMs != null)
		{
			o.addProperty("ourAgoMs", ourAgoMs);
		}
		o.addProperty("clientTs", clientTs);
		o.addProperty("build", FlipGoblinPlugin.BUILD);
		return o.toString();
	}

	/** The one console mirror line per login. It lands in client.log for correlation. */
	private void queueConsole()
	{
		if (consoleQueued || verdict == null)
		{
			return;
		}
		consoleQueued = true;
		String meaning = verdict == LoginCustody.Verdict.ACQUITTED
			? "last session was ours"
			: verdict == LoginCustody.Verdict.CONVICTED
				? "someone was online after our last session (another client or mobile?)"
				: "can't prove the last session was ours";
		// The call to action is red so it stands out. Chat has no bold.
		String openBank = "<col=ff0000>Open your bank once to restore full net worth.</col>";
		String gate = bankChainTrusted
			? "Bank snapshot trusted; full net worth restored."
			: verdict == LoginCustody.Verdict.ACQUITTED
				? "Your bank copy was already untrusted when your last session ended. " + openBank
				: openBank;
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.value("Flip Goblin custody: " + detail + ": " + verdict + " (" + meaning + "). " + gate)
			.build());
	}

	/** Saves the session record (with the current chain state) to this character's profile. */
	private void persistSessionRecord()
	{
		if (sessionRecord == null)
		{
			return;
		}
		sessionRecord.bankChainTrusted = bankChainTrusted;
		configManager.setRSProfileConfiguration(FlipGoblinPlugin.CONFIG_GROUP, SESSION_KEY,
			gson.toJson(sessionRecord));
	}

	/** The previous session's record from this character's profile, or null (first run or corrupt). */
	private LoginCustody.SessionRecord loadSessionRecord()
	{
		String json = configManager.getRSProfileConfiguration(FlipGoblinPlugin.CONFIG_GROUP, SESSION_KEY);
		if (json == null || json.isEmpty())
		{
			return null;
		}
		try
		{
			return gson.fromJson(json, LoginCustody.SessionRecord.class);
		}
		catch (RuntimeException e)
		{
			log.warn("corrupt session record in profile, treating as absent (AMBIGUOUS)", e);
			return null;
		}
	}
}
