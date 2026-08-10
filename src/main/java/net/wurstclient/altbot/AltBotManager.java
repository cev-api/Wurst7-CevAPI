/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.altbot;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

import net.minecraft.client.Minecraft;
import net.wurstclient.WurstClient;
import net.wurstclient.altmanager.Alt;
import net.wurstclient.altmanager.AltManager;
import net.wurstclient.altmanager.LoginException;
import net.wurstclient.altmanager.MinecraftProfile;
import net.wurstclient.altmanager.TokenAlt;
import net.wurstclient.proxy.ProxyProtocol;
import net.wurstclient.proxy.SocksProxy;
import net.wurstclient.util.ChatUtils;

/**
 * Persistent subsystem that owns every protocol-bot connection. All network
 * and authentication work happens on a dedicated worker executor, never on
 * the Minecraft render thread. GUI code reads thread-safe snapshots through
 * {@link #getState(Alt)}.
 */
public final class AltBotManager
{
	public enum BotProxyMode
	{
		DIRECT("Direct"),
		SELECTED("Selected Proxy"),
		RANDOM("Random Proxy");
		
		private final String label;
		
		BotProxyMode(String label)
		{
			this.label = label;
		}
		
		@Override
		public String toString()
		{
			return label;
		}
	}
	
	private final Object lock = new Object();
	private final IdentityHashMap<TokenAlt, AltBotSession> sessions =
		new IdentityHashMap<>();
	private final Set<TokenAlt> pendingAuth =
		Collections.newSetFromMap(new IdentityHashMap<>());
	private final IdentityHashMap<TokenAlt, String> failures =
		new IdentityHashMap<>();
	
	// Offline (cracked-name) bots are keyed by their lowercased name because
	// they are not saved TokenAlts.
	private final Map<String, AltBotSession> offlineSessions = new HashMap<>();
	private final Set<String> pendingOffline =
		Collections.newSetFromMap(new HashMap<>());
	private final Map<String, String> offlineFailures = new HashMap<>();
	
	private final ExecutorService workerExecutor;
	private volatile boolean shuttingDown;
	private volatile boolean autoRespawn;
	private volatile BotProxyMode botProxyMode = BotProxyMode.SELECTED;
	
	public AltBotManager()
	{
		workerExecutor = Executors.newFixedThreadPool(2, r -> {
			Thread t = new Thread(r, "Wurst AltBot");
			t.setDaemon(true);
			return t;
		});
		
		Runtime.getRuntime().addShutdownHook(
			new Thread(this::shutdown, "Wurst AltBot Shutdown"));
	}
	
	public boolean isAutoRespawnEnabled()
	{
		return autoRespawn;
	}
	
	public void setAutoRespawnEnabled(boolean enabled)
	{
		autoRespawn = enabled;
	}
	
	public BotProxyMode getBotProxyMode()
	{
		return botProxyMode;
	}
	
	public void cycleBotProxyMode()
	{
		BotProxyMode[] modes = BotProxyMode.values();
		int next = (botProxyMode.ordinal() + 1) % modes.length;
		botProxyMode = modes[next];
	}
	
	// ------------------------------------------------------- public API
	
	/**
	 * Connects the given token alt as a bot to the specified server. Safe to
	 * call from the render thread; authentication and connecting happen on a
	 * worker thread.
	 */
	public void connectBot(TokenAlt alt, String host, int port)
	{
		if(!validateConnect(alt, true))
			return;
		workerExecutor.execute(() -> connectBotAsync(alt, host, port, null));
	}
	
	private void connectBot(TokenAlt alt, String host, int port,
		SocksProxy proxy)
	{
		if(!validateConnect(alt, true))
			return;
		workerExecutor.execute(() -> connectBotAsync(alt, host, port, proxy));
	}
	
	/**
	 * Connects the source account as a bot during an account switch. Bypasses
	 * the rendered-client check because the switch controller has already
	 * disconnected the rendered client and is about to change its session.
	 */
	void connectBotForSwitch(TokenAlt alt, String host, int port)
	{
		if(!validateConnect(alt, false))
			return;
		workerExecutor.execute(() -> connectBotAsync(alt, host, port, null));
	}
	
