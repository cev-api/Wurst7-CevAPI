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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.wurstclient.WurstClient;

final class SignHistory
{
	private static final TypeToken<Map<String, Map<String, Map<String, SignRecord>>>> TYPE =
		new TypeToken<>()
		{};
	private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
	private final Map<String, Map<String, Map<String, SignRecord>>> data =
		new LinkedHashMap<>();
	private final ExecutorService writer =
		Executors.newSingleThreadExecutor(r -> {
			Thread thread = new Thread(r, "Wurst-SignHistory-Writer");
			thread.setDaemon(true);
			return thread;
		});
	private boolean loaded;
	private boolean saveQueued;
	private boolean dirty;
	
	synchronized void record(String server, String dimension, BlockPos pos,
		SignBlockEntity sign)
	{
		ensureLoaded();
		SignRecord record = getRecord(server, dimension, pos, true);
		Entry last = record.entries.isEmpty() ? null
			: record.entries.get(record.entries.size() - 1);
		Entry entry =
			Entry.present(sign, last != null && "present".equals(last.state));
		if(record.entries.isEmpty() || !last.sameText(entry))
		{
			record.entries.add(entry);
			markDirty();
		}
	}
	
	synchronized void recordRemoved(String server, String dimension,
		BlockPos pos)
	{
		ensureLoaded();
		SignRecord record = getRecord(server, dimension, pos, true);
		if(record.entries.isEmpty() || !"removed"
			.equals(record.entries.get(record.entries.size() - 1).state))
		{
			record.entries.add(Entry.removed());
			markDirty();
		}
	}
	
	synchronized SignRecord getRecord(String server, String dimension,
		BlockPos pos, boolean create)
	{
		ensureLoaded();
		Map<String, Map<String, SignRecord>> dimensions = data.get(server);
		if(dimensions == null && create)
			data.put(server, dimensions = new LinkedHashMap<>());
		if(dimensions == null)
			return null;
		Map<String, SignRecord> positions = dimensions.get(dimension);
		if(positions == null && create)
			dimensions.put(dimension, positions = new LinkedHashMap<>());
		if(positions == null)
			return null;
		String key = pos.getX() + "," + pos.getY() + "," + pos.getZ();
		SignRecord record = positions.get(key);
		if(record == null && create)
			positions.put(key, record = new SignRecord(dimension));
		if(record != null)
			record.key = key;
		return record;
	}
	
	private synchronized void markDirty()
	{
		dirty = true;
		if(saveQueued)
			return;
		saveQueued = true;
		writer.submit(this::saveAsync);
	}
	
	private void saveAsync()
	{
		try
		{
			Thread.sleep(1000);
			String json;
			synchronized(this)
			{
				if(!dirty)
					return;
				json = gson.toJson(data, TYPE.getType());
				dirty = false;
			}
			File file = file();
			File parent = file.getParentFile();
			if(parent != null)
				parent.mkdirs();
			try(FileWriter out = new FileWriter(file))
			{
				out.write(json);
			}
		}catch(Exception e)
		{
			System.err.println("Could not save sign history: " + e);
		}finally
		{
			synchronized(this)
			{
				saveQueued = false;
				if(dirty)
					markDirty();
			}
		}
	}
	
	private void ensureLoaded()
	{
		if(loaded)
			return;
		loaded = true;
		try
		{
			File file = file();
			if(file.exists())
				try(FileReader in = new FileReader(file))
				{
					Map<String, Map<String, Map<String, SignRecord>>> read =
						gson.fromJson(in, TYPE.getType());
					if(read != null)
						data.putAll(read);
				}
		}catch(Exception e)
		{
			System.err.println("Could not load sign history: " + e);
		}
	}
	
	private static File file()
	{
		return new File(WurstClient.MC.gameDirectory,
			"config/wurst/sign_history.json");
	}
	
	static final class SignRecord
	{
		String dimension;
		transient String key;
		List<Entry> entries = new ArrayList<>();
		
		SignRecord()
		{}
		
		SignRecord(String dimension)
		{
			this.dimension = dimension;
		}
	}
	
	static final class Entry
	{
		String date;
		String state;
		List<String> front;
		List<String> back;
		
		static Entry present(SignBlockEntity sign, boolean changed)
		{
			Entry e = new Entry();
			e.date = Instant.now().toString();
			e.state = changed ? "changed" : "present";
			e.front = text(sign.getFrontText());
			e.back = text(sign.getBackText());
			return e;
		}
		
		static Entry removed()
		{
			Entry e = new Entry();
			e.date = Instant.now().toString();
			e.state = "removed";
			return e;
		}
		
		private static List<String> text(SignText text)
		{
			List<String> lines = new ArrayList<>();
			for(int i = 0; i < 4; i++)
				lines.add(text.getMessage(i, false).getString());
			return lines;
		}
		
		boolean sameText(Entry other)
		{
			return java.util.Objects.equals(front, other.front)
				&& java.util.Objects.equals(back, other.back);
		}
	}
}
