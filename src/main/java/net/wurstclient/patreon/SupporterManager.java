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
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Coordinates cached entitlement validation, periodic Cloudflare
 * revalidation, Patreon authentication and the once-per-launch promotion.
 *
 * <p>
 * All network work happens on daemon threads. No method that touches the
 * network may be called from Minecraft's render thread.
 */
public final class SupporterManager
{
	private static final long REFRESH_INTERVAL_MS = 24L * 60L * 60L * 1000L;
	private static final long REFRESH_WINDOW_MS = 3L * 24L * 60L * 60L * 1000L;
	private static final long STARTUP_REFRESH_WAIT_MS = 8_000L;
	private static final long AUTH_MAX_DURATION_MS = 10L * 60L * 1000L;
	
	private final SupporterStore store;
	private final PatreonApi api;
	private final EntitlementVerifier verifier;
	private final LongSupplier clock;
	
	private final AtomicBoolean authRunning = new AtomicBoolean();
	private final AtomicBoolean refreshRunning = new AtomicBoolean();
	private final AtomicBoolean disconnectRunning = new AtomicBoolean();
	
	private SupporterData data = new SupporterData();
	private SupporterEntitlement entitlement;
	private long lastVerifiedAtMs;
	private volatile String statusMessage = "";
	
	private volatile AuthPhase authPhase = AuthPhase.IDLE;
	private volatile String authMessage = "";
	private volatile long authGeneration;
	
	private volatile boolean promotionShownThisLaunch;
	private volatile boolean startupRefreshStarted;
	private volatile boolean startupRefreshActive;
	private volatile long startupRefreshStartedAt;
	
	public SupporterManager(Path wurstFolder, Path encryptionFolder)
	{
		this(new SupporterCredentialStore(wurstFolder, encryptionFolder),
			new PatreonApi(), EntitlementVerifier.pinned(),
			System::currentTimeMillis);
	}
	
	SupporterManager(SupporterStore store, PatreonApi api,
		EntitlementVerifier verifier, LongSupplier clock)
	{
		this.store = store;
		this.api = api;
		this.verifier = verifier;
		this.clock = clock;
	}
	
	/**
	 * Loads and cryptographically validates the cached session. This never
	 * touches the network.
	 */
	public synchronized void load()
	{
		entitlement = null;
		
		try
		{
			data = store.load();
			
		}catch(IOException e)
		{
			data = new SupporterData();
			statusMessage =
				"Stored Patreon authentication data could not be read. Please reconnect.";
			return;
		}
		
		statusMessage = "";
		if(data.entitlement.isBlank() || data.deviceId.isBlank())
			return;
		
		try
		{
			entitlement = verifier.verify(data.entitlement, data.deviceId);
			lastVerifiedAtMs = clock.getAsLong();
			
		}catch(EntitlementException e)
		{
			entitlement = null;
			if(e.isExpired())
				statusMessage = "Your supporter entitlement has expired.";
			else
			{
				// Do not keep an unverifiable token around. The refresh token
				// is left untouched so a legitimate session can recover.
				data.entitlement = "";
				data.entitlementExpiresAt = 0L;
				statusMessage = e.getMessage();
				saveQuietly();
			}
		}
	}
	
	/**
	 * Returns true only if the cached JWT passes signature and claim
	 * validation and has not expired.
	 */
	public synchronized boolean hasValidSupporterEntitlement()
	{
		if(data.entitlement.isBlank() || data.deviceId.isBlank())
			return false;
		
		try
		{
			entitlement = verifier.verify(data.entitlement, data.deviceId);
			lastVerifiedAtMs = clock.getAsLong();
			return true;
			
		}catch(EntitlementException e)
		{
			entitlement = null;
			return false;
		}
	}
	