	private boolean validateConnect(TokenAlt alt, boolean checkActiveClient)
	{
		if(alt == null || alt.getDisplayName() == null
			|| alt.getDisplayName().isBlank())
			return false;
		
		synchronized(lock)
		{
			if(shuttingDown)
				return false;
			
			AltBotSession existing = sessions.get(alt);
			if(pendingAuth.contains(alt)
				|| existing != null && existing.getState() != BotState.FAILED
					&& existing.getState() != BotState.DISCONNECTED)
			{
				ChatUtils.error("Bot for \"" + alt.getDisplayName()
					+ "\" is already connecting or connected.");
				return false;
			}
			
			if(checkActiveClient && isActiveClientAlt(alt))
			{
				ChatUtils.error("Cannot connect \"" + alt.getDisplayName()
					+ "\" as a bot while it is the rendered client.");
				return false;
			}
			
			pendingAuth.add(alt);
			return true;
		}
	}
	
	/** Connects a bot to the server the rendered client is currently on. */
	public void connectBotToCurrentServer(TokenAlt alt)
	{
		String ip = AltBotUtils.getCurrentServerIp();
		if(ip == null || ip.isBlank())
		{
			ChatUtils.error("You must be on a multiplayer server to connect a"
				+ " bot to the current server.");
			return;
		}
		String[] hostPort = AltBotUtils.resolveHostPort(ip);
		connectBot(alt, hostPort[0], Integer.parseInt(hostPort[1]));
	}
	
	public void connectBotToCurrentServer(TokenAlt alt, SocksProxy proxy)
	{
		String ip = AltBotUtils.getCurrentServerIp();
		if(ip == null || ip.isBlank())
		{
			ChatUtils.error("You must be on a multiplayer server to connect a"
				+ " bot to the current server.");
			return;
		}
		String[] hostPort = AltBotUtils.resolveHostPort(ip);
		connectBot(alt, hostPort[0], Integer.parseInt(hostPort[1]), proxy);
	}
	
	/**
	 * Connects an offline (cracked) bot with the given name to the specified
	 * server. No Microsoft authentication is performed; works on offline
	 * servers. Safe to call from the render thread.
	 */
	public void connectOfflineBot(String name, String host, int port)
	{
		if(name == null || name.isBlank() || host == null || host.isBlank())
			return;
		
		String key = name.toLowerCase(Locale.ROOT);
		synchronized(lock)
		{
			if(shuttingDown)
				return;
			AltBotSession existing = offlineSessions.get(key);
			if(pendingOffline.contains(key)
				|| existing != null && existing.getState() != BotState.FAILED
					&& existing.getState() != BotState.DISCONNECTED)
			{
				ChatUtils.error(
					"Bot \"" + name + "\" is already connecting or connected.");
				return;
			}
			pendingOffline.add(key);
		}
		workerExecutor.execute(() -> connectOfflineBotAsync(name, host, port));
	}
	
	/** Connects an offline bot to the server the rendered client is on. */
	public void connectOfflineBotToCurrentServer(String name)
	{
		String ip = AltBotUtils.getCurrentServerIp();
		if(ip == null || ip.isBlank())
		{
			ChatUtils.error("You must be on a multiplayer server to connect a"
				+ " bot to the current server.");
			return;
		}
		String[] hostPort = AltBotUtils.resolveHostPort(ip);
		connectOfflineBot(name, hostPort[0], Integer.parseInt(hostPort[1]));
	}
	
	/** Disconnects an offline bot with the given name. */
	public void disconnectOfflineBot(String name)
	{
		if(name == null)
			return;
		String key = name.toLowerCase(Locale.ROOT);
		workerExecutor.execute(() -> {
			AltBotSession session;
			synchronized(lock)
			{
				pendingOffline.remove(key);
				session = offlineSessions.remove(key);
				offlineFailures.remove(key);
			}
			if(session != null)
				session.disconnect("Disconnected by user");
		});
	}
	
