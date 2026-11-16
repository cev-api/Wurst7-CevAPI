/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.patreon;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * Client for the existing Cevapi Cloudflare Worker. This class deliberately
 * only talks to the documented endpoints and never stores or logs credentials.
 */
public class PatreonApi
{
	public static final String DEFAULT_BASE_URL =
		"https://cevapi.cloudy-29c.workers.dev";
	
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
	private static final Gson GSON = new Gson();
	
	private final HttpClient client;
	private final String baseUrl;
	
	public PatreonApi()
	{
		this(DEFAULT_BASE_URL);
	}
	
	public PatreonApi(String baseUrl)
	{
		this.baseUrl = stripTrailingSlash(baseUrl);
		client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT)
			.followRedirects(HttpClient.Redirect.NORMAL).build();
	}
	
	public StartResult startAuthentication() throws PatreonApiException
	{
		JsonObject body = sendJson("POST", "/v1/auth/start", null, "{}");
		String sessionId = getString(body, "session_id");
		String pollToken = getString(body, "poll_token");
		String authorizationUrl = getString(body, "authorization_url");
		
		if(sessionId.isEmpty() || pollToken.isEmpty()
			|| authorizationUrl.isEmpty())
			throw PatreonApiException.response(0, "invalid_response",
				"Cloudflare returned an incomplete authentication session.");
		
		long expiresAt = getLong(body, "expires_at",
			System.currentTimeMillis() / 1000L + 600L);
		int pollInterval = (int)getLong(body, "poll_interval_seconds", 3L);
		if(pollInterval < 1)
			pollInterval = 3;
		if(pollInterval > 30)
			pollInterval = 30;
		
		return new StartResult(sessionId, pollToken, authorizationUrl,
			expiresAt, pollInterval);
	}
	
	public PollResult pollAuthentication(String sessionId, String pollToken)
		throws PatreonApiException
	{
		String query = URLEncoder.encode(sessionId, StandardCharsets.UTF_8);
		JsonObject body = sendJson("GET", "/v1/auth/result?session_id=" + query,
			pollToken, null);
		String status =
			getString(body, "status").toLowerCase(java.util.Locale.ROOT);
		String message = getErrorMessage(body);
		
		switch(status)
		{
			case "pending":
			return new PollResult(PollStatus.PENDING, "", "", "", 0L, message);
			
			case "complete":
			{
				String deviceId = getString(body, "device_id");
				String refreshToken = getString(body, "refresh_token");
				String entitlement = getString(body, "entitlement");
				long expiresAt = getLong(body, "entitlement_expires_at", 0L);
				if(deviceId.isEmpty() || refreshToken.isEmpty()
					|| entitlement.isEmpty())
					throw PatreonApiException.response(0, "invalid_response",
						"Cloudflare returned an incomplete entitlement.");
				return new PollResult(PollStatus.COMPLETE, deviceId,
					refreshToken, entitlement, expiresAt, message);
			}
			
			case "failed":
			return new PollResult(PollStatus.FAILED, "", "", "", 0L,
				message.isEmpty()
					? "Patreon authentication was cancelled or rejected."
					: message);
			
			case "expired":
			return new PollResult(PollStatus.EXPIRED, "", "", "", 0L,
				message.isEmpty()
					? "The Patreon authentication session expired." : message);
			
			default:
			throw PatreonApiException.response(0, "invalid_response",
				"Cloudflare returned an unknown authentication state.");
		}
	}
	
	public EntitlementResult refresh(String deviceId, String refreshToken)
		throws PatreonApiException
	{
		JsonObject request = new JsonObject();
		request.addProperty("device_id", deviceId);
		JsonObject body = sendJson("POST", "/v1/entitlement/refresh",
			refreshToken, GSON.toJson(request));
		
		String entitlement = getString(body, "entitlement");
		if(entitlement.isEmpty())
			entitlement = getString(body, "token");
		if(entitlement.isEmpty())
			entitlement = getString(body, "jwt");
		if(entitlement.isEmpty())
			throw PatreonApiException.response(0, "invalid_response",
				"Cloudflare did not return a supporter entitlement.");
		
		long expiresAt = getLong(body, "entitlement_expires_at",
			getLong(body, "expires_at", 0L));
		return new EntitlementResult(entitlement, expiresAt);
	}
	
	/**
	 * Revokes the current device. A 401 means the credential is already
	 * invalid, which is treated as a successful local disconnect.
	 */
	public void revoke(String deviceId, String refreshToken)
		throws PatreonApiException
	{
		if(refreshToken == null || refreshToken.isBlank())
			return;
		
		JsonObject request = new JsonObject();
		request.addProperty("device_id", deviceId);
		try
		{
			sendJson("POST", "/v1/entitlement/revoke", refreshToken,
				GSON.toJson(request));
			
		}catch(PatreonApiException e)
		{
			if(e.getStatusCode() == 401)
				return;
			throw e;
		}
	}
	
	private JsonObject sendJson(String method, String path, String bearerToken,
		String requestBody) throws PatreonApiException
	{
		HttpRequest.Builder builder =
			HttpRequest.newBuilder(URI.create(baseUrl + path))
				.timeout(REQUEST_TIMEOUT).header("Accept", "application/json")
				.header("User-Agent", "Wurst7-CevAPI");
		
		if(bearerToken != null && !bearerToken.isEmpty())
			builder.header("Authorization", "Bearer " + bearerToken);
		
		if("POST".equals(method))
		{
			builder.header("Content-Type", "application/json");
			builder.POST(HttpRequest.BodyPublishers.ofString(
				requestBody == null ? "{}" : requestBody,
				StandardCharsets.UTF_8));
		}else
			builder.GET();
		
		HttpResponse<String> response;
		try
		{
			response = client.send(builder.build(),
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			
		}catch(InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw PatreonApiException
				.network("The Patreon service request was interrupted.", e);
			
		}catch(IOException | IllegalArgumentException e)
		{
			throw PatreonApiException.network(
				"Couldn't connect to Cloudflare. Check your internet connection.",
				e);
		}
		
		String responseBody = response.body() == null ? "" : response.body();
		JsonObject json = parseObjectOrNull(responseBody);
		
		if(response.statusCode() < 200 || response.statusCode() >= 300)
		{
			String errorCode = getString(json, "error");
			String message = getErrorMessage(json);
			if(message.isEmpty())
				message = "Cloudflare rejected the request (HTTP "
					+ response.statusCode() + ").";
			throw PatreonApiException.response(response.statusCode(), errorCode,
				message);
		}
		
		if(json == null)
			throw PatreonApiException.response(response.statusCode(),
				"invalid_response", "Cloudflare returned an invalid response.");
		
		return json;
	}
	
	private static JsonObject parseObjectOrNull(String body)
	{
		try
		{
			JsonElement element = JsonParser.parseString(body);
			return element.isJsonObject() ? element.getAsJsonObject() : null;
			
		}catch(Exception e)
		{
			return null;
		}
	}
	
	private static String getErrorMessage(JsonObject object)
	{
		if(object == null)
			return "";
		
		for(String key : new String[]{"message", "detail", "description",
			"reason", "error_description"})
		{
			String value = getString(object, key);
			if(!value.isEmpty() && !value.equals(getString(object, "error")))
				return value;
		}
		return "";
	}
	
	private static String getString(JsonObject object, String name)
	{
		if(object == null)
			return "";
		
		JsonElement element = object.get(name);
		if(element == null || !element.isJsonPrimitive()
			|| !element.getAsJsonPrimitive().isString())
			return "";
		return element.getAsString().trim();
	}
	
	private static long getLong(JsonObject object, String name, long fallback)
	{
		if(object == null)
			return fallback;
		
		JsonElement element = object.get(name);
		if(element == null || !element.isJsonPrimitive())
			return fallback;
		
		JsonPrimitive primitive = element.getAsJsonPrimitive();
		if(primitive.isNumber())
		{
			try
			{
				return primitive.getAsLong();
				
			}catch(NumberFormatException e)
			{
				return fallback;
			}
		}
		
		if(!primitive.isString())
			return fallback;
		
		try
		{
			return Long.parseLong(primitive.getAsString().trim());
			
		}catch(NumberFormatException e)
		{
			return fallback;
		}
	}
	
	private static String stripTrailingSlash(String url)
	{
		if(url == null || url.isBlank())
			return DEFAULT_BASE_URL;
		while(url.endsWith("/"))
			url = url.substring(0, url.length() - 1);
		return url;
	}
	
	public enum PollStatus
	{
		PENDING,
		COMPLETE,
		FAILED,
		EXPIRED
	}
	
	public record StartResult(String sessionId, String pollToken,
		String authorizationUrl, long expiresAt, int pollIntervalSeconds)
	{}
	
	public record PollResult(PollStatus status, String deviceId,
		String refreshToken, String entitlement, long entitlementExpiresAt,
		String message)
	{}
	
	public record EntitlementResult(String entitlement,
		long entitlementExpiresAt)
	{}
}
