/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.patreon;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Verifies the ES256 entitlement JWTs issued by the Cevapi Cloudflare Worker.
 *
 * <p>
 * The public key is pinned. A key downloaded at runtime is never trusted, even
 * if it is served by the correct issuer.
 */
public final class EntitlementVerifier
{
	public static final String DEFAULT_ISSUER =
		"https://cevapi.cloudy-29c.workers.dev";
	public static final String DEFAULT_AUDIENCE = "cevapi-supporter";
	public static final String DEFAULT_KEY_ID = "cevapi-p256-v1";
	
	private static final String PUBLIC_KEY_X =
		"vGurz3FqrbvYMLco2mQOtNIYASvcGeTBNpaZJvwqCt8";
	private static final String PUBLIC_KEY_Y =
		"yDAzfNUlJqoENV1we0HBk5ptWokie9uGS4X4gZBAB-M";
	
	private static final int MAX_TOKEN_LENGTH = 32 * 1024;
	private static final int MAX_SEGMENT_LENGTH = 16 * 1024;
	private static final long CLOCK_SKEW_SECONDS = 300L;
	
	private final String issuer;
	private final String audience;
	private final String keyId;
	private final ECPublicKey publicKey;
	
	/**
	 * Creates a verifier for testing with an arbitrary P-256 public key.
	 */
	public EntitlementVerifier(String issuer, String audience, String keyId,
		ECPublicKey publicKey)
	{
		this.issuer = issuer;
		this.audience = audience;
		this.keyId = keyId;
		this.publicKey = publicKey;
	}
	
	public static EntitlementVerifier pinned()
	{
		return new EntitlementVerifier(DEFAULT_ISSUER, DEFAULT_AUDIENCE,
			DEFAULT_KEY_ID, decodePublicKey(PUBLIC_KEY_X, PUBLIC_KEY_Y));
	}
	
	public String getIssuer()
	{
		return issuer;
	}
	
	public String getAudience()
	{
		return audience;
	}
	
	public String getKeyId()
	{
		return keyId;
	}
	
	/**
	 * Verifies the token and returns its trusted claims. The caller must still
	 * check {@link SupporterEntitlement#isExpired(long)} when using a
	 * previously verified token.
	 */
	public SupporterEntitlement verify(String token, String expectedDeviceId)
		throws EntitlementException
	{
		if(token == null || token.isEmpty())
			throw new EntitlementException(
				"No supporter entitlement was provided.");
		
		if(token.length() > MAX_TOKEN_LENGTH)
			throw new EntitlementException(
				"The supporter entitlement is too large.");
		
		if(expectedDeviceId == null || expectedDeviceId.isBlank())
			throw new EntitlementException(
				"No registered device identifier is available.");
		
		String[] segments = token.split("\\.", -1);
		if(segments.length != 3)
			throw new EntitlementException(
				"The supporter entitlement must have three segments.");
		
		byte[] headerBytes = decodeSegment(segments[0]);
		byte[] payloadBytes = decodeSegment(segments[1]);
		byte[] signatureBytes = decodeSegment(segments[2]);
		
		JsonObject header = parseObject(headerBytes, "header");
		verifyHeader(header);
		
		byte[] signingInput = (segments[0] + "." + segments[1])
			.getBytes(StandardCharsets.US_ASCII);
		verifySignature(signingInput, signatureBytes);
		
		JsonObject payload = parseObject(payloadBytes, "payload");
		return readClaims(payload, token, expectedDeviceId);
	}
	
	private void verifyHeader(JsonObject header) throws EntitlementException
	{
		String alg = requireString(header, "alg", "header");
		if(!"ES256".equals(alg))
			throw new EntitlementException(
				"The supporter entitlement uses an unsupported algorithm.");
		
		String kid = requireString(header, "kid", "header");
		if(!keyId.equals(kid))
			throw new EntitlementException(
				"The supporter entitlement uses an unknown signing key.");
		
		JsonElement typ = header.get("typ");
		if(typ != null)
		{
			if(!isString(typ))
				throw new EntitlementException(
					"The supporter entitlement has an invalid type.");
			String type = typ.getAsString();
			if(!"JWT".equalsIgnoreCase(type)
				&& !"at+jwt".equalsIgnoreCase(type))
				throw new EntitlementException(
					"The supporter entitlement has an unsupported type.");
		}
		
		// Key-reference and critical-extension headers could be used to make
		// a token look like it trusts a different key. The pinned key is the
		// only trust anchor, so these headers are rejected outright.
		for(String forbidden : new String[]{"jku", "jwk", "x5u", "x5c", "crit"})
			if(header.has(forbidden))
				throw new EntitlementException(
					"The supporter entitlement contains unsupported header '"
						+ forbidden + "'.");
	}
	