	public boolean isOfflineBotConnected(String name)
	{
		if(name == null)
			return false;
		String key = name.toLowerCase(Locale.ROOT);
		synchronized(lock)
		{
			AltBotSession session = offlineSessions.get(key);
			return session != null
				&& session.getState() != BotState.DISCONNECTED
				&& session.getState() != BotState.FAILED;
		}
	}
	
	/** @return the names of currently connected offline bots. */
	public List<String> getConnectedOfflineBotNames()
	{
		synchronized(lock)
		{
			ArrayList<String> names = new ArrayList<>();
			for(AltBotSession session : offlineSessions.values())
				if(session.getState() != BotState.DISCONNECTED
					&& session.getState() != BotState.FAILED)
					names.add(session.getUsername());
			return List.copyOf(names);
		}
	}
	
	/** @return an immutable snapshot of an offline bot's state. */
	public AltBotState getOfflineBotState(String name)
	{
		if(name == null)
			name = "";
		String key = name.toLowerCase(Locale.ROOT);
		synchronized(lock)
		{
			AltBotSession session = offlineSessions.get(key);
			if(session != null)
				return snapshot(session);
			
			if(pendingOffline.contains(key))
				return new AltBotState(null, name, null, null, null,
					BotState.CONNECTING, null, 0L, false, false, 0, 0, 0,
					false);
			
			String error = offlineFailures.get(key);
			if(error != null)
				return new AltBotState(null, name, null, null, null,
					BotState.FAILED, error, 0L, false, false, 0, 0, 0, false);
		}
		
		return new AltBotState(null, name, null, null, null,
			BotState.DISCONNECTED, null, 0L, false, false, 0, 0, 0, false);
	}
	
	/** @return true if a packet was sent through the offline bot. */
	public boolean sendOfflineChat(String name, String text)
	{
		AltBotSession session = getOfflineSession(name);
		return session != null && session.sendChat(text);
	}
	
	/** @return true if a command packet was sent through the offline bot. */
	public boolean sendOfflineCommand(String name, String command)
	{
		AltBotSession session = getOfflineSession(name);
		return session != null && session.sendCommand(command);
	}
	
	private AltBotSession getOfflineSession(String name)
	{
		if(name == null)
			return null;
		synchronized(lock)
		{
			return offlineSessions.get(name.toLowerCase(Locale.ROOT));
		}
	}
	
	/** Requests a clean disconnect of the bot for the given alt. */
	public void disconnectBot(TokenAlt alt)
	{
		disconnectBot(alt, null);
	}
	
	/**
	 * Disconnects the bot and runs the callback on the Minecraft client thread
	 * once it is fully disconnected.
	 */
	public void disconnectBot(TokenAlt alt, Runnable onClientDone)
	{
		workerExecutor.execute(() -> {
			AltBotSession session;
			synchronized(lock)
			{
				pendingAuth.remove(alt);
				session = sessions.remove(alt);
				failures.remove(alt);
			}
			if(session != null)
				session.disconnect("Disconnected by user");
			
			if(onClientDone != null)
				Minecraft.getInstance().execute(onClientDone);
		});
	}
	
	/**
	 * Disconnects every bot. Used when Minecraft exits. Runs on the worker
	 * executor so the render thread is never blocked.
	 */
	public void disconnectAll()
	{
		if(shuttingDown)
			return;
		workerExecutor.execute(() -> {
			synchronized(lock)
			{
				shuttingDown = true;
				pendingAuth.clear();
				for(AltBotSession session : sessions.values())
					session.disconnect("Minecraft closing");
				sessions.clear();
				failures.clear();
				pendingOffline.clear();
				for(AltBotSession session : offlineSessions.values())
					session.disconnect("Minecraft closing");
				offlineSessions.clear();
				offlineFailures.clear();
			}
		});
	}
	
