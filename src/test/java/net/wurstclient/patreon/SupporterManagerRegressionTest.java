/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.patreon;

import java.io.IOException;
import java.security.KeyPair;
import java.security.interfaces.ECPublicKey;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import com.google.gson.JsonObject;

/**
 * Exercises the supporter state machine with a real P-256 verifier, an
 * in-memory credential store and a mocked Cloudflare client.
 */
public final class SupporterManagerRegressionTest
{
	private static final String DEVICE_ID = "device-1";
	
	public static void main(String[] args) throws Exception
	{
		KeyPair keyPair = TestTokens.generateKeyPair();
		EntitlementVerifier verifier =
			new EntitlementVerifier(TestTokens.ISSUER, TestTokens.AUDIENCE,
				TestTokens.KEY_ID, (ECPublicKey)keyPair.getPublic());
		
		showsPromotionWithoutCredentials(verifier);
		handlesCorruptedCredentials(verifier);
		authenticatesAndPersists(verifier, keyPair);
		acceptsReloadedValidEntitlement(verifier, keyPair);
		rejectsForgedCachedEntitlement(verifier, keyPair);
		refreshesExpiredEntitlement(verifier, keyPair);
		rejectsInactiveMembership(verifier, keyPair);
		keepsEntitlementOnNetworkFailure(verifier, keyPair);
		disconnects(verifier, keyPair);
		rejectsConcurrentAuthentication(verifier, keyPair);
		
		System.out.println("Supporter manager regression checks passed.");
	}
	
	private static void showsPromotionWithoutCredentials(
		EntitlementVerifier verifier)
	{
		SupporterManager manager =
			newManager(new MemoryStore(), new FakeApi(), verifier);
		manager.load();
		check(!manager.hasValidSupporterEntitlement(),
			"no credentials means no entitlement");
		check(manager.shouldShowPromotionNow(),
			"a free user must see the promotion");
		check(!manager.shouldShowPromotionNow(),
			"the promotion must only open once per launch");
	}
	
	private static void handlesCorruptedCredentials(
		EntitlementVerifier verifier)
	{
		SupporterStore corrupted = new SupporterStore()
		{
			@Override
			public SupporterData load() throws IOException
			{
				throw new IOException("corrupted");
			}
			
			@Override
			public void save(SupporterData data)
			{}
			
			@Override
			public void delete()
			{}
		};
		
		SupporterManager manager =
			newManager(corrupted, new FakeApi(), verifier);
		manager.load();
		check(!manager.hasValidSupporterEntitlement(),
			"corrupted credentials must not authenticate");
		check(manager.getStatusMessage().contains("could not be read"),
			"corrupted credentials must be reported");
		check(manager.shouldShowPromotionNow(),
			"corrupted credentials must not suppress the promotion");
	}
	
	private static void authenticatesAndPersists(EntitlementVerifier verifier,
		KeyPair keyPair) throws Exception
	{
		MemoryStore store = new MemoryStore();
		FakeApi api = new FakeApi();
		api.entitlement = validToken(keyPair);
		SupporterManager manager = newManager(store, api, verifier);
		manager.load();
		
		AtomicReference<String> openedUrl = new AtomicReference<>();
		check(manager.beginAuthentication(openedUrl::set),
			"authentication must start");
		waitFor(() -> openedUrl.get() != null, 2000L,
			"browser URL must be opened");
		check(openedUrl.get().startsWith("https://www.patreon.com/"),
			"browser URL must be opened");
		
		waitFor(
			() -> manager.getAuthSnapshot()
				.phase() == SupporterManager.AuthPhase.SUCCESS,
			5000L, "authentication must complete");
		check(manager.hasValidSupporterEntitlement(),
			"authentication must produce a valid entitlement");
		check(api.entitlement.equals(store.data.entitlement),
			"entitlement must be persisted");
		check("refresh-token".equals(store.data.refreshToken),
			"refresh credential must be persisted");
		check(!manager.shouldShowPromotionNow(),
			"an authenticated supporter must not see the promotion");
	}
	
	private static void acceptsReloadedValidEntitlement(
		EntitlementVerifier verifier, KeyPair keyPair) throws Exception
	{
		MemoryStore store = new MemoryStore();
		store.data.deviceId = DEVICE_ID;
		store.data.entitlement = validToken(keyPair);
		store.data.entitlementExpiresAt =
			System.currentTimeMillis() / 1000L + 3600L;
		store.data.lastRefreshAt = System.currentTimeMillis() / 1000L;
		
		SupporterManager manager = newManager(store, new FakeApi(), verifier);
		manager.load();
		check(manager.hasValidSupporterEntitlement(),
			"a cached valid entitlement must be accepted");
		check(!manager.shouldShowPromotionNow(),
			"a returning supporter must not see the promotion");
	}
	
