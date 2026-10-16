/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;
import net.minecraft.ChatFormatting;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringUtil;
import net.wurstclient.WurstClient;
import net.wurstclient.proxy.ProxyProtocol;
import net.wurstclient.proxy.SocksProxy;

/**
 * Remembers server ban outcomes and the public identity used for each
 * connection. Each server gets its own JSON file.
 */
public final class BanMemoryManager
{
	private static final Gson GSON =
		new GsonBuilder().setPrettyPrinting().create();
	private static final String DIRECT_IDENTITY = "direct";
	private static final String PUBLIC_IP_URL = "https://myip.wtf/json";
	private static final long PUBLIC_IP_CACHE_TIME = 5 * 60 * 1000L;
	private static final long FAILED_IP_CACHE_TIME = 30 * 1000L;
	
	private final Path folder;
	private final java.util.Map<String, ServerRecord> loaded =
		new java.util.HashMap<>();
	private final java.util.Map<String, PublicIpResult> publicIpCache =
		new java.util.HashMap<>();
	private ConnectionContext activeAttempt;
	private ConnectionContext pendingCleanup;
	
	public BanMemoryManager(Path folder)
	{
		this.folder = folder;
	}
	
	public synchronized void loadHistory()
	{
		if(!Files.isDirectory(folder))
			return;
		try(var paths = Files.list(folder))
		{
			for(Path path : paths.filter(p -> p.toString().endsWith(".json"))
				.toList())
				try
				{
					ServerRecord record = GSON.fromJson(Files.readString(path),
						ServerRecord.class);
					if(record != null && record.server != null
						&& !record.server.isBlank())
						load(record.server);
				}catch(IOException | RuntimeException e)
				{
					System.err.println("Couldn't read ban history file "
						+ path.getFileName() + ".");
				}
		}catch(IOException e)
		{
			System.err.println("Couldn't read ban history folder.");
		}
	}
	
	public synchronized List<BanHistoryEntry> getAccountBanHistory(String name)
	{
		if(name == null || name.isBlank())
			return List.of();
		List<BanHistoryEntry> result = new ArrayList<>();
		for(ServerRecord record : loaded.values())
			for(AccountRecord account : record.accounts)
				if(account != null && account.accountBanned
					&& name.equalsIgnoreCase(account.name))
					result.add(new BanHistoryEntry(record.server,
						"IP".equals(account.lastBanType)
							? "IP ban (account used)" : "Account ban",
						account.name, "", account.lastBanReason,
						account.lastBanAt > 0 ? account.lastBanAt
							: account.lastSeen));
		return sortedHistory(result);
	}
	
	public synchronized List<BanHistoryEntry> getProxyBanHistory(
		SocksProxy proxy)
	{
		if(proxy == null)
			return getDirectBanHistory();
		String key = proxyKey(proxy);
		String endpoint = proxy.getHost() + ":" + proxy.getPort();
		String legacyKey = "proxy|" + proxy.getProtocol().name() + "|"
			+ proxy.getHost().toLowerCase(Locale.ROOT) + "|" + proxy.getPort();
		String legacyLabel =
			proxy.getProtocol().getDisplayName() + " proxy exit IP " + endpoint;
		PublicIpResult cached = publicIpCache
			.get(proxy.getProtocol().name() + "|" + proxy.getStorageId());
		List<BanHistoryEntry> result = new ArrayList<>();
		for(ServerRecord record : loaded.values())
			for(IdentityRecord identity : record.identities)
				if(identity != null && identity.identityBanned
					&& ((identity.proxyKeys == null
						|| identity.proxyKeys.isEmpty())
						&& (legacyKey.equalsIgnoreCase(identity.identity)
							|| legacyLabel.equalsIgnoreCase(identity.label))
						|| identity.proxyKeys != null && identity.proxyKeys
							.stream().anyMatch(key::equalsIgnoreCase)
						|| cached != null && cached.ip != null
							&& ("ip|" + cached.ip).equals(identity.identity)))
					result.add(identityHistory(record, identity));
		return sortedHistory(result);
	}
	
