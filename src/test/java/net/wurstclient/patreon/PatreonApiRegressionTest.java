/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.patreon;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Exercises the Cloudflare HTTP contract against a local mock server. No
 * Patreon account or real token is required.
 */
public final class PatreonApiRegressionTest
{
	private static final String TOKEN = "test-entitlement-token";
	
	public static void main(String[] args) throws Exception
	{
		HttpServer server =
			HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		AtomicInteger pollCount = new AtomicInteger();
		AtomicInteger refreshMode = new AtomicInteger();
		AtomicReference<String> lastAuthorization = new AtomicReference<>();
		AtomicReference<String> lastBody = new AtomicReference<>();
		
		server.createContext("/v1/auth/start", exchange -> {
			requireMethod(exchange, "POST");
			respond(exchange, 201, "{\"session_id\":\"session-1\","
				+ "\"poll_token\":\"poll-token\","
				+ "\"authorization_url\":\"https://www.patreon.com/oauth2/authorize?state=abc\","
				+ "\"expires_at\":"
				+ (System.currentTimeMillis() / 1000L + 600L)
				+ ",\"poll_interval_seconds\":1}");
		});
		
		server.createContext("/v1/auth/result", exchange -> {
			requireMethod(exchange, "GET");
			lastAuthorization
				.set(exchange.getRequestHeaders().getFirst("Authorization"));
			if(!exchange.getRequestURI().getQuery()
				.equals("session_id=session-1"))
			{
				respond(exchange, 400, "{\"error\":\"bad_session\"}");
				return;
			}
			
			if(pollCount.getAndIncrement() == 0)
			{
				respond(exchange, 200, "{\"status\":\"pending\"}");
				return;
			}
			
			respond(exchange, 200,
				"{\"status\":\"complete\"," + "\"device_id\":\"device-1\","
					+ "\"refresh_token\":\"refresh-token\","
					+ "\"entitlement\":\"" + TOKEN + "\","
					+ "\"entitlement_expires_at\":"
					+ (System.currentTimeMillis() / 1000L + 3600L) + "}");
		});
		
		server.createContext("/v1/entitlement/refresh", exchange -> {
			requireMethod(exchange, "POST");
			lastAuthorization
				.set(exchange.getRequestHeaders().getFirst("Authorization"));
			lastBody.set(new String(exchange.getRequestBody().readAllBytes(),
				StandardCharsets.UTF_8));
			
			switch(refreshMode.get())
			{
				case 401:
				respond(exchange, 401, "{\"error\":\"unauthorized\"}");
				return;
				case 503:
				respond(exchange, 503, "{\"error\":\"unavailable\"}");
				return;
				default:
				respond(exchange, 200,
					"{\"entitlement\":\"" + TOKEN
						+ "\",\"entitlement_expires_at\":"
						+ (System.currentTimeMillis() / 1000L + 3600L) + "}");
			}
		});
		
		server.createContext("/v1/entitlement/revoke", exchange -> {
			requireMethod(exchange, "POST");
			lastAuthorization
				.set(exchange.getRequestHeaders().getFirst("Authorization"));
			lastBody.set(new String(exchange.getRequestBody().readAllBytes(),
				StandardCharsets.UTF_8));
			respond(exchange, 200, "{\"revoked\":true}");
		});
		
		server.setExecutor(Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "Patreon API mock");
			thread.setDaemon(true);
			return thread;
		}));
		server.start();
		
		try
		{
			String baseUrl =
				"http://127.0.0.1:" + server.getAddress().getPort();
			PatreonApi api = new PatreonApi(baseUrl);
			
			PatreonApi.StartResult start = api.startAuthentication();
			check("session-1".equals(start.sessionId()), "session id");
			check("poll-token".equals(start.pollToken()), "poll token");
			check(
				start.authorizationUrl().startsWith("https://www.patreon.com/"),
				"authorization url");
			check(start.pollIntervalSeconds() == 1, "poll interval");
			check(start.expiresAt() > System.currentTimeMillis() / 1000L,
				"session expiry");
			
			PatreonApi.PollResult pending =
				api.pollAuthentication(start.sessionId(), start.pollToken());
			check(pending.status() == PatreonApi.PollStatus.PENDING,
				"first poll must be pending");
			check("Bearer poll-token".equals(lastAuthorization.get()),
				"poll token must be sent as a bearer credential");
			
			PatreonApi.PollResult complete =
				api.pollAuthentication(start.sessionId(), start.pollToken());
			check(complete.status() == PatreonApi.PollStatus.COMPLETE,
				"second poll must complete");
			check("device-1".equals(complete.deviceId()), "device id");
			check("refresh-token".equals(complete.refreshToken()),
				"refresh token");
			check(TOKEN.equals(complete.entitlement()), "entitlement");
			
			PatreonApi.EntitlementResult refreshed =
				api.refresh("device-1", "refresh-token");
			check(TOKEN.equals(refreshed.entitlement()), "refresh entitlement");
			check("Bearer refresh-token".equals(lastAuthorization.get()),
				"refresh token must be used as a bearer credential");
			check(lastBody.get().contains("\"device_id\":\"device-1\""),
				"refresh request must include the device id");
			
			api.revoke("device-1", "refresh-token");
			check("Bearer refresh-token".equals(lastAuthorization.get()),
				"revoke token must be used as a bearer credential");
			
			refreshMode.set(401);
			expectDefinitiveInvalid(api, "unauthorized refresh");
			
			refreshMode.set(503);
			expectServiceUnavailable(api, "unavailable refresh");
			
			PatreonApi offline = new PatreonApi("http://127.0.0.1:1");
			try
			{
				offline.refresh("device-1", "refresh-token");
				throw new AssertionError("offline refresh must fail");
				
			}catch(PatreonApiException expected)
			{
				check(expected.isNetworkError(),
					"offline failure must be a network error");
				check(!expected.isDefinitiveInvalid(),
					"network failure must not invalidate the entitlement");
			}
			
		}finally
		{
			server.stop(0);
		}
		
		System.out.println("Patreon API regression checks passed.");
	}
	
	private static void expectDefinitiveInvalid(PatreonApi api, String label)
	{
		try
		{
			api.refresh("device-1", "refresh-token");
			throw new AssertionError(label + " must fail");
			
		}catch(PatreonApiException expected)
		{
			check(expected.isDefinitiveInvalid(),
				label + " must be definitive");
		}
	}
	
	private static void expectServiceUnavailable(PatreonApi api, String label)
	{
		try
		{
			api.refresh("device-1", "refresh-token");
			throw new AssertionError(label + " must fail");
			
		}catch(PatreonApiException expected)
		{
			check(expected.isServiceUnavailable(),
				label + " must be transient");
			check(!expected.isDefinitiveInvalid(),
				label + " must not invalidate the entitlement");
		}
	}
	
	private static void requireMethod(HttpExchange exchange, String method)
		throws IOException
	{
		if(!method.equals(exchange.getRequestMethod()))
			respond(exchange, 405, "{\"error\":\"method_not_allowed\"}");
	}
	
	private static void respond(HttpExchange exchange, int status, String body)
		throws IOException
	{
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		try(OutputStream out = exchange.getResponseBody())
		{
			out.write(bytes);
		}
	}
	
	private static void check(boolean condition, String message)
	{
		if(!condition)
			throw new AssertionError(message);
	}
}