	private static void rejectsForgedCachedEntitlement(
		EntitlementVerifier verifier, KeyPair keyPair) throws Exception
	{
		MemoryStore store = new MemoryStore();
		store.data.deviceId = DEVICE_ID;
		store.data.entitlement =
			TestTokens.tamperSignature(validToken(keyPair));
		
		SupporterManager manager = newManager(store, new FakeApi(), verifier);
		manager.load();
		check(!manager.hasValidSupporterEntitlement(),
			"a forged cached entitlement must be rejected");
		check(manager.shouldShowPromotionNow(),
			"a forged entitlement must not suppress the promotion");
	}
	
	private static void refreshesExpiredEntitlement(
		EntitlementVerifier verifier, KeyPair keyPair) throws Exception
	{
		MemoryStore store = new MemoryStore();
		store.data.deviceId = DEVICE_ID;
		store.data.refreshToken = "refresh-token";
		store.data.entitlement = expiredToken(keyPair);
		
		FakeApi api = new FakeApi();
		api.entitlement = validToken(keyPair);
		SupporterManager manager = newManager(store, api, verifier);
		manager.load();
		check(!manager.hasValidSupporterEntitlement(),
			"an expired entitlement must not be trusted");
		check(manager.refreshAsync(), "refresh must start");
		waitFor(manager::hasValidSupporterEntitlement, 5000L,
			"refresh must install a new entitlement");
		check(manager.shouldShowPromotionNow() == false,
			"a refreshed supporter must not see the promotion");
	}
	
	private static void rejectsInactiveMembership(EntitlementVerifier verifier,
		KeyPair keyPair) throws Exception
	{
		MemoryStore store = new MemoryStore();
		store.data.deviceId = DEVICE_ID;
		store.data.refreshToken = "refresh-token";
		store.data.entitlement = expiredToken(keyPair);
		
		FakeApi api = new FakeApi();
		api.refreshFailure = PatreonApiException.response(403,
			"membership_inactive", "Membership is inactive");
		SupporterManager manager = newManager(store, api, verifier);
		manager.load();
		check(manager.refreshAsync(), "refresh must start");
		waitFor(() -> manager.getStatusMessage().contains("no longer active"),
			5000L, "inactive membership must be reported");
		check(!manager.hasValidSupporterEntitlement(),
			"inactive membership must clear the entitlement");
	}
	
	private static void keepsEntitlementOnNetworkFailure(
		EntitlementVerifier verifier, KeyPair keyPair) throws Exception
	{
		MemoryStore store = new MemoryStore();
		store.data.deviceId = DEVICE_ID;
		store.data.refreshToken = "refresh-token";
		store.data.entitlement = validToken(keyPair);
		store.data.entitlementExpiresAt =
			System.currentTimeMillis() / 1000L + 3600L;
		
		FakeApi api = new FakeApi();
		api.refreshFailure =
			PatreonApiException.network("offline", new IOException("offline"));
		SupporterManager manager = newManager(store, api, verifier);
		manager.load();
		check(manager.hasValidSupporterEntitlement(),
			"cached entitlement must be valid before refresh");
		check(manager.refreshAsync(), "refresh must start");
		waitFor(() -> manager.getStatusMessage().contains("Couldn't verify"),
			5000L, "network failure must be reported");
		check(manager.hasValidSupporterEntitlement(),
			"network failure must not discard a valid entitlement");
	}
	
	private static void disconnects(EntitlementVerifier verifier,
		KeyPair keyPair) throws Exception
	{
		MemoryStore store = new MemoryStore();
		store.data.deviceId = DEVICE_ID;
		store.data.refreshToken = "refresh-token";
		store.data.entitlement = validToken(keyPair);
		
		FakeApi api = new FakeApi();
		SupporterManager manager = newManager(store, api, verifier);
		manager.load();
		check(manager.disconnectAsync(), "disconnect must start");
		waitFor(
			() -> manager.getAuthSnapshot()
				.phase() == SupporterManager.AuthPhase.IDLE,
			5000L, "disconnect must finish");
		check(api.revoked, "the device must be revoked");
		check(!manager.hasRefreshCredentials(),
			"local refresh credentials must be cleared");
		check(!manager.hasValidSupporterEntitlement(),
			"local entitlement must be cleared");
		check(store.deleted, "the stored session file must be deleted");
	}
	
