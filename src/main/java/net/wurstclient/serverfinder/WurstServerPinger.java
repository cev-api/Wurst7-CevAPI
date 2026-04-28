/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.serverfinder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKey;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.util.Crypt;
import net.wurstclient.WurstClient;

public class WurstServerPinger
{
	private static final Pattern WHITELIST_PATTERN = Pattern.compile(
		"(\\bwhite\\s*-?\\s*list\\b|\\bwhitelisted\\b|\\bnot[_\\s-]*whitelisted\\b|multiplayer\\.disconnect\\.not_whitelisted)",
		Pattern.CASE_INSENSITIVE);
	private static final Pattern ACCESS_BLOCK_PATTERN = Pattern.compile(
		"(\\bdiscord\\b|\\bapply\\b|\\bapplication\\b|\\binvite\\b|\\bnot\\s+allowed\\b|\\bbanned\\b|\\bkicked\\b|\\bdenied\\b)",
		Pattern.CASE_INSENSITIVE);
	private static final long AUTH_THROTTLE_MS = 3000L;
	private static final int VERIFY_ATTEMPTS = 3;
	private static final long VERIFY_RETRY_BASE_DELAY_MS = 1000L;
	
	private static final AtomicInteger threadNumber = new AtomicInteger(0);
	private static final AtomicLong nextAuthTime = new AtomicLong(0L);
	private ProbeResult result;
	private boolean done = false;
	
	public void ping(String ip)
	{
		ping(ip, 25565);
	}
	
	public void ping(String ip, int port)
	{
		result = new ProbeResult(ip, port);
		
		new Thread(() -> pingInCurrentThread(ip, port),
			"Wurst Server Pinger #" + threadNumber.incrementAndGet()).start();
	}
	
	private void pingInCurrentThread(String ip, int port)
	{
		System.out.println("Pinging " + ip + ":" + port + "...");
		
		try
		{
			StatusResult status = statusPing(ip, port);
			result.online = true;
			result.motd = status.motd;
			result.versionName = status.versionName;
			result.protocolVersion = status.protocolVersion;
			result.crackedBySample = status.crackedBySample;
			result.probeDetail = result.crackedBySample
				? "CRACKED by players.sample UUID" : "Status response";
			System.out.println("Ping successful: " + ip + ":" + port + " -> "
				+ result.probeDetail);
			
		}catch(SocketTimeoutException e)
		{
			result.online = false;
			result.probeDetail = "Timeout";
			
		}catch(Exception e)
		{
			result.online = false;
			result.probeDetail =
				e.getMessage() == null ? "Ping failed" : e.getMessage();
		}
		
		done = true;
	}
	
	public boolean isStillPinging()
	{
		return !done;
	}
	
	public boolean isWorking()
	{
		return result != null && result.online;
	}
	
	public String getServerIP()
	{
		return result.host + ":" + result.port;
	}
	
	public ProbeResult getResult()
	{
		return result;
	}
	
