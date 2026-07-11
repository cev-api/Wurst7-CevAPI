/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.settings;

import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import net.wurstclient.clickgui.Component;
import net.wurstclient.clickgui.components.PlayerMuteListEditButton;
import net.wurstclient.keybinds.PossibleKeybind;
import net.wurstclient.hacks.PlayerMuteHack;
import net.wurstclient.util.text.WText;

public final class PlayerMuteListSetting extends Setting
{
	private final PlayerMuteHack hack;
	
	public PlayerMuteListSetting(String name, String description,
		PlayerMuteHack hack)
	{
		super(name, WText.literal(description));
		this.hack = hack;
	}
	
	public PlayerMuteHack getHack()
	{
		return hack;
	}
	
	@Override
	public Component getComponent()
	{
		return new PlayerMuteListEditButton(this);
	}
	
	@Override
	public void fromJson(JsonElement json)
	{}
	
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
		json.addProperty("type", "PlayerMuteList");
		return json;
	}
	
	@Override
	public Set<PossibleKeybind> getPossibleKeybinds(String featureName)
	{
		return Set.of();
	}
}