	private static void rejectsConcurrentAuthentication(
		EntitlementVerifier verifier, KeyPair keyPair) throws Exception
	{
		FakeApi api = new FakeApi();
		api.entitlement = validToken(keyPair);
		api.holdPending = true;
		SupporterManager manager = newManager(new MemoryStore(), api, verifier);
		manager.load();
		
		check(manager.beginAuthentication(url -> {}), "first auth must start");
		check(!manager.beginAuthentication(url -> {}),
			"second auth must be rejected while one is running");
		manager.cancelAuthentication();
		api.holdPending = false;
	}
	
	private static SupporterManager newManager(SupporterStore store,
		PatreonApi api, EntitlementVerifier verifier)
	{
		return new SupporterManager(store, api, verifier,
			System::currentTimeMillis);
	}
	
	private static String validToken(KeyPair keyPair) throws Exception
	{
		return TestTokens.sign(keyPair.getPrivate(),
			TestTokens.validPayload(DEVICE_ID));
	}
	
	private static String expiredToken(KeyPair keyPair) throws Exception
	{
		long now = System.currentTimeMillis() / 1000L;
		JsonObject payload = TestTokens.validPayload(DEVICE_ID);
		payload.addProperty("iat", now - 1000L);
		payload.addProperty("exp", now - 10L);
		return TestTokens.sign(keyPair.getPrivate(), payload);
	}
	
	private static void waitFor(BooleanSupplier condition, long timeoutMs,
		String message) throws InterruptedException
	{
		long deadline = System.currentTimeMillis() + timeoutMs;
		while(System.currentTimeMillis() < deadline)
		{
			if(condition.getAsBoolean())
				return;
			Thread.sleep(25L);
		}
		throw new AssertionError("Timed out: " + message);
	}
	
	private static void check(boolean condition, String message)
	{
		if(!condition)
			throw new AssertionError(message);
	}
	
	private static final class MemoryStore implements SupporterStore
	{
		private SupporterData data = new SupporterData();
		private boolean deleted;
		
		@Override
		public synchronized SupporterData load()
		{
			return copy(data);
		}
		
		@Override
		public synchronized void save(SupporterData updated)
		{
			data = copy(updated);
		}
		
		@Override
		public synchronized void delete()
		{
			data = new SupporterData();
			deleted = true;
		}
		
		private static SupporterData copy(SupporterData source)
		{
			SupporterData copy = new SupporterData();
			copy.deviceId = source.deviceId;
			copy.refreshToken = source.refreshToken;
			copy.entitlement = source.entitlement;
			copy.entitlementExpiresAt = source.entitlementExpiresAt;
			copy.lastRefreshAt = source.lastRefreshAt;
			return copy;
		}
	}
	
	private static final class FakeApi extends PatreonApi
	{
		private final AtomicInteger polls = new AtomicInteger();
		private volatile String entitlement = "";
		private volatile boolean holdPending;
		private volatile boolean revoked;
		private volatile PatreonApiException refreshFailure;
		
		private FakeApi()
		{
			super("http://127.0.0.1:1");
		}
		
		@Override
		public StartResult startAuthentication()
		{
			return new StartResult("session-1", "poll-token",
				"https://www.patreon.com/oauth2/authorize?state=test",
				System.currentTimeMillis() / 1000L + 600L, 1);
		}
		
		@Override
		public PollResult pollAuthentication(String sessionId, String pollToken)
			throws PatreonApiException
		{
			if(holdPending || polls.getAndIncrement() == 0)
				return new PollResult(PollStatus.PENDING, "", "", "", 0L, "");
			
			return new PollResult(PollStatus.COMPLETE, DEVICE_ID,
				"refresh-token", entitlement,
				System.currentTimeMillis() / 1000L + 3600L, "");
		}
		
		@Override
		public EntitlementResult refresh(String deviceId, String refreshToken)
			throws PatreonApiException
		{
			if(refreshFailure != null)
				throw refreshFailure;
			return new EntitlementResult(entitlement,
				System.currentTimeMillis() / 1000L + 3600L);
		}
		
		@Override
		public void revoke(String deviceId, String refreshToken)
		{
			revoked = true;
		}
	}
}
