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
import java.util.Objects;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
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
				.map(s -> Registries.ENTITY_TYPE.get(Identifier.of(s)))
				.filter(Objects::nonNull)
				.map(t -> Registries.ENTITY_TYPE.getId(t).toString()).distinct()
				.sorted().forEachOrdered(s -> typeNames.add(s));
		}else
		{
			// Default to all non-MISC spawn group entity types (typical mobs)
			Registries.ENTITY_TYPE.getIds().forEach(id -> {
				EntityType<?> t = Registries.ENTITY_TYPE.get(id);
				SpawnGroup g = t.getSpawnGroup();
				if(g != SpawnGroup.MISC)
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
	
	public void add(EntityType<?> type)
	{
		String name = Registries.ENTITY_TYPE.getId(type).toString();
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
			JsonUtils.getAsArray(json).getAllStrings().parallelStream()
				.map(s -> Registries.ENTITY_TYPE.get(Identifier.of(s)))
				.filter(Objects::nonNull)
				.map(t -> Registries.ENTITY_TYPE.getId(t).toString()).distinct()
				.sorted().forEachOrdered(s -> typeNames.add(s));
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
