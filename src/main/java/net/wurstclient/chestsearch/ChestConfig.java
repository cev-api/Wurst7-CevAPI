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
	
	private final File file;
	private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
	
	public ChestConfig(File file)
	{
		this.file = file;
		load();
	}
	
	public ChestConfig()
	{
		this(new File("config/wurst/chest_search.json"));
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
				ChestConfig c = gson.fromJson(r, ChestConfig.class);
				if(c != null)
				{
					this.enabled = c.enabled;
					this.storeFullItemNbt = c.storeFullItemNbt;
					this.dbPath = c.dbPath;
				}
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
			gson.toJson(this, w);
		}catch(Exception e)
		{
			e.printStackTrace();
		}
	}
}
