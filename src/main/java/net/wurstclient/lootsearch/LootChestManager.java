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

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class LootChestManager extends ChestManager
{
	private final List<ChestEntry> entries = new ArrayList<>();
	
	public LootChestManager(File lootFile, String serverIp)
	{
		super();
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
		String qq = q.toLowerCase();
		List<ChestEntry> out = new ArrayList<>();
		for(ChestEntry e : entries)
		{
			boolean matched = false;
			if(e.dimension != null && e.dimension.toLowerCase().contains(qq))
				matched = true;
			if(!matched && e.items != null)
			{
				for(ChestEntry.ItemEntry it : e.items)
				{
					if(it == null)
						continue;
					if(it.itemId != null
						&& it.itemId.toLowerCase().contains(qq))
					{
						matched = true;
						break;
					}
				}
			}
			if(matched)
				out.add(e);
		}
		return out;
	}
	
	@Override
	public java.util.List<ChestEntry> all()
	{
		return new ArrayList<>(entries);
	}
	
	@Override
	public void removeChest(String serverIp, String dimension, int x, int y,
		int z)
	{
		entries.removeIf(e -> e.x == x && e.y == y && e.z == z);
	}
}