	public synchronized List<BanHistoryEntry> getDirectBanHistory()
	{
		List<BanHistoryEntry> result = new ArrayList<>();
		for(ServerRecord record : loaded.values())
			for(IdentityRecord identity : record.identities)
				if(identity != null && identity.identityBanned
					&& (identity.usedDirect
						|| DIRECT_IDENTITY.equals(identity.identity)
						|| "public IP".equals(identity.label)
						|| "direct IP (unresolved)".equals(identity.label)))
					result.add(identityHistory(record, identity));
		return sortedHistory(result);
	}
	
	private static BanHistoryEntry identityHistory(ServerRecord record,
		IdentityRecord identity)
	{
		String label =
			identity.identity != null && identity.identity.startsWith("ip|")
				? identity.identity.substring(3)
				: safe(identity.label, "Unknown IP");
		return new BanHistoryEntry(record.server,
			hasExplicitIpMarker(identity.lastBanReason) ? "IP ban"
				: "Used during account ban",
			safe(identity.bannedByAccount, "Unknown account"), label,
			identity.lastBanReason,
			identity.lastBanAt > 0 ? identity.lastBanAt : identity.lastSeen);
	}
	
	private static List<BanHistoryEntry> sortedHistory(
		List<BanHistoryEntry> entries)
	{
		entries.sort(Comparator.comparingLong(BanHistoryEntry::timestamp)
			.reversed().thenComparing(BanHistoryEntry::server));
		return List.copyOf(entries);
	}
	
	public static List<Component> historyTooltip(List<BanHistoryEntry> history)
	{
		List<Component> lines = new ArrayList<>();
		lines.add(Component.literal("Recorded ban history")
			.withStyle(ChatFormatting.GOLD));
		if(history.isEmpty())
			lines.add(Component.literal("No recorded bans.")
				.withStyle(ChatFormatting.GRAY));
		DateTimeFormatter dates = DateTimeFormatter
			.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
		for(BanHistoryEntry entry : history.stream().limit(8).toList())
		{
			lines.add(Component.literal(entry.server())
				.withStyle(ChatFormatting.RED));
			lines.add(Component.literal(entry.type() + " — " + entry.account())
				.withStyle(ChatFormatting.YELLOW));
			if(!entry.identity().isBlank())
				lines.add(Component.literal("Address: " + entry.identity())
					.withStyle(ChatFormatting.GRAY));
			String reason = safe(entry.reason(), "Reason not recorded")
				.replaceAll("[\\r\\n\\t]+", " ");
			if(reason.length() > 120)
				reason = reason.substring(0, 117) + "...";
			lines.add(Component.literal(reason).withStyle(ChatFormatting.GRAY));
			if(entry.timestamp() > 0)
				lines.add(Component
					.literal(
						dates.format(Instant.ofEpochMilli(entry.timestamp())))
					.withStyle(ChatFormatting.DARK_GRAY));
		}
		if(history.size() > 8)
			lines.add(
				Component.literal("+ " + (history.size() - 8) + " more records")
					.withStyle(ChatFormatting.GRAY));
		return lines;
	}
	
	static String proxyKey(SocksProxy proxy)
	{
		return fileName(proxy.getProtocol().name() + "\u0000"
			+ proxy.getHost().toLowerCase(Locale.ROOT) + "\u0000"
			+ proxy.getPort() + "\u0000" + proxy.getUsername() + "\u0000"
			+ proxy.getPassword());
	}
	
