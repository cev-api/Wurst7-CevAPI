/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;
import net.wurstclient.WurstClient;

/** Persistent storage for deterministic-roll hack state. */
public final class RollStateStore
{
	private static final int VERSION = 1;
	private static final Gson GSON =
		new GsonBuilder().setPrettyPrinting().create();
	private static final Object LOCK = new Object();
	
	private static JsonObject root;
	
	private RollStateStore()
	{}
	
	/**
	 * Returns a stable identity for the currently connected server/world.
	 * Server addresses include their port, so different servers on one host do
	 * not share state. Seed records are kept separately inside that identity.
	 */
	public static String getCurrentServerKey()
	{
		Minecraft client = WurstClient.MC;
		if(client == null)
			return null;
		if(client.getCurrentServer() != null
			&& client.getCurrentServer().ip != null
			&& !client.getCurrentServer().ip.isBlank())
			return "multiplayer:"
				+ client.getCurrentServer().ip.trim().toLowerCase(Locale.ROOT);
		if(client.getSingleplayerServer() != null)
			return "singleplayer";
		return null;
	}
	
	/** Loads the newest record when {@code seed} is unknown. */
	public static JsonObject load(String section, String serverKey, Long seed)
	{
		if(serverKey == null || serverKey.isBlank())
			return null;
		synchronized(LOCK)
		{
			loadRoot();
			JsonObject records = getRecords(section, serverKey, false);
			if(records == null)
				return null;
			JsonObject result = seed == null ? findNewest(records)
				: getObject(records, Long.toString(seed), false);
			if(result == null || !result.isJsonObject())
				return null;
			return JsonParser.parseString(result.toString()).getAsJsonObject();
		}
	}
	
	/** Saves one section without disturbing the other roll hack's state. */
	public static void save(String section, String serverKey, long seed,
		JsonObject state)
	{
		if(serverKey == null || serverKey.isBlank() || state == null)
			return;
		synchronized(LOCK)
		{
			loadRoot();
			JsonObject records = getRecords(section, serverKey, true);
			JsonObject record =
				JsonParser.parseString(state.toString()).getAsJsonObject();
			record.addProperty("server", serverKey);
			record.addProperty("seed", seed);
			record.addProperty("updatedAt", System.currentTimeMillis());
			records.add(Long.toString(seed), record);
			writeRoot();
		}
	}
	
	/** Removes one server/seed record, if it exists. */
	public static void clear(String section, String serverKey, long seed)
	{
		if(serverKey == null || serverKey.isBlank())
			return;
		synchronized(LOCK)
		{
			loadRoot();
			JsonObject records = getRecords(section, serverKey, false);
			if(records == null || !records.has(Long.toString(seed)))
				return;
			records.remove(Long.toString(seed));
			writeRoot();
		}
	}
	
	private static void loadRoot()
	{
		if(root != null)
			return;
		root = new JsonObject();
		root.addProperty("version", VERSION);
		Path path = getPath();
		try
		{
			if(!Files.exists(path))
				return;
			JsonElement parsed = JsonParser
				.parseString(Files.readString(path, StandardCharsets.UTF_8));
			if(parsed.isJsonObject())
				root = parsed.getAsJsonObject();
		}catch(Exception e)
		{
			System.err.println("Failed to load roll state: " + e.getMessage());
		}
	}
	
	private static JsonObject getRecords(String section, String serverKey,
		boolean create)
	{
		JsonObject sections = getObject(root, "sections", create);
		if(sections == null)
			return null;
		JsonObject sectionObject = getObject(sections, section, create);
		if(sectionObject == null)
			return null;
		JsonObject servers = getObject(sectionObject, "servers", create);
		if(servers == null)
			return null;
		return getObject(servers, serverKey, create);
	}
	
	private static JsonObject getObject(JsonObject parent, String name,
		boolean create)
	{
		JsonElement element = parent.get(name);
		if(element != null && element.isJsonObject())
			return element.getAsJsonObject();
		if(!create)
			return null;
		JsonObject result = new JsonObject();
		parent.add(name, result);
		return result;
	}
	
	private static JsonObject findNewest(JsonObject records)
	{
		JsonObject newest = null;
		long newestTime = Long.MIN_VALUE;
		for(var entry : records.entrySet())
		{
			JsonElement element = entry.getValue();
			if(!element.isJsonObject())
				continue;
			JsonObject record = element.getAsJsonObject();
			long updatedAt = getLong(record, "updatedAt", Long.MIN_VALUE);
			if(newest == null || updatedAt > newestTime)
			{
				newest = record;
				newestTime = updatedAt;
			}
		}
		return newest;
	}
	
	private static long getLong(JsonObject object, String name, long fallback)
	{
		try
		{
			JsonElement value = object.get(name);
			return value == null ? fallback : value.getAsLong();
		}catch(RuntimeException e)
		{
			return fallback;
		}
	}
	
	private static Path getPath()
	{
		return WurstClient.INSTANCE.getWurstFolder()
			.resolve("roll-states.json");
	}
	
	private static void writeRoot()
	{
		Path path = getPath();
		Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
		try
		{
			Files.createDirectories(path.getParent());
			Files.writeString(temporary, GSON.toJson(root),
				StandardCharsets.UTF_8);
			try
			{
				Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING,
					StandardCopyOption.ATOMIC_MOVE);
			}catch(AtomicMoveNotSupportedException e)
			{
				Files.move(temporary, path,
					StandardCopyOption.REPLACE_EXISTING);
			}
		}catch(IOException e)
		{
			System.err.println("Failed to save roll state: " + e.getMessage());
		}
	}
}
