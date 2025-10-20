/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.settings;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import net.wurstclient.clickgui.Component;
import net.wurstclient.clickgui.components.ButtonComponent;
import net.wurstclient.keybinds.PossibleKeybind;
import net.wurstclient.util.text.WText;

public final class ButtonSetting extends Setting
{
	private final Runnable action;
	
	public ButtonSetting(String name, WText description, Runnable action)
	{
		super(name, description);
		this.action = Objects.requireNonNull(action);
	}
	
	public ButtonSetting(String name, String descriptionKey, Runnable action)
	{
		this(name, WText.translated(descriptionKey), action);
	}
	
	public ButtonSetting(String name, Runnable action)
	{
		this(name, WText.empty(), action);
	}
	
	@Override
	public Component getComponent()
	{
		return new ButtonComponent(getName(), action);
	}
	
	@Override
	public void fromJson(JsonElement json)
	{
		// buttons do not persist state
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
		json.addProperty("type", "Button");
		return json;
	}
	
	@Override
	public Set<PossibleKeybind> getPossibleKeybinds(String featureName)
	{
		return Collections.emptySet();
	}
}
