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
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.wurstclient.WurstClient;

final class SignHistory
{
	private static final TypeToken<Map<String, Map<String, Map<String, SignRecord>>>> TYPE =
		new TypeToken<>()
		{};
	// Only the newest N changes per sign are kept, so a single sign that is
	// edited very often cannot grow the file without bound.
	private static final int MAX_ENTRIES_PER_RECORD = 50;
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
			trimEntries(record);
			markDirty();
		}
	}
	
	synchronized void recordRemoved(String server, String dimension,
		BlockPos pos)
	{
		ensureLoaded();
		// Never create a record just to mark it removed. Positions that were
		// never a tracked sign are not part of the history.
		SignRecord record = getRecord(server, dimension, pos, false);
		if(record == null)
			return;
		if(!record.entries.isEmpty() && "removed"
			.equals(record.entries.get(record.entries.size() - 1).state))
			return;
		record.entries.add(Entry.removed());
		trimEntries(record);
		markDirty();
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
		String key = key(pos);
		SignRecord record = positions.get(key);
		if(record == null && create)
			positions.put(key, record = new SignRecord(dimension));
		if(record != null)
			record.key = key;
		return record;
	}
	
	synchronized boolean hasRecord(String server, String dimension,
		BlockPos pos)
	{
		ensureLoaded();
		Map<String, Map<String, SignRecord>> dimensions = data.get(server);
		if(dimensions == null)
			return false;
		Map<String, SignRecord> positions = dimensions.get(dimension);
		return positions != null && positions.containsKey(key(pos));
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
			Map<String, Map<String, Map<String, SignRecord>>> snapshot;
			synchronized(this)
			{
				if(!dirty)
					return;
				dirty = false;
				snapshot = copy(data);
			}
			// Serialize and write without holding the history lock, so that
			// saving a large file never blocks the client thread.
			String json = gson.toJson(snapshot, TYPE.getType());
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
					{
						boolean pruned = pruneJunk(read);
						data.putAll(read);
						// Rewrite the file once if junk was removed, so an
						// oversized file heals without being re-parsed forever.
						if(pruned)
							markDirty();
					}
				}
		}catch(Exception e)
		{
			System.err.println("Could not load sign history: " + e);
		}
	}
	
	private static String key(BlockPos pos)
	{
		return pos.getX() + "," + pos.getY() + "," + pos.getZ();
	}
	
	private void trimEntries(SignRecord record)
	{
		int excess = record.entries.size() - MAX_ENTRIES_PER_RECORD;
		if(excess > 0)
			record.entries.subList(0, excess).clear();
	}
	
	/**
	 * Removes records that only contain "removed" entries (created by an older
	 * bug that treated every block update as a possible removed sign), trims
	 * records to the newest MAX_ENTRIES_PER_RECORD entries and returns whether
	 * anything was removed.
	 */
	private static boolean pruneJunk(
		Map<String, Map<String, Map<String, SignRecord>>> all)
	{
		boolean[] changed = {false};
		all.values().removeIf(dimensions -> {
			dimensions.values().removeIf(positions -> {
				positions.entrySet().removeIf(entry -> {
					SignRecord record = entry.getValue();
					if(record == null || record.entries == null)
					{
						changed[0] = true;
						return true;
					}
					if(record.entries.size() > MAX_ENTRIES_PER_RECORD)
					{
						changed[0] = true;
						record.entries = new ArrayList<>(record.entries.subList(
							record.entries.size() - MAX_ENTRIES_PER_RECORD,
							record.entries.size()));
					}
					for(Entry e : record.entries)
						if(!"removed".equals(e.state))
							return false;
					changed[0] = true;
					return true;
				});
				return positions.isEmpty();
			});
			return dimensions.isEmpty();
		});
		return changed[0];
	}
	
	/**
	 * Returns a deep copy of the history, taken under the lock, so the JSON
	 * can be generated outside the lock without concurrent modification.
	 */
	private static Map<String, Map<String, Map<String, SignRecord>>> copy(
		Map<String, Map<String, Map<String, SignRecord>>> source)
	{
		Map<String, Map<String, Map<String, SignRecord>>> result =
			new LinkedHashMap<>();
		for(var serverEntry : source.entrySet())
		{
			Map<String, Map<String, SignRecord>> dimensions =
				new LinkedHashMap<>();
			for(var dimensionEntry : serverEntry.getValue().entrySet())
			{
				Map<String, SignRecord> positions = new LinkedHashMap<>();
				for(var positionEntry : dimensionEntry.getValue().entrySet())
					positions.put(positionEntry.getKey(),
						copy(positionEntry.getValue()));
				dimensions.put(dimensionEntry.getKey(), positions);
			}
			result.put(serverEntry.getKey(), dimensions);
		}
		return result;
	}
	
	private static SignRecord copy(SignRecord record)
	{
		SignRecord result = new SignRecord(record.dimension);
		result.entries = new ArrayList<>(record.entries);
		return result;
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
			e.front = text(sign.getText(SignTextSlot.FRONT));
			e.back = text(sign.getText(SignTextSlot.BACK));
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
				lines.add(text.getMessages(false).get(i).getString());
			return lines;
		}
		
		boolean sameText(Entry other)
		{
			return java.util.Objects.equals(front, other.front)
				&& java.util.Objects.equals(back, other.back);
		}
	}
}
