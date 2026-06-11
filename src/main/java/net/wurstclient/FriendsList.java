/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.google.gson.JsonArray;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.commands.FriendsCmd;
import net.wurstclient.events.ChatInputListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hacks.PlayerEspHack;
import net.wurstclient.hacks.WaypointsHack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.json.JsonException;
import net.wurstclient.util.json.JsonUtils;
import net.wurstclient.waypoints.Waypoint;
import net.wurstclient.waypoints.WaypointDimension;

public class FriendsList implements UpdateListener, ChatInputListener
{
	private static final String PING_HEADER = "FPNG";
	private static final String AUTO_PING_HEADER = "FPNA";
	private static final byte PING_FORMAT_VERSION = 2;
	private static final byte FLAG_REPLY = 0x1;
	private static final byte DIM_OVERWORLD = 0;
	private static final byte DIM_NETHER = 1;
	private static final byte DIM_END = 2;
	private static final byte DIM_OTHER = 3;
	private static final SecureRandom SECURE_RANDOM = new SecureRandom();
	private static final Pattern WHISPER_TO_YOU_CHAT = Pattern.compile(
		"^.*?([A-Za-z0-9_\\-*.]{1,24})\\s+"
			+ "(?:whispers|msgs|messages)\\s+(?:to\\s+)?you:\\s+(.+)$",
		Pattern.CASE_INSENSITIVE);
	private static final Pattern INCOMING_ARROW_WHISPER_CHAT = Pattern.compile(
		"^\\s*(?:(?:\\[[^\\]]{1,32}\\]|‹[^›]{1,32}›)\\s*)*(?:←|<-)\\s*"
			+ "(?:(?:\\[[^\\]]{1,32}\\]|‹[^›]{1,32}›)\\s*)*"
			+ "([A-Za-z0-9_\\-*.]{1,24})\\s*[»>]\\s+(.+)$",
		Pattern.CASE_INSENSITIVE);
	private static final Pattern FROM_WHISPER_CHAT = Pattern.compile(
		"^(?:(?:\\[[^\\]]{1,32}\\]|‹[^›]{1,32}›)\\s*)*from\\s+"
			+ "([A-Za-z0-9_\\-*.]{1,24})\\s*[:>]\\s+(.+)$",
		Pattern.CASE_INSENSITIVE);
	private static final Pattern SPEAKER_ARROW_CHAT = Pattern.compile(
		"^(?:(?:\\[[^\\]]{1,32}\\]|‹[^›]{1,32}›)\\s*)*"
			+ "([A-Za-z0-9_\\-*.]{1,24})\\s*[»>]\\s+(.+)$",
		Pattern.CASE_INSENSITIVE);
	
	private final TreeSet<String> friends = new TreeSet<>();
	private final HashSet<String> onlineFriends = new HashSet<>();
	private final Map<String, Long> lastReplyTimes = new HashMap<>();
	private Path path;
	private String lastServerKey = "";
	private long lastAutoPingAt;
	
	public FriendsList(Path path)
	{
		this.path = path;
	}
	
	public void addAndSave(String name)
	{
		friends.add(name);
		save();
	}
	
	public void removeAndSave(String name)
	{
		friends.remove(name);
		save();
	}
	
	public void removeAllAndSave()
	{
		friends.clear();
		save();
	}
	
	public void middleClick(Entity entity)
	{
		if(entity == null || !(entity instanceof Player))
			return;
		
		FriendsCmd friendsCmd = WurstClient.INSTANCE.getCmds().friendsCmd;
		CheckboxSetting middleClickFriends = friendsCmd.getMiddleClickFriends();
		if(!middleClickFriends.isChecked())
			return;
		
		String name = entity.getName().getString();
		
		if(contains(name))
		{
			removeAndSave(name);
			ChatUtils
				.message("Removed \"" + name + "\" from your friends list.");
		}else
		{
			addAndSave(name);
			ChatUtils.message("Added \"" + name + "\" to your friends list.");
		}
	}
	
	public boolean contains(String name)
	{
		reloadFromDiskIfEmpty();
		return findStoredFriendName(name) != null;
	}
	
	public boolean isFriend(Entity entity)
	{
		return entity != null && contains(entity.getName().getString());
	}
	