	public synchronized ConnectionContext createContext(ServerData server,
		ServerAddress address)
	{
		if(server == null || address == null)
			return null;
		
		Minecraft minecraft = WurstClient.MC;
		String accountName = "Unknown account";
		String accountUuid = "";
		if(minecraft != null && minecraft.getUser() != null)
		{
			accountName = safe(minecraft.getUser().getName(), accountName);
			UUID uuid = minecraft.getUser().getProfileId();
			accountUuid = uuid == null ? "" : uuid.toString();
		}
		
		SocksProxy proxy = WurstClient.INSTANCE.getProxyManager() == null ? null
			: WurstClient.INSTANCE.getProxyManager().getEffectiveProxy();
		String cacheKey = proxy == null ? DIRECT_IDENTITY
			: proxy.getProtocol().name() + "|" + proxy.getStorageId();
		String publicIp = resolvePublicIp(proxy, cacheKey);
		String identity;
		String identityLabel;
		if(publicIp != null)
		{
			identity = "ip|" + publicIp;
			identityLabel = proxy == null ? "public IP"
				: proxy.getProtocol().getDisplayName() + " proxy exit IP "
					+ proxy.getHost() + ":" + proxy.getPort();
		}else if(proxy == null)
		{
			identity = DIRECT_IDENTITY;
			identityLabel = "direct IP (unresolved)";
		}else
		{
			identity = "proxy|" + proxy.getProtocol().name() + "|"
				+ proxy.getHost().toLowerCase(Locale.ROOT) + "|"
				+ proxy.getPort();
			identityLabel = "proxy " + proxy.getHost() + ":" + proxy.getPort();
		}
		
		String host = address.getHost().toLowerCase(Locale.ROOT);
		String serverKey = host + ":" + address.getPort();
		return new ConnectionContext(serverKey, host + ":" + address.getPort(),
			accountName, accountUuid, identity, identityLabel,
			proxy == null ? "" : proxyKey(proxy));
	}
	
	private String resolvePublicIp(SocksProxy proxy, String cacheKey)
	{
		long now = System.currentTimeMillis();
		PublicIpResult cached = publicIpCache.get(cacheKey);
		if(cached != null && now - cached.checkedAt < (cached.ip == null
			? FAILED_IP_CACHE_TIME : PUBLIC_IP_CACHE_TIME))
			return cached.ip;
		
		String ip = null;
		try
		{
			URLConnection connection = new URL(PUBLIC_IP_URL).openConnection(
				proxy == null ? java.net.Proxy.NO_PROXY : proxy.toJavaProxy());
			connection.setConnectTimeout(2000);
			connection.setReadTimeout(2000);
			if(connection instanceof HttpURLConnection http)
			{
				http.setRequestProperty("Accept", "application/json");
				if(proxy != null && proxy.getProtocol() == ProxyProtocol.HTTP
					&& proxy.hasCredentials())
				{
					String credentials =
						proxy.getUsername() + ":" + proxy.getPassword();
					String encoded = Base64.getEncoder().encodeToString(
						credentials.getBytes(StandardCharsets.ISO_8859_1));
					http.setRequestProperty("Proxy-Authorization",
						"Basic " + encoded);
				}
			}
			String json = new String(connection.getInputStream().readAllBytes(),
				StandardCharsets.UTF_8);
			JsonObject object = JsonParser.parseString(json).getAsJsonObject();
			if(object.has("YourFuckingIPAddress"))
			{
				String value = object.get("YourFuckingIPAddress").getAsString();
				if(value.matches("[0-9a-fA-F:.]+"))
					ip = value;
			}
		}catch(Exception e)
		{
			System.err
				.println("Couldn't resolve public IP for ban memory through "
					+ (proxy == null ? "direct connection" : "configured proxy")
					+ ".");
		}
		publicIpCache.put(cacheKey, new PublicIpResult(ip, now));
		return ip;
	}
	
