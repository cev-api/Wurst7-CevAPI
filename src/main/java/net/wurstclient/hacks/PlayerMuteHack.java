/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.PlayerMuteListSetting;

@SearchTags({"player mute", "mute chat", "ignore player"})
public final class PlayerMuteHack extends Hack
{
	private static final Pattern ANGLE_SENDER = Pattern.compile(
		"^\\s*(?:\\[[^\\]]{1,32} head\\]\\s*)*<([A-Za-z0-9_.*-]{1,32})>");
	private static final Pattern DELIMITED_SENDER =
		Pattern.compile("^\\s*(?:\\[[^\\]]{1,32} head\\]\\s*)*"
			+ "([A-Za-z0-9_.*-]{1,32})\\s*(?::|»|>)\\s");
	private static final Gson GSON =
		new GsonBuilder().setPrettyPrinting().create();
	
	private final Map<UUID, String> mutedPlayers = new LinkedHashMap<>();
	private final Map<String, String> mutedNames = new LinkedHashMap<>();
	private final PlayerMuteListSetting playerList = new PlayerMuteListSetting(
		"Muted players", "Opens the online-player mute manager.", this);
	private boolean loaded;
	
	public PlayerMuteHack()
	{
		super("PlayerMute");
		setCategory(Category.CHAT);
		addSetting(playerList);
	}
	
	@Override
	protected void onEnable()
	{
		load();
	}
	
	public boolean shouldMute(Component message)
	{
		if(!isEnabled() || message == null)
			return false;
		
		load();
		String senderName = findSenderName(message.getString());
		if(senderName == null)
			return false;
		if(mutedNames.containsKey(senderName.toLowerCase()))
			return true;
		PlayerInfo sender = findOnlinePlayer(senderName);
		return sender != null && isMuted(sender.getProfile().id());
	}
	
	public PlayerInfo findOnlinePlayer(String name)
	{
		if(name == null || MC.getConnection() == null)
			return null;
		
		for(PlayerInfo info : MC.getConnection().getOnlinePlayers())
			if(info.getProfile().name().equalsIgnoreCase(name))
				return info;
			
		return null;
	}
	
	private String findSenderName(String text)
	{
		if(text == null)
			return null;
		
		String plain = text.replaceAll("\\u00a7[0-9A-FK-ORa-fk-or]", "");
		Matcher matcher = ANGLE_SENDER.matcher(plain);
		if(!matcher.find())
		{
			matcher = DELIMITED_SENDER.matcher(plain);
			if(!matcher.find())
				return null;
		}
		
		return matcher.group(1);
	}
	
	public boolean isMuted(UUID uuid)
	{
		load();
		return uuid != null && mutedPlayers.containsKey(uuid);
	}
	
	public String toggle(PlayerInfo info)
	{
		if(info == null)
			return "No such online player.";
		
		load();
		UUID uuid = info.getProfile().id();
		String name = info.getProfile().name();
		if(mutedPlayers.remove(uuid) != null)
		{
			save();
			return "Unmuted " + name + ".";
		}
		
		mutedPlayers.put(uuid, name);
		save();
		return "Muted " + name + ".";
	}
	
	public void mute(PlayerInfo info)
	{
		if(info == null)
			return;
		load();
		mutedPlayers.put(info.getProfile().id(), info.getProfile().name());
		save();
	}
	
	public void unmute(PlayerInfo info)
	{
		if(info == null)
			return;
		load();
		mutedPlayers.remove(info.getProfile().id());
		mutedNames.remove(info.getProfile().name().toLowerCase());
		save();
	}
	
	public void muteName(String name)
	{
		if(name == null || name.isBlank())
			return;
		load();
		String trimmed = name.trim();
		mutedNames.put(trimmed.toLowerCase(), trimmed);
		save();
	}
	
	public void unmuteName(String name)
	{
		if(name == null || name.isBlank())
			return;
		load();
		mutedNames.remove(name.trim().toLowerCase());
		save();
	}
	
	public List<PlayerInfo> getOnlinePlayers()
	{
		if(MC.getConnection() == null)
			return List.of();
		
		return MC.getConnection().getOnlinePlayers().stream()
			.sorted(Comparator.comparing(info -> info.getProfile().name(),
				String.CASE_INSENSITIVE_ORDER))
			.toList();
	}
	
	public List<UUID> getMutedOnlineIds()
	{
		load();
		return getOnlinePlayers().stream()
			.filter(info -> mutedPlayers.containsKey(info.getProfile().id())
				|| mutedNames
					.containsKey(info.getProfile().name().toLowerCase()))
			.map(info -> info.getProfile().id()).toList();
	}
	
	public void applyOnlineMuteSelection(List<String> ids)
	{
		load();
		for(PlayerInfo info : getOnlinePlayers())
		{
			UUID uuid = info.getProfile().id();
			if(ids.contains(uuid.toString()))
				mutedPlayers.put(uuid, info.getProfile().name());
			else
			{
				mutedPlayers.remove(uuid);
				mutedNames.remove(info.getProfile().name().toLowerCase());
			}
		}
		save();
	}
	
	public List<String> getMutedNames()
	{
		load();
		List<String> names = new ArrayList<>(mutedPlayers.values());
		for(String name : mutedNames.values())
			if(!names.contains(name))
				names.add(name);
		return names;
	}
	
	private void load()
	{
		if(loaded)
			return;
		loaded = true;
		try
		{
			Path path = getPath();
			if(!Files.exists(path))
				return;
			JsonElement root = JsonParser.parseString(Files.readString(path));
			if(!root.isJsonObject())
				return;
			JsonObject object = root.getAsJsonObject();
			JsonObject uuids =
				object.has("uuids") && object.get("uuids").isJsonObject()
					? object.getAsJsonObject("uuids") : object;
			for(Map.Entry<String, JsonElement> entry : uuids.entrySet())
				try
				{
					mutedPlayers.put(UUID.fromString(entry.getKey()),
						entry.getValue().getAsString());
				}catch(IllegalArgumentException ignored)
				{}
			
			if(object.has("names") && object.get("names").isJsonArray())
				for(JsonElement element : object.getAsJsonArray("names"))
				{
					String name = element.getAsString();
					mutedNames.put(name.toLowerCase(), name);
				}
		}catch(Exception ignored)
		{}
	}
	
	private void save()
	{
		try
		{
			Path path = getPath();
			Files.createDirectories(path.getParent());
			JsonObject root = new JsonObject();
			JsonObject uuids = new JsonObject();
			mutedPlayers.forEach(
				(uuid, name) -> uuids.addProperty(uuid.toString(), name));
			root.add("uuids", uuids);
			JsonArray names = new JsonArray();
			mutedNames.values().forEach(names::add);
			root.add("names", names);
			Files.writeString(path, GSON.toJson(root));
		}catch(IOException ignored)
		{}
	}
	
	private Path getPath()
	{
		return WURST.getWurstFolder().resolve("muted-players.json");
	}
}
