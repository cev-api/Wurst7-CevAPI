/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.TreeSet;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import com.google.gson.JsonArray;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.wurstclient.commands.FriendsCmd;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.util.json.JsonException;
import net.wurstclient.util.json.JsonUtils;

public class FriendsList implements UpdateListener
{
	private final TreeSet<String> friends = new TreeSet<>();
	private Path path;
	private String lastServerKey = "";
	private final HashSet<String> onlineFriends = new HashSet<>();
	
	public FriendsList(Path path)
	{
		this.path = path;
	}
	
	public void addAndSave(String name)
	{
		friends.add(name);
		save();
	}
	
	public void removeAndSave(String name)
	{
		friends.remove(name);
		save();
	}
	
	public void removeAllAndSave()
	{
		friends.clear();
		save();
	}
	
	public void middleClick(Entity entity)
	{
		if(entity == null || !(entity instanceof Player))
			return;
		
		FriendsCmd friendsCmd = WurstClient.INSTANCE.getCmds().friendsCmd;
		CheckboxSetting middleClickFriends = friendsCmd.getMiddleClickFriends();
		if(!middleClickFriends.isChecked())
			return;
		
		String name = entity.getName().getString();
		
		if(contains(name))
			removeAndSave(name);
		else
			addAndSave(name);
	}
	
	public boolean contains(String name)
	{
		return friends.contains(name);
	}
	
	public boolean isFriend(Entity entity)
	{
		return entity != null && contains(entity.getName().getString());
	}
	
	@Override
	public void onUpdate()
	{
		Minecraft mc = WurstClient.MC;
		if(mc == null)
			return;
		
		FriendsCmd friendsCmd = WurstClient.INSTANCE.getCmds().friendsCmd;
		if(friendsCmd == null || !friendsCmd.getFriendJoinAlerts().isChecked())
		{
			lastServerKey = "";
			onlineFriends.clear();
			return;
		}
		
		String serverKey = resolveServerKey(mc);
		if(serverKey.isEmpty() || mc.getConnection() == null
			|| mc.player == null)
		{
			lastServerKey = "";
			onlineFriends.clear();
			return;
		}
		
		boolean serverChanged = !serverKey.equals(lastServerKey);
		HashSet<String> nowOnlineFriends = new HashSet<>();
		for(PlayerInfo info : mc.getConnection().getOnlinePlayers())
		{
			if(info == null || info.getProfile() == null)
				continue;
			
			String name = info.getProfile().name();
			if(name == null || name.isBlank() || !contains(name)
				|| name.equals(mc.player.getName().getString()))
				continue;
			
			nowOnlineFriends.add(name);
		}
		
		if(serverChanged)
		{
			for(String name : nowOnlineFriends)
				sendFriendAlert(name + " is already on this server.");
		}else
		{
			for(String name : nowOnlineFriends)
				if(!onlineFriends.contains(name))
					sendFriendAlert(name + " joined this server.");
		}
		
		onlineFriends.clear();
		onlineFriends.addAll(nowOnlineFriends);
		lastServerKey = serverKey;
	}
	
	public ArrayList<String> toList()
	{
		return new ArrayList<>(friends);
	}
	
	public void load()
	{
		try
		{
			friends.clear();
			friends.addAll(JsonUtils.parseFileToArray(path).getAllStrings());
			
		}catch(NoSuchFileException e)
		{
			// The file doesn't exist yet. No problem, we'll create it later.
			
		}catch(IOException | JsonException e)
		{
			System.out.println("Couldn't load " + path.getFileName());
			e.printStackTrace();
		}
		
		save();
	}
	
	private void save()
	{
		try
		{
			JsonUtils.toJson(createJson(), path);
			
		}catch(JsonException | IOException e)
		{
			System.out.println("Couldn't save " + path.getFileName());
			e.printStackTrace();
		}
	}
	
	private JsonArray createJson()
	{
		JsonArray json = new JsonArray();
		friends.forEach(json::add);
		return json;
	}
	
	private static String resolveServerKey(Minecraft mc)
	{
		try
		{
			if(mc.getCurrentServer() == null
				|| mc.getCurrentServer().ip == null)
				return "";
			return mc.getCurrentServer().ip.trim().toLowerCase(Locale.ROOT);
		}catch(Throwable ignored)
		{
			return "";
		}
	}
	
	private static void sendFriendAlert(String message)
	{
		Minecraft mc = WurstClient.MC;
		if(mc == null || mc.gui == null)
			return;
		
		MutableComponent prefix =
			Component.literal("[Friends] ").withStyle(ChatFormatting.BLUE);
		MutableComponent body =
			Component.literal(message).withStyle(ChatFormatting.WHITE);
		mc.gui.getChat().addClientSystemMessage(prefix.append(body));
	}
}
