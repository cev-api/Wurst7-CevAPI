/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.altbot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
	private final Object lock = new Object();
	private final IdentityHashMap<TokenAlt, AltBotSession> sessions =
		new IdentityHashMap<>();
	private final Set<TokenAlt> pendingAuth =
		Collections.newSetFromMap(new IdentityHashMap<>());
	private final IdentityHashMap<TokenAlt, String> failures =
		new IdentityHashMap<>();
	
	private final ExecutorService workerExecutor;
	private volatile boolean shuttingDown;
	
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
		workerExecutor.execute(() -> connectBotAsync(alt, host, port));
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
		workerExecutor.execute(() -> connectBotAsync(alt, host, port));
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
			if(sessions.get(session.getAlt()) == session)
				sessions.remove(session.getAlt());
				
			// Keep the reason visible for unexpected disconnects (kicks,
			// network drops) so the GUI can show why the bot went offline.
			if(!expected)
			{
				String message = reason == null || reason.isBlank()
					? "Disconnected" : "Disconnected: " + reason;
				failures.put(session.getAlt(), message);
			}
		}
	}
	
	// ---------------------------------------------------------- private
	
	private void connectBotAsync(TokenAlt alt, String host, int port)
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
				port, certs, buildProxyInfo());
			
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
			SocksProxy proxy =
				WurstClient.INSTANCE.getProxyManager().getSelectedProxy();
			if(proxy == null)
				return null;
			
			org.geysermc.mcprotocollib.network.ProxyInfo.Type type =
				proxy.getProtocol() == ProxyProtocol.HTTP
					? org.geysermc.mcprotocollib.network.ProxyInfo.Type.HTTP
					: org.geysermc.mcprotocollib.network.ProxyInfo.Type.SOCKS5;
			return new org.geysermc.mcprotocollib.network.ProxyInfo(type,
				proxy.getAddress(), proxy.getUsername(), proxy.getPassword());
			
		}catch(Throwable e)
		{
			return null;
		}
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