	/**
	 * Called once per title-screen tick. Starts a due background refresh and
	 * decides whether this launch should show the promotional screen.
	 */
	public synchronized boolean shouldShowPromotionNow()
	{
		// Automated client gametests must not be interrupted by the promo.
		if(System.getProperty("fabric.client.gametest") != null)
			return false;
		
		if(promotionShownThisLaunch)
			return false;
		
		boolean valid = hasValidSupporterEntitlement();
		ensureStartupRefresh(valid);
		
		if(valid)
			return false;
			
		// Give a returning supporter's expired-but-refreshable session a
		// short, non-blocking chance to renew before showing the promotion.
		if(startupRefreshActive && clock.getAsLong()
			- startupRefreshStartedAt < STARTUP_REFRESH_WAIT_MS)
			return false;
		
		promotionShownThisLaunch = true;
		return true;
	}
	
	public boolean isPromotionShownThisLaunch()
	{
		return promotionShownThisLaunch;
	}
	
	private void ensureStartupRefresh(boolean hasValidEntitlement)
	{
		if(startupRefreshStarted || !data.hasRefreshCredentials())
			return;
		
		if(!isRefreshDue(hasValidEntitlement))
			return;
		
		startupRefreshStarted = true;
		startupRefreshStartedAt = clock.getAsLong();
		if(!refreshRunning.compareAndSet(false, true))
		{
			startupRefreshActive = true;
			return;
		}
		
		startupRefreshActive = true;
		startRefreshWorker();
	}
	
	private boolean isRefreshDue(boolean hasValidEntitlement)
	{
		if(!data.hasRefreshCredentials())
			return false;
		
		if(!hasValidEntitlement)
			return true;
		
		long now = clock.getAsLong();
		if(data.lastRefreshAt <= 0L
			|| now - data.lastRefreshAt * 1000L >= REFRESH_INTERVAL_MS)
			return true;
		
		SupporterEntitlement current = entitlement;
		return current != null
			&& current.expiresAt() * 1000L - now <= REFRESH_WINDOW_MS;
	}
	
	/**
	 * Starts a background entitlement refresh. Returns false if another
	 * refresh is already running.
	 */
	public boolean refreshAsync()
	{
		if(!refreshRunning.compareAndSet(false, true))
			return false;
		
		startupRefreshStarted = true;
		startupRefreshActive = true;
		startupRefreshStartedAt = clock.getAsLong();
		startRefreshWorker();
		return true;
	}
	
	private void startRefreshWorker()
	{
		Thread thread =
			new Thread(this::runRefresh, "PatreonEntitlementRefresh");
		thread.setDaemon(true);
		thread.start();
	}
	
	private void runRefresh()
	{
		String deviceId;
		String refreshToken;
		synchronized(this)
		{
			deviceId = data.deviceId;
			refreshToken = data.refreshToken;
		}
		
		if(deviceId.isBlank() || refreshToken.isBlank())
		{
			refreshRunning.set(false);
			startupRefreshActive = false;
			return;
		}
		
		try
		{
			PatreonApi.EntitlementResult result =
				api.refresh(deviceId, refreshToken);
			SupporterEntitlement verified =
				verifier.verify(result.entitlement(), deviceId);
			
			synchronized(this)
			{
				if(!deviceId.equals(data.deviceId))
					return;
				
				data.entitlement = result.entitlement();
				data.entitlementExpiresAt = verified.expiresAt();
				data.lastRefreshAt = clock.getAsLong() / 1000L;
				entitlement = verified;
				lastVerifiedAtMs = clock.getAsLong();
				statusMessage = "";
				saveQuietly();
			}
			
		}catch(PatreonApiException e)
		{
			handleRefreshFailure(deviceId, e);
			
		}catch(EntitlementException e)
		{
			synchronized(this)
			{
				if(deviceId.equals(data.deviceId))
					statusMessage =
						"Cloudflare returned a supporter entitlement that could not be verified.";
			}
			
		}catch(Exception e)
		{
			synchronized(this)
			{
				if(deviceId.equals(data.deviceId))
					statusMessage =
						"Couldn't verify your Patreon membership right now. Your existing entitlement remains valid until it expires.";
			}
			
		}finally
		{
			refreshRunning.set(false);
			startupRefreshActive = false;
		}
	}
	
