/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.altbot;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Chat-signing key pair for a real account, fetched from
 * <code>api.minecraftservices.com/player/certificates</code> using the
 * account's access token. Used to sign chat messages the same way the real
 * client does. Never logged.
 */
public final class PlayerCertificates
{
	private final long expiresAt;
	private final KeyPair keyPair;
	private final byte[] publicKeySignature;
	
	private PlayerCertificates(long expiresAt, KeyPair keyPair,
		byte[] publicKeySignature)
	{
		this.expiresAt = expiresAt;
		this.keyPair = keyPair;
		this.publicKeySignature = publicKeySignature;
	}
	
	public long getExpireTimeMs()
	{
		return expiresAt;
	}
	
	public KeyPair getKeyPair()
	{
		return keyPair;
	}
	
	public byte[] getPublicKeySignature()
	{
		return publicKeySignature;
	}
	
	/**
	 * Fetches fresh chat certificates for the given Minecraft access token.
	 *
	 * @return the certificates, or null if the server rejected the request or
	 *         the account has none (signed chat will then be unavailable).
	 */
	public static PlayerCertificates fetch(String accessToken)
	{
		if(accessToken == null || accessToken.isBlank())
			return null;
		
		try
		{
			HttpClient client = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(10)).build();
			HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(
					"https://api.minecraftservices.com/player/certificates"))
				.header("Authorization", "Bearer " + accessToken)
				.timeout(Duration.ofSeconds(10))
				.POST(HttpRequest.BodyPublishers.noBody()).build();
			HttpResponse<String> response = client.send(request,
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if(response.statusCode() != 200)
				return null;
			
			JsonObject json =
				JsonParser.parseString(response.body()).getAsJsonObject();
			long expiresAt = Instant.parse(json.get("expiresAt").getAsString())
				.toEpochMilli();
			JsonObject keyPair = json.getAsJsonObject("keyPair");
			KeyFactory factory = KeyFactory.getInstance("RSA");
			PrivateKey privateKey =
				factory.generatePrivate(new PKCS8EncodedKeySpec(
					decodePem(keyPair.get("privateKey").getAsString())));
			PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(
				decodePem(keyPair.get("publicKey").getAsString())));
			byte[] signature = Base64.getDecoder()
				.decode(json.get("publicKeySignatureV2").getAsString());
			
			return new PlayerCertificates(expiresAt,
				new KeyPair(publicKey, privateKey), signature);
			
		}catch(Exception e)
		{
			AltBotUtils.log("cert",
				"Chat certificates unavailable: " + e.getMessage());
			return null;
		}
	}
	
	private static byte[] decodePem(String pem)
	{
		String base64 = pem.replaceAll("-----BEGIN [A-Z ]*KEY-----", "")
			.replaceAll("-----END [A-Z ]*KEY-----", "").replaceAll("\\s", "");
		return Base64.getDecoder().decode(base64);
	}
}
