/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.settings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import net.wurstclient.clickgui.Component;
import net.wurstclient.clickgui.components.SettingGroupComponent;
import net.wurstclient.keybinds.PossibleKeybind;
import net.wurstclient.util.text.WText;

/**
 * Logical container for multiple settings that should only appear inside a
 * pop-out menu. Child settings remain part of the owning feature so their
 * values continue to save/load normally, but their UI is hidden from the
 * default view.
 */
public final class SettingGroup extends Setting
{
	private final ArrayList<Setting> children = new ArrayList<>();
	private final boolean defaultExpanded;
	private final boolean popout;
	
	public SettingGroup(String name, WText description)
	{
		this(name, description, false, true);
	}
	
	public SettingGroup(String name, WText description, boolean defaultExpanded,
		boolean popout)
	{
		super(name, description);
		this.defaultExpanded = defaultExpanded;
		this.popout = popout;
	}
	
	public SettingGroup addChild(Setting setting)
	{
		if(setting == null)
			throw new IllegalArgumentException("Setting cannot be null.");
		if(setting == this)
			throw new IllegalArgumentException(
				"SettingGroup cannot contain itself.");
		if(children.contains(setting))
			return this;
		
		children.add(setting);
		setting.setVisibleInGui(false);
		return this;
	}
	
	public SettingGroup addChildren(Setting... settings)
	{
		if(settings == null)
			return this;
		Arrays.stream(settings).forEach(this::addChild);
		return this;
	}
	
	public List<Setting> getChildren()
	{
		return Collections.unmodifiableList(children);
	}
	
	public boolean isDefaultExpanded()
	{
		return defaultExpanded;
	}
	
	public boolean isPopout()
	{
		return popout;
	}
	
	@Override
	public Component getComponent()
	{
		return getComponent(true);
	}
	
	public Component getComponent(boolean allowPopout)
	{
		if(children.isEmpty())
			return null;
		
		return new SettingGroupComponent(this, allowPopout);
	}
	
	@Override
	public void fromJson(JsonElement json)
	{
		// groups do not store state
	}
	
	@Override
	public JsonElement toJson()
	{
		return JsonNull.INSTANCE;
	}
	
	@Override
	public JsonObject exportWikiData()
	{
		JsonObject json = new JsonObject();
		json.addProperty("name", getName());
		json.addProperty("description", getDescription());
		json.addProperty("type", "SettingGroup");
		
		JsonArray childArray = new JsonArray();
		for(Setting child : children)
			childArray.add(child.getName());
		json.add("children", childArray);
		return json;
	}
	
	@Override
	public Set<PossibleKeybind> getPossibleKeybinds(String featureName)
	{
		return Collections.emptySet();
	}
}