	private void handleRefreshFailure(String deviceId, PatreonApiException e)
	{
		synchronized(this)
		{
			if(!deviceId.equals(data.deviceId))
				return;
			
			if(!e.isDefinitiveInvalid())
			{
				statusMessage =
					"Couldn't verify your Patreon membership right now. Your existing entitlement remains valid until it expires.";
				return;
			}
			
			data.entitlement = "";
			data.entitlementExpiresAt = 0L;
			entitlement = null;
			
			if(isUnauthorized(e))
			{
				data = new SupporterData();
				statusMessage =
					"Your Patreon session is no longer valid. Please reconnect.";
				
			}else
				statusMessage =
					"Your Patreon membership is no longer active. Startup promotions will return.";
			
			saveQuietly();
		}
	}
	
	/**
	 * Starts the browser-based Patreon device flow. The callback receives the
	 * authorization URL on a background thread and must schedule any GUI work
	 * on Minecraft's client thread.
	 */
	public boolean beginAuthentication(Consumer<String> browserOpener)
	{
		if(!authRunning.compareAndSet(false, true))
			return false;
		
		long generation = ++authGeneration;
		authPhase = AuthPhase.WAITING;
		authMessage = "Waiting for Patreon authentication...";
		
		Thread thread =
			new Thread(() -> runAuthentication(generation, browserOpener),
				"PatreonAuthentication");
		thread.setDaemon(true);
		thread.start();
		return true;
	}
	
	private void runAuthentication(long generation,
		Consumer<String> browserOpener)
	{
		try
		{
			PatreonApi.StartResult start = api.startAuthentication();
			if(!isAuthCurrent(generation))
				return;
			
			if(!isTrustedAuthorizationUrl(start.authorizationUrl()))
			{
				failAuthentication(generation,
					"Cloudflare returned an unsafe authentication URL.");
				return;
			}
			
			try
			{
				browserOpener.accept(start.authorizationUrl());
				
			}catch(Exception e)
			{
				failAuthentication(generation,
					"Couldn't open your browser. Please open Patreon manually.");
				return;
			}
			
			long now = clock.getAsLong();
			long deadline = start.expiresAt() * 1000L;
			if(deadline <= now)
				deadline = now + AUTH_MAX_DURATION_MS;
			deadline = Math.min(deadline, now + AUTH_MAX_DURATION_MS);
			
			int intervalSeconds =
				Math.max(1, Math.min(30, start.pollIntervalSeconds()));
			int transientFailures = 0;
			
			while(isAuthCurrent(generation))
			{
				long remaining = deadline - clock.getAsLong();
				if(remaining <= 0L)
				{
					failAuthentication(generation,
						"Patreon authentication timed out. Please try again.");
					return;
				}
				
				try
				{
					Thread.sleep(Math.min(intervalSeconds * 1000L, remaining));
					
				}catch(InterruptedException e)
				{
					Thread.currentThread().interrupt();
					failAuthentication(generation,
						"Patreon authentication was cancelled.");
					return;
				}
				
				if(!isAuthCurrent(generation))
					return;
				
				PatreonApi.PollResult result;
				try
				{
					result = api.pollAuthentication(start.sessionId(),
						start.pollToken());
					transientFailures = 0;
					
				}catch(PatreonApiException e)
				{
					if(e.isDefinitiveInvalid())
					{
						failAuthentication(generation, describeApiError(e));
						return;
					}
					
					if(++transientFailures >= 5)
					{
						failAuthentication(generation,
							"Couldn't reach Cloudflare while waiting for Patreon authentication.");
						return;
					}
					continue;
				}
				
				switch(result.status())
				{
					case PENDING:
					break;
					
					case COMPLETE:
					completeAuthentication(generation, result);
					return;
					
					case FAILED:
					failAuthentication(generation, result.message().isBlank()
						? "Patreon authentication was cancelled or rejected."
						: result.message());
					return;
					
					case EXPIRED:
					failAuthentication(generation,
						"The Patreon authentication session expired. Please try again.");
					return;
				}
			}
			
		}catch(PatreonApiException e)
		{
			failAuthentication(generation, describeApiError(e));
			
		}catch(Exception e)
		{
			failAuthentication(generation,
				"Patreon authentication failed unexpectedly. Please try again.");
			
		}finally
		{
			if(isAuthCurrent(generation))
				authRunning.set(false);
		}
	}
	
