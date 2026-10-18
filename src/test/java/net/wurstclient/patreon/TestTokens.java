/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.patreon;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

import com.google.gson.JsonObject;

final class TestTokens
{
	static final String ISSUER = "https://cevapi.test";
	static final String AUDIENCE = "cevapi-supporter";
	static final String KEY_ID = "test-key";
	
	private TestTokens()
	{}
	
	static KeyPair generateKeyPair() throws Exception
	{
		KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
		generator.initialize(new ECGenParameterSpec("secp256r1"));
		return generator.generateKeyPair();
	}
	
	static String sign(PrivateKey privateKey, JsonObject header,
		String payloadJson) throws Exception
	{
		String headerJson = header.toString();
		String encodedHeader =
			base64Url(headerJson.getBytes(StandardCharsets.UTF_8));
		String encodedPayload =
			base64Url(payloadJson.getBytes(StandardCharsets.UTF_8));
		String signingInput = encodedHeader + "." + encodedPayload;
		
		Signature signature = Signature.getInstance("SHA256withECDSA");
		signature.initSign(privateKey);
		signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
		byte[] raw = derToRaw(signature.sign());
		return signingInput + "." + base64Url(raw);
	}
	
	static String sign(PrivateKey privateKey, JsonObject payload)
		throws Exception
	{
		JsonObject header = new JsonObject();
		header.addProperty("alg", "ES256");
		header.addProperty("kid", KEY_ID);
		header.addProperty("typ", "JWT");
		return sign(privateKey, header, payload.toString());
	}
	
	static JsonObject validPayload(String deviceId)
	{
		long now = System.currentTimeMillis() / 1000L;
		JsonObject payload = new JsonObject();
		payload.addProperty("iss", ISSUER);
		payload.addProperty("aud", AUDIENCE);
		payload.addProperty("sub", "patreon-user-1");
		payload.addProperty("supporter", true);
		payload.addProperty("device_id", deviceId);
		payload.addProperty("iat", now - 10L);
		payload.addProperty("exp", now + 3600L);
		return payload;
	}
	
	static String base64Url(byte[] bytes)
	{
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
	
	static String tamperSignature(String token)
	{
		String[] parts = token.split("\\.", -1);
		byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
		signature[0] ^= 0x01;
		return parts[0] + "." + parts[1] + "." + base64Url(signature);
	}
	
	static byte[] derToRaw(byte[] der)
	{
		int offset = 0;
		if(der[offset++] != 0x30)
			throw new IllegalArgumentException("Not a DER sequence");
		int sequenceLength = der[offset++] & 0xFF;
		if((sequenceLength & 0x80) != 0)
			throw new IllegalArgumentException("Unsupported DER length");
		
		if(der[offset++] != 0x02)
			throw new IllegalArgumentException("Expected R integer");
		int rLength = der[offset++] & 0xFF;
		BigInteger r = new BigInteger(1,
			java.util.Arrays.copyOfRange(der, offset, offset + rLength));
		offset += rLength;
		
		if(der[offset++] != 0x02)
			throw new IllegalArgumentException("Expected S integer");
		int sLength = der[offset++] & 0xFF;
		BigInteger s = new BigInteger(1,
			java.util.Arrays.copyOfRange(der, offset, offset + sLength));
		
		byte[] raw = new byte[64];
		writeFixed(raw, 0, r);
		writeFixed(raw, 32, s);
		return raw;
	}
	
	private static void writeFixed(byte[] target, int offset, BigInteger value)
	{
		byte[] bytes = value.toByteArray();
		int start = 0;
		while(start < bytes.length - 1 && bytes[start] == 0)
			start++;
		int length = bytes.length - start;
		if(length > 32)
			throw new IllegalArgumentException("Coordinate too large");
		System.arraycopy(bytes, start, target, offset + 32 - length, length);
	}
}
