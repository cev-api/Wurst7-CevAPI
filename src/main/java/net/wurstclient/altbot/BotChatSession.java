/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.altbot;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Instant;
import java.util.BitSet;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatSessionUpdatePacket;

/**
 * Signs chat messages with the account's real private key, mirroring the
 * vanilla client's chat-signing format for Minecraft 26.2. If no certificates
 * are available the bot falls back to unsigned chat, which is what servers
 * that don't enforce secure chat expect anyway.
 */
final class BotChatSession
{
	private final UUID profileId;
	private final UUID sessionId = UUID.randomUUID();
	private final PrivateKey privateKey;
	private final PlayerCertificates certs;
	private int messageIndex;
	
	BotChatSession(UUID profileId, PlayerCertificates certs)
	{
		this.profileId = profileId;
		this.certs = certs;
		privateKey = certs != null && certs.getKeyPair() != null
			? certs.getKeyPair().getPrivate() : null;
	}
	
	boolean canSign()
	{
		return privateKey != null && certs != null;
	}
	
	/** Sends the chat session update when entering the play state. */
	void sendSessionUpdate(Session session)
	{
		if(certs == null || certs.getKeyPair() == null
			|| certs.getKeyPair().getPublic() == null
			|| certs.getPublicKeySignature() == null)
			return;
		
		session.send(new ServerboundChatSessionUpdatePacket(sessionId,
			certs.getExpireTimeMs(), certs.getKeyPair().getPublic(),
			certs.getPublicKeySignature()));
	}
	
	/**
	 * Sends chat or a command, depending on whether the text starts with "/".
	 *
	 * @return true if a packet was sent.
	 */
	boolean send(Session session, String text)
	{
		if(text.startsWith("/"))
		{
			String command = text.substring(1).trim();
			if(command.isEmpty())
				return false;
			session.send(new ServerboundChatCommandPacket(command));
			return true;
		}
		
		long timestamp = Instant.now().toEpochMilli();
		long salt = ThreadLocalRandom.current().nextLong();
		if(privateKey == null)
		{
			session.send(new ServerboundChatPacket(text, timestamp, salt, null,
				0, new BitSet(20), 0));
			return true;
		}
		
		try
		{
			byte[] signature = sign(text, timestamp, salt, messageIndex++);
			session.send(new ServerboundChatPacket(text, timestamp, salt,
				signature, 0, new BitSet(20), 1));
			return true;
			
		}catch(Exception e)
		{
			AltBotUtils.log("chat", "Chat signing failed: " + e.getMessage());
			// Fall back to an unsigned message rather than dropping it.
			session.send(new ServerboundChatPacket(text, timestamp, salt, null,
				0, new BitSet(20), 0));
			return true;
		}
	}
	
	private byte[] sign(String message, long timestampMs, long salt, int index)
		throws Exception
	{
		Signature signature = Signature.getInstance("SHA256withRSA");
		signature.initSign(privateKey);
		putInt(signature, 1);
		putUuid(signature, profileId);
		putUuid(signature, sessionId);
		putInt(signature, index);
		putLong(signature, salt);
		putLong(signature, Math.floorDiv(timestampMs, 1000L));
		byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
		putInt(signature, bytes.length);
		signature.update(bytes);
		putInt(signature, 0); // no last-seen signatures
		return signature.sign();
	}
	
	private static void putInt(Signature signature, int value) throws Exception
	{
		signature
			.update(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
	}
	
	private static void putLong(Signature signature, long value)
		throws Exception
	{
		signature
			.update(ByteBuffer.allocate(Long.BYTES).putLong(value).array());
	}
	
	private static void putUuid(Signature signature, UUID value)
		throws Exception
	{
		ByteBuffer buffer = ByteBuffer.allocate(16);
		buffer.putLong(value.getMostSignificantBits());
		buffer.putLong(value.getLeastSignificantBits());
		signature.update(buffer.array());
	}
}
