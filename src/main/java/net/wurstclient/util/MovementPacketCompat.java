/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.SharedConstants;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.wurstclient.WurstClient;

/**
 * Restores synchronous position-packet bursts on 26.3 servers by separating
 * their position packets with synthetic client tick boundaries. The tick
 * boundaries are only inserted when the server on the other end needs them.
 */
public final class MovementPacketCompat
{
	private static final boolean DEBUG = false;
	private static final ThreadLocal<Boolean> sendingSyntheticTickEnd =
		ThreadLocal.withInitial(() -> false);
	private static final Map<Connection, ConnectionState> states =
		new WeakHashMap<>();
	private static volatile Class<?> viaFabricPlusClass;
	private static volatile boolean viaFabricPlusLookedUp;
	
	private MovementPacketCompat()
	{}
	
	public static boolean isSendingSyntheticTickEnd()
	{
		return sendingSyntheticTickEnd.get();
	}
	
	public static synchronized void beforePacketSend(Connection connection,
		Packet<?> packet)
	{
		ConnectionState state = getState(connection);
		
		// Every tick boundary closes the position slot, whether Minecraft sent
		// it or this class did.
		if(packet instanceof ServerboundClientTickEndPacket)
		{
			state.positionSentThisTick = false;
			return;
		}
		
		// Only positional movement packets occupy the slot. Rotation-only
		// movement and all other packets leave it untouched, so it doesn't
		// matter what else is sent in between them.
		if(!(packet instanceof ServerboundMovePlayerPacket move)
			|| !move.hasPosition())
			return;
		
		ProtocolTarget target = getRemoteProtocol(connection, state);
		printConnectionSummary(state, target);
		if(!shouldUseSyntheticMovementScheduling(target))
		{
			state.positionSentThisTick = false;
			return;
		}
		
		if(state.positionSentThisTick)
			sendSyntheticTickEnd(connection);
		
		state.positionSentThisTick = true;
	}
	
	/**
	 * Sends a tick boundary that Minecraft didn't send itself. The nested call
	 * closes the current tick and then starts a new one, and it can't recurse
	 * any further because the packet it sends is turned away at the top of
	 * {@link #beforePacketSend}.
	 */
	private static synchronized void sendSyntheticTickEnd(Connection connection)
	{
		sendingSyntheticTickEnd.set(true);
		try
		{
			connection.send(ServerboundClientTickEndPacket.INSTANCE);
		}finally
		{
			sendingSyntheticTickEnd.remove();
		}
	}
	
	public static synchronized void reset(Connection connection)
	{
		states.remove(connection);
	}
	
	public static synchronized void reset()
	{
		states.clear();
	}
	
	private static ConnectionState getState(Connection connection)
	{
		return states.computeIfAbsent(connection, c -> new ConnectionState());
	}
	
	private static boolean shouldUseSyntheticMovementScheduling(
		ProtocolTarget target)
	{
		if(WurstClient.INSTANCE == null
			|| WurstClient.INSTANCE.getOtfs() == null
			|| !WurstClient.INSTANCE.getOtfs().wurstOptionsOtf
				.shouldRestorePacketSchedulingOnOlderServers())
			return true;
		
		return target.protocol == null || target.protocol >= currentProtocol();
	}
	
	private static ProtocolTarget getRemoteProtocol(Connection connection,
		ConnectionState state)
	{
		if(state.cachedProtocolTarget != null)
			return state.cachedProtocolTarget;
		
		ProtocolTarget target = detectRemoteProtocol(connection);
		if(target.protocol != null)
			state.cachedProtocolTarget = target;
		
		return target;
	}
	