	private SupporterEntitlement readClaims(JsonObject payload, String token,
		String expectedDeviceId) throws EntitlementException
	{
		String iss = requireString(payload, "iss", "payload");
		if(!issuer.equals(iss))
			throw new EntitlementException(
				"The supporter entitlement was issued by the wrong issuer.");
		
		String aud = readAudience(payload);
		if(!audience.equals(aud))
			throw new EntitlementException(
				"The supporter entitlement is scoped to the wrong audience.");
		
		String sub = requireString(payload, "sub", "payload");
		if(sub.isBlank())
			throw new EntitlementException(
				"The supporter entitlement has no subject.");
		
		JsonElement supporter = payload.get("supporter");
		if(supporter == null || !supporter.isJsonPrimitive()
			|| !supporter.getAsJsonPrimitive().isBoolean()
			|| !supporter.getAsBoolean())
			throw new EntitlementException(
				"The supporter entitlement does not confirm support.");
		
		String deviceId = requireString(payload, "device_id", "payload");
		if(!expectedDeviceId.equals(deviceId))
			throw new EntitlementException(
				"The supporter entitlement belongs to a different device.");
		
		long now = System.currentTimeMillis() / 1000L;
		long exp = requireLong(payload, "exp", "payload");
		long iat = requireLong(payload, "iat", "payload");
		
		if(exp <= 0 || iat <= 0 || iat > exp)
			throw new EntitlementException(
				"The supporter entitlement has invalid timestamps.");
		
		if(exp <= now)
			throw new EntitlementException(
				"Your supporter entitlement has expired.", true);
		
		if(iat > now + CLOCK_SKEW_SECONDS)
			throw new EntitlementException(
				"The supporter entitlement was issued in the future.");
		
		JsonElement nbf = payload.get("nbf");
		if(nbf != null)
		{
			long notBefore = requireLong(payload, "nbf", "payload");
			if(notBefore > now + CLOCK_SKEW_SECONDS)
				throw new EntitlementException(
					"The supporter entitlement is not valid yet.");
		}
		
		return new SupporterEntitlement(token, sub, deviceId, iat, exp);
	}
	
	private static String readAudience(JsonObject payload)
		throws EntitlementException
	{
		JsonElement aud = payload.get("aud");
		if(aud == null)
			throw new EntitlementException(
				"The supporter entitlement has no audience.");
		
		if(isString(aud))
			return aud.getAsString();
		
		if(!aud.isJsonArray())
			throw new EntitlementException(
				"The supporter entitlement has an invalid audience.");
		
		JsonArray array = aud.getAsJsonArray();
		if(array.size() != 1 || !isString(array.get(0)))
			throw new EntitlementException(
				"The supporter entitlement has an invalid audience.");
		
		return array.get(0).getAsString();
	}
	
	private void verifySignature(byte[] signingInput, byte[] rawSignature)
		throws EntitlementException
	{
		byte[] derSignature = rawSignatureToDer(rawSignature);
		try
		{
			Signature signature = Signature.getInstance("SHA256withECDSA");
			signature.initVerify((PublicKey)publicKey);
			signature.update(signingInput);
			if(!signature.verify(derSignature))
				throw new EntitlementException(
					"The supporter entitlement signature is invalid.");
			
		}catch(EntitlementException e)
		{
			throw e;
			
		}catch(Exception e)
		{
			throw new EntitlementException(
				"The supporter entitlement signature could not be verified.",
				e);
		}
	}
	
	private static JsonObject parseObject(byte[] json, String part)
		throws EntitlementException
	{
		JsonElement element =
			StrictJson.parse(new String(json, StandardCharsets.UTF_8));
		if(!element.isJsonObject())
			throw new EntitlementException(
				"The supporter entitlement " + part + " is not a JSON object.");
		return element.getAsJsonObject();
	}
	
