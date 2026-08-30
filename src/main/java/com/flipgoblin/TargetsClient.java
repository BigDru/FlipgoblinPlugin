package com.flipgoblin;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Fetches the user's website watchlist and alert rules so they can be shown beside the
 * live price while composing an offer. Refreshed on a slow cadence. The plugin never
 * changes targets; they are managed on the website. Fetches run in the background, and
 * rendering reads the last successfully fetched map.
 */
@Slf4j
public final class TargetsClient
{
	/** One alert rule as the overlay renders it. */
	public static final class TargetAlert
	{
		public final String metric;
		public final String op;
		public final long threshold;
		public final boolean enabled;

		public TargetAlert(String metric, String op, long threshold, boolean enabled)
		{
			this.metric = metric;
			this.op = op;
			this.threshold = threshold;
			this.enabled = enabled;
		}
	}

	public static final class Target
	{
		public final boolean watched;
		public final List<TargetAlert> alerts;

		public Target(boolean watched, List<TargetAlert> alerts)
		{
			this.watched = watched;
			this.alerts = alerts;
		}
	}

	private final OkHttpClient http;
	private volatile Map<Integer, Target> targets = Collections.emptyMap();

	public TargetsClient(OkHttpClient http)
	{
		this.http = http;
	}

	/** The last fetched target for an item, or null. Safe to call from render. */
	public Target get(int itemId)
	{
		return targets.get(itemId);
	}

	/** Formats one rule the way the website words it, e.g. "Buy ≤ 1,000,000" or "ROI ≥ 2.5%". Pure. */
	static String alertLabel(TargetAlert a)
	{
		String metric = "bid".equals(a.metric) ? "Buy"
			: "ask".equals(a.metric) ? "Sell"
			: "margin".equals(a.metric) ? "Margin" : "ROI";
		String op = "gte".equals(a.op) ? "≥" : "≤";
		String value = "roi".equals(a.metric)
			? trimPct(a.threshold / 100.0) + "%"
			: Gp.exact(a.threshold);
		return metric + " " + op + " " + value;
	}

	private static String trimPct(double pct)
	{
		String s = String.format(java.util.Locale.ROOT, "%.2f", pct);
		return s.replaceAll("\\.?0+$", "");
	}

	/** Parses the /plugin/targets response body. Returns null on malformed JSON. Pure. */
	static Map<Integer, Target> parse(Gson gson, String body)
	{
		try
		{
			JsonObject root = gson.fromJson(body, JsonObject.class);
			if (root == null || !root.has("targets") || !root.get("targets").isJsonArray())
			{
				return null;
			}
			Map<Integer, Target> out = new HashMap<>();
			JsonArray arr = root.getAsJsonArray("targets");
			for (JsonElement el : arr)
			{
				JsonObject t = el.getAsJsonObject();
				int itemId = t.get("itemId").getAsInt();
				boolean watched = t.has("watched") && t.get("watched").getAsBoolean();
				List<TargetAlert> alerts = new ArrayList<>();
				if (t.has("alerts") && t.get("alerts").isJsonArray())
				{
					for (JsonElement a : t.getAsJsonArray("alerts"))
					{
						JsonObject ao = a.getAsJsonObject();
						alerts.add(new TargetAlert(
							ao.get("metric").getAsString(),
							ao.get("op").getAsString(),
							ao.get("threshold").getAsLong(),
							!ao.has("enabled") || ao.get("enabled").getAsBoolean()));
					}
				}
				out.put(itemId, new Target(watched, alerts));
			}
			return out;
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	/** Fetches the targets in the background. Keeps the previous map on any failure. */
	public void refresh(Gson gson, String apiBase, String token)
	{
		Request request = new Request.Builder()
			.url(apiBase.replaceAll("/+$", "") + "/plugin/targets")
			.header("Authorization", "Bearer " + token)
			.get()
			.build();
		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("targets refresh failed", e);
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (Response r = response)
				{
					if (!r.isSuccessful() || r.body() == null)
					{
						log.debug("targets refresh HTTP {}", r.code());
						return;
					}
					Map<Integer, Target> parsed = parse(gson, r.body().string());
					if (parsed != null)
					{
						targets = parsed;
					}
				}
			}
		});
	}
}
