/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.patreon;

import java.security.KeyPair;
import java.security.interfaces.ECPublicKey;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Regression coverage for the signed-entitlement trust checks.
 */
public final class EntitlementVerifierRegressionTest
{
	private static final String DEVICE_ID = "device-123";
	
	public static void main(String[] args) throws Exception
	{
		KeyPair keyPair = TestTokens.generateKeyPair();
		EntitlementVerifier verifier =
			new EntitlementVerifier(TestTokens.ISSUER, TestTokens.AUDIENCE,
				TestTokens.KEY_ID, (ECPublicKey)keyPair.getPublic());
		
		acceptsValidToken(verifier, keyPair);
		rejectsTamperedSignature(verifier, keyPair);
		rejectsWrongAlgorithm(verifier, keyPair);
		rejectsWrongKeyId(verifier, keyPair);
		rejectsWrongIssuer(verifier, keyPair);
		rejectsWrongAudience(verifier, keyPair);
		rejectsExpiredToken(verifier, keyPair);
		rejectsFutureIssuedAt(verifier, keyPair);
		rejectsNotBefore(verifier, keyPair);
		rejectsFalseSupporter(verifier, keyPair);
		rejectsStringSupporter(verifier, keyPair);
		rejectsWrongDevice(verifier, keyPair);
		rejectsDuplicateClaims(verifier, keyPair);
		rejectsMissingSubject(verifier, keyPair);
		rejectsMalformedSegments(verifier, keyPair);
		rejectsInvalidBase64(verifier, keyPair);
		rejectsNoneAlgorithm(verifier, keyPair);
		rejectsKeyReferenceHeader(verifier, keyPair);
		rejectsFractionalTimestamps(verifier, keyPair);
		acceptsSingleAudienceArray(verifier, keyPair);
		
		System.out.println("Entitlement verifier regression checks passed.");
	}
	
	private static void acceptsValidToken(EntitlementVerifier verifier,
		KeyPair keyPair) throws Exception
	{
		String token = TestTokens.sign(keyPair.getPrivate(),
			TestTokens.validPayload(DEVICE_ID));
		SupporterEntitlement entitlement = verifier.verify(token, DEVICE_ID);
		check(DEVICE_ID.equals(entitlement.deviceId()),
			"device id must be preserved");
		check("patreon-user-1".equals(entitlement.subject()),
			"subject must be preserved");
		check(!entitlement.isExpired(System.currentTimeMillis()),
			"valid token must not be expired");
	}
	
	private static void rejectsTamperedSignature(EntitlementVerifier verifier,
		KeyPair keyPair) throws Exception
	{
		String token = TestTokens.sign(keyPair.getPrivate(),
			TestTokens.validPayload(DEVICE_ID));
		expectFailure(verifier, TestTokens.tamperSignature(token), DEVICE_ID,
			"tampered signature");
	}
	
	private static void rejectsWrongAlgorithm(EntitlementVerifier verifier,
		KeyPair keyPair) throws Exception
	{
		JsonObject header = header("RS256", TestTokens.KEY_ID);
		String token = TestTokens.sign(keyPair.getPrivate(), header,
			TestTokens.validPayload(DEVICE_ID).toString());
		expectFailure(verifier, token, DEVICE_ID, "wrong algorithm");
	}
	
	private static void rejectsWrongKeyId(EntitlementVerifier verifier,
		KeyPair keyPair) throws Exception
	{
		JsonObject header = header("ES256", "attacker-key");
		String token = TestTokens.sign(keyPair.getPrivate(), header,
			TestTokens.validPayload(DEVICE_ID).toString());
		expectFailure(verifier, token, DEVICE_ID, "wrong key id");
	}
	
	private static void rejectsWrongIssuer(EntitlementVerifier verifier,
		KeyPair keyPair) throws Exception
	{
		JsonObject payload = TestTokens.validPayload(DEVICE_ID);
		payload.addProperty("iss", "https://attacker.invalid");
		expectFailure(verifier, sign(keyPair, payload), DEVICE_ID,
			"wrong issuer");
	}
	
