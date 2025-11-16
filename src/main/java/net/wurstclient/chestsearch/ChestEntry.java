/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.chestsearch;

import com.google.gson.JsonElement;
import java.time.Instant;
import java.util.List;
import net.minecraft.core.BlockPos;

public class ChestEntry
{
	public String serverIp;
	public String dimension; // e.g. "overworld", "the_nether"
	public int x, y, z;
	public int maxX, maxY, maxZ;
	public String facing; // e.g. "north", "south"
	public String lastSeen; // ISO-8601 timestamp
	public List<ItemEntry> items;
	// Exact block the player clicked when recording (may differ from
	// canonical primary coords used for dedupe). Nullable.
	public Integer clickedX;
	public Integer clickedY;
	public Integer clickedZ;
	
	public static class ItemEntry
	{
		public int slot;
		public int count;
		public String itemId;
		public String displayName;
		/** Full ItemStack NBT if configured; stored as a JsonObject */
		public JsonElement nbt;
		/**
		 * Optional list of enchantment ids/paths (e.g. "sharpness") extracted
		 * when recording.
		 */
		public java.util.List<String> enchantments;
		/**
		 * Optional parallel list of enchantment levels matching the
		 * enchantments
		 * list. Same length as enchantments when present.
		 */
		public java.util.List<Integer> enchantmentLevels;
		/**
		 * Optional list of potion/effect ids/paths (e.g. "speed") extracted
		 * when recording.
		 */
		public java.util.List<String> potionEffects;
		/** Optional primary potion/effect name for quick categorization. */
		public String primaryPotion;
	}
	
	public ChestEntry()
	{}
	
	public ChestEntry(String serverIp, String dimension, int x, int y, int z,
		List<ItemEntry> items)
	{
		this(serverIp, dimension, x, y, z, items, x, y, z, null);
	}
	
	public ChestEntry(String serverIp, String dimension, int x, int y, int z,
		List<ItemEntry> items, int maxX, int maxY, int maxZ, String facing)
	{
		this.serverIp = serverIp;
		this.dimension = dimension;
		this.x = x;
		this.y = y;
		this.z = z;
		this.maxX = maxX;
		this.maxY = maxY;
		this.maxZ = maxZ;
		this.facing = facing;
		this.items = items;
		this.lastSeen = Instant.now().toString();
	}
	
	public ChestEntry(String serverIp, String dimension, int x, int y, int z,
		List<ItemEntry> items, int maxX, int maxY, int maxZ, String facing,
		Integer clickedX, Integer clickedY, Integer clickedZ)
	{
		this(serverIp, dimension, x, y, z, items, maxX, maxY, maxZ, facing);
		this.clickedX = clickedX;
		this.clickedY = clickedY;
		this.clickedZ = clickedZ;
	}
	
	public void ensureBounds()
	{
		if(maxX == 0 && maxY == 0 && maxZ == 0 && (x != 0 || y != 0 || z != 0))
		{
			maxX = x;
			maxY = y;
			maxZ = z;
		}else
		{
			if(maxX < x)
				maxX = x;
			if(maxY < y)
				maxY = y;
			if(maxZ < z)
				maxZ = z;
		}
	}
	
	public void touch()
	{
		this.lastSeen = Instant.now().toString();
	}
	
	public BlockPos getMinPos()
	{
		int minX = Math.min(this.x, this.maxX);
		int minY = Math.min(this.y, this.maxY);
		int minZ = Math.min(this.z, this.maxZ);
		return new BlockPos(minX, minY, minZ);
	}
	
	public BlockPos getMaxPos()
	{
		int maxX = Math.max(this.x, this.maxX);
		int maxY = Math.max(this.y, this.maxY);
		int maxZ = Math.max(this.z, this.maxZ);
		return new BlockPos(maxX, maxY, maxZ);
	}
	
	/**
	 * Return the exact clicked position if available, otherwise fall back
	 * to the canonical min pos.
	 */
	public BlockPos getClickedPos()
	{
		if(clickedX != null && clickedY != null && clickedZ != null)
			return new BlockPos(clickedX, clickedY, clickedZ);
		return getMinPos();
	}
}
