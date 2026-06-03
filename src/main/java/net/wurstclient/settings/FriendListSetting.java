/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.settings;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.ArrayList;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import net.wurstclient.WurstClient;
import net.wurstclient.clickgui.Component;
import net.wurstclient.clickgui.components.FriendListEditButton;
import net.wurstclient.keybinds.PossibleKeybind;
import net.wurstclient.util.json.JsonException;
import net.wurstclient.util.json.JsonUtils;
import net.wurstclient.util.text.WText;

public final class FriendListSetting extends Setting
{
	public FriendListSetting(String name, WText description)
	{
		super(name, description);
	}
	
	public FriendListSetting(String name, String description)
	{
		this(name, WText.literal(description));
	}
	
	public List<String> getFriendNames()
	{
		if(WurstClient.INSTANCE == null)
			return List.of();
		
		if(WurstClient.INSTANCE.getFriends() != null)
		{
			List<String> liveFriends =
				WurstClient.INSTANCE.getFriends().toList();
			if(!liveFriends.isEmpty())
				return Collections.unmodifiableList(liveFriends);
		}
		
		return Collections.unmodifiableList(loadFriendNamesFromDisk());
	}
	
	public void addName(String name)
	{
		if(name == null)
			return;
		
		String trimmed = name.trim();
		if(trimmed.isEmpty())
			return;
		
		if(WurstClient.INSTANCE.getFriends().contains(trimmed))
			return;
		
		WurstClient.INSTANCE.getFriends().addAndSave(trimmed);
	}
	
	public void removeName(String name)
	{
		if(name == null)
			return;
		
		WurstClient.INSTANCE.getFriends().removeAndSave(name);
	}
	
	public void removeNames(Collection<String> names)
	{
		if(names == null || names.isEmpty())
			return;
		
		for(String name : names)
			removeName(name);
	}
	
	public void clear()
	{
		WurstClient.INSTANCE.getFriends().removeAllAndSave();
	}
	
	private List<String> loadFriendNamesFromDisk()
	{
		try
		{
			Path wurstFolder = WurstClient.INSTANCE.getWurstFolder();
			if(wurstFolder == null)
				return List.of();
			
			Path friendsFile = wurstFolder.resolve("friends.json");
			List<String> friends =
				JsonUtils.parseFileToArray(friendsFile).getAllStrings();
			if(!friends.isEmpty())
				return friends;
			
		}catch(IOException | JsonException e)
		{
			// fall through to tolerant plain-text parsing below
		}
		
		try
		{
			Path wurstFolder = WurstClient.INSTANCE.getWurstFolder();
			if(wurstFolder == null)
				return List.of();
			
			Path friendsFile = wurstFolder.resolve("friends.json");
			if(!Files.exists(friendsFile))
				return List.of();
			
			ArrayList<String> plainTextFriends = new ArrayList<>();
			for(String line : Files.readAllLines(friendsFile))
			{
				String trimmed = line.trim();
				if(trimmed.isEmpty() || trimmed.equals("[")
					|| trimmed.equals("]"))
					continue;
				
				trimmed = trimmed.replace("\"", "").replace(",", "").trim();
				if(!trimmed.isEmpty())
					plainTextFriends.add(trimmed);
			}
			return plainTextFriends;
			
		}catch(IOException e)
		{
			return List.of();
		}
	}
	
	@Override
	public Component getComponent()
	{
		return new FriendListEditButton(this);
	}
	
	@Override
	public void fromJson(JsonElement json)
	{
		// Friends are persisted separately in friends.json.
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
		json.addProperty("type", "FriendList");
		return json;
	}
	
	@Override
	public Set<PossibleKeybind> getPossibleKeybinds(String featureName)
	{
		LinkedHashSet<PossibleKeybind> pkb = new LinkedHashSet<>();
		pkb.add(new PossibleKeybind(".friends add ",
			"Add a friend via " + featureName));
		pkb.add(new PossibleKeybind(".friends remove ",
			"Remove a friend via " + featureName));
		return pkb;
	}
}
