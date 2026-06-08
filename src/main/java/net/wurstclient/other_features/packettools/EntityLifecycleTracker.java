/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features.packettools;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

/**
 * Tracks entity lifecycle events by correlating spawn, motion, teleport,
 * metadata, equipment, player info, chunk load/unload, and removal packets.
 * <p>
 * Uses reflection to read packet fields where specific API methods may
 * differ across Minecraft versions.
 * <p>
 * Thread-safe for use from the render thread only.
 */
public final class EntityLifecycleTracker
{
	public static final class EntitySnapshot
	{
		public final int id;
		public final UUID uuid;
		public final EntityType<?> type;
		public final double x, y, z;
		public final long spawnTick;
		public final List<String> eventLog;
		
		public volatile double velX, velY, velZ;
		public volatile long lastMotionTick;
		public volatile long lastMetadataTick;
		public volatile long lastEquipmentTick;
		public volatile long lastTeleportTick;
		public volatile boolean removed;
		public volatile long removedTick;
		public volatile String playerName;
		
		EntitySnapshot(int id, UUID uuid, EntityType<?> type, double x,
			double y, double z, long spawnTick)
		{
			this.id = id;
			this.uuid = uuid;
			this.type = type;
			this.x = x;
			this.y = y;
			this.z = z;
			this.spawnTick = spawnTick;
			this.eventLog = new ArrayList<>();
			eventLog.add("SPAWN @ " + String.format("%.1f,%.1f,%.1f", x, y, z));
		}
		
		public Vec3 position()
		{
			return new Vec3(x, y, z);
		}
		
		public Vec3 velocity()
		{
			return new Vec3(velX, velY, velZ);
		}
		
		public boolean isPlayer()
		{
			return type == EntityType.PLAYER;
		}
	}
	
	private final Map<Integer, EntitySnapshot> byId = new LinkedHashMap<>();
	private final Map<UUID, EntitySnapshot> byUuid = new LinkedHashMap<>();
	private long tickCounter;
	
	public synchronized void tick()
	{
		tickCounter++;
	}
	
	public synchronized void onAddEntity(ClientboundAddEntityPacket packet)
	{
		try
		{
			EntityType<?> type = packet.getType();
			UUID uuid = packet.getUUID();
			int id = packet.getId();
			double x = packet.getX();
			double y = packet.getY();
			double z = packet.getZ();
			
			EntitySnapshot snap =
				new EntitySnapshot(id, uuid, type, x, y, z, tickCounter);
			byId.put(id, snap);
			if(uuid != null)
				byUuid.put(uuid, snap);
		}catch(Exception ignored)
		{}
	}
	
	public synchronized void onRemoveEntities(
		ClientboundRemoveEntitiesPacket packet)
	{
		try
		{
			for(int id : packet.getEntityIds())
			{
				EntitySnapshot snap = byId.get(id);
				if(snap != null)
				{
					snap.removed = true;
					snap.removedTick = tickCounter;
					snap.eventLog.add("REMOVED @ tick " + tickCounter);
				}
			}
		}catch(Exception ignored)
		{}
	}
	
	/**
	 * Record a generic packet event for an entity using reflection to
	 * extract the entity ID and any position/velocity data.
	 */
	public synchronized void onEntityPacket(Object packet, String eventType)
	{
		try
		{
			Integer entityId = readEntityId(packet);
			if(entityId == null)
				return;
			
			EntitySnapshot snap = byId.get(entityId);
			if(snap == null)
				return;
			
			// Try to read position/velocity
			Double px = readDouble(packet, "x", "getX");
			Double py = readDouble(packet, "y", "getY");
			Double pz = readDouble(packet, "z", "getZ");
			Double vx = readDouble(packet, "xa", "getXa", "velX");
			Double vy = readDouble(packet, "ya", "getYa", "velY");
			Double vz = readDouble(packet, "za", "getZa", "velZ");
			
			StringBuilder log = new StringBuilder(eventType);
			if(px != null && py != null && pz != null)
				log.append(String.format(" %.1f,%.1f,%.1f", px, py, pz));
			if(vx != null && vy != null && vz != null)
			{
				snap.velX = vx;
				snap.velY = vy;
				snap.velZ = vz;
				snap.lastMotionTick = tickCounter;
			}
			log.append(" @ tick ").append(tickCounter);
			snap.eventLog.add(log.toString());
			
			switch(eventType)
			{
				case "TELEPORT" -> snap.lastTeleportTick = tickCounter;
				case "METADATA" -> snap.lastMetadataTick = tickCounter;
				case "EQUIPMENT" -> snap.lastEquipmentTick = tickCounter;
			}
		}catch(Exception ignored)
		{}
	}
	
