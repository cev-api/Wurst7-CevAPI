/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.altmanager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Map.Entry;

import com.google.gson.JsonObject;

import net.wurstclient.util.json.JsonException;
import net.wurstclient.util.json.JsonUtils;
import net.wurstclient.util.json.WsonObject;

public final class AltsFile
{
	private static final String SETTINGS_KEY = "__wurst_altmanager_settings";
	private static final String DISCONNECT_RANDOM_ALT_RECONNECT_KEY =
		"disconnect_random_alt_reconnect";
	
	private final Path path;
	private final Path encFolder;
	private boolean disableSaving;
	private Encryption encryption;
	private IOException folderException;
	
	public AltsFile(Path path, Path encFolder)
	{
		this.path = path;
		this.encFolder = encFolder;
	}
	
	public void load(AltManager altManager)
	{
		try
		{
			if(encryption == null)
				encryption = new Encryption(encFolder);
			
		}catch(IOException e)
		{
			System.out.println("Couldn't create '.Wurst encryption' folder.");
			e.printStackTrace();
			folderException = e;
			return;
		}
		
		try
		{
			WsonObject wson = encryption.parseFileToObject(path);
			loadAlts(wson, altManager);
			
		}catch(NoSuchFileException e)
		{
			// The file doesn't exist yet. No problem, we'll create it later.
			
		}catch(IOException | JsonException e)
		{
			System.out.println("Couldn't load " + path.getFileName());
			e.printStackTrace();
			
			renameCorrupted();
		}
		
		save(altManager);
	}
	
	private void renameCorrupted()
	{
		try
		{
			Path newPath =
				path.resolveSibling("!CORRUPTED_" + path.getFileName());
			Files.move(path, newPath, StandardCopyOption.REPLACE_EXISTING);
			System.out.println("Renamed to " + newPath.getFileName());
			
		}catch(IOException e)
		{
			System.out.println(
				"Couldn't rename corrupted file " + path.getFileName());
			e.printStackTrace();
		}
	}
	
	private void loadAlts(WsonObject wson, AltManager altManager)
	{
		ArrayList<Alt> alts = parseJson(wson);
		boolean disconnectRandomAltReconnect = readDisconnectSetting(wson);
		
		try
		{
			disableSaving = true;
			altManager.setDisconnectRandomAltReconnectEnabledSilently(
				disconnectRandomAltReconnect);
			altManager.addAll(alts);
			
		}finally
		{
			disableSaving = false;
		}
	}
	
	private boolean readDisconnectSetting(WsonObject wson)
	{
		if(!wson.has(SETTINGS_KEY))
			return true;
		
		try
		{
			return wson.getObject(SETTINGS_KEY)
				.getBoolean(DISCONNECT_RANDOM_ALT_RECONNECT_KEY, true);
			
		}catch(JsonException e)
		{
			return true;
		}
	}
	
	public static ArrayList<Alt> parseJson(WsonObject wson)
	{
		ArrayList<Alt> alts = new ArrayList<>();
		
		for(Entry<String, JsonObject> e : wson.getAllJsonObjects().entrySet())
		{
			String nameOrEmail = e.getKey();
			if(nameOrEmail.isEmpty())
				continue;
			if(SETTINGS_KEY.equals(nameOrEmail))
				continue;
			
			JsonObject jsonAlt = e.getValue();
			alts.add(loadAlt(nameOrEmail, jsonAlt));
		}
		
		return alts;
	}
	
	private static Alt loadAlt(String nameOrEmail, JsonObject jsonAlt)
	{
		String type = JsonUtils.getAsString(jsonAlt.get("type"), "");
		boolean starred = JsonUtils.getAsBoolean(jsonAlt.get("starred"), false);
		
		if("token".equalsIgnoreCase(type))
		{
			String token = JsonUtils.getAsString(jsonAlt.get("token"), "");
			String refreshToken =
				JsonUtils.getAsString(jsonAlt.get("refresh_token"), "");
			String name = JsonUtils.getAsString(jsonAlt.get("name"), "");
			
			if(!token.isEmpty() || !refreshToken.isEmpty())
				return new TokenAlt(token, refreshToken, name, starred);
			
			return new CrackedAlt(nameOrEmail, starred);
		}
		
		String password = JsonUtils.getAsString(jsonAlt.get("password"), "");
		
		if(password.isEmpty())
			return new CrackedAlt(nameOrEmail, starred);
		
		String name = JsonUtils.getAsString(jsonAlt.get("name"), "");
		return new MojangAlt(nameOrEmail, password, name, starred);
	}
	
	public void save(AltManager alts)
	{
		if(disableSaving)
			return;
		
		try
		{
			if(encryption == null)
				encryption = new Encryption(encFolder);
			
		}catch(IOException e)
		{
			System.out.println("Couldn't create '.Wurst encryption' folder.");
			e.printStackTrace();
			folderException = e;
			return;
		}
		
		JsonObject json = createJson(alts);
		
		try
		{
			encryption.toEncryptedJson(json, path);
			
		}catch(IOException | JsonException e)
		{
			System.out.println("Couldn't save " + path.getFileName());
			e.printStackTrace();
		}
	}
	
	public static JsonObject createJson(AltManager alts)
	{
		JsonObject json = new JsonObject();
		JsonObject settings = new JsonObject();
		settings.addProperty(DISCONNECT_RANDOM_ALT_RECONNECT_KEY,
			alts.isDisconnectRandomAltReconnectEnabled());
		json.add(SETTINGS_KEY, settings);
		
		for(Alt alt : alts.getList())
			alt.exportAsJson(json);
		
		return json;
	}
	
	public IOException getFolderException()
	{
		return folderException;
	}
}
