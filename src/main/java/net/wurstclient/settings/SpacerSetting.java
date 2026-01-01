/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.settings;

import java.util.Collections;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import net.wurstclient.clickgui.Component;
import net.wurstclient.clickgui.components.SpacerComponent;
import net.wurstclient.keybinds.PossibleKeybind;
import net.wurstclient.util.text.WText;

/**
 * Adds blank vertical space between groups of settings. Stores no data.
 */
public final class SpacerSetting extends Setting
{
	private static int COUNTER = 0;
	
	private final int height;
	private final int width;
	
	public SpacerSetting()
	{
		this(8);
	}
	
	public SpacerSetting(int height)
	{
		this(height, 230);
	}
	
	public SpacerSetting(int height, int width)
	{
		this("spacer_" + COUNTER++, height, width);
	}
	
	private SpacerSetting(String name, int height, int width)
	{
		super(name, WText.empty());
		this.height = height;
		this.width = width;
	}
	
	@Override
	public Component getComponent()
	{
		return new SpacerComponent(height, width);
	}
	
	@Override
	public void fromJson(JsonElement json)
	{
		// no state to load
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
		json.addProperty("name", "Spacer");
		json.addProperty("description", "Visual spacer between settings.");
		json.addProperty("type", "Spacer");
		return json;
	}
	
	@Override
	public Set<PossibleKeybind> getPossibleKeybinds(String featureName)
	{
		return Collections.emptySet();
	}
}