	private void completeAuthentication(long generation,
		PatreonApi.PollResult result)
	{
		try
		{
			SupporterEntitlement verified =
				verifier.verify(result.entitlement(), result.deviceId());
			
			synchronized(this)
			{
				if(!isAuthCurrent(generation))
					return;
				
				SupporterData updated = new SupporterData();
				updated.deviceId = result.deviceId();
				updated.refreshToken = result.refreshToken();
				updated.entitlement = result.entitlement();
				updated.entitlementExpiresAt = verified.expiresAt();
				updated.lastRefreshAt = clock.getAsLong() / 1000L;
				data = updated;
				entitlement = verified;
				lastVerifiedAtMs = clock.getAsLong();
				statusMessage = "";
				
				String saveError = null;
				try
				{
					store.save(data);
					if(!store.isPersistent())
						saveError =
							" Secure storage is unavailable, so you may need to reconnect next launch.";
					
				}catch(IOException e)
				{
					saveError =
						" Your session could not be saved; you may need to reconnect next launch.";
				}
				
				authPhase = AuthPhase.SUCCESS;
				authMessage =
					"Thank you for supporting Cevapi! Your Patreon membership has been verified. Startup promotions are now disabled while your supporter membership remains active."
						+ (saveError == null ? "" : saveError);
			}
			
		}catch(EntitlementException e)
		{
			failAuthentication(generation,
				"The supporter entitlement could not be verified. Authentication was rejected.");
		}
	}
	
	private void failAuthentication(long generation, String message)
	{
		synchronized(this)
		{
			if(!isAuthCurrent(generation))
				return;
			authPhase = AuthPhase.ERROR;
			authMessage = message;
		}
	}
	
	private boolean isAuthCurrent(long generation)
	{
		return generation == authGeneration;
	}
	
	/**
	 * Cancels a pending authentication attempt and stops the polling thread.
	 */
	public synchronized void cancelAuthentication()
	{
		if(authPhase == AuthPhase.WAITING)
		{
			authGeneration++;
			authRunning.set(false);
			authPhase = AuthPhase.IDLE;
			authMessage = "Authentication cancelled.";
		}
	}
	
	/**
	 * Revokes the device and clears local credentials. If Cloudflare cannot be
	 * reached, the local credentials are kept so nothing is silently lost.
	 */
	public boolean disconnectAsync()
	{
		if(!disconnectRunning.compareAndSet(false, true))
			return false;
		
		authPhase = AuthPhase.DISCONNECTING;
		authMessage = "Disconnecting from Patreon...";
		
		Thread thread = new Thread(this::runDisconnect, "PatreonDisconnect");
		thread.setDaemon(true);
		thread.start();
		return true;
	}
	
	private void runDisconnect()
	{
		String deviceId;
		String refreshToken;
		synchronized(this)
		{
			deviceId = data.deviceId;
			refreshToken = data.refreshToken;
		}
		
		try
		{
			api.revoke(deviceId, refreshToken);
			
		}catch(PatreonApiException e)
		{
			if(!e.isDefinitiveInvalid())
			{
				synchronized(this)
				{
					authPhase = AuthPhase.ERROR;
					authMessage =
						"Couldn't reach Cloudflare to revoke this device. Check your connection and try again.";
				}
				disconnectRunning.set(false);
				return;
			}
			
		}catch(Exception e)
		{
			synchronized(this)
			{
				authPhase = AuthPhase.ERROR;
				authMessage =
					"Couldn't disconnect from Patreon. Check your connection and try again.";
			}
			disconnectRunning.set(false);
			return;
			
		}finally
		{
			disconnectRunning.set(false);
		}
		
		synchronized(this)
		{
			data = new SupporterData();
			entitlement = null;
			lastVerifiedAtMs = 0L;
			statusMessage = "";
			try
			{
				store.delete();
				
			}catch(IOException ignored)
			{}
			authPhase = AuthPhase.IDLE;
			authMessage = "Disconnected from Patreon.";
		}
	}
	
