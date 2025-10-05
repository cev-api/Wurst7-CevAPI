/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.settings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import net.minecraft.block.Block;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.wurstclient.WurstClient;
import net.wurstclient.clickgui.Component;
import net.wurstclient.clickgui.components.BlockListEditButton;
import net.wurstclient.keybinds.PossibleKeybind;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.json.JsonException;
import net.wurstclient.util.json.JsonUtils;
import net.wurstclient.util.text.WText;

public class BlockListSetting extends Setting
{
	private final ArrayList<String> blockNames = new ArrayList<>();
	private final String[] defaultNames;
	
	public BlockListSetting(String name, WText description, String... blocks)
	{
		super(name, description);
		
		Arrays.stream(blocks).forEach(this::addFromString);
		defaultNames = blockNames.toArray(new String[0]);
	}
	
	public BlockListSetting(String name, String descriptionKey,
		String... blocks)
	{
		this(name, WText.translated(descriptionKey), blocks);
	}
	
	private void addFromString(String s)
	{
		if(s == null)
			return;
		String raw = s.trim();
		if(raw.isEmpty())
			return;
		
		Identifier id = Identifier.tryParse(raw);
		String name = raw;
		
		if(id != null && Registries.BLOCK.containsId(id))
			name = id.toString();
		
		if(Collections.binarySearch(blockNames, name) < 0)
		{
			blockNames.add(name);
			Collections.sort(blockNames);
		}
	}
	
	public List<String> getBlockNames()
	{
		return Collections.unmodifiableList(blockNames);
	}
	
	public int indexOf(String name)
	{
		if(name == null)
			return -1;
		
		return Collections.binarySearch(blockNames, name);
	}
	
	public int indexOf(Block block)
	{
		return indexOf(BlockUtils.getName(block));
	}
	
	public boolean contains(String name)
	{
		return indexOf(name) >= 0;
	}
	
	public boolean contains(Block block)
	{
		return indexOf(block) >= 0;
	}
	
	public int size()
	{
		return blockNames.size();
	}
	
	public void add(Block block)
	{
		String name = BlockUtils.getName(block);
		if(Collections.binarySearch(blockNames, name) >= 0)
			return;
		
		blockNames.add(name);
		Collections.sort(blockNames);
		WurstClient.INSTANCE.saveSettings();
	}
	
	// New: allow adding raw keyword entries
	public void addRawName(String raw)
	{
		int before = blockNames.size();
		addFromString(raw);
		if(blockNames.size() != before)
			WurstClient.INSTANCE.saveSettings();
	}
	
	public void remove(int index)
	{
		if(index < 0 || index >= blockNames.size())
			return;
		
		blockNames.remove(index);
		WurstClient.INSTANCE.saveSettings();
	}
	
	public void resetToDefaults()
	{
		blockNames.clear();
		blockNames.addAll(Arrays.asList(defaultNames));
		WurstClient.INSTANCE.saveSettings();
	}
	
	public void clear()
	{
		blockNames.clear();
		WurstClient.INSTANCE.saveSettings();
	}
	
	@Override
	public Component getComponent()
	{
		return new BlockListEditButton(this);
	}
	
	@Override
	public void fromJson(JsonElement json)
	{
		try
		{
			blockNames.clear();
			
			// if string "default", load default blocks
			if(JsonUtils.getAsString(json, "nope").equals("default"))
			{
				blockNames.addAll(Arrays.asList(defaultNames));
				return;
			}
			
			// otherwise, load the strings; keep unknown as raw keywords
			for(String rawName : JsonUtils.getAsArray(json).getAllStrings())
				addFromString(rawName);
			
		}catch(JsonException e)
		{
			e.printStackTrace();
			resetToDefaults();
		}
	}
	
	@Override
	public JsonElement toJson()
	{
		// if blockNames is the same as defaultNames, save string "default"
		if(blockNames.equals(Arrays.asList(defaultNames)))
			return new JsonPrimitive("default");
		
		JsonArray json = new JsonArray();
		blockNames.forEach(s -> json.add(s));
		return json;
	}
	
	@Override
	public JsonObject exportWikiData()
	{
		JsonObject json = new JsonObject();
		
		json.addProperty("name", getName());
		json.addProperty("description", getDescription());
		json.addProperty("type", "BlockList");
		
		JsonArray defaultBlocksJson = new JsonArray();
		for(String blockName : defaultNames)
			defaultBlocksJson.add(blockName);
		json.add("defaultBlocks", defaultBlocksJson);
		
		return json;
	}
	
	@Override
	public Set<PossibleKeybind> getPossibleKeybinds(String featureName)
	{
		String fullName = featureName + " " + getName();
		
		String command = ".blocklist " + featureName.toLowerCase() + " ";
		command += getName().toLowerCase().replace(" ", "_") + " ";
		
		LinkedHashSet<PossibleKeybind> pkb = new LinkedHashSet<>();
		// Can't just list all the blocks here. Would need to change UI to allow
		// user to choose a block after selecting this option.
		// pkb.add(new PossibleKeybind(command + "add dirt",
		// "Add dirt to " + fullName));
		// pkb.add(new PossibleKeybind(command + "remove dirt",
		// "Remove dirt from " + fullName));
		pkb.add(new PossibleKeybind(command + "reset", "Reset " + fullName));
		
		return pkb;
	}
	
	/**
	 * Keyword-aware match: returns true if the list contains the block's exact
	 * ID or if any non-identifier entry (keyword) matches typical names for
	 * that block (full ID, local ID, spaced local, translation key, display
	 * name). Intended for lighter checks; performance-sensitive hacks should
	 * precompute keyword caches themselves.
	 */
	public boolean matchesBlock(net.minecraft.block.Block block)
	{
		String idFull = net.wurstclient.util.BlockUtils.getName(block);
		if(contains(idFull))
			return true;
		String localId = idFull.contains(":")
			? idFull.substring(idFull.indexOf(":") + 1) : idFull;
		String localSpaced = localId.replace('_', ' ');
		String transKey = block.getTranslationKey();
		String display = block.getName().getString();
		for(String s : blockNames)
		{
			net.minecraft.util.Identifier id =
				net.minecraft.util.Identifier.tryParse(s);
			if(id != null)
				continue; // already checked exact ID above
			String term = s.toLowerCase(java.util.Locale.ROOT);
			if(containsNormalized(idFull, term)
				|| containsNormalized(localId, term)
				|| containsNormalized(localSpaced, term)
				|| containsNormalized(transKey, term)
				|| containsNormalized(display, term))
				return true;
		}
		return false;
	}
	
	private static boolean containsNormalized(String haystack, String needle)
	{
		return haystack != null
			&& haystack.toLowerCase(java.util.Locale.ROOT).contains(needle);
	}
}
