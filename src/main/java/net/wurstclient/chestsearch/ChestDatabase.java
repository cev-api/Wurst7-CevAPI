/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.chestsearch;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

public class ChestDatabase
{
	private final File file;
	private final Gson gson;
	private List<ChestEntry> entries;
	
	public ChestDatabase(File file)
	{
		this.file = file;
		this.gson = new GsonBuilder().setPrettyPrinting().create();
		this.entries = new ArrayList<>();
		load();
	}
	
	private void load()
	{
		try
		{
			if(!file.exists())
			{
				file.getParentFile().mkdirs();
				file.createNewFile();
				this.entries = new ArrayList<>();
				save();
				return;
			}
			Type t = new TypeToken<List<ChestEntry>>()
			{}.getType();
			try(FileReader r = new FileReader(file))
			{
				List<ChestEntry> read = gson.fromJson(r, t);
				if(read != null)
				{
					for(ChestEntry entry : read)
					{
						if(entry != null)
							entry.ensureBounds();
					}
					this.entries = read;
					if(dedupeLoadedEntries())
						save();
				}
			}
		}catch(Exception e)
		{
			e.printStackTrace();
			this.entries = new ArrayList<>();
		}
	}
	
	public synchronized void save()
	{
		try(FileWriter w = new FileWriter(file))
		{
			gson.toJson(entries, w);
			System.out.println("[ChestDatabase] saved " + entries.size()
				+ " entries to " + file.getAbsolutePath());
		}catch(Exception e)
		{
			e.printStackTrace();
		}
	}
	
	public synchronized void upsert(ChestEntry entry)
	{
		if(entry != null)
			entry.ensureBounds();
		// Try to find an existing entry with the same contents (best match)
		try
		{
			String eServer = entry.serverIp;
			String eDim = entry.dimension;
			String entryKey = contentsKey(entry);
			if(entryKey != null)
			{
				for(ChestEntry e : entries)
				{
					if(!equalsServerDim(e, eServer, eDim))
						continue;
					String k = contentsKey(e);
					if(k != null && k.equals(entryKey))
					{
						// Only merge by contents if the recorded bounds match
						// the new entry.
						// This prevents different chests that happen to contain
						// identical
						// items (e.g. two single-chest halves of a double chest
						// or
						// two separate chests) from being merged together.
						if(!equalsPos(e, entry))
							continue;
						// merge: preserve original primary coords
						entry.x = e.x;
						entry.y = e.y;
						entry.z = e.z;
						// preserve original clicked coords as well
						entry.clickedX = e.clickedX;
						entry.clickedY = e.clickedY;
						entry.clickedZ = e.clickedZ;
						// remove existing and replace
						entries.remove(e);
						entry.touch();
						entries.add(entry);
						finalizeInsert(entry);
						System.out.println(
							"[ChestDatabase] merged entry by contents; preserved primary="
								+ entry.x + "," + entry.y + "," + entry.z);
						return;
					}
				}
			}
		}catch(Throwable ignored)
		{}
		Iterator<ChestEntry> it = entries.iterator();
		while(it.hasNext())
		{
			ChestEntry e = it.next();
			if(equalsPos(e, entry) && equalsServerDim(e, entry))
			{
				// preserve the original primary recorded position (first seen)
				entry.x = e.x;
				entry.y = e.y;
				entry.z = e.z;
				entry.clickedX = e.clickedX;
				entry.clickedY = e.clickedY;
				entry.clickedZ = e.clickedZ;
				entry.touch();
				it.remove();
				entries.add(entry);
				finalizeInsert(entry);
				System.out.println("[ChestDatabase] updated entry at bounds "
					+ entry.getMinPos() + " -> " + entry.getMaxPos()
					+ " facing=" + entry.facing + " (preserved primary="
					+ entry.x + "," + entry.y + "," + entry.z + ")");
				return;
			}
		}
		entry.touch();
		entries.add(entry);
		finalizeInsert(entry);
		System.out.println(
			"[ChestDatabase] added entry at bounds " + entry.getMinPos()
				+ " -> " + entry.getMaxPos() + " facing=" + entry.facing);
	}
	
	public synchronized void removeAt(String serverIp, String dimension, int x,
		int y, int z)
	{
		entries = entries
			.stream().filter(e -> !(equalsServerDim(e, serverIp, dimension)
				&& e.x == x && e.y == y && e.z == z))
			.collect(Collectors.toList());
		save();
		System.out.println(
			"[ChestDatabase] removed entry at " + x + "," + y + "," + z);
	}
	
	public synchronized List<ChestEntry> search(String query)
	{
		String q = query.toLowerCase();
		List<ChestEntry> res = new ArrayList<>();
		for(ChestEntry e : entries)
		{
			boolean matched = false;
			if(e.serverIp != null && e.serverIp.toLowerCase().contains(q))
				matched = true;
			if(e.dimension != null && e.dimension.toLowerCase().contains(q))
				matched = true;
			for(ChestEntry.ItemEntry item : e.items)
			{
				if(item.itemId != null && item.itemId.toLowerCase().contains(q))
					matched = true;
				if(item.displayName != null
					&& item.displayName.toLowerCase().contains(q))
					matched = true;
				if(!matched && item.nbt != null
					&& item.nbt.toString().toLowerCase().contains(q))
					matched = true;
			}
			if(matched)
				res.add(e);
		}
		return res;
	}
	