	/**
	 * Returns a warning, or {@code null} when connecting is safe to proceed.
	 */
	public synchronized Warning findWarning(ConnectionContext context)
	{
		if(context == null)
			return null;
		
		ServerRecord record = load(context.serverKey());
		List<String> messages = new ArrayList<>();
		AccountRecord account = findAccount(record, context);
		if(account != null && account.accountBanned)
			messages.add("This account " + context.accountName()
				+ " is recorded as banned on this server.");
		
		IdentityRecord identity = findIdentity(record, context.identity());
		if(identity != null && identity.identityBanned
			&& identity.bannedByAccount != null && !sameAccount(context,
				identity.bannedByAccountUuid, identity.bannedByAccount))
		{
			boolean explicitIpBan = hasExplicitIpMarker(identity.lastBanReason);
			messages.add("This " + identityNoun(context)
				+ (explicitIpBan ? " was banned" : " was used")
				+ " from this server when you used account "
				+ identity.bannedByAccount + ".");
		}else if(identity != null && identity.identityBanned
			&& sameAccount(context, identity.bannedByAccountUuid,
				identity.bannedByAccount)
			&& (account == null || !account.accountBanned))
		{
			// Compatibility with older records that did not taint the account.
			messages.add("This account " + context.accountName()
				+ " is recorded as banned on this server.");
		}else if(identity != null && !identity.identityBanned
			&& identity.usedDuringBanBy != null && !sameAccount(context,
				identity.usedDuringBanByUuid, identity.usedDuringBanBy))
		{
			// Compatibility with older account-only records.
			messages.add("This " + identityNoun(context)
				+ " was used when you were banned using account "
				+ identity.usedDuringBanBy + ".");
		}
		if(messages.isEmpty())
			return null;
		messages.add("Continue anyway? This may disclose this account or "
			+ identityNoun(context) + " to the server.");
		System.out.println("[BanMemory] Blocking reconnect to "
			+ context.serverLabel() + ": " + String.join("; ", messages));
		return new Warning(String.join("\n\n", messages));
	}
	
	/**
	 * Records an actual connection attempt and makes it the active ban context.
	 */
	public synchronized void rememberAttempt(ConnectionContext context)
	{
		if(context == null)
			return;
		
		ServerRecord record = load(context.serverKey());
		record.server = context.serverKey();
		long now = System.currentTimeMillis();
		AccountRecord account = ensureAccount(record, context);
		account.lastSeen = now;
		IdentityRecord identity = ensureIdentity(record, context);
		identity.lastSeen = now;
		VisitRecord visit = findVisit(record, context);
		if(visit == null)
		{
			visit = new VisitRecord();
			visit.accountUuid = context.accountUuid();
			visit.accountName = context.accountName();
			visit.identity = context.identity();
			record.visits.add(visit);
		}
		visit.lastSeen = now;
		activeAttempt = context;
		save(record);
		System.out.println("[BanMemory] Tracking connection to "
			+ context.serverLabel() + " as " + context.accountName() + " via "
			+ context.identityLabel());
	}
	
	/**
	 * Marks a confirmed Continue choice for cleanup after a successful login.
	 */
	public synchronized void markContinue(ConnectionContext context)
	{
		pendingCleanup = context;
	}
	
	/** Removes stale ban entries only after the server accepts the login. */
	public synchronized void rememberSuccessfulConnection()
	{
		ConnectionContext context = pendingCleanup;
		pendingCleanup = null;
		if(context == null)
			return;
		
		ServerRecord record = load(context.serverKey());
		record.accounts.removeIf(account -> account.accountBanned);
		record.identities.removeIf(identity -> identity.identityBanned
			|| identity.usedDuringBanBy != null);
		save(record);
		System.out.println("[BanMemory] Cleared stale ban memory for "
			+ context.serverLabel() + " after successful connection.");
	}
	
