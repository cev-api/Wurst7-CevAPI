/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket;
import net.wurstclient.WurstClient;
import net.wurstclient.events.PacketInputListener;
import net.wurstclient.events.PacketInputListener.PacketInputEvent;

public final class ServerObserver implements PacketInputListener
{
	private static final int AVERAGE_OF = 15;
	private static final int REQUIRED_TRANSACTIONS = 5;
	
	private final Object lock = new Object();
	private final Minecraft mc;
	private final List<Integer> transactions = new ArrayList<>();
	private boolean isCapturingTransactions;
	private final Deque<Double> timeIntervals =
		new ArrayDeque<>(AVERAGE_OF + 1);
	private long lastTimeUpdateMs = -1L;
	private double tps = Double.NaN;
	
	private String lastServerAddress;
	private String lastPingPacketName;
	private Integer lastPingId;
	private String lastLoginPacketName;
	private boolean notifiedAntiCheat;
	
	public ServerObserver(Minecraft mc)
	{
		this.mc = mc;
	}
	
	@Override
	public void onReceivedPacket(PacketInputEvent event)
	{
		Packet<?> packet = event.getPacket();
		String simpleName = packet.getClass().getSimpleName();
		boolean isLogin = packet instanceof ClientboundLoginPacket
			|| isLoginPacket(simpleName);
		boolean isDisconnect = packet instanceof ClientboundDisconnectPacket
			|| packet instanceof ClientboundLoginDisconnectPacket
			|| isDisconnectPacket(simpleName);
		boolean isTimeUpdate = packet instanceof ClientboundSetTimePacket
			|| isTimeUpdatePacket(simpleName);
		boolean isPing = packet instanceof ClientboundKeepAlivePacket
			|| packet instanceof ClientboundPingPacket
			|| isPingPacket(simpleName);
		
		if(isLogin)
		{
			synchronized(lock)
			{
				resetTransactionsLocked();
				lastLoginPacketName = simpleName;
				lastServerAddress = getCurrentServerAddress();
				isCapturingTransactions = true;
				notifiedAntiCheat = false;
			}
			return;
		}
		
		if(isDisconnect)
		{
			reset();
			return;
		}
		
		if(isTimeUpdate)
		{
			trackTimeUpdate();
			return;
		}
		
		if(isPing)
		{
			lastPingPacketName = packet.getClass().getName();
			Integer id = null;
			if(packet instanceof ClientboundKeepAlivePacket keepAlive)
				id = asInt(keepAlive.getId());
			else if(packet instanceof ClientboundPingPacket ping)
				id = ping.getId();
			else
				id = tryInvokeInt(packet, "parameter", "getParameter", "id",
					"getId", "pingId", "getPingId", "keepAliveId",
					"getKeepAliveId");
			lastPingId = id;
			if(id == null)
				return;
			
			synchronized(lock)
			{
				isCapturingTransactions = true;
				transactions.add(id);
				if(transactions.size() >= REQUIRED_TRANSACTIONS)
				{
					isCapturingTransactions = false;
					if(!notifiedAntiCheat)
					{
						notifiedAntiCheat = true;
						WurstClient.INSTANCE.getHax().antiCheatDetectHack
							.completed();
					}
				}
			}
		}
	}
	
	public void handleDisconnect()
	{
		reset();
	}
	
	public void requestCaptureIfNeeded()
	{
		synchronized(lock)
		{
			if(transactions.size() < REQUIRED_TRANSACTIONS)
				isCapturingTransactions = true;
		}
	}
	
	public String getDebugStatus()
	{
		synchronized(lock)
		{
			return "login=" + safe(lastLoginPacketName) + ", ping="
				+ safe(lastPingPacketName) + ", id="
				+ (lastPingId == null ? "null" : lastPingId) + ", count="
				+ transactions.size();
		}
	}
	
	public String getServerAddress()
	{
		String current = getCurrentServerAddress();
		return current != null ? current : lastServerAddress;
	}
	
	public double getTps()
	{
		synchronized(lock)
		{
			return tps;
		}
	}
	