	/** Full shutdown used by the JVM shutdown hook. */
	void shutdown()
	{
		synchronized(lock)
		{
			shuttingDown = true;
			for(AltBotSession session : sessions.values())
				session.disconnect("Minecraft closing");
			sessions.clear();
			pendingAuth.clear();
			failures.clear();
			for(AltBotSession session : offlineSessions.values())
				session.disconnect("Minecraft closing");
			offlineSessions.clear();
			pendingOffline.clear();
			offlineFailures.clear();
		}
		workerExecutor.shutdown();
	}
	
	/** @return true if a packet was sent through the bot. */
	public boolean sendChat(TokenAlt alt, String text)
	{
		AltBotSession session = getSession(alt);
		if(session == null)
			return false;
		return session.sendChat(text);
	}
	
	/** @return true if a command packet was sent through the bot. */
	public boolean sendCommand(TokenAlt alt, String command)
	{
		AltBotSession session = getSession(alt);
		if(session == null)
			return false;
		return session.sendCommand(command);
	}
	
	public AltBotSession getSession(TokenAlt alt)
	{
		synchronized(lock)
		{
			return sessions.get(alt);
		}
	}
	
	public boolean isBotConnected(TokenAlt alt)
	{
		AltBotSession session = getSession(alt);
		return session != null && session.getState() != BotState.DISCONNECTED
			&& session.getState() != BotState.FAILED;
	}
	
	public boolean isBotReady(TokenAlt alt)
	{
		AltBotSession session = getSession(alt);
		return session != null && session.isReady();
	}
	
	/**
	 * @return an immutable snapshot of the bot state for the given alt. Never
	 *         null.
	 */
	public AltBotState getState(Alt alt)
	{
		if(alt instanceof TokenAlt tokenAlt)
		{
			AltBotSession session;
			synchronized(lock)
			{
				session = sessions.get(tokenAlt);
				if(session != null)
					return snapshot(session);
				
				if(pendingAuth.contains(tokenAlt))
					return new AltBotState(tokenAlt, tokenAlt.getDisplayName(),
						null, null, null, BotState.AUTHENTICATING, null, 0L,
						false, isActiveClientAlt(tokenAlt), 0, 0, 0, false);
				
				String error = failures.get(tokenAlt);
				if(error != null)
					return new AltBotState(tokenAlt, tokenAlt.getDisplayName(),
						null, null, null, BotState.FAILED, error, 0L, false,
						isActiveClientAlt(tokenAlt), 0, 0, 0, false);
			}
		}
		
		return new AltBotState(null, alt.getDisplayName(), null, null, null,
			BotState.DISCONNECTED, null, 0L, false, isActiveClientAlt(alt), 0,
			0, 0, false);
	}
	
	/** @return snapshots for every bot-compatible saved alt. */
	public List<AltBotState> getAllStates()
	{
		ArrayList<AltBotState> states = new ArrayList<>();
		for(Alt alt : getAlts())
			states.add(getState(alt));
		return List.copyOf(states);
	}
	
	/** @return true if the given alt is the account of the rendered client. */
	public boolean isActiveClientAlt(Alt alt)
	{
		if(alt == null)
			return false;
		
		Minecraft mc = Minecraft.getInstance();
		if(mc == null)
			return false;
		
		var user = mc.getUser();
		if(user == null)
			return false;
		
		if(alt instanceof TokenAlt tokenAlt)
		{
			AltBotSession session = getSession(tokenAlt);
			if(session != null && session.getUuid() != null
				&& user.getProfileId() != null)
				return session.getUuid().equals(user.getProfileId());
		}
		
		return AltBotUtils.matchesName(alt.getName(), user.getName());
	}
	
	/**
	 * @return the stored alt that currently matches the rendered client, or
	 *         null.
	 */
	public TokenAlt resolveCurrentAlt()
	{
		Minecraft mc = Minecraft.getInstance();
		if(mc == null || mc.getUser() == null)
			return null;
		
		String name = mc.getUser().getName();
		var profileId = mc.getUser().getProfileId();
		
		for(Alt alt : getAlts())
			if(alt instanceof TokenAlt tokenAlt)
			{
				AltBotSession session = getSession(tokenAlt);
				if(session != null && session.getUuid() != null
					&& profileId != null && session.getUuid().equals(profileId))
					return tokenAlt;
				if(AltBotUtils.matchesName(tokenAlt.getName(), name))
					return tokenAlt;
			}
		return null;
	}
	
