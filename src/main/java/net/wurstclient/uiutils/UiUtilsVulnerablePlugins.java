/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.uiutils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;

public final class UiUtilsVulnerablePlugins
{
	private static final Gson GSON =
		new GsonBuilder().setPrettyPrinting().create();
	private static final String DEFAULT_RESOURCE =
		"/assets/wurst/vulnerable_plugins.json";
	private static final String CONFIG_FILE_NAME =
		"ui-utils-vulnerable-plugins.json";
	
	private static volatile boolean initialized;
	private static volatile Map<String, VulnerableEntry> entriesByKey =
		Map.of();
	
	private UiUtilsVulnerablePlugins()
	{}
	
	public static synchronized void init()
	{
		if(initialized)
			return;
		reload();
		initialized = true;
	}
	
	public static synchronized void reload()
	{
		Path target = configPath();
		ensureExternalJsonExists(target);
		entriesByKey = Collections.unmodifiableMap(readEntries(target));
	}
	
	public static Set<String> keys()
	{
		return entriesByKey.keySet();
	}
	
	public static Map<String, VulnerableEntry> entriesByKey()
	{
		return entriesByKey;
	}
	
	public static String normalizeKey(String raw)
	{
		if(raw == null)
			return "";
		String lower = raw.toLowerCase(Locale.ROOT);
		StringBuilder sb = new StringBuilder(lower.length());
		for(int i = 0; i < lower.length(); i++)
		{
			char c = lower.charAt(i);
			if(Character.isLetterOrDigit(c))
				sb.append(c);
		}
		return sb.toString();
	}
	
	private static Path configPath()
	{
		return FabricLoader.getInstance().getConfigDir()
			.resolve(CONFIG_FILE_NAME);
	}
	
	private static void ensureExternalJsonExists(Path target)
	{
		if(Files.exists(target))
			return;
		try
		{
			Files.createDirectories(target.getParent());
			JsonArray defaults = readDefaultArray();
			try(Writer writer =
				Files.newBufferedWriter(target, StandardCharsets.UTF_8))
			{
				GSON.toJson(defaults, writer);
			}
		}catch(Throwable t)
		{
			UiUtils.LOGGER.warn(
				"Failed to write default vulnerable plugin JSON to {}", target,
				t);
		}
	}
	
	private static JsonArray readDefaultArray()
	{
		try(InputStream in = UiUtilsVulnerablePlugins.class
			.getResourceAsStream(DEFAULT_RESOURCE))
		{
			if(in == null)
				return new JsonArray();
			try(Reader reader =
				new InputStreamReader(in, StandardCharsets.UTF_8))
			{
				JsonElement parsed = JsonParser.parseReader(reader);
				return parsed != null && parsed.isJsonArray()
					? parsed.getAsJsonArray() : new JsonArray();
			}
		}catch(Throwable t)
		{
			UiUtils.LOGGER
				.warn("Failed to read bundled vulnerable plugin JSON.", t);
			return new JsonArray();
		}
	}
	
	private static Map<String, VulnerableEntry> readEntries(Path target)
	{
		JsonArray source = new JsonArray();
		try(Reader reader =
			Files.newBufferedReader(target, StandardCharsets.UTF_8))
		{
			JsonElement parsed = JsonParser.parseReader(reader);
			if(parsed != null && parsed.isJsonArray())
				source = parsed.getAsJsonArray();
		}catch(IOException e)
		{
			UiUtils.LOGGER.warn("Failed to read vulnerable plugin JSON from {}",
				target, e);
		}
		
		Map<String, VulnerableEntry> map = new LinkedHashMap<>();
		for(JsonElement element : source)
		{
			if(element == null || !element.isJsonObject())
				continue;
			JsonObject obj = element.getAsJsonObject();
			String name = getStringOrNull(obj, "name");
			String key = normalizeKey(name);
			if(key.isEmpty())
				continue;
			VulnerableEntry entry =
				map.computeIfAbsent(key, ignored -> new VulnerableEntry(
					name == null || name.isBlank() ? key : name.trim()));
			String version = getStringOrNull(obj, "version");
			if(version != null && !version.isBlank())
				entry.versions().add(version.trim());
		}
		return map;
	}
	
	private static String getStringOrNull(JsonObject obj, String key)
	{
		if(obj == null || key == null || !obj.has(key)
			|| obj.get(key).isJsonNull())
			return null;
		JsonElement element = obj.get(key);
		return element.isJsonPrimitive() ? element.getAsString() : null;
	}
	
	public static final class VulnerableEntry
	{
		private final String displayName;
		private final Set<String> versions = new LinkedHashSet<>();
		
		private VulnerableEntry(String displayName)
		{
			this.displayName = displayName;
		}
		
		public String displayName()
		{
			return displayName;
		}
		
		public Set<String> versions()
		{
			return versions;
		}
	}
}