	private static void rejectsWrongAudience(EntitlementVerifier verifier,
		KeyPair keyPair) throws Exception
	{
		JsonObject payload = TestTokens.validPayload(DEVICE_ID);
		payload.addProperty("aud", "some-other-service");
		expectFailure(verifier, sign(keyPair, payload), DEVICE_ID,
			"wrong audience");
	}
	
	private static void rejectsExpiredToken(EntitlementVerifier verifier,
		KeyPair keyPair) throws Exception
	{
		JsonObject payload = TestTokens.validPayload(DEVICE_ID);
		long now = System.currentTimeMillis() / 1000L;
		payload.addProperty("iat", now - 1000L);
		payload.addProperty("exp", now - 10L);
		expectExpired(verifier, sign(keyPair, payload), DEVICE_ID,
			"expired token");
	}
	
	private static void rejectsFutureIssuedAt(EntitlementVerifier verifier,
		KeyPair keyPair) throws Exception
	{
		JsonObject payload = TestTokens.validPayload(DEVICE_ID);
		payload.addProperty("iat", System.currentTimeMillis() / 1000L + 3600L);
		expectFailure(verifier, sign(keyPair, payload), DEVICE_ID,
			"future iat");
	}
	
	private static void rejectsNotBefore(EntitlementVerifier verifier,
		KeyPair keyPair) throws Exception
	{
		JsonObject payload = TestTokens.validPayload(DEVICE_ID);
		payload.addProperty("nbf", System.currentTimeMillis() / 1000L + 3600L);
		expectFailure(verifier, sign(keyPair, payload), DEVICE_ID,
			"future nbf");
	}
	
	private static void rejectsFalseSupporter(EntitlementVerifier verifier,
		KeyPair keyPair) throws Exception
	{
		JsonObject payload = TestTokens.validPayload(DEVICE_ID);
		payload.addProperty("supporter", false);
		expectFailure(verifier, sign(keyPair, payload), DEVICE_ID,
			"false supporter");
	}
	
	private static void rejectsStringSupporter(EntitlementVerifier verifier,
		KeyPair keyPair) throws Exception
	{
		JsonObject payload = TestTokens.validPayload(DEVICE_ID);
		payload.addProperty("supporter", "true");
		expectFailure(verifier, sign(keyPair, payload), DEVICE_ID,
			"string supporter");
	}
	
	private static void rejectsWrongDevice(EntitlementVerifier verifier,
		KeyPair keyPair) throws Exception
	{
		expectFailure(verifier,
			sign(keyPair, TestTokens.validPayload("other-device")), DEVICE_ID,
			"wrong device");
	}
	
	private static void rejectsDuplicateClaims(EntitlementVerifier verifier,
		KeyPair keyPair) throws Exception
	{
		long now = System.currentTimeMillis() / 1000L;
		String payload = "{\"iss\":\"" + TestTokens.ISSUER + "\",\"aud\":\""
			+ TestTokens.AUDIENCE + "\",\"sub\":\"patreon-user-1\","
			+ "\"supporter\":true,\"device_id\":\"" + DEVICE_ID + "\",\"iat\":"
			+ (now - 10L) + ",\"exp\":" + (now + 3600L) + ",\"exp\":"
			+ (now + 7200L) + "}";
		JsonObject header = header("ES256", TestTokens.KEY_ID);
		expectFailure(verifier,
			TestTokens.sign(keyPair.getPrivate(), header, payload), DEVICE_ID,
			"duplicate claims");
	}
	
	private static void rejectsMissingSubject(EntitlementVerifier verifier,
		KeyPair keyPair) throws Exception
	{
		JsonObject payload = TestTokens.validPayload(DEVICE_ID);
		payload.remove("sub");
		expectFailure(verifier, sign(keyPair, payload), DEVICE_ID,
			"missing subject");
	}
	
