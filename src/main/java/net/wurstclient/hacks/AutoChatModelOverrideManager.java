/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.wurstclient.WurstClient;

/**
 * Loads optional per-model AutoChat overrides from
 * {@code wurst/autochat/model_overrides.json}.
 */
public final class AutoChatModelOverrideManager
{
	public static final String OVERRIDES_FILE = "autochat/model_overrides.json";
	
	private final Path overridesFile;
	private long lastModified = Long.MIN_VALUE;
	private JsonObject cachedRoot = new JsonObject();
	
	public AutoChatModelOverrideManager()
	{
		Path wurstFolder = WurstClient.INSTANCE.getWurstFolder();
		overridesFile = wurstFolder.resolve(OVERRIDES_FILE);
	}
	
	public synchronized JsonObject getOverridesForModel(String modelName)
	{
		JsonObject root = loadRoot();
		JsonObject merged = new JsonObject();
		
		JsonObject defaults = getObject(root, "default");
		if(defaults != null)
			mergeInto(merged, defaults);
		
		JsonObject models = getObject(root, "models");
		if(models != null && modelName != null && !modelName.isBlank())
		{
			for(var entry : models.entrySet())
			{
				if(!entry.getKey().equalsIgnoreCase(modelName)
					|| !entry.getValue().isJsonObject())
					continue;
				
				mergeInto(merged, entry.getValue().getAsJsonObject());
				break;
			}
		}
		
		return merged;
	}
	
	private JsonObject loadRoot()
	{
		try
		{
			if(!Files.exists(overridesFile))
			{
				lastModified = Long.MIN_VALUE;
				cachedRoot = new JsonObject();
				return cachedRoot.deepCopy();
			}
			
			long modified = Files.getLastModifiedTime(overridesFile).toMillis();
			if(modified == lastModified)
				return cachedRoot.deepCopy();
			
			String json = Files.readString(overridesFile);
			JsonElement parsed = JsonParser.parseString(json);
			if(parsed.isJsonObject())
				cachedRoot = parsed.getAsJsonObject();
			else
				cachedRoot = new JsonObject();
			
			lastModified = modified;
			return cachedRoot.deepCopy();
			
		}catch(IOException | RuntimeException e)
		{
			System.out.println("AutoChat: Could not load model overrides.");
			e.printStackTrace();
			cachedRoot = new JsonObject();
			lastModified = Long.MIN_VALUE;
			return new JsonObject();
		}
	}
	
	private static JsonObject getObject(JsonObject root, String key)
	{
		if(root == null || key == null || !root.has(key)
			|| !root.get(key).isJsonObject())
			return null;
		
		return root.getAsJsonObject(key);
	}
	
	private static void mergeInto(JsonObject target, JsonObject source)
	{
		for(var entry : source.entrySet())
			target.add(entry.getKey(), entry.getValue().deepCopy());
	}
}