	/** Resolves a saved alt by display name (case-insensitive). */
	public TokenAlt findAltByName(String name)
	{
		if(name == null)
			return null;
		for(Alt alt : getAlts())
			if(alt instanceof TokenAlt tokenAlt
				&& AltBotUtils.matchesName(tokenAlt.getDisplayName(), name))
				return tokenAlt;
		return null;
	}
	
	/** @return all bot-compatible (token) saved alts. */
	public List<TokenAlt> getCompatibleAlts()
	{
		ArrayList<TokenAlt> alts = new ArrayList<>();
		for(Alt alt : getAlts())
			if(alt instanceof TokenAlt tokenAlt)
				alts.add(tokenAlt);
		return List.copyOf(alts);
	}
	
	/** Must be called when an alt is removed from the Alt Manager. */
	public void onAltRemoved(Alt alt)
	{
		if(alt instanceof TokenAlt tokenAlt)
			disconnectBot(tokenAlt);
	}
	
	/**
	 * Persists a token alt after its refresh token was rotated. Marshalled to
	 * the client thread because the alt list is owned by the GUI thread.
	 */
	void saveAltIfNeeded(TokenAlt alt)
	{
		Minecraft.getInstance().execute(() -> {
			try
			{
				WurstClient.INSTANCE.getAltManager().saveTokenAlt(alt);
			}catch(Throwable e)
			{
				log(alt.getDisplayName(),
					"could not save rotated refresh token: " + e.getMessage());
			}
		});
	}
	
	// ---------------------------------------------------- internal callbacks
	
	void onBotReady(AltBotSession session)
	{
		log(session.getLabel(), "bot is ready (play state)");
	}
	
	void onBotDisconnected(AltBotSession session, String reason,
		boolean expected)
	{
		synchronized(lock)
		{
			TokenAlt alt = session.getAlt();
			if(alt != null)
			{
				if(sessions.get(alt) == session)
					sessions.remove(alt);
					
				// Keep the reason visible for unexpected disconnects (kicks,
				// network drops) so the GUI can show why the bot went offline.
				if(!expected)
				{
					String message = reason == null || reason.isBlank()
						? "Disconnected" : "Disconnected: " + reason;
					failures.put(alt, message);
				}
			}else
			{
				String key = session.getUsername().toLowerCase(Locale.ROOT);
				if(offlineSessions.get(key) == session)
					offlineSessions.remove(key);
				
				if(!expected)
				{
					String message = reason == null || reason.isBlank()
						? "Disconnected" : "Disconnected: " + reason;
					offlineFailures.put(key, message);
				}
			}
		}
	}
	
	// ---------------------------------------------------------- private
	
	private void connectBotAsync(TokenAlt alt, String host, int port,
		SocksProxy proxyOverride)
	{
		try
		{
			AltBotUtils.log(alt.getDisplayName(),
				"authenticating for bot connection...");
			MinecraftProfile profile = alt.authenticateWithoutSession();
			
			if(profile == null || profile.getName() == null
				|| profile.getName().isBlank())
				throw new LoginException("Authentication returned no profile.");
			
			// Persist a rotated refresh token if Microsoft issued one.
			saveAltIfNeeded(alt);
			
			PlayerCertificates certs =
				PlayerCertificates.fetch(profile.getAccessToken());
			AltBotSession session = new AltBotSession(this, alt, profile, host,
				port, certs, proxyOverride == null ? buildProxyInfo()
					: buildProxyInfo(proxyOverride));
			
			synchronized(lock)
			{
				if(shuttingDown)
					return;
				sessions.put(alt, session);
				pendingAuth.remove(alt);
			}
			
			session.start();
			
		}catch(Throwable e)
		{
			synchronized(lock)
			{
				pendingAuth.remove(alt);
				failures.put(alt, friendlyAuthError(e));
			}
			ChatUtils.error("Failed to connect " + alt.getDisplayName()
				+ " as bot: " + friendlyAuthError(e));
			log(alt.getDisplayName(),
				"bot connection failed: " + safeMessage(e));
		}
	}
	
