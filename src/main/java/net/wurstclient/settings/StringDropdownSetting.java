/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.settings;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import net.wurstclient.WurstClient;
import net.wurstclient.clickgui.Component;
import net.wurstclient.clickgui.components.StringDropdownComponent;
import net.wurstclient.keybinds.PossibleKeybind;
import net.wurstclient.util.json.JsonUtils;
import net.wurstclient.util.text.WText;

public final class StringDropdownSetting extends Setting
{
	private final List<String> values = new ArrayList<>();
	private final String defaultValue;
	private String selected;
	
	public StringDropdownSetting(String name, WText description)
	{
		super(name, description);
		defaultValue = "";
		selected = defaultValue;
		values.add(defaultValue);
	}
	
	public StringDropdownSetting(String name, String descriptionKey)
	{
		this(name, WText.translated(descriptionKey));
	}
	
	public List<String> getValues()
	{
		return Collections.unmodifiableList(values);
	}
	
	public String getSelected()
	{
		return selected;
	}
	
	public String getDefaultValue()
	{
		return defaultValue;
	}
	
	public void setOptions(Collection<String> options)
	{
		List<String> updated = new ArrayList<>();
		updated.add(defaultValue);
		
		if(options != null)
		{
			for(String option : options)
			{
				if(option == null)
					continue;
				
				option = option.trim();
				if(option.isEmpty() || updated.contains(option))
					continue;
				
				updated.add(option);
			}
		}
		
		if(values.equals(updated))
			return;
		
		values.clear();
		values.addAll(updated);
		
		if(!values.contains(selected))
			selected = defaultValue;
		
		WurstClient.INSTANCE.saveSettings();
	}
	
	public void setSelected(String selected)
	{
		if(selected == null)
			return;
		
		if(values.contains(selected))
		{
			if(this.selected.equals(selected))
				return;
			
			this.selected = selected;
			WurstClient.INSTANCE.saveSettings();
		}
	}
	
	public void resetToDefault()
	{
		setSelected(defaultValue);
	}
	
	@Override
	public Component getComponent()
	{
		return new StringDropdownComponent(this);
	}
	
	@Override
	public void fromJson(JsonElement json)
	{
		if(!JsonUtils.isString(json))
			return;
		
		setSelected(json.getAsString());
	}
	
	@Override
	public JsonElement toJson()
	{
		return new JsonPrimitive(selected);
	}
	
	@Override
	public JsonObject exportWikiData()
	{
		JsonObject json = new JsonObject();
		json.addProperty("name", getName());
		json.addProperty("description", getDescription());
		json.addProperty("type", "StringDropdown");
		json.addProperty("defaultValue", defaultValue);
		
		return json;
	}
	
	@Override
	public LinkedHashSet<PossibleKeybind> getPossibleKeybinds(
		String featureName)
	{
		return new LinkedHashSet<>();
	}
}
