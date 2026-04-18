/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import java.util.HashMap;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.authlib.minecraft.MinecraftProfileTextures;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.entity.player.PlayerSkin;
import net.wurstclient.WurstClient;
import net.wurstclient.util.json.JsonUtils;
import net.wurstclient.util.json.WsonObject;

@Mixin(SkinManager.class)
public abstract class SkinManagerMixin
{
	@Unique
	private static final String WURST_CAPES_URL =
		"https://www.wurstclient.net/api/v1/capes.json";
	
	@Unique
	private static final String FORK_CAPES_URL =
		"https://gist.github.com/cev-api/dc3a20eb270a679d172724989f9e6d44/raw/capes.json";
	
	@Unique
	private static HashMap<String, String> capes;
	
	@Unique
	private MinecraftProfileTexture currentCape;
	
	@Inject(
		method = "registerTextures(Ljava/util/UUID;Lcom/mojang/authlib/minecraft/MinecraftProfileTextures;)Ljava/util/concurrent/CompletableFuture;",
		at = @At("HEAD"))
	private void onFetchSkinTextures(UUID uuid,
		MinecraftProfileTextures textures,
		CallbackInfoReturnable<CompletableFuture<PlayerSkin>> cir)
	{
		String uuidString = uuid.toString();
		
		try
		{
			if(!WurstClient.INSTANCE.getOtfs().wurstCapesOtf.isEnabled())
			{
				currentCape = null;
				return;
			}
			
			if(capes == null)
				setupCapeMap();
			
			if(capes.containsKey(uuidString))
			{
				String capeURL = capes.get(uuidString);
				currentCape = new MinecraftProfileTexture(capeURL, null);
				
			}else
				currentCape = null;
			
		}catch(Exception e)
		{
			System.err
				.println("[Wurst] Failed to load cape for UUID " + uuidString);
			
			e.printStackTrace();
		}
	}
	
	@ModifyVariable(
		method = "registerTextures(Ljava/util/UUID;Lcom/mojang/authlib/minecraft/MinecraftProfileTextures;)Ljava/util/concurrent/CompletableFuture;",
		at = @At("STORE"),
		ordinal = 1,
		name = "minecraftProfileTexture2")
	private MinecraftProfileTexture modifyCapeTexture(
		MinecraftProfileTexture old)
	{
		if(currentCape == null)
			return old;
		
		MinecraftProfileTexture result = currentCape;
		currentCape = null;
		return result;
	}
	
	@Unique
	private void setupCapeMap()
	{
		try
		{
			// assign map first to prevent endless retries if download fails
			capes = new HashMap<>();
			loadCapeSource("Wurst",
				JsonUtils.parseURLToObject(WURST_CAPES_URL));
			loadForkCapeSource();
			
		}catch(Exception e)
		{
			System.err.println("[Wurst] Failed to load cape maps!");
			
			e.printStackTrace();
		}
	}
	
	@Unique
	private void loadCapeSource(String sourceName, WsonObject rawCapes)
	{
		Pattern uuidPattern = Pattern.compile(
			"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
		
		for(Entry<String, String> entry : rawCapes.getAllStrings().entrySet())
		{
			String name = entry.getKey();
			String capeURL = entry.getValue();
			
			// check if name is already a UUID
			if(uuidPattern.matcher(name).matches())
			{
				capes.put(name, capeURL);
				continue;
			}
			
			// convert names to offline UUIDs so the same list can be shared
			// between cracked/offline and online-style account names.
			String offlineUUID = "" + UUIDUtil.createOfflinePlayerUUID(name);
			capes.put(offlineUUID, capeURL);
		}
		
		System.out.println("[Wurst] Loaded " + rawCapes.getAllStrings().size()
			+ " cape entries from " + sourceName + ".");
	}
	
	@Unique
	private void loadForkCapeSource()
	{
		String forkCapesUrl = FORK_CAPES_URL;
		if(forkCapesUrl == null || forkCapesUrl.isBlank())
			return;
		
		try
		{
			loadCapeSource("fork", JsonUtils.parseURLToObject(forkCapesUrl));
			
		}catch(Exception e)
		{
			System.err.println(
				"[Wurst] Failed to load fork capes from " + forkCapesUrl + "!");
			e.printStackTrace();
		}
	}
}