	@Override
	public void onUpdate()
	{
		Minecraft mc = WurstClient.MC;
		if(mc == null)
			return;
		
		updateFriendJoinAlerts(mc);
		updateAutoPing(mc);
	}
	
	@Override
	public void onReceivedMessage(ChatInputEvent event)
	{
		FriendsCmd friendsCmd = WurstClient.INSTANCE.getCmds().friendsCmd;
		if(friendsCmd == null)
			return;
		
		String password =
			normalizePassword(friendsCmd.getPingPassword().getValue());
		
		IncomingPingMessage incoming =
			parseIncomingPingMessage(event.getComponent().getString());
		if(incoming == null)
			return;
		
		String storedFriendName = findStoredFriendName(incoming.sender());
		if(storedFriendName == null)
			return;
		
		event.cancel();
		if(password.isEmpty())
		{
			updateLastPingStatus(friendsCmd,
				"Encrypted ping from " + storedFriendName
					+ " received, but no password is configured.");
			return;
		}
		
		DecryptResult decryptResult =
			decryptFriendPing(incoming.payload(), password);
		if(!decryptResult.success())
		{
			updateLastPingStatus(friendsCmd, "Encrypted ping from "
				+ storedFriendName + " could not be decrypted.");
			return;
		}
		
		FriendPingPayload payload = decryptResult.payload();
		rememberFriendPing(storedFriendName, payload, friendsCmd);
		String summary = buildPingSummary(storedFriendName, payload);
		updateLastPingStatus(friendsCmd, summary);
		
		if(!incoming.automatic())
			sendFriendPingMessage(storedFriendName, payload);
		
		if(!incoming.automatic() && !payload.reply())
			sendReplyPing(storedFriendName);
	}
	
	public ArrayList<String> toList()
	{
		reloadFromDiskIfEmpty();
		return new ArrayList<>(friends);
	}
	
	public void load()
	{
		boolean shouldSave = true;
		try
		{
			friends.clear();
			friends.addAll(JsonUtils.parseFileToArray(path).getAllStrings());
			
		}catch(NoSuchFileException e)
		{
			// The file doesn't exist yet. No problem, we'll create it later.
			
		}catch(IOException | JsonException e)
		{
			System.out.println("Couldn't load " + path.getFileName());
			e.printStackTrace();
			friends.clear();
			friends.addAll(loadFriendNamesFromDisk());
			shouldSave = !friends.isEmpty();
		}
		
		if(shouldSave)
			save();
	}
	
	public void sendManualPing(String targetName)
	{
		sendFriendPing(targetName, false, false, false);
	}
	
	public void addWaypointFromPing(String friendName, String dimensionName,
		int x, int y, int z)
	{
		WaypointsHack waypointsHack =
			WurstClient.INSTANCE.getHax().waypointsHack;
		if(waypointsHack == null)
			return;
		
		Waypoint waypoint = new Waypoint(java.util.UUID.randomUUID(),
			System.currentTimeMillis());
		waypoint.setName(
			nextWaypointName(commandSafeFriendName(friendName), waypointsHack));
		waypoint.setPos(new BlockPos(x, y, z));
		waypoint.setDimension(toWaypointDimension(dimensionName));
		waypoint.setColor(0xFF40A0FF);
		waypoint.setIcon("diamond");
		waypoint.setLines(true);
		waypoint.setBeaconMode(Waypoint.BeaconMode.OFF);
		waypointsHack.addWaypointFromCommand(waypoint);
		sendFriendAlert("Added waypoint \"" + waypoint.getName() + "\" at "
			+ formatCoords(x, y, z) + " in "
			+ waypoint.getDimension().name().toLowerCase(Locale.ROOT) + ".");
	}
	
