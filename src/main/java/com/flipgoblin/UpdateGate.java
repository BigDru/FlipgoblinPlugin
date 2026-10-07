package com.flipgoblin;

import java.io.IOException;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Sends the plugin version with every request to our API, and pauses the API once the server
 * answers 426 (this version is too old). After that, every API call gets a local 426 without
 * touching the network until RuneLite restarts and installs the update. Queued trades are kept,
 * because the sync code never drops a batch on 426.
 */
final class UpdateGate implements Interceptor
{
	static final String HEADER = "X-FlipGoblin-Version";
	static final int UPGRADE_REQUIRED = 426;

	private final String version;
	private final String apiBase;
	private final Runnable onRequired;
	private volatile boolean required;

	UpdateGate(String version, String apiBase, Runnable onRequired)
	{
		this.version = version;
		this.apiBase = apiBase.replaceAll("/+$", "");
		this.onRequired = onRequired;
	}

	/** True once the server has said this version needs an update. */
	boolean required()
	{
		return required;
	}

	/** True for an answer that means the request itself is bad, so retrying it cannot help. */
	static boolean rejectsForGood(int code)
	{
		return code >= 400 && code < 500 && code != 401 && code != UPGRADE_REQUIRED && code != 429;
	}

	@Override
	public Response intercept(Chain chain) throws IOException
	{
		Request request = chain.request();
		if (!request.url().toString().startsWith(apiBase))
		{
			return chain.proceed(request);
		}
		if (required)
		{
			return upgradeRequired(request);
		}
		Response response = chain.proceed(request.newBuilder().header(HEADER, version).build());
		if (response.code() == UPGRADE_REQUIRED && !required)
		{
			required = true;
			onRequired.run();
		}
		return response;
	}

	private static Response upgradeRequired(Request request)
	{
		return new Response.Builder()
			.request(request)
			.protocol(Protocol.HTTP_1_1)
			.code(UPGRADE_REQUIRED)
			.message("Upgrade Required")
			.body(ResponseBody.create(MediaType.parse("application/json"), "{\"ok\":false,\"error\":\"update required\"}"))
			.build();
	}
}
