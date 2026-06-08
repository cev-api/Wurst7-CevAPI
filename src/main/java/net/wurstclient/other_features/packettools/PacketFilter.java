/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features.packettools;

import java.util.UUID;
import java.util.regex.Pattern;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.world.phys.Vec3;

import net.wurstclient.WurstClient;

/**
 * Packet filters for verbose logging. Supports filtering by:
 * <ul>
 * <li>Packet class name (regex)</li>
 * <li>Entity ID</li>
 * <li>Entity UUID</li>
 * <li>Chunk coordinates</li>
 * <li>Position radius around the local player</li>
 * <li>Registry type (e.g. "entity:", "item:", "block:")</li>
 * <li>Text query in packet fields</li>
 * </ul>
 * <p>
 * Each filter can be enabled/disabled independently. When all filters are
 * disabled, everything passes.
 */
public final class PacketFilter
{
	private boolean enabled;
	
	private String classRegex;
	private Pattern classPattern;
	
	private boolean filterByEntityId;
	private int targetEntityId;
	
	private boolean filterByEntityUuid;
	private UUID targetEntityUuid;
	
	private boolean filterByChunk;
	private int chunkX, chunkZ;
	
	private boolean filterByRadius;
	private double radius;
	private Vec3 radiusCenter;
	
	private boolean filterByRegistryType;
	private String registryTypePrefix; // e.g. "entity:", "item:", "block:"
	
	private boolean filterByText;
	private String textQuery; // case-insensitive substring search
	
	private boolean invert; // when true, exclude matching packets
	
	public PacketFilter()
	{}
	
	// ---- Setters ----
	
	public PacketFilter setEnabled(boolean enabled)
	{
		this.enabled = enabled;
		return this;
	}
	
	public PacketFilter setClassRegex(String regex)
	{
		this.classRegex = regex;
		this.classPattern = regex != null && !regex.isEmpty()
			? Pattern.compile(regex, Pattern.CASE_INSENSITIVE) : null;
		return this;
	}
	
	public PacketFilter setEntityId(int entityId)
	{
		this.filterByEntityId = true;
		this.targetEntityId = entityId;
		return this;
	}
	
	public PacketFilter clearEntityId()
	{
		this.filterByEntityId = false;
		return this;
	}
	
	public PacketFilter setEntityUuid(UUID uuid)
	{
		this.filterByEntityUuid = true;
		this.targetEntityUuid = uuid;
		return this;
	}
	
	public PacketFilter clearEntityUuid()
	{
		this.filterByEntityUuid = false;
		return this;
	}
	
	public PacketFilter setChunk(int x, int z)
	{
		this.filterByChunk = true;
		this.chunkX = x;
		this.chunkZ = z;
		return this;
	}
	
	public PacketFilter clearChunk()
	{
		this.filterByChunk = false;
		return this;
	}
	
	public PacketFilter setRadius(double radius, Vec3 center)
	{
		this.filterByRadius = true;
		this.radius = radius;
		this.radiusCenter = center;
		return this;
	}
	
	public PacketFilter clearRadius()
	{
		this.filterByRadius = false;
		return this;
	}
	
	public PacketFilter setRegistryType(String prefix)
	{
		this.filterByRegistryType = true;
		this.registryTypePrefix = prefix;
		return this;
	}
	
	public PacketFilter clearRegistryType()
	{
		this.filterByRegistryType = false;
		return this;
	}
	
	public PacketFilter setTextQuery(String query)
	{
		this.filterByText = true;
		this.textQuery = query;
		return this;
	}
	
	public PacketFilter clearTextQuery()
	{
		this.filterByText = false;
		return this;
	}
	
	public PacketFilter setInvert(boolean invert)
	{
		this.invert = invert;
		return this;
	}
	
	// ---- Matching ----
	
