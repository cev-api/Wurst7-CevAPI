/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import net.wurstclient.WurstClient;

final class RespawnBedStore
{
	private static final TypeToken<Map<String, Bed>> TYPE = new TypeToken<>()
	{};
	private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
	private final Map<String, Bed> beds = new LinkedHashMap<>();
	private boolean loaded;
	
	synchronized Bed get(String server)
	{
		load();
		return beds.get(server);
	}
	
	synchronized void put(String server, String dimension, int x, int y, int z)
	{
		load();
		Bed bed = beds.get(server);
		if(bed != null && bed.dimension.equals(dimension) && bed.x == x
			&& bed.y == y && bed.z == z)
			return;
		beds.put(server, new Bed(dimension, x, y, z));
		save();
	}
	
	synchronized void remove(String server)
	{
		load();
		if(beds.remove(server) != null)
			save();
	}
	
	private void load()
	{
		if(loaded)
			return;
		loaded = true;
		File file = file();
		if(!file.exists())
			return;
		try(FileReader reader = new FileReader(file))
		{
			Map<String, Bed> read = gson.fromJson(reader, TYPE.getType());
			if(read != null)
				beds.putAll(read);
		}catch(Exception e)
		{
			System.err.println("Could not load respawn bed memory: " + e);
		}
	}
	
	private void save()
	{
		File file = file();
		try
		{
			file.getParentFile().mkdirs();
			try(FileWriter writer = new FileWriter(file))
			{
				gson.toJson(beds, TYPE.getType(), writer);
			}
		}catch(Exception e)
		{
			System.err.println("Could not save respawn bed memory: " + e);
		}
	}
	
	private static File file()
	{
		File root = WurstClient.MC != null ? WurstClient.MC.gameDirectory
			: new File(".");
		return new File(root, "config/wurst/respawn_beds.json");
	}
	
	static final class Bed
	{
		String dimension;
		int x, y, z;
		
		Bed()
		{}
		
		Bed(String dimension, int x, int y, int z)
		{
			this.dimension = dimension;
			this.x = x;
			this.y = y;
			this.z = z;
		}
	}
}
