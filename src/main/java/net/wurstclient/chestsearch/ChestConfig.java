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

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

public class ChestConfig
{
	public boolean enabled = true;
	public boolean storeFullItemNbt = true;
	public String dbPath = "config/wurst/chest_database.json";
	// grace period in ticks to wait after joining/changing dimension before
	// allowing automatic deletions (20 ticks = 1 second)
	public int graceTicks = 200; // ~10s
	// scan radius in blocks within which the player must be to validate
	// a recorded chest
	public int scanRadius = 64;
	
	private final transient File file;
	private final transient Gson gson =
		new GsonBuilder().setPrettyPrinting().create();
	
	public ChestConfig(File file)
	{
		this.file = file;
		load();
	}
	
	public ChestConfig()
	{
		this(new File("config/wurst/chest_search.json"));
	}
	
	private ChestConfigData toData()
	{
		ChestConfigData data = new ChestConfigData();
		data.enabled = enabled;
		data.storeFullItemNbt = storeFullItemNbt;
		data.dbPath = dbPath;
		data.graceTicks = graceTicks;
		data.scanRadius = scanRadius;
		return data;
	}
	
	private void applyData(ChestConfigData data)
	{
		if(data == null)
			return;
		
		enabled = data.enabled;
		storeFullItemNbt = data.storeFullItemNbt;
		if(data.dbPath != null && !data.dbPath.isBlank())
			dbPath = data.dbPath;
		graceTicks = data.graceTicks;
		scanRadius = data.scanRadius;
	}
	
	public synchronized void load()
	{
		try
		{
			if(!file.exists())
			{
				file.getParentFile().mkdirs();
				save();
				return;
			}
			try(FileReader r = new FileReader(file))
			{
				ChestConfigData c = gson.fromJson(r, ChestConfigData.class);
				applyData(c);
			}
		}catch(Exception e)
		{
			e.printStackTrace();
		}
	}
	
	public synchronized void save()
	{
		try(FileWriter w = new FileWriter(file))
		{
			gson.toJson(toData(), w);
		}catch(Exception e)
		{
			e.printStackTrace();
		}
	}
	
	private static class ChestConfigData
	{
		public boolean enabled = true;
		public boolean storeFullItemNbt = true;
		public String dbPath = "config/wurst/chest_database.json";
		public int graceTicks = 200;
		public int scanRadius = 64;
	}
}