	public static void verifyLogin(ProbeResult result)
	{
		String lastDetail = "Whitelist verification failed";
		for(int attempt = 1; attempt <= VERIFY_ATTEMPTS; attempt++)
		{
			System.out.println(
				"[Server Finder] JOIN-CHECK start " + result.ipWithPort()
					+ " attempt " + attempt + " / " + VERIFY_ATTEMPTS);
			
			try
			{
				WurstServerPinger verifier = new WurstServerPinger();
				LoginProbeResult login =
					verifier.loginProbe(result.host, result.port,
						result.protocolVersion, result.crackedBySample);
				result.crackedByLogin |= login.cracked;
				result.whitelisted = login.whitelisted;
				result.blocked = login.blocked;
				result.joinable = login.joinable;
				result.rateLimited = login.rateLimited;
				result.protocolError = login.protocolError;
				result.timedOut = login.timedOut;
				result.whitelistVerified = login.verified;
				result.probeDetail = login.detail;
				lastDetail = login.detail;
				
				System.out.println("[Server Finder] JOIN-CHECK done "
					+ result.ipWithPort() + " attempt " + attempt + " / "
					+ VERIFY_ATTEMPTS + " -> " + result.probeDetail
					+ (result.whitelisted ? " [whitelist]" : "")
					+ (result.blocked && !result.whitelisted ? " [blocked]"
						: ""));
				
				if(login.verified)
					return;
				
			}catch(Exception e)
			{
				if(result.crackedBySample)
				{
					result.crackedByLogin = true;
					result.whitelistVerified = true;
					result.joinable = true;
					result.probeDetail = "CRACKED by players.sample UUID";
					System.out.println("[Server Finder] JOIN-CHECK failed "
						+ result.ipWithPort() + " -> " + result.probeDetail);
					return;
				}
				
				lastDetail = e.getMessage() == null
					? "Whitelist verification failed" : e.getMessage();
				System.out.println("[Server Finder] JOIN-CHECK failed "
					+ result.ipWithPort() + " attempt " + attempt + " / "
					+ VERIFY_ATTEMPTS + " -> " + lastDetail);
			}
			
			if(attempt < VERIFY_ATTEMPTS)
				sleepBeforeRetry(attempt);
		}
		
		result.probeDetail = "Whitelist verification failed after "
			+ VERIFY_ATTEMPTS + " attempts: " + lastDetail;
		result.whitelistVerified = false;
		result.joinable = false;
		System.out.println("[Server Finder] JOIN-CHECK giving up "
			+ result.ipWithPort() + " -> " + result.probeDetail);
	}
	
	private static void sleepBeforeRetry(int attempt)
	{
		try
		{
			Thread.sleep(VERIFY_RETRY_BASE_DELAY_MS * attempt);
		}catch(InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}
	}
	
	private StatusResult statusPing(String host, int port) throws IOException
	{
		try(Socket socket = new Socket())
		{
			socket.connect(new InetSocketAddress(host, port), 3500);
			socket.setSoTimeout(3500);
			DataInputStream in = new DataInputStream(socket.getInputStream());
			DataOutputStream out =
				new DataOutputStream(socket.getOutputStream());
			
			sendPacket(out, packet -> {
				writeVarInt(packet, 0x00);
				writeVarInt(packet, 760);
				writeString(packet, host);
				packet.writeShort(port & 0xFFFF);
				writeVarInt(packet, 1);
			});
			sendPacket(out, packet -> writeVarInt(packet, 0x00));
			
			readVarInt(in);
			int packetId = readVarInt(in);
			if(packetId != 0x00)
				throw new IOException("Bad status packet id: " + packetId);
			
			String json = readString(in);
			JsonObject root = JsonParser.parseString(json).getAsJsonObject();
			
			StatusResult result = new StatusResult();
			result.protocolVersion = 760;
			if(root.has("version") && root.get("version").isJsonObject())
			{
				JsonObject version = root.getAsJsonObject("version");
				if(version.has("protocol"))
					result.protocolVersion = version.get("protocol").getAsInt();
				if(version.has("name"))
					result.versionName = version.get("name").getAsString();
			}
			
			if(root.has("description"))
				result.motd = toPlainText(root.get("description"));
			
			result.crackedBySample = isCrackedFromSample(root);
			return result;
		}
	}
	
