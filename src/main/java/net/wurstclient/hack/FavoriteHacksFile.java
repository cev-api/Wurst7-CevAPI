/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hack;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import com.google.gson.JsonArray;

import net.wurstclient.util.json.JsonException;
import net.wurstclient.util.json.JsonUtils;
import net.wurstclient.util.json.WsonArray;

public final class FavoriteHacksFile
{
	private final Path path;
	private boolean disableSaving;
	
	public FavoriteHacksFile(Path path)
	{
		this.path = path;
	}
	
	public void load(HackList hackList)
	{
		try
		{
			WsonArray wson = JsonUtils.parseFileToArray(path);
			applyFavorites(hackList, wson);
			
		}catch(NoSuchFileException e)
		{
			// file doesn't exist yet
		}catch(IOException | JsonException e)
		{
			System.out.println("Couldn't load " + path.getFileName());
			e.printStackTrace();
		}
		
		save(hackList);
	}
	
	private void applyFavorites(HackList hax, WsonArray wson)
	{
		try
		{
			disableSaving = true;
			for(Hack hack : hax.getAllHax())
				hack.setFavorite(false);
			
			for(String name : wson.getAllStrings())
			{
				Hack hack = hax.getHackByName(name);
				if(hack == null)
					continue;
				hack.setFavorite(true);
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
	
	private JsonArray createJson(HackList hax)
	{
		JsonArray json = new JsonArray();
		hax.getAllHax().stream().filter(Hack::isFavorite).map(Hack::getName)
			.forEach(name -> json.add(name));
		return json;
	}
}