	/**
	 * Restores a locally cleared session without contacting Cloudflare. Used
	 * when secure storage is unavailable and the user disconnects.
	 */
	public synchronized void clearLocalSession()
	{
		authGeneration++;
		authRunning.set(false);
		data = new SupporterData();
		entitlement = null;
		lastVerifiedAtMs = 0L;
		statusMessage = "";
		try
		{
			store.delete();
			
		}catch(IOException ignored)
		{}
	}
	
	public AuthSnapshot getAuthSnapshot()
	{
		return new AuthSnapshot(authPhase, authMessage);
	}
	
	public synchronized String getStatusMessage()
	{
		return statusMessage;
	}
	
	public synchronized String getAuthMessage()
	{
		return authMessage;
	}
	
	public synchronized boolean hasRefreshCredentials()
	{
		return data.hasRefreshCredentials();
	}
	
	public synchronized long getEntitlementExpiresAt()
	{
		if(data.entitlementExpiresAt > 0L)
			return data.entitlementExpiresAt;
		SupporterEntitlement current = entitlement;
		return current == null ? 0L : current.expiresAt();
	}
	
	public synchronized long getLastSuccessfulRefresh()
	{
		return data.lastRefreshAt;
	}
	
	public synchronized long getLastVerifiedAt()
	{
		return lastVerifiedAtMs;
	}
	
	public synchronized String getShortStatus()
	{
		return getShortStatus(hasValidSupporterEntitlement());
	}
	
	public synchronized String getShortStatus(boolean hasValidEntitlement)
	{
		if(hasValidEntitlement)
			return "Verified";
		if(authPhase == AuthPhase.WAITING)
			return "Connecting...";
		if(data.hasRefreshCredentials())
			return "Not verified";
		return "Not connected";
	}
	
	public synchronized AuthPhase getAuthPhase()
	{
		return authPhase;
	}
	
	public synchronized void shutdown()
	{
		authGeneration++;
		authRunning.set(false);
	}
	
	private void saveQuietly()
	{
		if(!store.isPersistent())
		{
			statusMessage =
				"Secure storage is unavailable. This session will only last until Minecraft is closed.";
			return;
		}
		
		try
		{
			store.save(data);
			
		}catch(IOException e)
		{
			statusMessage =
				"Verified, but your supporter session could not be saved. You may need to reconnect next launch.";
		}
	}
	
	private static boolean isUnauthorized(PatreonApiException e)
	{
		if(e.getStatusCode() == 401)
			return true;
		String code = e.getErrorCode().toLowerCase(Locale.ROOT);
		return "unauthorized".equals(code) || "invalid_token".equals(code)
			|| "invalid_grant".equals(code);
	}
	
	private static String describeApiError(PatreonApiException e)
	{
		if(e.isNetworkError())
			return "Couldn't connect to Cloudflare. Check your internet connection and try again.";
		if(e.isServiceUnavailable())
			return "Patreon or Cloudflare is temporarily unavailable. Please try again later.";
		if(e.getStatusCode() == 401 || e.getStatusCode() == 403)
			return "No active paid Patreon membership was found for this account.";
		return e.getMessage();
	}
	
	private static boolean isTrustedAuthorizationUrl(String url)
	{
		try
		{
			URI uri = URI.create(url);
			String host = uri.getHost();
			if(host == null || !"https".equalsIgnoreCase(uri.getScheme()))
				return false;
			host = host.toLowerCase(Locale.ROOT);
			return "patreon.com".equals(host) || host.endsWith(".patreon.com");
			
		}catch(IllegalArgumentException e)
		{
			return false;
		}
	}
	
	public enum AuthPhase
	{
		IDLE,
		WAITING,
		SUCCESS,
		ERROR,
		DISCONNECTING
	}
	
	public record AuthSnapshot(AuthPhase phase, String message)
	{}
}