	private LoginProbeResult loginProbe(String host, int port, int protocol,
		boolean sampleOfflineHint) throws Exception
	{
		try(Socket socket = new Socket())
		{
			socket.connect(new InetSocketAddress(host, port), 3500);
			socket.setSoTimeout(3500);
			DataInputStream in = new DataInputStream(socket.getInputStream());
			DataOutputStream out =
				new DataOutputStream(socket.getOutputStream());
			
			sendPacket(out, packet -> {
				writeVarInt(packet, 0x00);
				writeVarInt(packet, protocol);
				writeString(packet, host);
				packet.writeShort(port & 0xFFFF);
				writeVarInt(packet, 2);
			});
			
			sendPacket(out, packet -> {
				writeVarInt(packet, 0x00);
				writeString(packet, WurstClient.MC.getUser().getName());
				if(protocol >= 759)
					writeUuid(packet, WurstClient.MC.getUser().getProfileId());
			});
			
			int compressionThreshold = -1;
			while(true)
			{
				byte[] body = readPacketPayload(in, compressionThreshold);
				DataInputStream packetIn =
					new DataInputStream(new ByteArrayInputStream(body));
				int packetId = readVarInt(packetIn);
				
				if(packetId == 0x01)
					return authenticatedOnlineModeProbe(socket, out, packetIn,
						sampleOfflineHint);
				
				if(packetId == 0x02)
					return new LoginProbeResult(true,
						"JOINABLE (login accepted)", false, true, true, false);
				
				if(packetId == 0x03)
				{
					compressionThreshold = readVarInt(packetIn);
					continue;
				}
				
				if(packetId == 0x04)
				{
					int transactionId = readVarInt(packetIn);
					sendPacket(out, packet -> {
						writeVarInt(packet, 0x02);
						writeVarInt(packet, transactionId);
						packet.writeBoolean(false);
					});
					continue;
				}
				
				if(packetId == 0x00)
				{
					String reason = toPlainText(readJsonElement(packetIn));
					return classifyPreAcceptanceDisconnect(reason, true);
				}
				
				return LoginProbeResult
					.forFailure("PROTOCOL_ERROR before acceptance (packetId="
						+ packetId + ")", sampleOfflineHint);
			}
		}
	}
	
	private LoginProbeResult authenticatedOnlineModeProbe(Socket socket,
		DataOutputStream out, DataInputStream packetIn,
		boolean sampleOfflineHint) throws IOException
	{
		try
		{
			String serverId = readString(packetIn);
			byte[] publicKeyBytes = readByteArray(packetIn);
			byte[] challenge = readByteArray(packetIn);
			boolean shouldAuthenticate = packetIn.readBoolean();
			
			SecretKey secretKey = Crypt.generateSecretKey();
			PublicKey publicKey = Crypt.byteToPublicKey(publicKeyBytes);
			String serverHash =
				new BigInteger(Crypt.digestData(serverId, publicKey, secretKey))
					.toString(16);
			
			if(shouldAuthenticate)
			{
				waitForAuthSlot();
				WurstClient.MC.services().sessionService().joinServer(
					WurstClient.MC.getUser().getProfileId(),
					WurstClient.MC.getUser().getAccessToken(), serverHash);
			}
			
			byte[] encryptedSecret =
				Crypt.encryptUsingKey(publicKey, secretKey.getEncoded());
			byte[] encryptedChallenge =
				Crypt.encryptUsingKey(publicKey, challenge);
			
			sendPacket(out, packet -> {
				writeVarInt(packet, 0x01);
				writeByteArray(packet, encryptedSecret);
				writeByteArray(packet, encryptedChallenge);
			});
			
			Cipher decrypt = Crypt.getCipher(2, secretKey);
			Cipher encrypt = Crypt.getCipher(1, secretKey);
			DataInputStream encryptedIn = new DataInputStream(
				new CipherInputStream(socket.getInputStream(), decrypt));
			DataOutputStream encryptedOut = new DataOutputStream(
				new CipherOutputStream(socket.getOutputStream(), encrypt));
			
			return readAuthenticatedLoginResult(encryptedIn, encryptedOut,
				sampleOfflineHint);
			
		}catch(Exception e)
		{
			if(sampleOfflineHint)
				return new LoginProbeResult(true,
					"CRACKED by players.sample UUID", false);
			String msg = e.getMessage() == null ? "unknown" : e.getMessage();
			return LoginProbeResult.forFailure(
				"ONLINE-MODE (authenticated probe failed: " + msg + ")",
				sampleOfflineHint);
		}
	}
	
	private void waitForAuthSlot() throws InterruptedException
	{
		long now = System.currentTimeMillis();
		long slot;
		do
		{
			slot = nextAuthTime.get();
			if(now < slot)
			{
				Thread.sleep(slot - now);
				now = System.currentTimeMillis();
			}
		}while(!nextAuthTime.compareAndSet(slot, now + AUTH_THROTTLE_MS));
	}
	