	private void updateFriendJoinAlerts(Minecraft mc)
	{
		FriendsCmd friendsCmd = WurstClient.INSTANCE.getCmds().friendsCmd;
		if(friendsCmd == null || !friendsCmd.getFriendJoinAlerts().isChecked())
		{
			lastServerKey = "";
			onlineFriends.clear();
			return;
		}
		
		String serverKey = resolveServerKey(mc);
		if(serverKey.isEmpty() || mc.getConnection() == null
			|| mc.player == null)
		{
			lastServerKey = "";
			onlineFriends.clear();
			return;
		}
		
		boolean serverChanged = !serverKey.equals(lastServerKey);
		HashSet<String> nowOnlineFriends = new HashSet<>();
		for(PlayerInfo info : mc.getConnection().getOnlinePlayers())
		{
			if(info == null || info.getProfile() == null)
				continue;
			
			String name = info.getProfile().name();
			if(name == null || name.isBlank() || !containsIgnoreCase(name)
				|| name.equals(mc.player.getName().getString()))
				continue;
			
			nowOnlineFriends.add(name);
		}
		
		if(serverChanged)
		{
			for(String name : nowOnlineFriends)
				sendFriendAlert(name + " is already on this server.");
		}else
		{
			for(String name : nowOnlineFriends)
				if(!onlineFriends.contains(name))
					sendFriendAlert(name + " joined this server.");
		}
		
		onlineFriends.clear();
		onlineFriends.addAll(nowOnlineFriends);
		lastServerKey = serverKey;
	}
	
	private void updateAutoPing(Minecraft mc)
	{
		FriendsCmd friendsCmd = WurstClient.INSTANCE.getCmds().friendsCmd;
		if(friendsCmd == null || !friendsCmd.getAutoPing().isChecked()
			|| mc.getConnection() == null || mc.player == null
			|| mc.level == null)
		{
			return;
		}
		
		long now = System.currentTimeMillis();
		long intervalMs = (long)friendsCmd.getPingInterval().getValue() * 1000L;
		if(now - lastAutoPingAt < intervalMs)
			return;
		
		lastAutoPingAt = now;
		sendFriendPing(null, false, true, true);
	}
	
	private void rememberFriendPing(String friendName,
		FriendPingPayload payload, FriendsCmd friendsCmd)
	{
		PlayerEspHack playerEsp = WurstClient.INSTANCE.getHax().playerEspHack;
		if(playerEsp == null)
			return;
		
		long timeoutMs = (long)friendsCmd.getEspTimeout().getValue() * 1000L;
		Vec3 pos = new Vec3(payload.x(), payload.y(), payload.z());
		playerEsp.rememberFriendPing(friendName, pos, payload.dimension(),
			timeoutMs);
	}
	
	private void sendReplyPing(String friendName)
	{
		long now = System.currentTimeMillis();
		String key = friendName.toLowerCase(Locale.ROOT);
		Long lastReply = lastReplyTimes.get(key);
		if(lastReply != null && now - lastReply < 1000L)
			return;
		
		if(sendFriendPing(friendName, true, true, false) > 0)
			lastReplyTimes.put(key, now);
	}
	
	private int sendFriendPing(String targetName, boolean reply,
		boolean quietWhenNoRecipients, boolean automatic)
	{
		Minecraft mc = WurstClient.MC;
		FriendsCmd friendsCmd = WurstClient.INSTANCE.getCmds().friendsCmd;
		if(mc == null || mc.getConnection() == null || mc.player == null
			|| mc.level == null || friendsCmd == null)
		{
			if(!quietWhenNoRecipients)
				sendFriendAlert(
					"You need to be in a world to send friend pings.");
			return 0;
		}
		
		String password =
			normalizePassword(friendsCmd.getPingPassword().getValue());
		if(password.isEmpty())
		{
			if(!quietWhenNoRecipients)
				sendFriendAlert(
					"Set a ping password before sending friend pings.");
			return 0;
		}
		
		ArrayList<String> recipients = resolveRecipients(targetName, mc);
		if(recipients.isEmpty())
		{
			if(!quietWhenNoRecipients)
			{
				if(targetName != null && !targetName.isBlank())
					sendFriendAlert("\"" + targetName
						+ "\" is not online on this server or is not in your friends list.");
				else
					sendFriendAlert(
						"No online friends on this server to send a ping to.");
			}
			return 0;
		}
		
		FriendPingPayload payload =
			createPayload(mc, reply, mc.player.getName().getString());
		String encoded;
		try
		{
			encoded = encryptFriendPing(payload, password, automatic);
			
		}catch(GeneralSecurityException e)
		{
			if(!quietWhenNoRecipients)
				sendFriendAlert("Couldn't encrypt the friend ping: "
					+ e.getClass().getSimpleName() + ".");
			return 0;
		}
		
		for(String recipient : recipients)
			mc.getConnection().sendCommand("msg " + recipient + " " + encoded);
		
		if(!quietWhenNoRecipients && !reply)
			sendFriendAlert("Sent ping to " + String.join(", ", recipients)
				+ ": " + formatCoords(payload.x(), payload.y(), payload.z())
				+ " in " + payload.dimension() + ".");
		
		return recipients.size();
	}
	
