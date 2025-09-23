/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.settings;

import java.util.LinkedHashSet;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.wurstclient.clickgui.Component;
import net.wurstclient.clickgui.components.WaypointsEditButton;
import net.wurstclient.util.text.WText;
import net.wurstclient.waypoints.WaypointsManager;
import net.wurstclient.keybinds.PossibleKeybind;

public final class WaypointsSetting extends Setting
{
	private final WaypointsManager manager;
	
	public WaypointsSetting(String name, WaypointsManager manager)
	{
		super(name, WText.empty());
		this.manager = manager;
	}
	
	public WaypointsManager getManager()
	{
		return manager;
	}
	
	@Override
	public Component getComponent()
	{
		return new WaypointsEditButton(this);
	}
	
	@Override
	public void fromJson(JsonElement json)
	{
		// no-op
	}
	
	@Override
	public JsonElement toJson()
	{
		return new JsonObject();
	}
	
	@Override
	public JsonObject exportWikiData()
	{
		JsonObject json = new JsonObject();
		json.addProperty("name", getName());
		json.addProperty("description", getDescription());
		json.addProperty("type", "WaypointsManager");
		return json;
	}
	
	@Override
	public Set<PossibleKeybind> getPossibleKeybinds(String featureName)
	{
		LinkedHashSet<PossibleKeybind> pkb = new LinkedHashSet<>();
		pkb.add(new PossibleKeybind(".waypoints", "Open Waypoints manager"));
		return pkb;
	}
}