	private LoginProbeResult readAuthenticatedLoginResult(DataInputStream in,
		DataOutputStream out, boolean sampleOfflineHint)
		throws IOException, DataFormatException
	{
		int compressionThreshold = -1;
		long deadline = System.currentTimeMillis() + 5000L;
		while(System.currentTimeMillis() < deadline)
		{
			byte[] body = readPacketPayload(in, compressionThreshold);
			DataInputStream packetIn =
				new DataInputStream(new ByteArrayInputStream(body));
			int packetId = readVarInt(packetIn);
			
			if(packetId == 0x00)
			{
				String reason = toPlainText(readJsonElement(packetIn));
				return classifyPreAcceptanceDisconnect(reason,
					sampleOfflineHint);
			}
			
			if(packetId == 0x02)
				// Login Success means the account has been accepted. Stop
				// immediately; no post-login packet waiting/chunk loading.
				return new LoginProbeResult(sampleOfflineHint,
					"JOINABLE (login accepted)", false, true, true, false);
			
			if(packetId == 0x03)
			{
				compressionThreshold = readVarInt(packetIn);
				continue;
			}
			
			if(packetId == 0x04)
			{
				int transactionId = readVarInt(packetIn);
				sendPacket(out, packet -> {
					writeVarInt(packet, 0x02);
					writeVarInt(packet, transactionId);
					packet.writeBoolean(false);
				});
			}
		}
		
		LoginProbeResult timeout = LoginProbeResult.forFailure(
			"ONLINE-MODE (authenticated probe timed out)", sampleOfflineHint);
		timeout.timedOut = true;
		return timeout;
	}
	
	private LoginProbeResult readEarlyPostLoginProbe(DataInputStream in,
		DataOutputStream out, int compressionThreshold, boolean cracked,
		String acceptedDetail) throws IOException, DataFormatException
	{
		return new LoginProbeResult(cracked, "JOINABLE (login accepted)", false,
			true, true, false);
	}
	
	private String packetText(byte[] body)
	{
		String utf8 = new String(body, StandardCharsets.UTF_8);
		String latin1 = new String(body, StandardCharsets.ISO_8859_1);
		return utf8 + "\n" + latin1;
	}
	
	private boolean isWhitelistText(String text)
	{
		return text != null && WHITELIST_PATTERN.matcher(text).find();
	}
	
	private boolean isBlockedText(String text)
	{
		if(text == null || text.isBlank())
			return false;
		return WHITELIST_PATTERN.matcher(text).find()
			|| ACCESS_BLOCK_PATTERN.matcher(text).find();
	}
	
	private LoginProbeResult classifyPreAcceptanceDisconnect(String reason,
		boolean crackedHint)
	{
		String safeReason =
			(reason == null || reason.isBlank()) ? "no reason" : reason;
		if(isWhitelistText(safeReason))
			return new LoginProbeResult(crackedHint,
				"WHITELISTED (" + safeReason + ")", true, true, false, false);
		if(isBlockedText(safeReason))
			return new LoginProbeResult(crackedHint,
				"BLOCKED (" + safeReason + ")", false, true, false, true);
		return new LoginProbeResult(crackedHint,
			"DISCONNECTED BEFORE ACCEPTANCE (" + safeReason + ")", false, true,
			false, true);
	}
	
	private static boolean isRateLimitedText(String text)
	{
		if(text == null || text.isBlank())
			return false;
		String lower = text.toLowerCase();
		return lower.contains("ratelimiter") || lower.contains("rate limit")
			|| lower.contains("too many requests") || lower.contains("429");
	}
	
	private static boolean isProtocolErrorText(String text)
	{
		if(text == null || text.isBlank())
			return false;
		String lower = text.toLowerCase();
		return lower.contains("badly compressed packet")
			|| lower.contains("unknown packet id")
			|| lower.contains("decoderexception")
			|| lower.contains("packet status/clientbound");
	}
	
