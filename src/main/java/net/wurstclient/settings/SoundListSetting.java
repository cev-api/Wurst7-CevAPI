/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.settings;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.wurstclient.WurstClient;
import net.wurstclient.clickgui.Component;
import net.wurstclient.clickgui.components.SoundListEditButton;
import net.wurstclient.keybinds.PossibleKeybind;
import net.wurstclient.util.json.JsonException;
import net.wurstclient.util.json.JsonUtils;
import net.wurstclient.util.text.WText;

public final class SoundListSetting extends Setting
{
	private final Object lock = new Object();
	private final LinkedHashSet<Identifier> muted = new LinkedHashSet<>();
	private final Set<Identifier> defaults;
	
	public SoundListSetting(String name, WText description, String... sounds)
	{
		super(name, description);
		Arrays.stream(sounds).map(Identifier::tryParse).filter(id -> id != null)
			.forEach(muted::add);
		defaults = Set.copyOf(muted);
	}
	
	public SoundListSetting(String name, String description, String... sounds)
	{
		this(name, WText.literal(description), sounds);
	}
	
	public List<Identifier> getAvailableSounds()
	{
		return BuiltInRegistries.SOUND_EVENT.keySet().stream().sorted()
			.toList();
	}
	
	public Set<Identifier> getMutedSounds()
	{
		synchronized(lock)
		{
			return Set.copyOf(muted);
		}
	}
	
	public boolean contains(Identifier id)
	{
		synchronized(lock)
		{
			return id != null && muted.contains(id);
		}
	}
	
	public int size()
	{
		synchronized(lock)
		{
			return muted.size();
		}
	}
	
	public void setMuted(Set<Identifier> ids)
	{
		synchronized(lock)
		{
			muted.clear();
			muted.addAll(ids);
		}
		WurstClient.INSTANCE.saveSettings();
	}
	
	public void clear()
	{
		synchronized(lock)
		{
			muted.clear();
		}
		WurstClient.INSTANCE.saveSettings();
	}
	
	@Override
	public Component getComponent()
	{
		return new SoundListEditButton(this);
	}
	
	@Override
	public void resetToDefault()
	{
		synchronized(lock)
		{
			muted.clear();
			muted.addAll(defaults);
		}
		WurstClient.INSTANCE.saveSettings();
	}
	
	@Override
	public void fromJson(JsonElement json)
	{
		try
		{
			Set<Identifier> parsed = new LinkedHashSet<>();
			for(String raw : JsonUtils.getAsArray(json).getAllStrings())
			{
				Identifier id = Identifier.tryParse(raw);
				if(id != null)
					parsed.add(id);
			}
			synchronized(lock)
			{
				muted.clear();
				muted.addAll(parsed);
			}
		}catch(JsonException e)
		{
			resetToDefault();
		}
	}
	
	@Override
	public JsonElement toJson()
	{
		JsonArray json = new JsonArray();
		getMutedSounds().stream().map(Identifier::toString).sorted()
			.forEach(json::add);
		return json;
	}
	
	@Override
	public JsonObject exportWikiData()
	{
		JsonObject json = new JsonObject();
		json.addProperty("name", getName());
		json.addProperty("description", getDescription());
		json.addProperty("type", "SoundList");
		return json;
	}
	
	@Override
	public Set<PossibleKeybind> getPossibleKeybinds(String featureName)
	{
		String full = featureName + " " + getName();
		String cmd = ".soundlist " + featureName.toLowerCase() + " "
			+ getName().toLowerCase().replace(" ", "_") + " ";
		return Set.of(new PossibleKeybind(cmd + "reset", "Reset " + full));
	}
}
