/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.WurstClient;
import net.wurstclient.event.EventManager;
import net.wurstclient.events.UpdateListener;

/**
 * Centralized enter/exit detection used by features that care about players
 * getting close (PlayerESP alerts, Antisocial, etc.). The logic is kept here so
 * it keeps working even if the visual PlayerESP hack is disabled.
 */
public final class PlayerRangeAlertManager implements UpdateListener
{
	private final EventManager events;
	private final Minecraft mc = WurstClient.MC;
	private final Set<Listener> listeners = new LinkedHashSet<>();
	private final Map<UUID, PlayerInfo> knownPlayers = new HashMap<>();
	private boolean running;
	
	public PlayerRangeAlertManager(EventManager events)
	{
		this.events = events;
	}
	
	public synchronized void addListener(Listener listener)
	{
		if(listeners.add(listener) && !running)
		{
			events.add(UpdateListener.class, this);
			running = true;
		}
	}
	
	public synchronized void removeListener(Listener listener)
	{
		if(!listeners.remove(listener) || !listeners.isEmpty())
			return;
		
		stop();
	}
	
	private synchronized void stop()
	{
		if(!running)
			return;
		
		events.remove(UpdateListener.class, this);
		running = false;
		knownPlayers.clear();
	}
	
	@Override
	public void onUpdate()
	{
		if(!running)
			return;
		
		if(mc.player == null || mc.level == null)
		{
			flushAll();
			return;
		}
		
		HashSet<UUID> seen = new HashSet<>();
		for(Player player : mc.level.players())
		{
			if(player == mc.player || player instanceof FakePlayerEntity)
				continue;
			
			UUID id = player.getUUID();
			seen.add(id);
			
			PlayerInfo info = knownPlayers.get(id);
			boolean npc = isProbablyNpc(id);
			Vec3 pos = new Vec3(player.getX(), player.getY(), player.getZ());
			String name = player.getName().getString();
			
			if(info == null)
			{
				info = new PlayerInfo(id, name, pos, npc);
				knownPlayers.put(id, info);
				notifyEnter(player, info);
			}else
			{
				info.update(name, pos, npc);
			}
		}
		
		if(knownPlayers.isEmpty())
			return;
		
		Set<UUID> toRemove = new HashSet<>();
		for(Map.Entry<UUID, PlayerInfo> entry : knownPlayers.entrySet())
			if(!seen.contains(entry.getKey()))
			{
				notifyExit(entry.getValue());
				toRemove.add(entry.getKey());
			}
		
		for(UUID id : toRemove)
			knownPlayers.remove(id);
	}
	
	private boolean isProbablyNpc(UUID id)
	{
		ClientPacketListener handler = mc.getConnection();
		return handler != null && handler.getPlayerInfo(id) == null;
	}
	
	private void flushAll()
	{
		if(knownPlayers.isEmpty())
			return;
		
		knownPlayers.values().forEach(this::notifyExit);
		knownPlayers.clear();
	}
	
	private void notifyEnter(Player player, PlayerInfo info)
	{
		for(Listener listener : snapshotListeners())
			listener.onPlayerEnter(player, info);
	}
	
	private void notifyExit(PlayerInfo info)
	{
		for(Listener listener : snapshotListeners())
			listener.onPlayerExit(info);
	}
	
	private Listener[] snapshotListeners()
	{
		synchronized(this)
		{
			if(listeners.isEmpty())
				return new Listener[0];
			
			return listeners.toArray(new Listener[0]);
		}
	}
	
	public interface Listener
	{
		void onPlayerEnter(Player player, PlayerInfo info);
		
		void onPlayerExit(PlayerInfo info);
	}
	
	public static final class PlayerInfo
	{
		private final UUID uuid;
		private String name;
		private Vec3 lastPos;
		private boolean probablyNpc;
		
		private PlayerInfo(UUID uuid, String name, Vec3 lastPos,
			boolean probablyNpc)
		{
			this.uuid = uuid;
			this.name = name;
			this.lastPos = lastPos;
			this.probablyNpc = probablyNpc;
		}
		
		private void update(String name, Vec3 lastPos, boolean probablyNpc)
		{
			this.name = name;
			this.lastPos = lastPos;
			this.probablyNpc = probablyNpc;
		}
		
		public UUID getUuid()
		{
			return uuid;
		}
		
		public String getName()
		{
			return name;
		}
		
		public Vec3 getLastPos()
		{
			return lastPos;
		}
		
		public boolean isProbablyNpc()
		{
			return probablyNpc;
		}
	}
}