	public String guessAntiCheat(String address)
	{
		List<Integer> snapshot;
		synchronized(lock)
		{
			if(transactions.size() < REQUIRED_TRANSACTIONS)
				return null;
			
			snapshot = new ArrayList<>(transactions);
		}
		
		int first = snapshot.get(0);
		List<Integer> diffs = new ArrayList<>();
		for(int i = 1; i < snapshot.size(); i++)
			diffs.add(snapshot.get(i) - snapshot.get(i - 1));
		
		boolean allSame = diffs.stream().allMatch(d -> d.equals(diffs.get(0)));
		
		if(address != null && address.toLowerCase().endsWith("hypixel.net"))
			return "Watchdog";
		
		if(allSame)
		{
			int diff = diffs.get(0);
			if(diff == 1)
			{
				if(first >= -23772 && first <= -23762)
					return "Vulcan";
				if((first >= 95 && first <= 105)
					|| (first >= -20005 && first <= -19995))
					return "Matrix";
				if(first >= -32773 && first <= -32762)
					return "Grizzly";
				return "Verus";
			}
			
			if(diff == -1)
			{
				if(first >= -8287 && first <= -8280)
					return "Errata";
				if(first < -3000)
					return "Intave";
				if(first >= -5 && first <= 0)
					return "Grim";
				if(first >= -3000 && first <= -2995)
					return "Karhu";
				return "Polar";
			}
		}
		
		boolean twoEqual = snapshot.get(0).equals(snapshot.get(1));
		boolean restIncremental = true;
		for(int i = 2; i < snapshot.size(); i++)
			if(snapshot.get(i) - snapshot.get(i - 1) != 1)
			{
				restIncremental = false;
				break;
			}
		if(twoEqual && restIncremental)
			return "Verus";
		
		if(diffs.size() >= 3)
		{
			boolean polarPattern = diffs.get(0) >= 100 && diffs.get(1) == -1;
			for(int i = 2; i < diffs.size() && polarPattern; i++)
				if(diffs.get(i) != -1)
					polarPattern = false;
				
			if(polarPattern)
				return "Polar";
		}
		
		if(first < -3000 && snapshot.contains(0))
			return "Intave";
		
		if(snapshot.size() >= 5 && snapshot.get(0) == -30767
			&& snapshot.get(1) == -30766 && snapshot.get(2) == -25767)
		{
			boolean oldVulcan = true;
			for(int i = 3; i < snapshot.size() && oldVulcan; i++)
				if(snapshot.get(i) - snapshot.get(i - 1) != 1)
					oldVulcan = false;
				
			if(oldVulcan)
				return "Old Vulcan";
		}
		
		return "Unknown";
	}
	
	private void reset()
	{
		synchronized(lock)
		{
			resetTransactionsLocked();
			timeIntervals.clear();
			lastTimeUpdateMs = -1L;
			tps = Double.NaN;
			lastPingPacketName = null;
			lastPingId = null;
			lastLoginPacketName = null;
		}
	}
	
	private void resetTransactions()
	{
		synchronized(lock)
		{
			resetTransactionsLocked();
		}
	}
	
	private void resetTransactionsLocked()
	{
		transactions.clear();
		isCapturingTransactions = false;
	}
	
	private String getCurrentServerAddress()
	{
		ServerData server = mc.getCurrentServer();
		if(server == null)
			return null;
		
		return server.ip;
	}
	
	private void trackTimeUpdate()
	{
		synchronized(lock)
		{
			long now = System.currentTimeMillis();
			if(lastTimeUpdateMs >= 0)
			{
				double elapsed = now - lastTimeUpdateMs;
				timeIntervals.addLast(elapsed);
				while(timeIntervals.size() > AVERAGE_OF)
					timeIntervals.removeFirst();
				
				double average = timeIntervals.stream().mapToDouble(d -> d)
					.average().orElse(Double.NaN);
				if(!Double.isNaN(average) && average > 0)
					tps = Math.max(0.0,
						Math.min(20.0, 20.0 / (average / 1000.0)));
			}
			
			lastTimeUpdateMs = now;
		}
	}
	
	private boolean isLoginPacket(String simpleName)
	{
		return "ClientboundLoginPacket".equals(simpleName)
			|| "ClientboundGameJoinPacket".equals(simpleName);
	}
	
	private boolean isDisconnectPacket(String simpleName)
	{
		return "ClientboundDisconnectPacket".equals(simpleName)
			|| "ClientboundLoginDisconnectPacket".equals(simpleName);
	}
	
	private boolean isTimeUpdatePacket(String simpleName)
	{
		return "ClientboundSetTimePacket".equals(simpleName)
			|| "ClientboundUpdateTimePacket".equals(simpleName)
			|| "ClientboundTimeUpdatePacket".equals(simpleName);
	}
	
	private boolean isPingPacket(String simpleName)
	{
		if(simpleName == null || simpleName.isEmpty())
			return false;
		
		return simpleName.contains("Ping") || simpleName.contains("Pong")
			|| simpleName.contains("KeepAlive");
	}
	
	private Integer tryInvokeInt(Object target, String... methodNames)
	{
		for(String methodName : methodNames)
		{
			try
			{
				Method method = target.getClass().getMethod(methodName);
				Object result = method.invoke(target);
				Integer i = asInt(result);
				if(i != null)
					return i;
				
			}catch(Exception ignored)
			{}
		}
		
		try
		{
			for(java.lang.reflect.Field field : target.getClass()
				.getDeclaredFields())
			{
				Class<?> type = field.getType();
				if(type != int.class && type != Integer.class
					&& type != long.class && type != Long.class)
					continue;
				
				field.setAccessible(true);
				Object result = field.get(target);
				Integer i = asInt(result);
				if(i != null)
					return i;
			}
			
		}catch(Exception ignored)
		{}
		
		return null;
	}
	
	private Integer asInt(Object result)
	{
		if(result instanceof Integer i)
			return i;
		
		if(result instanceof Long l)
		{
			if(l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE)
				return (int)(long)l;
		}
		
		return null;
	}
	
	private String safe(String value)
	{
		return value == null ? "null" : value;
	}
}