	private void connectOfflineBotAsync(String name, String host, int port)
	{
		String key = name.toLowerCase(Locale.ROOT);
		try
		{
			UUID uuid = UUID.nameUUIDFromBytes(
				("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
			MinecraftProfile profile = new MinecraftProfile(uuid, name, "");
			AltBotSession session = new AltBotSession(this, null, profile, host,
				port, null, buildProxyInfo());
			
			synchronized(lock)
			{
				if(shuttingDown)
					return;
				offlineSessions.put(key, session);
				pendingOffline.remove(key);
			}
			
			session.start();
			
		}catch(Throwable e)
		{
			synchronized(lock)
			{
				pendingOffline.remove(key);
				offlineFailures.put(key, safeMessage(e));
			}
			ChatUtils.error("Failed to connect offline bot \"" + name + "\": "
				+ safeMessage(e));
			log(name, "offline bot connection failed: " + safeMessage(e));
		}
	}
	
	private String friendlyAuthError(Throwable e)
	{
		String m = safeMessage(e);
		if(m == null || m.isBlank())
			return "Unknown error";
		return m;
	}
	
	/** Builds the MCProtocolLib proxy from Wurst's selected proxy, if any. */
	private org.geysermc.mcprotocollib.network.ProxyInfo buildProxyInfo()
	{
		try
		{
			var proxyManager = WurstClient.INSTANCE.getProxyManager();
			SocksProxy proxy = switch(botProxyMode)
			{
				case DIRECT -> null;
				case SELECTED -> proxyManager.getSelectedProxy();
				case RANDOM ->
				{
					List<SocksProxy> proxies = proxyManager.getProxies();
					yield proxies.isEmpty() ? null : proxies.get(
						ThreadLocalRandom.current().nextInt(proxies.size()));
				}
			};
			return buildProxyInfo(proxy);
			
		}catch(Throwable e)
		{
			return null;
		}
	}
	
	private org.geysermc.mcprotocollib.network.ProxyInfo buildProxyInfo(
		SocksProxy proxy)
	{
		if(proxy == null)
		{
			AltBotUtils.log("proxy", "Bot connection using direct connection.");
			return null;
		}
		AltBotUtils.log("proxy",
			"Bot connection using " + proxy.getDisplayName() + ".");
		org.geysermc.mcprotocollib.network.ProxyInfo.Type type =
			proxy.getProtocol() == ProxyProtocol.HTTP
				? org.geysermc.mcprotocollib.network.ProxyInfo.Type.HTTP
				: org.geysermc.mcprotocollib.network.ProxyInfo.Type.SOCKS5;
		return new org.geysermc.mcprotocollib.network.ProxyInfo(type,
			proxy.getAddress(), proxy.getUsername(), proxy.getPassword());
	}
	
	private static String safeMessage(Throwable e)
	{
		if(e == null)
			return "Unknown error";
		String m = e.getMessage();
		return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
	}
	
	private AltBotState snapshot(AltBotSession session)
	{
		boolean active = isActiveClientAlt(session.getAlt());
		return new AltBotState(session.getAlt(), session.getUsername(),
			session.getUuid(), session.getUsername(), session.getServer(),
			session.getState(), session.getLastError(),
			session.getConnectionStart(), session.isReady(), active,
			session.getX(), session.getY(), session.getZ(),
			session.hasPosition());
	}
	
	private List<Alt> getAlts()
	{
		AltManager altManager = WurstClient.INSTANCE.getAltManager();
		return altManager == null ? List.of() : altManager.getList();
	}
	
	void log(String label, String message)
	{
		AltBotUtils.log(label, message);
	}
}
