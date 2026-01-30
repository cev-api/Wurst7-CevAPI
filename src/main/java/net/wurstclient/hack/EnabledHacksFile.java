/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.stream.Stream;

import com.google.gson.JsonArray;

import net.wurstclient.util.json.JsonException;
import net.wurstclient.util.json.JsonUtils;
import net.wurstclient.util.json.WsonArray;

public final class EnabledHacksFile
{
	private final Path path;
	private boolean disableSaving;
	
	public EnabledHacksFile(Path path)
	{
		this.path = path;
	}
	
	public void load(HackList hackList)
	{
		load(hackList, false);
	}
	
	public void load(HackList hackList, boolean disableOnly)
	{
		boolean shouldSave = true;
		
		try
		{
			WsonArray wson = JsonUtils.parseFileToArray(path);
			enableHacks(hackList, wson, disableOnly);
			
		}catch(NoSuchFileException e)
		{
			shouldSave = false;
			
		}catch(IOException | JsonException e)
		{
			System.out.println("Couldn't load " + path.getFileName());
			e.printStackTrace();
		}
		
		if(shouldSave)
			save(hackList);
	}
	
	public void loadProfile(HackList hax, Path profilePath)
		throws IOException, JsonException
	{
		if(!profilePath.getFileName().toString().endsWith(".json"))
			throw new IllegalArgumentException();
		
		WsonArray wson = JsonUtils.parseFileToArray(profilePath);
		enableHacks(hax, wson, false);
		
		save(hax);
	}
	
	private void enableHacks(HackList hax, WsonArray wson, boolean disableOnly)
	{
		try
		{
			disableSaving = true;
			
			if(disableOnly)
			{
				java.util.Set<String> desired = new java.util.HashSet<>();
				for(String name : wson.getAllStrings())
					desired.add(name);
				
				for(Hack hack : hax.getAllHax())
				{
					if(!hack.isStateSaved())
						continue;
					
					boolean shouldEnable = desired.contains(hack.getName());
					if(!shouldEnable && hack.isEnabled())
						hack.setEnabled(false);
				}
				return;
			}
			
			for(Hack hack : hax.getAllHax())
				hack.setEnabled(false);
			
			for(String name : wson.getAllStrings())
			{
				Hack hack = hax.getHackByName(name);
				if(hack == null || !hack.isStateSaved())
					continue;
				
				hack.setEnabled(true);
			}
			
		}finally
		{
			disableSaving = false;
		}
	}
	
	public void save(HackList hax)
	{
		if(disableSaving)
			return;
		
		try
		{
			net.minecraft.client.Minecraft mc = net.wurstclient.WurstClient.MC;
			if(mc != null
				&& mc.screen instanceof net.minecraft.client.gui.screens.DisconnectedScreen)
				return;
		}catch(Throwable ignored)
		{}
		
		JsonArray json = createJson(hax);
		
		try
		{
			JsonUtils.toJson(json, path);
			
		}catch(IOException | JsonException e)
		{
			System.out.println("Couldn't save " + path.getFileName());
			e.printStackTrace();
		}
	}
	
	public void saveProfile(HackList hax, Path profilePath)
		throws IOException, JsonException
	{
		if(!profilePath.getFileName().toString().endsWith(".json"))
			throw new IllegalArgumentException();
		
		JsonArray json = createJson(hax);
		Files.createDirectories(profilePath.getParent());
		JsonUtils.toJson(json, profilePath);
	}
	
	private JsonArray createJson(HackList hax)
	{
		Stream<Hack> enabledHax = hax.getAllHax().stream()
			.filter(Hack::isEnabled).filter(Hack::isStateSaved);
		
		JsonArray json = new JsonArray();
		enabledHax.map(Hack::getName).forEach(name -> json.add(name));
		
		return json;
	}
}