	private boolean equalsPos(ChestEntry a, ChestEntry b)
	{
		if(a == null || b == null)
			return false;
		// Compare canonical bounds (min/max)
		int aMinX = Math.min(a.x, a.maxX);
		int aMinY = Math.min(a.y, a.maxY);
		int aMinZ = Math.min(a.z, a.maxZ);
		int aMaxX = Math.max(a.x, a.maxX);
		int aMaxY = Math.max(a.y, a.maxY);
		int aMaxZ = Math.max(a.z, a.maxZ);
		
		int bMinX = Math.min(b.x, b.maxX);
		int bMinY = Math.min(b.y, b.maxY);
		int bMinZ = Math.min(b.z, b.maxZ);
		int bMaxX = Math.max(b.x, b.maxX);
		int bMaxY = Math.max(b.y, b.maxY);
		int bMaxZ = Math.max(b.z, b.maxZ);
		
		boolean boundsEqual = aMinX == bMinX && aMinY == bMinY && aMinZ == bMinZ
			&& aMaxX == bMaxX && aMaxY == bMaxY && aMaxZ == bMaxZ;
		if(!boundsEqual)
			return false;
		return true;
	}
	
	private void finalizeInsert(ChestEntry entry)
	{
		boolean removed = removeOverlappingDuplicates(entry);
		if(removed)
			System.out.println(
				"[ChestDatabase] cleaned overlapping duplicates after insert.");
		save();
	}
	
	private boolean removeOverlappingDuplicates(ChestEntry reference)
	{
		String refKey = contentsKey(reference);
		if(refKey == null)
			return false;
		int refMinX = Math.min(reference.x, reference.maxX);
		int refMinY = Math.min(reference.y, reference.maxY);
		int refMinZ = Math.min(reference.z, reference.maxZ);
		int refMaxX = Math.max(reference.x, reference.maxX);
		int refMaxY = Math.max(reference.y, reference.maxY);
		int refMaxZ = Math.max(reference.z, reference.maxZ);
		boolean removed = false;
		java.util.Iterator<ChestEntry> it = entries.iterator();
		while(it.hasNext())
		{
			ChestEntry other = it.next();
			if(other == reference)
				continue;
			if(!equalsServerDim(other, reference))
				continue;
			String otherKey = contentsKey(other);
			if(otherKey == null || !refKey.equals(otherKey))
				continue;
			int otherMinX = Math.min(other.x, other.maxX);
			int otherMinY = Math.min(other.y, other.maxY);
			int otherMinZ = Math.min(other.z, other.maxZ);
			int otherMaxX = Math.max(other.x, other.maxX);
			int otherMaxY = Math.max(other.y, other.maxY);
			int otherMaxZ = Math.max(other.z, other.maxZ);
			boolean overlap = refMinX <= otherMaxX && refMaxX >= otherMinX
				&& refMinY <= otherMaxY && refMaxY >= otherMinY
				&& refMinZ <= otherMaxZ && refMaxZ >= otherMinZ;
			if(!overlap)
				continue;
			it.remove();
			System.out
				.println("[ChestDatabase] removed overlapping duplicate at "
					+ other.getMinPos() + " -> " + other.getMaxPos());
			removed = true;
		}
		return removed;
	}
	
	private boolean dedupeLoadedEntries()
	{
		if(entries == null || entries.isEmpty())
			return false;
		java.util.List<ChestEntry> copy = new ArrayList<>(entries);
		entries = new ArrayList<>();
		boolean removedAny = false;
		for(ChestEntry entry : copy)
		{
			if(entry == null)
				continue;
			entry.ensureBounds();
			entries.add(entry);
			if(removeOverlappingDuplicates(entry))
				removedAny = true;
		}
		return removedAny;
	}
	
	private boolean equalsServerDim(ChestEntry a, ChestEntry b)
	{
		return equalsServerDim(a, b.serverIp, b.dimension);
	}
	
	private boolean equalsServerDim(ChestEntry a, String serverIp,
		String dimension)
	{
		if(a.serverIp == null && serverIp != null)
			return false;
		if(a.dimension == null && dimension != null)
			return false;
		return (a.serverIp == null || a.serverIp.equals(serverIp))
			&& (a.dimension == null || a.dimension.equals(dimension));
	}
	
	public synchronized List<ChestEntry> all()
	{
		return new ArrayList<>(entries);
	}
	
	private String contentsKey(ChestEntry e)
	{
		if(e == null || e.items == null)
			return null;
		java.util.Map<String, Integer> map = new java.util.HashMap<>();
		try
		{
			for(ChestEntry.ItemEntry it : e.items)
			{
				if(it == null)
					continue;
				String id = it.itemId == null ? "" : it.itemId;
				String nbt = it.nbt == null ? "" : it.nbt.toString();
				String key = id + "|" + nbt;
				map.put(key, map.getOrDefault(key, 0) + it.count);
			}
			// build stable string
			java.util.List<String> parts = new java.util.ArrayList<>();
			for(java.util.Map.Entry<String, Integer> en : map.entrySet())
				parts.add(en.getKey() + ":" + en.getValue());
			java.util.Collections.sort(parts);
			return String.join(";", parts);
		}catch(Throwable t)
		{
			return null;
		}
	}
	
	public static File defaultFile()
	{
		// defaults to config/wurst/chest_database.json relative to project
		// working directory
		return Paths.get("config", "wurst", "chest_database.json").toFile();
	}
}
