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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.wurstclient.WurstClient;
import net.wurstclient.clickgui.Component;
import net.wurstclient.clickgui.components.EntityTypeListEditButton;
import net.wurstclient.keybinds.PossibleKeybind;
import net.wurstclient.util.json.JsonException;
import net.wurstclient.util.json.JsonUtils;
import net.wurstclient.util.text.WText;

public final class EntityTypeListSetting extends Setting
{
	private final ArrayList<String> typeNames = new ArrayList<>();
	private final String[] defaultNames;
	
	public EntityTypeListSetting(String name, WText description,
		String... types)
	{
		super(name, description);
		if(types != null && types.length > 0)
		{
			Arrays.stream(types).parallel()
				.forEachOrdered(s -> addFromStringCanonicalizing(s));
		}else
		{
			// Default to all non-MISC spawn group entity types (typical mobs)
			BuiltInRegistries.ENTITY_TYPE.keySet().forEach(id -> {
				EntityType<?> t = BuiltInRegistries.ENTITY_TYPE.getValue(id);
				MobCategory g = t.getCategory();
				if(g != MobCategory.MISC)
					typeNames.add(id.toString());
			});
			Collections.sort(typeNames);
		}
		defaultNames = typeNames.toArray(new String[0]);
	}
	
	public EntityTypeListSetting(String name, String descriptionKey)
	{
		this(name, WText.translated(descriptionKey));
	}
	
	public List<String> getTypeNames()
	{
		return Collections.unmodifiableList(typeNames);
	}
	
	private void addFromStringCanonicalizing(String s)
	{
		if(s == null)
			return;
		String raw = s.trim();
		if(raw.isEmpty())
			return;
		
		Identifier id = Identifier.tryParse(raw);
		String name = raw;
		if(id != null && BuiltInRegistries.ENTITY_TYPE.containsKey(id))
			name = id.toString();
		
		if(Collections.binarySearch(typeNames, name) < 0)
		{
			typeNames.add(name);
			Collections.sort(typeNames);
		}
	}
	
	// Allow adding raw keyword entries
	public void addRawName(String raw)
	{
		int before = typeNames.size();
		addFromStringCanonicalizing(raw);
		if(typeNames.size() != before)
			WurstClient.INSTANCE.saveSettings();
	}
	
	public void add(EntityType<?> type)
	{
		String name = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
		if(Collections.binarySearch(typeNames, name) >= 0)
			return;
		typeNames.add(name);
		Collections.sort(typeNames);
		WurstClient.INSTANCE.saveSettings();
	}
	
	public void remove(int index)
	{
		if(index < 0 || index >= typeNames.size())
			return;
		typeNames.remove(index);
		WurstClient.INSTANCE.saveSettings();
	}
	
	public void resetToDefaults()
	{
		typeNames.clear();
		typeNames.addAll(Arrays.asList(defaultNames));
		WurstClient.INSTANCE.saveSettings();
	}
	
	public void clear()
	{
		typeNames.clear();
		WurstClient.INSTANCE.saveSettings();
	}
	
	@Override
	public Component getComponent()
	{
		return new EntityTypeListEditButton(this);
	}
	
	@Override
	public void fromJson(JsonElement json)
	{
		try
		{
			typeNames.clear();
			if(JsonUtils.getAsString(json, "nope").equals("default"))
			{
				typeNames.addAll(Arrays.asList(defaultNames));
				return;
			}
			for(String s : JsonUtils.getAsArray(json).getAllStrings())
				addFromStringCanonicalizing(s);
		}catch(JsonException e)
		{
			e.printStackTrace();
			resetToDefaults();
		}
	}
	
	@Override
	public JsonElement toJson()
	{
		if(typeNames.equals(Arrays.asList(defaultNames)))
			return new JsonPrimitive("default");
		JsonArray json = new JsonArray();
		typeNames.forEach(s -> json.add(s));
		return json;
	}
	
	@Override
	public JsonObject exportWikiData()
	{
		JsonObject json = new JsonObject();
		json.addProperty("name", getName());
		json.addProperty("description", getDescription());
		json.addProperty("type", "EntityTypeList");
		JsonArray defaults = new JsonArray();
		Arrays.stream(defaultNames).forEachOrdered(defaults::add);
		json.add("defaultTypes", defaults);
		return json;
	}
	
	@Override
	public Set<PossibleKeybind> getPossibleKeybinds(String featureName)
	{
		String fullName = featureName + " " + getName();
		String command = ".entitylist " + featureName.toLowerCase() + " "
			+ getName().toLowerCase().replace(" ", "_") + " ";
		LinkedHashSet<PossibleKeybind> pkb = new LinkedHashSet<>();
		pkb.add(new PossibleKeybind(command + "reset", "Reset " + fullName));
		return pkb;
	}
}
