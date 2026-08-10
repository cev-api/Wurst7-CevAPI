/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.altbot;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Hashtable;

import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.wurstclient.altmanager.MinecraftProfile;
import net.wurstclient.util.ChatUtils;

/**
 * Small helpers shared by the bot manager, the switch controller and the
 * Alt Manager screens. Nothing in here touches the Minecraft render thread
 * directly; callers are responsible for marshalling.
 */
public final class AltBotUtils
{
	private AltBotUtils()
	{}
	
	/** Builds a stable short label such as "SomeAlt (a1b2c3d4)". */
	public static String getLabel(String name, UUID uuid)
	{
		String n = name == null || name.isBlank() ? "unknown" : name;
		String shortUuid = uuid == null ? "????????"
			: uuid.toString().replace("-", "").substring(0, 8);
		return n + " (" + shortUuid + ")";
	}
	
	/** Builds the Minecraft session {@link User} for a profile. */
	public static User buildUser(MinecraftProfile profile)
	{
		return new User(profile.getName(), profile.getUUID(),
			profile.getAccessToken(), Optional.empty(), Optional.empty());
	}
	
	/** @return the ip string of the current server, or null. */
	public static String getCurrentServerIp()
	{
		ServerData server = getCurrentServer();
		return server == null ? null : server.ip;
	}
	
	public static ServerData getCurrentServer()
	{
		return Minecraft.getInstance().getCurrentServer();
	}
	
	public static boolean isOnServer()
	{
		Minecraft mc = Minecraft.getInstance();
		return mc.getConnection() != null && mc.level != null
			&& mc.getCurrentServer() != null;
	}
	
	/**
	 * Parses a ServerData ip string into a host and a port, resolving the SRV
	 * record when no explicit port was given (mirrors what the vanilla client
	 * does when joining).
	 */
	public static String[] resolveHostPort(String ip)
	{
		String[] parts = ip.split(":", 2);
		String host = parts[0];
		int port;
		if(parts.length > 1)
		{
			try
			{
				port = Integer.parseInt(parts[1]);
			}catch(NumberFormatException e)
			{
				port = 25565;
			}
		}else
		{
			int srvPort = resolveSrvPort(host);
			port = srvPort > 0 ? srvPort : 25565;
		}
		return new String[]{host, String.valueOf(port)};
	}
	
	/** Looks up the _minecraft._tcp SRV record, returning the port or -1. */
	public static int resolveSrvPort(String host)
	{
		String query = "_minecraft._tcp." + host;
		Hashtable<String, String> env = new Hashtable<>();
		env.put("java.naming.factory.initial",
			"com.sun.jndi.dns.DnsContextFactory");
		env.put("java.naming.provider.url", "dns:");
		try
		{
			DirContext context = new InitialDirContext(env);
			Attributes attrs =
				context.getAttributes(query, new String[]{"SRV"});
			Attribute srv = attrs.get("SRV");
			if(srv == null || srv.size() == 0)
				return -1;
			
			var all = srv.getAll();
			if(!all.hasMore())
				return -1;
			
			// Format: "priority weight port target"
			String[] parts = all.next().toString().trim().split("\\s+");
			if(parts.length < 3)
				return -1;
			
			return Integer.parseInt(parts[2]);
			
		}catch(Exception e)
		{
			return -1;
		}
	}
	
	/** Disconnects the rendered client from its current server. */
	public static void disconnectClient()
	{
		Minecraft mc = Minecraft.getInstance();
		if(mc.level != null)
			mc.level.disconnect(Component.literal("Switching account"));
	}
	
	/**
	 * Reconnects the rendered client to the given server. Must be called on
	 * the client thread.
	 */
	public static void reconnectClient(ServerData server, Screen prevScreen)
	{
		Minecraft mc = Minecraft.getInstance();
		Screen previous = prevScreen != null ? prevScreen
			: mc.screen;
		ServerAddress address = ServerAddress.parseString(server.ip);
		ConnectScreen.startConnecting(previous, mc, address, server, false,
			null);
	}
	
	/**
	 * Runs a task on the Minecraft client thread and blocks the calling thread
	 * until it has finished. Safe to call from worker threads.
	 */
	public static void runOnClientThread(Runnable task)
	{
		Minecraft mc = Minecraft.getInstance();
		if(mc == null)
			return;
		
		if(mc.isSameThread())
		{
			task.run();
			return;
		}
		
		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<Throwable> error = new AtomicReference<>();
		mc.execute(() -> {
			try
			{
				task.run();
			}catch(Throwable t)
			{
				error.set(t);
			}finally
			{
				latch.countDown();
			}
		});
		
		try
		{
			latch.await(10, TimeUnit.SECONDS);
		}catch(InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}
		
		Throwable t = error.get();
		if(t != null)
			throw new RuntimeException(t);
	}
	
	public static void chatMessage(String message)
	{
		ChatUtils.message(message);
	}
	
	public static void chatError(String message)
	{
		ChatUtils.error(message);
	}
	
	public static void log(String tag, String message)
	{
		System.out.println("[AltBot][" + tag + "] " + message);
	}
	
	/** Case-insensitive username match helper for commands. */
	public static boolean matchesName(String a, String b)
	{
		return a != null && b != null
			&& a.toLowerCase(Locale.ROOT).equals(b.toLowerCase(Locale.ROOT));
	}
}