	/** Learns from a server disconnect if its reason looks like a ban. */
	public synchronized void rememberDisconnect(Component reason)
	{
		ConnectionContext context = activeAttempt;
		if(context == null)
		{
			Minecraft minecraft = WurstClient.MC;
			ServerData server =
				minecraft == null ? null : minecraft.getCurrentServer();
			if(server != null && server.ip != null && !server.ip.isBlank())
				context =
					createContext(server, ServerAddress.parseString(server.ip));
		}
		if(context == null || !isBanReason(reason))
		{
			pendingCleanup = null;
			return;
		}
		pendingCleanup = null;
		
		BanType type = isIpBan(reason) ? BanType.IP : BanType.ACCOUNT;
		ServerRecord record = load(context.serverKey());
		AccountRecord account = ensureAccount(record, context);
		IdentityRecord identity = ensureIdentity(record, context);
		String text = normalizedReason(reason);
		long now = System.currentTimeMillis();
		account.lastBanReason = text;
		account.lastBanType = type.name();
		account.lastBanAt = now;
		account.lastSeen = now;
		identity.lastBanReason = text;
		identity.lastBanAt = now;
		identity.lastSeen = now;
		
		// A ban taints both the account and the exact public identity used.
		// Warnings are emitted only when one of those two identities matches.
		account.accountBanned = true;
		identity.identityBanned = true;
		identity.bannedByAccount = context.accountName();
		identity.bannedByAccountUuid = context.accountUuid();
		if(type != BanType.IP)
		{
			identity.usedDuringBanBy = context.accountName();
			identity.usedDuringBanByUuid = context.accountUuid();
		}
		save(record);
		System.out.println("[BanMemory] Recorded " + type + " ban for "
			+ context.serverLabel() + " as " + context.accountName());
	}
	
	public static boolean isBanReason(Component reason)
	{
		if(reason == null)
			return false;
		String text = normalizedReason(reason);
		return text.contains("ban") || text.contains("blacklist")
			|| (text.contains("blocked")
				&& (text.contains("ip") || text.contains("address")));
	}
	
	private static boolean isIpBan(Component reason)
	{
		String text = normalizedReason(reason);
		return hasExplicitIpMarker(text)
			&& (text.contains("ban") || text.contains("block"));
	}
	
	private static boolean hasExplicitIpMarker(String text)
	{
		if(text == null)
			return false;
		for(String token : text.split("[^a-z]+"))
			if(token.equals("ip") || token.equals("address"))
				return true;
		return false;
	}
	
	private static String normalizedReason(Component reason)
	{
		if(reason == null)
			return "";
		return StringUtil.stripColor(reason.getString() + " " + reason)
			.toLowerCase(Locale.ROOT);
	}
	
	private String identityNoun(ConnectionContext context)
	{
		if(context.identity().startsWith("ip|"))
			return context.identityLabel().contains("proxy exit IP")
				? "proxy IP" : "IP";
		return context.identity().equals(DIRECT_IDENTITY) ? "IP" : "proxy";
	}
	
	private boolean sameAccount(ConnectionContext context, String uuid,
		String name)
	{
		return !context.accountUuid().isBlank()
			&& context.accountUuid().equals(uuid)
			|| context.accountName().equalsIgnoreCase(name);
	}
	
	private AccountRecord findAccount(ServerRecord record,
		ConnectionContext context)
	{
		for(AccountRecord account : record.accounts)
			if((!context.accountUuid().isBlank()
				&& context.accountUuid().equals(account.uuid))
				|| context.accountName().equalsIgnoreCase(account.name))
				return account;
		return null;
	}
	
	private AccountRecord ensureAccount(ServerRecord record,
		ConnectionContext context)
	{
		AccountRecord account = findAccount(record, context);
		if(account != null)
			return account;
		account = new AccountRecord();
		account.uuid = context.accountUuid();
		account.name = context.accountName();
		record.accounts.add(account);
		return account;
	}
	
	private IdentityRecord findIdentity(ServerRecord record, String identity)
	{
		for(IdentityRecord entry : record.identities)
			if(identity.equals(entry.identity))
				return entry;
		return null;
	}
	
	private IdentityRecord ensureIdentity(ServerRecord record,
		ConnectionContext context)
	{
		IdentityRecord identity = findIdentity(record, context.identity());
		if(identity == null)
		{
			identity = new IdentityRecord();
			identity.identity = context.identity();
			identity.label = context.identityLabel();
			record.identities.add(identity);
		}
		if(identity.proxyKeys == null)
			identity.proxyKeys = new ArrayList<>();
		if(!context.proxyKey().isBlank()
			&& !identity.proxyKeys.contains(context.proxyKey()))
			identity.proxyKeys.add(context.proxyKey());
		if(DIRECT_IDENTITY.equals(context.identity())
			|| "public IP".equals(context.identityLabel()))
			identity.usedDirect = true;
		return identity;
	}
	