	private static byte[] decodeSegment(String segment)
		throws EntitlementException
	{
		if(segment.isEmpty() || segment.length() > MAX_SEGMENT_LENGTH)
			throw new EntitlementException(
				"The supporter entitlement has an invalid segment length.");
		
		for(int i = 0; i < segment.length(); i++)
		{
			char c = segment.charAt(i);
			boolean valid = c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z'
				|| c >= '0' && c <= '9' || c == '-' || c == '_';
			if(!valid)
				throw new EntitlementException(
					"The supporter entitlement is not correctly encoded.");
		}
		
		try
		{
			return Base64.getUrlDecoder().decode(segment);
			
		}catch(IllegalArgumentException e)
		{
			throw new EntitlementException(
				"The supporter entitlement is not correctly encoded.", e);
		}
	}
	
	/**
	 * JOSE ES256 signatures are raw {@code R || S} values, while Java's
	 * {@code SHA256withECDSA} expects ASN.1 DER. This converts between them
	 * without adding a JWT library.
	 */
	static byte[] rawSignatureToDer(byte[] raw) throws EntitlementException
	{
		if(raw.length != 64)
			throw new EntitlementException(
				"The supporter entitlement signature has an invalid length.");
		
		BigInteger r = new BigInteger(1, Arrays.copyOfRange(raw, 0, 32));
		BigInteger s = new BigInteger(1, Arrays.copyOfRange(raw, 32, 64));
		byte[] rBytes = r.toByteArray();
		byte[] sBytes = s.toByteArray();
		
		int sequenceLength = 2 + rBytes.length + 2 + sBytes.length;
		if(sequenceLength >= 128)
			throw new EntitlementException(
				"The supporter entitlement signature is malformed.");
		
		ByteArrayOutputStream out =
			new ByteArrayOutputStream(2 + sequenceLength);
		out.write(0x30);
		out.write(sequenceLength);
		out.write(0x02);
		out.write(rBytes.length);
		out.writeBytes(rBytes);
		out.write(0x02);
		out.write(sBytes.length);
		out.writeBytes(sBytes);
		return out.toByteArray();
	}
	
	static ECPublicKey decodePublicKey(String xBase64, String yBase64)
	{
		try
		{
			byte[] xBytes = Base64.getUrlDecoder().decode(xBase64);
			byte[] yBytes = Base64.getUrlDecoder().decode(yBase64);
			if(xBytes.length != 32 || yBytes.length != 32)
				throw new IllegalArgumentException(
					"P-256 coordinates must be 32 bytes.");
			
			AlgorithmParameters parameters =
				AlgorithmParameters.getInstance("EC");
			parameters.init(new ECGenParameterSpec("secp256r1"));
			ECParameterSpec ecParameters =
				parameters.getParameterSpec(ECParameterSpec.class);
			
			ECPoint point = new ECPoint(new BigInteger(1, xBytes),
				new BigInteger(1, yBytes));
			return (ECPublicKey)KeyFactory.getInstance("EC")
				.generatePublic(new ECPublicKeySpec(point, ecParameters));
			
		}catch(Exception e)
		{
			throw new ExceptionInInitializerError(e);
		}
	}
	
	private static String requireString(JsonObject object, String name,
		String part) throws EntitlementException
	{
		JsonElement element = object.get(name);
		if(element == null || !isString(element))
			throw new EntitlementException("The supporter entitlement " + part
				+ " is missing '" + name + "'.");
		return element.getAsString();
	}
	
	private static long requireLong(JsonObject object, String name, String part)
		throws EntitlementException
	{
		JsonElement element = object.get(name);
		if(element == null || !element.isJsonPrimitive())
			throw new EntitlementException("The supporter entitlement " + part
				+ " is missing '" + name + "'.");
		
		JsonPrimitive primitive = element.getAsJsonPrimitive();
		if(!primitive.isNumber())
			throw new EntitlementException("The supporter entitlement " + part
				+ " has a non-numeric '" + name + "'.");
		
		try
		{
			BigDecimal value = primitive.getAsBigDecimal();
			if(value.stripTrailingZeros().scale() > 0)
				throw new ArithmeticException("not an integer");
			return value.longValueExact();
			
		}catch(ArithmeticException | NumberFormatException e)
		{
			throw new EntitlementException("The supporter entitlement " + part
				+ " has an invalid '" + name + "'.");
		}
	}
	
	private static boolean isString(JsonElement element)
	{
		return element != null && element.isJsonPrimitive()
			&& element.getAsJsonPrimitive().isString();
	}
}
