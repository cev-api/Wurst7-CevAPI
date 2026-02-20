/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.lootsearch;

import net.wurstclient.chestsearch.ChestEntry;
import net.wurstclient.chestsearch.ChestManager;
import net.wurstclient.WurstClient;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class LootChestManager extends ChestManager
{
	private final List<ChestEntry> entries = new ArrayList<>();
	private final boolean mixedDimensionFile;
	
	public LootChestManager(File lootFile, String serverIp)
	{
		super();
		mixedDimensionFile = LootSearchUtil.isMixedDimensionFile(lootFile);
		if(lootFile != null && lootFile.exists())
		{
			entries.addAll(LootSearchUtil.parseLootFile(lootFile, serverIp));
		}
	}
	
	@Override
	public java.util.List<ChestEntry> search(String q)
	{
		if(q == null || q.isBlank())
			return new ArrayList<>(entries);
		String qq = q.toLowerCase(Locale.ROOT);
		List<ChestEntry> out = new ArrayList<>();
		for(ChestEntry e : entries)
		{
			boolean matched = false;
			if(e.serverIp != null
				&& e.serverIp.toLowerCase(Locale.ROOT).contains(qq))
				matched = true;
			if(e.dimension != null
				&& e.dimension.toLowerCase(Locale.ROOT).contains(qq))
				matched = true;
			if(!matched && e.items != null)
			{
				for(ChestEntry.ItemEntry it : e.items)
				{
					if(it == null)
						continue;
					if(it.itemId != null
						&& it.itemId.toLowerCase(Locale.ROOT).contains(qq))
					{
						matched = true;
						break;
					}
					if(it.displayName != null
						&& it.displayName.toLowerCase(Locale.ROOT).contains(qq))
					{
						matched = true;
						break;
					}
					if(it.nbt != null && it.nbt.toString()
						.toLowerCase(Locale.ROOT).contains(qq))
					{
						matched = true;
						break;
					}
					if(it.enchantments != null)
					{
						for(String en : it.enchantments)
						{
							if(en != null
								&& en.toLowerCase(Locale.ROOT).contains(qq))
							{
								matched = true;
								break;
							}
						}
						if(matched)
							break;
					}
					if(it.potionEffects != null)
					{
						for(String pe : it.potionEffects)
						{
							if(pe != null
								&& pe.toLowerCase(Locale.ROOT).contains(qq))
							{
								matched = true;
								break;
							}
						}
						if(matched)
							break;
					}
					if(it.primaryPotion != null && it.primaryPotion
						.toLowerCase(Locale.ROOT).contains(qq))
					{
						matched = true;
						break;
					}
				}
			}
			if(matched)
				out.add(e);
		}
		return filterForCurrentDimension(out);
	}
	
	@Override
	public java.util.List<ChestEntry> all()
	{
		return filterForCurrentDimension(new ArrayList<>(entries));
	}
	
	@Override
	public void removeChest(String serverIp, String dimension, int x, int y,
		int z)
	{
		entries.removeIf(e -> e.x == x && e.y == y && e.z == z);
	}
	
	private List<ChestEntry> filterForCurrentDimension(List<ChestEntry> input)
	{
		if(!mixedDimensionFile || input == null || input.isEmpty())
			return input;
		
		String playerDim = getPlayerDimensionKey();
		if(playerDim.isEmpty())
			return input;
		
		List<ChestEntry> filtered = new ArrayList<>(input.size());
		for(ChestEntry entry : input)
		{
			if(entry == null)
				continue;
			String entryDim = canonicalDimension(entry.dimension);
			if(entryDim.isEmpty() || entryDim.equals("mixed")
				|| entryDim.equals(playerDim))
			{
				filtered.add(entry);
			}
		}
		return filtered;
	}
	
	private static String getPlayerDimensionKey()
	{
		try
		{
			if(WurstClient.MC == null || WurstClient.MC.level == null)
				return "";
			String full =
				WurstClient.MC.level.dimension().identifier().toString();
			return canonicalDimension(full);
		}catch(Throwable ignored)
		{
			return "";
		}
	}
	
	private static String canonicalDimension(String dimension)
	{
		if(dimension == null || dimension.isBlank())
			return "";
		String lower = dimension.trim().toLowerCase(Locale.ROOT);
		int colon = lower.indexOf(':');
		if(colon >= 0 && colon < lower.length() - 1)
			return lower.substring(colon + 1);
		return lower;
	}
}
