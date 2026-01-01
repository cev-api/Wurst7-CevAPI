/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.chestsearch;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.wurstclient.WurstClient;
import net.wurstclient.chestsearch.ChestEntry.ItemEntry;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ChestManager provides methods to record chests and query the database.
 */
public class ChestManager
{
	private static final Map<String, ChestDatabase> DB_CACHE =
		new ConcurrentHashMap<>();
	
	private final File baseDir;
	private final ChestConfig config;
	
	public ChestManager(File storageFile, ChestConfig config)
	{
		File target =
			storageFile == null ? ChestDatabase.defaultFile() : storageFile;
		File absolute = target.getAbsoluteFile();
		if(absolute.isDirectory())
		{
			this.baseDir = absolute;
		}else
		{
			String name = absolute.getName();
			if(name.endsWith(".json"))
				name = name.substring(0, name.length() - 5);
			File parent = absolute.getParentFile();
			if(parent == null)
				parent = new File(".");
			this.baseDir = new File(parent, name);
		}
		if(!this.baseDir.exists())
			this.baseDir.mkdirs();
		this.config = config;
	}
	
	public ChestManager()
	{
		this(new ChestConfig());
	}
	
	public ChestManager(ChestConfig config)
	{
		this(new File(config.dbPath), config);
	}
	
	public void upsertChest(String serverIp, String dimension, int x, int y,
		int z, List<ItemEntry> items)
	{
		upsertChest(serverIp, dimension, x, y, z, items, x, y, z, null,
			Integer.valueOf(x), Integer.valueOf(y), Integer.valueOf(z));
	}
	
	public void upsertChest(String serverIp, String dimension, int x, int y,
		int z, List<ItemEntry> items, int maxX, int maxY, int maxZ,
		String facing)
	{
		upsertChest(serverIp, dimension, x, y, z, items, maxX, maxY, maxZ,
			facing, Integer.valueOf(x), Integer.valueOf(y), Integer.valueOf(z));
	}
	
	public void upsertChest(String serverIp, String dimension, int x, int y,
		int z, List<ItemEntry> items, int maxX, int maxY, int maxZ,
		String facing, Integer clickedX, Integer clickedY, Integer clickedZ)
	{
		if(!config.enabled)
			return;
		ChestEntry entry = new ChestEntry(serverIp, dimension, x, y, z, items,
			maxX, maxY, maxZ, facing, clickedX, clickedY, clickedZ);
		getDb(serverIp).upsert(entry);
	}
	
	public void removeChest(String serverIp, String dimension, int x, int y,
		int z)
	{
		getDb(serverIp).removeAt(serverIp, dimension, x, y, z);
	}
	
	public List<ChestEntry> search(String q)
	{
		return getCurrentDb().search(q == null ? "" : q);
	}
	
	public List<ChestEntry> all()
	{
		return getCurrentDb().all();
	}
	
	public ChestConfig getConfig()
	{
		return this.config;
	}
	
	// Helper: parse a string containing NBT JSON into JsonElement; caller may
	// obtain full ItemStack NBT
	public static JsonElement parseNbtJson(String nbtJson)
	{
		if(nbtJson == null)
			return null;
		try
		{
			return JsonParser.parseString(nbtJson);
		}catch(Exception e)
		{
			return null;
		}
	}
	
	// Helper to construct ItemEntry programmatically
	public static ItemEntry makeItemEntry(int slot, int count, String itemId,
		String displayName, JsonElement nbt)
	{
		ItemEntry it = new ItemEntry();
		it.slot = slot;
		it.count = count;
		it.itemId = itemId;
		it.displayName = displayName;
		it.nbt = nbt;
		return it;
	}
	
	private ChestDatabase getCurrentDb()
	{
		String serverIp = null;
		try
		{
			if(WurstClient.MC != null
				&& WurstClient.MC.getCurrentServer() != null)
				serverIp = WurstClient.MC.getCurrentServer().ip;
		}catch(Throwable ignored)
		{}
		return getDb(serverIp);
	}
	
	private ChestDatabase getDb(String serverIp)
	{
		File file = resolveFile(serverIp);
		String key = file.getAbsolutePath();
		return DB_CACHE.computeIfAbsent(key, k -> new ChestDatabase(file));
	}
	
	private File resolveFile(String serverIp)
	{
		String name = sanitizeServer(serverIp);
		return new File(baseDir, name + ".json");
	}
	
	private String sanitizeServer(String serverIp)
	{
		if(serverIp == null || serverIp.isBlank())
			return "singleplayer";
		return serverIp.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]",
			"_");
	}
}