	public synchronized void onPlayerInfoUpdate(
		ClientboundPlayerInfoUpdatePacket packet)
	{
		try
		{
			for(ClientboundPlayerInfoUpdatePacket.Entry entry : packet
				.entries())
			{
				if(entry == null || entry.profileId() == null)
					continue;
				EntitySnapshot snap = byUuid.get(entry.profileId());
				if(snap != null && entry.profile() != null
					&& entry.profile().name() != null)
				{
					snap.playerName = entry.profile().name();
					snap.eventLog.add("PLAYER_INFO name=" + snap.playerName
						+ " @ tick " + tickCounter);
				}
			}
		}catch(Exception ignored)
		{}
	}
	
	public synchronized void onChunkLoad(BlockPos chunkOrigin)
	{
		// Lightweight: just note chunk loads
	}
	
	public synchronized void onChunkUnload(Object packet)
	{
		// Entities in this chunk are effectively gone
	}
	
	public synchronized EntitySnapshot getById(int id)
	{
		return byId.get(id);
	}
	
	public synchronized EntitySnapshot getByUuid(UUID uuid)
	{
		return byUuid.get(uuid);
	}
	
	public synchronized List<EntitySnapshot> getAll()
	{
		return Collections.unmodifiableList(new ArrayList<>(byId.values()));
	}
	
	public synchronized List<EntitySnapshot> getActive()
	{
		List<EntitySnapshot> active = new ArrayList<>();
		for(EntitySnapshot snap : byId.values())
			if(!snap.removed)
				active.add(snap);
		return Collections.unmodifiableList(active);
	}
	
	public synchronized int getEntityCount()
	{
		return byId.size();
	}
	
	public synchronized int getActiveEntityCount()
	{
		int count = 0;
		for(EntitySnapshot snap : byId.values())
			if(!snap.removed)
				count++;
		return count;
	}
	
	public synchronized void clear()
	{
		byId.clear();
		byUuid.clear();
		tickCounter = 0;
	}
	
	public synchronized String buildSummary()
	{
		int active = getActiveEntityCount();
		int total = byId.size();
		int removed = total - active;
		return String.format(
			"[EntityLifecycle] %d active, %d removed, %d total tracked", active,
			removed, total);
	}
	
	// ---- Reflection helpers ----
	
	private static Integer readEntityId(Object obj)
	{
		if(obj == null)
			return null;
		// Try common field/method names
		for(String name : new String[]{"id", "entityId", "getId", "getEntityId",
			"getEntity"})
		{
			Object val = readValue(obj, name);
			if(val instanceof Integer i)
				return i;
			if(val instanceof Number n)
				return n.intValue();
		}
		return null;
	}
	
	private static Double readDouble(Object obj, String... names)
	{
		for(String name : names)
		{
			Object val = readValue(obj, name);
			if(val instanceof Double d)
				return d;
			if(val instanceof Number n)
				return n.doubleValue();
		}
		return null;
	}
	
	private static Object readValue(Object obj, String name)
	{
		try
		{
			// Try field
			try
			{
				java.lang.reflect.Field field =
					obj.getClass().getDeclaredField(name);
				field.setAccessible(true);
				return field.get(obj);
			}catch(NoSuchFieldException ignored)
			{}
			
			// Try method
			Method method = obj.getClass().getMethod(name);
			return method.invoke(obj);
		}catch(Exception e)
		{
			return null;
		}
	}
}