	private static ProtocolTarget detectRemoteProtocol(Connection connection)
	{
		Class<?> viaFabricPlus;
		try
		{
			viaFabricPlus = findViaFabricPlus();
		}catch(LinkageError | SecurityException e)
		{
			return ProtocolTarget.unknown();
		}
		
		if(viaFabricPlus == null)
			return ProtocolTarget.nativeTarget();
		
		// ViaFabricPlus 5.x public API.
		try
		{
			Object api = viaFabricPlus.getMethod("api").invoke(null);
			ClassLoader classLoader =
				MovementPacketCompat.class.getClassLoader();
			Class<?> apiClass = Class.forName(
				"com.viaversion.viafabricplus.api.ViaFabricPlusAPI", false,
				classLoader);
			Object translation =
				apiClass.getMethod("protocolTranslation").invoke(api);
			Class<?> translationClass = Class.forName(
				"com.viaversion.viafabricplus.api.protocoltranslator.ProtocolTranslation",
				false, classLoader);
			Object target =
				translationClass.getMethod("targetVersion", Connection.class)
					.invoke(translation, connection);
			ProtocolTarget result =
				ProtocolTarget.from(target, "ViaFabricPlus");
			if(result.protocol != null)
				return result;
		}catch(ReflectiveOperationException | LinkageError
			| SecurityException e)
		{}
		
		// ViaFabricPlus' public, deprecated compatibility API.
		try
		{
			Object legacy = viaFabricPlus.getMethod("getImpl").invoke(null);
			Class<?> legacyApi = Class.forName(
				"com.viaversion.viafabricplus.api.ViaFabricPlusBase", false,
				MovementPacketCompat.class.getClassLoader());
			Object target =
				legacyApi.getMethod("getTargetVersion", Connection.class)
					.invoke(legacy, connection);
			ProtocolTarget result =
				ProtocolTarget.from(target, "ViaFabricPlus");
			if(result.protocol != null)
				return result;
		}catch(ReflectiveOperationException | LinkageError
			| SecurityException e)
		{}
		
		// Compatibility fallback for ViaFabricPlus releases before its public
		// API.
		try
		{
			Class<?> implementation =
				Class.forName("com.viaversion.viafabricplus.ViaFabricPlusImpl",
					false, MovementPacketCompat.class.getClassLoader());
			Object instance = implementation.getField("INSTANCE").get(null);
			Object target =
				implementation.getMethod("getTargetVersion", Connection.class)
					.invoke(instance, connection);
			ProtocolTarget result =
				ProtocolTarget.from(target, "ViaFabricPlus");
			if(result.protocol != null)
				return result;
		}catch(ReflectiveOperationException | LinkageError
			| SecurityException e)
		{}
		
		return ProtocolTarget.unknown();
	}
	
	/**
	 * Resolves ViaFabricPlus once instead of once per outgoing packet. Returns
	 * null if it isn't installed.
	 */
	private static Class<?> findViaFabricPlus()
	{
		if(!viaFabricPlusLookedUp)
		{
			try
			{
				viaFabricPlusClass =
					Class.forName("com.viaversion.viafabricplus.ViaFabricPlus",
						false, MovementPacketCompat.class.getClassLoader());
			}catch(ClassNotFoundException e)
			{
				viaFabricPlusClass = null;
			}
			viaFabricPlusLookedUp = true;
		}
		
		return viaFabricPlusClass;
	}
	
	private static int currentProtocol()
	{
		return SharedConstants.getCurrentVersion().protocolVersion();
	}
	
	private static void printConnectionSummary(ConnectionState state,
		ProtocolTarget target)
	{
		if(!DEBUG || state.summaryPrinted
			&& (!state.summaryWasUnknown || target.protocol == null))
			return;
		
		boolean synthetic = shouldUseSyntheticMovementScheduling(target);
		System.out.println("MovementCompat: target=" + target.name + " source="
			+ target.source + " synthetic=" + synthetic);
		state.summaryPrinted = true;
		state.summaryWasUnknown = target.protocol == null;
	}
	
	private static final class ConnectionState
	{
		private boolean positionSentThisTick;
		private ProtocolTarget cachedProtocolTarget;
		private boolean summaryPrinted;
		private boolean summaryWasUnknown;
	}
	
	private static final class ProtocolTarget
	{
		private final Integer protocol;
		private final String name;
		private final String source;
		
		private ProtocolTarget(Integer protocol, String name, String source)
		{
			this.protocol = protocol;
			this.name = name;
			this.source = source;
		}
		
		private static ProtocolTarget from(Object target, String source)
		{
			if(target == null)
				return unknown();
			
			try
			{
				Method isKnown;
				try
				{
					isKnown = target.getClass().getMethod("isKnown");
				}catch(NoSuchMethodException e)
				{
					isKnown = null;
				}
				if(isKnown != null
					&& Boolean.FALSE.equals(isKnown.invoke(target)))
					return unknown();
				
				Object version =
					target.getClass().getMethod("getVersion").invoke(target);
				if(!(version instanceof Number number) || number.intValue() < 0)
					return unknown();
				
				String name = number.toString();
				try
				{
					Object targetName =
						target.getClass().getMethod("getName").invoke(target);
					if(targetName instanceof String string)
						name = string;
				}catch(NoSuchMethodException e)
				{}
				return new ProtocolTarget(number.intValue(), name, source);
			}catch(ReflectiveOperationException | LinkageError
				| SecurityException e)
			{
				return unknown();
			}
		}
		
		private static ProtocolTarget nativeTarget()
		{
			return new ProtocolTarget(currentProtocol(),
				SharedConstants.getCurrentVersion().name(), "native");
		}
		
		private static ProtocolTarget unknown()
		{
			return new ProtocolTarget(null, "unknown", "unknown");
		}
	}
}