	private boolean isCrackedFromSample(JsonObject root)
	{
		if(!root.has("players") || !root.get("players").isJsonObject())
			return false;
		
		JsonObject players = root.getAsJsonObject("players");
		if(!players.has("sample") || !players.get("sample").isJsonArray())
			return false;
		
		JsonArray sample = players.getAsJsonArray("sample");
		for(JsonElement entryEl : sample)
		{
			if(!entryEl.isJsonObject())
				continue;
			
			JsonObject entry = entryEl.getAsJsonObject();
			if(!entry.has("name") || !entry.has("id"))
				continue;
			
			String name = entry.get("name").getAsString();
			String id = entry.get("id").getAsString();
			try
			{
				UUID reported = UUID.fromString(id);
				UUID offline = offlineUuid(name);
				if(reported.equals(offline))
					return true;
				
			}catch(Exception ignored)
			{}
		}
		
		return false;
	}
	
	private UUID offlineUuid(String name)
	{
		return UUID.nameUUIDFromBytes(
			("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
	}
	
	private JsonElement readJsonElement(DataInputStream in) throws IOException
	{
		return JsonParser.parseString(readString(in));
	}
	
	private String toPlainText(JsonElement value)
	{
		if(value == null || value.isJsonNull())
			return "";
		if(value.isJsonPrimitive())
			return value.getAsString();
		if(value.isJsonObject())
		{
			JsonObject obj = value.getAsJsonObject();
			StringBuilder out = new StringBuilder();
			if(obj.has("text"))
				out.append(obj.get("text").getAsString());
			if(obj.has("translate"))
				out.append(obj.get("translate").getAsString());
			if(obj.has("with") && obj.get("with").isJsonArray())
			{
				for(JsonElement el : obj.getAsJsonArray("with"))
					out.append(' ').append(toPlainText(el));
			}
			if(obj.has("extra") && obj.get("extra").isJsonArray())
			{
				for(JsonElement el : obj.getAsJsonArray("extra"))
					out.append(toPlainText(el));
			}
			return out.toString();
		}
		if(value.isJsonArray())
		{
			StringBuilder out = new StringBuilder();
			for(JsonElement el : value.getAsJsonArray())
				out.append(toPlainText(el));
			return out.toString();
		}
		return value.toString();
	}
	
	private interface PacketWriter
	{
		void write(DataOutputStream packet) throws IOException;
	}
	
	private void sendPacket(DataOutputStream out, PacketWriter writer)
		throws IOException
	{
		ByteBufferBackedDataOutput packet = new ByteBufferBackedDataOutput();
		writer.write(packet);
		byte[] body = packet.toByteArray();
		writeVarInt(out, body.length);
		out.write(body);
		out.flush();
	}
	
	private void writeString(DataOutputStream out, String value)
		throws IOException
	{
		byte[] data = value.getBytes(StandardCharsets.UTF_8);
		writeByteArray(out, data);
	}
	
	private void writeByteArray(DataOutputStream out, byte[] data)
		throws IOException
	{
		writeVarInt(out, data.length);
		out.write(data);
	}
	
	private void writeUuid(DataOutputStream out, UUID uuid) throws IOException
	{
		out.writeLong(uuid.getMostSignificantBits());
		out.writeLong(uuid.getLeastSignificantBits());
	}
	
	private String readString(DataInputStream in) throws IOException
	{
		int len = readVarInt(in);
		if(len < 0 || len > 32767)
			throw new IOException("String length invalid: " + len);
		byte[] data = new byte[len];
		in.readFully(data);
		return new String(data, StandardCharsets.UTF_8);
	}
	
	private byte[] readByteArray(DataInputStream in) throws IOException
	{
		int len = readVarInt(in);
		if(len < 0 || len > 1048576)
			throw new IOException("Byte array length invalid: " + len);
		byte[] data = new byte[len];
		in.readFully(data);
		return data;
	}
	
	private void writeVarInt(DataOutputStream out, int value) throws IOException
	{
		while((value & -128) != 0)
		{
			out.writeByte(value & 127 | 128);
			value >>>= 7;
		}
		out.writeByte(value);
	}
	
	private int readVarInt(InputStream in) throws IOException
	{
		int numRead = 0;
		int result = 0;
		int read;
		do
		{
			read = in.read();
			if(read < 0)
				throw new EOFException("Unexpected end of stream");
			int value = read & 0b01111111;
			result |= value << (7 * numRead);
			
			numRead++;
			if(numRead > 5)
				throw new EOFException("VarInt too long");
			
		}while((read & 0b10000000) != 0);
		
		return result;
	}
	
	private byte[] readPacketPayload(DataInputStream in,
		int compressionThreshold) throws IOException, DataFormatException
	{
		int length = readVarInt(in);
		byte[] data = new byte[length];
		in.readFully(data);
		
		if(compressionThreshold < 0)
			return data;
		
		ByteArrayInputStream packetStream = new ByteArrayInputStream(data);
		int dataLength = readVarInt(packetStream);
		if(dataLength == 0)
			return packetStream.readAllBytes();
		
		byte[] compressed = packetStream.readAllBytes();
		Inflater inflater = new Inflater();
		inflater.setInput(compressed);
		ByteArrayOutputStream out = new ByteArrayOutputStream(dataLength);
		byte[] buffer = new byte[8192];
		while(!inflater.finished())
		{
			int read = inflater.inflate(buffer);
			if(read <= 0)
				break;
			out.write(buffer, 0, read);
		}
		inflater.end();
		return out.toByteArray();
	}
	
	public static final class ProbeResult
	{
		public final String host;
		public final int port;
		public boolean online;
		public boolean crackedBySample;
		public boolean crackedByLogin;
		public boolean whitelisted;
		public boolean blocked;
		public boolean whitelistVerified;
		public boolean joinable;
		public boolean rateLimited;
		public boolean protocolError;
		public boolean timedOut;
		public int protocolVersion = 760;
		public String motd = "";
		public String versionName = "";
		public String probeDetail = "";
		
		private ProbeResult(String host, int port)
		{
			this.host = host;
			this.port = port;
		}
		
		public String ipWithPort()
		{
			return host + ":" + port;
		}
		
		public boolean isCracked()
		{
			return crackedBySample || crackedByLogin;
		}
	}
	
	private static final class StatusResult
	{
		private int protocolVersion;
		private String motd = "";
		private String versionName = "";
		private boolean crackedBySample;
	}
	
	private static final class LoginProbeResult
	{
		private final boolean cracked;
		private final String detail;
		private final boolean whitelisted;
		private final boolean verified;
		private final boolean joinable;
		private final boolean blocked;
		private boolean rateLimited;
		private boolean protocolError;
		private boolean timedOut;
		
		private LoginProbeResult(boolean cracked, String detail,
			boolean whitelisted)
		{
			this(cracked, detail, whitelisted, true, !whitelisted, whitelisted);
		}
		
		private LoginProbeResult(boolean cracked, String detail,
			boolean whitelisted, boolean verified)
		{
			this(cracked, detail, whitelisted, verified, !whitelisted,
				whitelisted);
		}
		
		private LoginProbeResult(boolean cracked, String detail,
			boolean whitelisted, boolean verified, boolean joinable,
			boolean blocked)
		{
			this.cracked = cracked;
			this.detail = detail;
			this.whitelisted = whitelisted;
			this.verified = verified;
			this.joinable = joinable;
			this.blocked = blocked;
		}
		
		private static LoginProbeResult forFailure(String detail,
			boolean sampleOfflineHint)
		{
			LoginProbeResult result = new LoginProbeResult(sampleOfflineHint,
				detail, false, true, false, true);
			result.rateLimited = isRateLimitedText(detail);
			result.protocolError = isProtocolErrorText(detail);
			String lower = detail.toLowerCase();
			result.timedOut =
				lower.contains("timed out") || lower.contains("timeout");
			return result;
		}
	}
	
	private static final class ByteBufferBackedDataOutput
		extends DataOutputStream
	{
		private final java.io.ByteArrayOutputStream bytes =
			new java.io.ByteArrayOutputStream();
		
		private ByteBufferBackedDataOutput()
		{
			super(new java.io.ByteArrayOutputStream());
			out = bytes;
		}
		
		private byte[] toByteArray()
		{
			return bytes.toByteArray();
		}
	}
}