	public boolean matches(Packet<?> packet)
	{
		if(!enabled)
			return true;
		
		boolean anyFilterActive = classPattern != null || filterByEntityId
			|| filterByEntityUuid || filterByChunk || filterByRadius
			|| filterByRegistryType || filterByText;
		
		if(!anyFilterActive)
			return true;
		
		boolean matched = false;
		
		// Class name regex
		if(classPattern != null)
		{
			String name = packet.getClass().getSimpleName();
			if(classPattern.matcher(name).find())
				matched = true;
		}
		
		// Entity ID
		if(filterByEntityId && !matched)
		{
			if(packet instanceof ClientboundAddEntityPacket add)
			{
				if(add.getId() == targetEntityId)
					matched = true;
			}
		}
		
		// Entity UUID
		if(filterByEntityUuid && !matched)
		{
			if(packet instanceof ClientboundAddEntityPacket add)
			{
				if(targetEntityUuid.equals(add.getUUID()))
					matched = true;
			}
		}
		
		// Chunk filter
		if(filterByChunk && !matched && MC().level != null)
		{
			if(packet instanceof ClientboundAddEntityPacket add)
			{
				int pcx = (int)Math.floor(add.getX()) >> 4;
				int pcz = (int)Math.floor(add.getZ()) >> 4;
				if(pcx == chunkX && pcz == chunkZ)
					matched = true;
			}
		}
		
		// Radius filter
		if(filterByRadius && !matched)
		{
			if(packet instanceof ClientboundAddEntityPacket add)
			{
				Vec3 pos = new Vec3(add.getX(), add.getY(), add.getZ());
				if(radiusCenter != null
					&& pos.distanceTo(radiusCenter) <= radius)
					matched = true;
			}
		}
		
		// Registry type and text filters are applied post-dump in PacketDumper
		// For pre-filter we fall back to the class name check
		if(filterByRegistryType && !matched)
		{
			String className = packet.getClass().getSimpleName();
			if(className.toLowerCase()
				.contains(registryTypePrefix.toLowerCase()))
				matched = true;
		}
		
		if(filterByText && !matched)
		{
			String data = String.valueOf(packet);
			if(data.toLowerCase().contains(textQuery.toLowerCase()))
				matched = true;
		}
		
		return invert ? !matched : matched;
	}
	
	/**
	 * Check whether a JSONL line (post-dump) matches text/registry filters.
	 * Called by PacketDumper for fine-grained filtering.
	 */
	public boolean matchesJsonLine(String jsonLine)
	{
		if(!enabled)
			return true;
		
		boolean matched = false;
		
		if(filterByRegistryType && registryTypePrefix != null)
		{
			if(jsonLine.toLowerCase()
				.contains(registryTypePrefix.toLowerCase()))
				matched = true;
		}
		
		if(filterByText && textQuery != null)
		{
			if(jsonLine.toLowerCase().contains(textQuery.toLowerCase()))
				matched = true;
		}
		
		// If neither registry nor text filters are active but other filters
		// are, we already filtered at the packet level.
		boolean onlyPostFilters = filterByRegistryType || filterByText;
		if(!onlyPostFilters)
			return true;
		
		return invert ? !matched : matched;
	}
	
	public boolean isActive()
	{
		return enabled && (classPattern != null || filterByEntityId
			|| filterByEntityUuid || filterByChunk || filterByRadius
			|| filterByRegistryType || filterByText);
	}
	
	public String describe()
	{
		if(!enabled)
			return "Filters: disabled";
		StringBuilder sb = new StringBuilder("Filters: ");
		if(classPattern != null)
			sb.append("class~").append(classRegex).append(" ");
		if(filterByEntityId)
			sb.append("entityId=").append(targetEntityId).append(" ");
		if(filterByEntityUuid)
			sb.append("uuid=").append(targetEntityUuid).append(" ");
		if(filterByChunk)
			sb.append("chunk=").append(chunkX).append(",").append(chunkZ)
				.append(" ");
		if(filterByRadius)
			sb.append("radius=").append(String.format("%.1f", radius))
				.append(" ");
		if(filterByRegistryType)
			sb.append("registry=").append(registryTypePrefix).append(" ");
		if(filterByText)
			sb.append("text=").append(textQuery).append(" ");
		if(invert)
			sb.append("(inverted)");
		return sb.toString().trim();
	}
	
	private static net.minecraft.client.Minecraft MC()
	{
		return WurstClient.MC;
	}
}