	private ArrayList<String> resolveRecipients(String targetName, Minecraft mc)
	{
		ArrayList<String> recipients = new ArrayList<>();
		if(mc.getConnection() == null || mc.player == null)
			return recipients;
		
		if(targetName != null && !targetName.isBlank())
		{
			String onlineFriend = findOnlineFriendName(targetName, mc);
			if(onlineFriend != null)
				recipients.add(onlineFriend);
			return recipients;
		}
		
		for(PlayerInfo info : mc.getConnection().getOnlinePlayers())
		{
			if(info == null || info.getProfile() == null)
				continue;
			
			String name = info.getProfile().name();
			if(name == null || name.isBlank()
				|| name.equals(mc.player.getName().getString()))
				continue;
			
			if(containsIgnoreCase(name))
				recipients.add(name);
		}
		
		return recipients;
	}
	
	private String findOnlineFriendName(String name, Minecraft mc)
	{
		if(!containsIgnoreCase(name) || mc.getConnection() == null)
			return null;
		
		for(PlayerInfo info : mc.getConnection().getOnlinePlayers())
		{
			if(info == null || info.getProfile() == null)
				continue;
			
			String onlineName = info.getProfile().name();
			if(onlineName != null && onlineName.equalsIgnoreCase(name))
				return onlineName;
		}
		
		return null;
	}
	
	private String findStoredFriendName(String name)
	{
		reloadFromDiskIfEmpty();
		if(name == null)
			return null;
		
		for(String friend : friends)
			if(friend.equalsIgnoreCase(name))
				return friend;
			
		return null;
	}
	
	private boolean containsIgnoreCase(String name)
	{
		return findStoredFriendName(name) != null;
	}
	
	private void save()
	{
		try
		{
			JsonUtils.toJson(createJson(), path);
			
		}catch(JsonException | IOException e)
		{
			System.out.println("Couldn't save " + path.getFileName());
			e.printStackTrace();
		}
	}
	
	private void reloadFromDiskIfEmpty()
	{
		if(!friends.isEmpty())
			return;
		
		friends.addAll(loadFriendNamesFromDisk());
	}
	
	private ArrayList<String> loadFriendNamesFromDisk()
	{
		try
		{
			if(path == null || !Files.exists(path))
				return new ArrayList<>();
			ArrayList<String> diskFriends =
				JsonUtils.parseFileToArray(path).getAllStrings();
			if(!diskFriends.isEmpty())
				return diskFriends;
			
		}catch(IOException | JsonException ignored)
		{}
		
		ArrayList<String> plainTextFriends = new ArrayList<>();
		try
		{
			if(path == null || !Files.exists(path))
				return plainTextFriends;
			
			for(String line : Files.readAllLines(path))
			{
				String trimmed = line.trim();
				if(trimmed.isEmpty() || trimmed.equals("[")
					|| trimmed.equals("]"))
				{
					continue;
				}
				
				trimmed = trimmed.replace("\"", "").replace(",", "").trim();
				if(!trimmed.isEmpty())
					plainTextFriends.add(trimmed);
			}
			
		}catch(IOException ignored)
		{}
		
		return plainTextFriends;
	}
	
	private JsonArray createJson()
	{
		JsonArray json = new JsonArray();
		friends.forEach(json::add);
		return json;
	}
	
	private static FriendPingPayload createPayload(Minecraft mc, boolean reply,
		String senderName)
	{
		String dimension = mc.level.dimension().identifier().toString();
		return new FriendPingPayload(senderName, dimension, mc.player.getX(),
			mc.player.getY(), mc.player.getZ(), reply,
			System.currentTimeMillis());
	}
	
