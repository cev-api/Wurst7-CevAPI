/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.google.gson.JsonObject;

import net.wurstclient.util.json.JsonException;
import net.wurstclient.util.json.JsonUtils;
import net.wurstclient.util.json.WsonArray;
import net.wurstclient.util.json.WsonObject;

/**
 * Resolves a Minecraft player name to that player's skin texture (as raw PNG
 * bytes), using Mojang's public web APIs.
 */
public final class CustomTotemSkinFetcher
{
	private static final String PROFILE_URL =
		"https://api.mojang.com/users/profiles/minecraft/";
	private static final String SESSION_URL =
		"https://sessionserver.mojang.com/session/minecraft/profile/";
	
	private CustomTotemSkinFetcher()
	{}
	
	/**
	 * Fetches the skin of the given player as PNG bytes.
	 *
	 * @throws IOException
	 *             if the player doesn't exist, has no skin, or the network
	 *             request fails.
	 * @throws JsonException
	 *             if the Mojang API returns malformed data.
	 */
	public static byte[] fetchSkin(String playerName)
		throws IOException, JsonException
	{
		String name = playerName.trim();
		
		// 1. Resolve the player name to a UUID.
		String encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8);
		WsonObject profile =
			JsonUtils.parseURLToObject(PROFILE_URL + encodedName);
		String uuid = profile.getString("id");
		
		// 2. Ask the session server for the player's texture property.
		WsonObject session =
			JsonUtils.parseURLToObject(SESSION_URL + uuid + "?unsigned=false");
		WsonArray properties = session.getArray("properties");
		
		String texturesBase64 = null;
		for(WsonObject property : properties.getAllObjects())
		{
			if("textures".equals(property.getString("name", "")))
			{
				texturesBase64 = property.getString("value");
				break;
			}
		}
		if(texturesBase64 == null || texturesBase64.isEmpty())
			throw new IOException(
				"Player \"" + name + "\" doesn't have a skin.");
		
		// 3. Decode the base64 "textures" payload to find the skin URL.
		String decoded;
		try
		{
			byte[] bytes = Base64.getDecoder().decode(texturesBase64);
			decoded = new String(bytes, StandardCharsets.UTF_8);
			
		}catch(IllegalArgumentException e)
		{
			throw new IOException(
				"Malformed texture data for \"" + name + "\".", e);
		}
		
		JsonObject json = JsonUtils.GSON.fromJson(decoded, JsonObject.class);
		if(json == null)
			throw new IOException(
				"Malformed texture data for \"" + name + "\".");
		
		String skinUrl = new WsonObject(json).getObject("textures")
			.getObject("SKIN").getString("url");
		
		// 4. Download the skin image.
		try(InputStream in = URI.create(skinUrl).toURL().openStream())
		{
			return in.readAllBytes();
		}
	}
}