	private static void rejectsMalformedSegments(EntitlementVerifier verifier,
		KeyPair keyPair) throws Exception
	{
		expectFailure(verifier, "not-a-jwt", DEVICE_ID, "one segment");
		expectFailure(verifier, "aaa.bbb", DEVICE_ID, "two segments");
	}
	
	private static void rejectsInvalidBase64(EntitlementVerifier verifier,
		KeyPair keyPair) throws Exception
	{
		expectFailure(verifier, "!!!.???.***", DEVICE_ID, "invalid base64");
	}
	
	private static void rejectsNoneAlgorithm(EntitlementVerifier verifier,
		KeyPair keyPair) throws Exception
	{
		JsonObject header = new JsonObject();
		header.addProperty("alg", "none");
		header.addProperty("kid", TestTokens.KEY_ID);
		String token = TestTokens.sign(keyPair.getPrivate(), header,
			TestTokens.validPayload(DEVICE_ID).toString());
		expectFailure(verifier, token, DEVICE_ID, "none algorithm");
	}
	
	private static void rejectsKeyReferenceHeader(EntitlementVerifier verifier,
		KeyPair keyPair) throws Exception
	{
		JsonObject header = header("ES256", TestTokens.KEY_ID);
		header.addProperty("jwk", "https://attacker.invalid/key");
		String token = TestTokens.sign(keyPair.getPrivate(), header,
			TestTokens.validPayload(DEVICE_ID).toString());
		expectFailure(verifier, token, DEVICE_ID, "jwk header");
	}
	
	private static void rejectsFractionalTimestamps(
		EntitlementVerifier verifier, KeyPair keyPair) throws Exception
	{
		long now = System.currentTimeMillis() / 1000L;
		String payload = "{\"iss\":\"" + TestTokens.ISSUER + "\",\"aud\":\""
			+ TestTokens.AUDIENCE + "\",\"sub\":\"patreon-user-1\","
			+ "\"supporter\":true,\"device_id\":\"" + DEVICE_ID + "\",\"iat\":"
			+ (now - 10L) + ",\"exp\":" + (now + 3600) + ".5}";
		JsonObject header = header("ES256", TestTokens.KEY_ID);
		expectFailure(verifier,
			TestTokens.sign(keyPair.getPrivate(), header, payload), DEVICE_ID,
			"fractional exp");
	}
	
	private static void acceptsSingleAudienceArray(EntitlementVerifier verifier,
		KeyPair keyPair) throws Exception
	{
		JsonObject payload = TestTokens.validPayload(DEVICE_ID);
		JsonArray audience = new JsonArray();
		audience.add(TestTokens.AUDIENCE);
		payload.add("aud", audience);
		SupporterEntitlement entitlement =
			verifier.verify(sign(keyPair, payload), DEVICE_ID);
		check(DEVICE_ID.equals(entitlement.deviceId()),
			"single-element audience array must be accepted");
	}
	
	private static JsonObject header(String alg, String kid)
	{
		JsonObject header = new JsonObject();
		header.addProperty("alg", alg);
		header.addProperty("kid", kid);
		return header;
	}
	
	private static String sign(KeyPair keyPair, JsonObject payload)
		throws Exception
	{
		return TestTokens.sign(keyPair.getPrivate(), payload);
	}
	
	private static void expectFailure(EntitlementVerifier verifier,
		String token, String deviceId, String label)
	{
		try
		{
			verifier.verify(token, deviceId);
			throw new AssertionError(label + " must be rejected");
			
		}catch(EntitlementException expected)
		{
			check(!expected.isExpired(),
				label + " must fail for another reason");
		}
	}
	
	private static void expectExpired(EntitlementVerifier verifier,
		String token, String deviceId, String label)
	{
		try
		{
			verifier.verify(token, deviceId);
			throw new AssertionError(label + " must be rejected");
			
		}catch(EntitlementException expected)
		{
			check(expected.isExpired(), label + " must be marked as expired");
		}
	}
	
	private static void check(boolean condition, String message)
	{
		if(!condition)
			throw new AssertionError(message);
	}
}