	private static String encryptFriendPing(FriendPingPayload payload,
		String password, boolean automatic) throws GeneralSecurityException
	{
		ByteBuffer plain = ByteBuffer.allocate(15);
		plain.put(PING_FORMAT_VERSION);
		plain.put(payload.reply() ? FLAG_REPLY : (byte)0);
		plain.put(encodeDimension(payload.dimension()));
		plain.putInt((int)Math.round(payload.x()));
		plain.putInt((int)Math.round(payload.y()));
		plain.putInt((int)Math.round(payload.z()));
		
		byte[] iv = new byte[16];
		SECURE_RANDOM.nextBytes(iv);
		Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
		cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password),
			new IvParameterSpec(iv));
		byte[] encrypted = cipher.doFinal(plain.array());
		byte[] combined = new byte[iv.length + encrypted.length];
		System.arraycopy(iv, 0, combined, 0, iv.length);
		System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
		return (automatic ? AUTO_PING_HEADER : PING_HEADER)
			+ Base64.getEncoder().encodeToString(combined);
	}
	
	private static DecryptResult decryptFriendPing(String encoded,
		String password)
	{
		if(encoded == null || (!encoded.startsWith(PING_HEADER)
			&& !encoded.startsWith(AUTO_PING_HEADER)))
			return DecryptResult.failed();
		
		try
		{
			int headerLength = encoded.startsWith(AUTO_PING_HEADER)
				? AUTO_PING_HEADER.length() : PING_HEADER.length();
			byte[] combined =
				Base64.getDecoder().decode(encoded.substring(headerLength));
			if(combined.length <= 16)
				return DecryptResult.failed();
			
			byte[] iv = new byte[16];
			byte[] ciphertext = new byte[combined.length - 16];
			System.arraycopy(combined, 0, iv, 0, 16);
			System.arraycopy(combined, 16, ciphertext, 0, ciphertext.length);
			
			Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
			cipher.init(Cipher.DECRYPT_MODE, deriveKey(password),
				new IvParameterSpec(iv));
			byte[] plain = cipher.doFinal(ciphertext);
			FriendPingPayload payload = decodeCompactPayload(plain);
			return payload == null ? DecryptResult.failed()
				: DecryptResult.success(payload);
			
		}catch(Exception e)
		{
			return DecryptResult.failed();
		}
	}
	
	private static SecretKeySpec deriveKey(String password)
		throws GeneralSecurityException
	{
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		byte[] keyBytes =
			digest.digest(password.getBytes(StandardCharsets.UTF_8));
		return new SecretKeySpec(keyBytes, "AES");
	}
	
	private static FriendPingPayload decodeCompactPayload(byte[] plain)
	{
		if(plain == null || plain.length < 15
			|| plain[0] != PING_FORMAT_VERSION)
			return null;
		
		ByteBuffer buffer = ByteBuffer.wrap(plain);
		buffer.get(); // version
		byte flags = buffer.get();
		String dimension = decodeDimension(buffer.get());
		int x = buffer.getInt();
		int y = buffer.getInt();
		int z = buffer.getInt();
		return new FriendPingPayload("", dimension, x, y, z,
			(flags & FLAG_REPLY) != 0, 0L);
	}
	
	private static byte encodeDimension(String dimension)
	{
		if("minecraft:overworld".equalsIgnoreCase(dimension))
			return DIM_OVERWORLD;
		if("minecraft:the_nether".equalsIgnoreCase(dimension))
			return DIM_NETHER;
		if("minecraft:the_end".equalsIgnoreCase(dimension))
			return DIM_END;
		return DIM_OTHER;
	}
	
	private static String decodeDimension(byte code)
	{
		return switch(code)
		{
			case DIM_OVERWORLD -> "minecraft:overworld";
			case DIM_NETHER -> "minecraft:the_nether";
			case DIM_END -> "minecraft:the_end";
			default -> "minecraft:unknown";
		};
	}
	
	private static IncomingPingMessage parseIncomingPingMessage(String plain)
	{
		if(plain == null || plain.isBlank())
			return null;
		
		String trimmed = plain.trim();
		Matcher whisper = WHISPER_TO_YOU_CHAT.matcher(trimmed);
		if(whisper.matches())
			return buildIncomingPingMessage(whisper.group(1), whisper.group(2));
		
		Matcher arrowWhisper = INCOMING_ARROW_WHISPER_CHAT.matcher(trimmed);
		if(arrowWhisper.matches())
			return buildIncomingPingMessage(arrowWhisper.group(1),
				arrowWhisper.group(2));
		
		Matcher fromWhisper = FROM_WHISPER_CHAT.matcher(trimmed);
		if(fromWhisper.matches())
			return buildIncomingPingMessage(fromWhisper.group(1),
				fromWhisper.group(2));
		
		Matcher speakerArrow = SPEAKER_ARROW_CHAT.matcher(trimmed);
		if(speakerArrow.matches())
			return buildIncomingPingMessage(speakerArrow.group(1),
				speakerArrow.group(2));
		
		return null;
	}
	
	private static IncomingPingMessage buildIncomingPingMessage(String sender,
		String payload)
	{
		if(sender == null || payload == null)
			return null;
		
		String trimmedPayload = payload.trim();
		boolean automatic = trimmedPayload.startsWith(AUTO_PING_HEADER);
		if(!automatic && !trimmedPayload.startsWith(PING_HEADER))
			return null;
		
		return new IncomingPingMessage(sender.trim(), trimmedPayload,
			automatic);
	}
	
	private static String normalizePassword(String password)
	{
		return password == null ? "" : password.trim();
	}
	
	private static String resolveServerKey(Minecraft mc)
	{
		try
		{
			if(mc.getCurrentServer() == null
				|| mc.getCurrentServer().ip == null)
				return "";
			return mc.getCurrentServer().ip.trim().toLowerCase(Locale.ROOT);
		}catch(Throwable ignored)
		{
			return "";
		}
	}
	
	private static String formatCoords(double x, double y, double z)
	{
		return (int)Math.round(x) + ", " + (int)Math.round(y) + ", "
			+ (int)Math.round(z);
	}
	
	private static void sendFriendAlert(String message)
	{
		Minecraft mc = WurstClient.MC;
		if(mc == null || mc.gui == null)
			return;
		
		mc.execute(() -> {
			if(mc.gui == null)
				return;
			
			MutableComponent prefix =
				Component.literal("[Friends] ").withStyle(ChatFormatting.BLUE);
			MutableComponent body =
				Component.literal(message).withStyle(ChatFormatting.WHITE);
			mc.gui.getChat().addClientSystemMessage(prefix.append(body));
		});
	}
	
	private static void sendFriendPingMessage(String friendName,
		FriendPingPayload payload)
	{
		MutableComponent action = addWaypointAction(friendName, payload);
		if(isCurrentDimension(payload.dimension()))
		{
			MutableComponent sameDim =
				Component
					.literal(
						"Ping from " + friendName + ": "
							+ formatCoords(payload.x(), payload.y(),
								payload.z())
							+ " in your dimension, "
							+ formatDistanceForDisplay(payload) + ". ")
					.withStyle(ChatFormatting.WHITE);
			ChatUtils.component(sameDim.append(action));
			return;
		}
		
		String dimText = formatDimensionForDisplay(payload.dimension());
		MutableComponent body = Component
			.literal("Ping from " + friendName + ": "
				+ formatCoords(payload.x(), payload.y(), payload.z()) + " in "
				+ dimText + ", " + formatDistanceForDisplay(payload) + ". ")
			.withStyle(ChatFormatting.WHITE);
		ChatUtils.component(body.append(action));
	}
	
	private static MutableComponent addWaypointAction(String friendName,
		FriendPingPayload payload)
	{
		String command = ".friends waypoint "
			+ commandSafeFriendName(friendName) + " "
			+ (int)Math.round(payload.x()) + " " + (int)Math.round(payload.y())
			+ " " + (int)Math.round(payload.z()) + " "
			+ dimensionCommandArg(payload.dimension());
		MutableComponent action = Component.literal("[Add waypoint]")
			.withStyle(style -> style.withColor(ChatFormatting.AQUA)
				.withUnderlined(true)
				.withClickEvent(new ClickEvent.RunCommand(command))
				.withHoverEvent(new HoverEvent.ShowText(
					Component.literal("Add a waypoint for " + friendName))));
		return action;
	}
	
	private static String commandSafeFriendName(String friendName)
	{
		if(friendName == null)
			return "friend";
		
		String cleaned = friendName.replaceAll("[^A-Za-z0-9_\\-*.]", "");
		return cleaned.isBlank() ? "friend" : cleaned;
	}
	
	private static boolean isCurrentDimension(String dimension)
	{
		Minecraft mc = WurstClient.MC;
		return mc != null && mc.level != null && mc.level.dimension()
			.identifier().toString().equalsIgnoreCase(dimension);
	}
	
	private static String formatDistanceForDisplay(FriendPingPayload payload)
	{
		double distance = distanceToPing(payload);
		if(distance < 0)
			return "distance unknown";
		
		boolean approximate = !isCurrentDimension(payload.dimension());
		return (approximate ? "approx " : "") + (int)Math.round(distance)
			+ " blocks away";
	}
	
	private static double distanceToPing(FriendPingPayload payload)
	{
		Minecraft mc = WurstClient.MC;
		if(mc == null || mc.player == null || mc.level == null
			|| payload == null)
			return -1;
		
		Vec3 target = toCurrentDimensionPos(payload);
		if(target == null)
			return -1;
		
		return mc.player.position().distanceTo(target);
	}
	
	private static Vec3 toCurrentDimensionPos(FriendPingPayload payload)
	{
		Minecraft mc = WurstClient.MC;
		String current = mc.level.dimension().identifier().toString();
		String pingDim = payload.dimension();
		double x = payload.x();
		double z = payload.z();
		
		if(current.equalsIgnoreCase(pingDim))
			return new Vec3(x, payload.y(), z);
		
		if("minecraft:overworld".equalsIgnoreCase(current)
			&& "minecraft:the_nether".equalsIgnoreCase(pingDim))
			return new Vec3(x * 8.0, payload.y(), z * 8.0);
		
		if("minecraft:the_nether".equalsIgnoreCase(current)
			&& "minecraft:overworld".equalsIgnoreCase(pingDim))
			return new Vec3(x / 8.0, payload.y(), z / 8.0);
		
		return new Vec3(x, payload.y(), z);
	}
	
	private static String buildPingSummary(String friendName,
		FriendPingPayload payload)
	{
		String suffix = payload.reply() ? " [reply]" : "";
		return friendName + ": "
			+ formatCoords(payload.x(), payload.y(), payload.z()) + " in "
			+ formatDimensionForDisplay(payload.dimension()) + suffix;
	}
	
	private static String formatDimensionForDisplay(String dimension)
	{
		return switch(dimension)
		{
			case "minecraft:overworld" -> "overworld";
			case "minecraft:the_nether" -> "nether";
			case "minecraft:the_end" -> "end";
			default -> dimension;
		};
	}
	
	private static String dimensionCommandArg(String dimension)
	{
		return switch(dimension)
		{
			case "minecraft:the_nether" -> "nether";
			case "minecraft:the_end" -> "end";
			default -> "overworld";
		};
	}
	
	private static WaypointDimension toWaypointDimension(String dimension)
	{
		return switch(dimension == null ? ""
			: dimension.toLowerCase(Locale.ROOT))
		{
			case "minecraft:the_nether", "nether" -> WaypointDimension.NETHER;
			case "minecraft:the_end", "end" -> WaypointDimension.END;
			default -> WaypointDimension.OVERWORLD;
		};
	}
	
	private static String nextWaypointName(String friendName,
		WaypointsHack waypointsHack)
	{
		String base = friendName + " coordinate ";
		int next = 1;
		for(Waypoint waypoint : waypointsHack.getAllWaypoints())
		{
			String name = waypoint.getName();
			if(name == null || !name.startsWith(base))
				continue;
			
			String suffix = name.substring(base.length()).trim();
			if(!suffix.startsWith("#"))
				continue;
			
			try
			{
				next =
					Math.max(next, Integer.parseInt(suffix.substring(1)) + 1);
			}catch(NumberFormatException ignored)
			{}
		}
		
		return base + "#" + next;
	}
	
	private static void updateLastPingStatus(FriendsCmd friendsCmd,
		String status)
	{
		if(friendsCmd == null)
			return;
		
		friendsCmd.getLastPingStatus().setValue(
			status == null || status.isBlank() ? "No ping yet." : status);
	}
	
	private record IncomingPingMessage(String sender, String payload,
		boolean automatic)
	{}
	
	private record DecryptResult(boolean success, FriendPingPayload payload)
	{
		private static DecryptResult success(FriendPingPayload payload)
		{
			return new DecryptResult(true, payload);
		}
		
		private static DecryptResult failed()
		{
			return new DecryptResult(false, null);
		}
	}
	
	private record FriendPingPayload(String sender, String dimension, double x,
		double y, double z, boolean reply, long sentAt)
	{}
}