	private VisitRecord findVisit(ServerRecord record,
		ConnectionContext context)
	{
		for(VisitRecord visit : record.visits)
			if(context.identity().equals(visit.identity)
				&& ((context.accountUuid().isBlank() && context.accountName()
					.equalsIgnoreCase(visit.accountName))
					|| (!context.accountUuid().isBlank()
						&& context.accountUuid().equals(visit.accountUuid))))
				return visit;
		return null;
	}
	
	private ServerRecord load(String serverKey)
	{
		ServerRecord record = loaded.get(serverKey);
		if(record != null)
			return record;
		Path path = folder.resolve(fileName(serverKey));
		try
		{
			if(Files.exists(path))
				record =
					GSON.fromJson(Files.readString(path), ServerRecord.class);
		}catch(Exception e)
		{
			System.err
				.println("Couldn't load ban memory for " + serverKey + ".");
		}
		if(record == null)
			record = new ServerRecord();
		if(record.accounts == null)
			record.accounts = new ArrayList<>();
		if(record.identities == null)
			record.identities = new ArrayList<>();
		if(record.visits == null)
			record.visits = new ArrayList<>();
		loaded.put(serverKey, record);
		return record;
	}
	
	private void save(ServerRecord record)
	{
		try
		{
			Files.createDirectories(folder);
			Files.writeString(folder.resolve(fileName(record.server)),
				GSON.toJson(record), StandardCharsets.UTF_8);
		}catch(IOException e)
		{
			System.err
				.println("Couldn't save ban memory for " + record.server + ".");
		}
	}
	
	private static String fileName(String serverKey)
	{
		try
		{
			byte[] hash = MessageDigest.getInstance("SHA-256")
				.digest(serverKey.getBytes(StandardCharsets.UTF_8));
			StringBuilder result = new StringBuilder();
			for(byte value : hash)
				result.append(String.format(Locale.ROOT, "%02x", value));
			return result + ".json";
		}catch(Exception e)
		{
			return Integer.toHexString(serverKey.hashCode()) + ".json";
		}
	}
	
	private static String safe(String value, String fallback)
	{
		return value == null || value.isBlank() ? fallback : value;
	}
	
	private record PublicIpResult(String ip, long checkedAt)
	{}
	
	public record ConnectionContext(String serverKey, String serverLabel,
		String accountName, String accountUuid, String identity,
		String identityLabel, String proxyKey)
	{
		public ConnectionContext(String serverKey, String serverLabel,
			String accountName, String accountUuid, String identity,
			String identityLabel)
		{
			this(serverKey, serverLabel, accountName, accountUuid, identity,
				identityLabel, "");
		}
	}
	
	public record BanHistoryEntry(String server, String type, String account,
		String identity, String reason, long timestamp)
	{}
	
	public record Warning(String message)
	{}
	
	private enum BanType
	{
		ACCOUNT,
		IP
	}
	
	private static final class ServerRecord
	{
		String server;
		List<AccountRecord> accounts = new ArrayList<>();
		List<IdentityRecord> identities = new ArrayList<>();
		List<VisitRecord> visits = new ArrayList<>();
	}
	
	private static final class AccountRecord
	{
		String uuid = "";
		String name = "";
		boolean accountBanned;
		String lastBanType;
		String lastBanReason;
		long lastBanAt;
		long lastSeen;
	}
	
	private static final class IdentityRecord
	{
		String identity = "";
		String label = "";
		List<String> proxyKeys = new ArrayList<>();
		boolean usedDirect;
		boolean identityBanned;
		String bannedByAccount;
		String bannedByAccountUuid;
		String usedDuringBanBy;
		String usedDuringBanByUuid;
		String lastBanReason;
		long lastBanAt;
		long lastSeen;
	}
	
	private static final class VisitRecord
	{
		String accountUuid = "";
		String accountName = "";
		String identity = "";
		long lastSeen;
	}
}
