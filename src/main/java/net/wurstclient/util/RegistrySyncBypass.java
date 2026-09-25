/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.wurstclient.WurstClient;
import net.wurstclient.mixin.MappedRegistryFrozenAccessor;

/**
 * Removes everything that would make Fabric's registry sync reject a server,
 * so that the client can still join it.
 *
 * Registries that Wurst knows how to fake get placeholders for the server's
 * extra entries, which keeps their raw IDs aligned with the server. Every
 * other registry with unknown entries is left out of the sync completely,
 * because a registry that gets remapped with holes in it can't be remapped
 * again - that would break the client's copy of it for the rest of the
 * session.
 */
public final class RegistrySyncBypass
{
	private static final int MAX_LOGGED_REGISTRIES = 24;
	
	private RegistrySyncBypass()
	{}
	
	public static boolean isEnabled()
	{
		WurstClient wurst = WurstClient.INSTANCE;
		
		if(wurst == null || wurst.getOtfs() == null)
			return false;
		
		return wurst.getOtfs().wurstOptionsOtf.shouldIgnoreRegistrySyncErrors();
	}
	
	/**
	 * Called with the registry data that the server just sent, while the
	 * client is still in the configuration phase. Only modifies the incoming
	 * map, so a disabled bypass changes nothing at all.
	 */
	public static void prepareIncomingMap(
		Map<Identifier, Object2IntMap<Identifier>> registryMap)
	{
		if(!isEnabled() || registryMap == null || registryMap.isEmpty())
			return;
		
		List<Identifier> unknownRegistries = new ArrayList<>();
		List<Identifier> spoofedRegistries = new ArrayList<>();
		List<String> unSyncedRegistries = new ArrayList<>();
		
		Iterator<Map.Entry<Identifier, Object2IntMap<Identifier>>> registries =
			registryMap.entrySet().iterator();
		
		while(registries.hasNext())
		{
			Map.Entry<Identifier, Object2IntMap<Identifier>> entry =
				registries.next();
			Identifier registryId = entry.getKey();
			Object2IntMap<Identifier> remoteIds = entry.getValue();
			Registry<?> registry =
				BuiltInRegistries.REGISTRY.getValue(registryId);
			
			// An unknown registry can't be remapped at all, so the only way
			// past it is to never receive it in the first place.
			if(registry == null)
			{
				unknownRegistries.add(registryId);
				registries.remove();
				continue;
			}
			
			if(remoteIds == null)
			{
				registries.remove();
				continue;
			}
			
			int unknownEntries = countUnknownEntries(registry, remoteIds);
			
			if(unknownEntries == 0)
				continue;
			
			if(spoofUnknownEntries(registry, remoteIds))
			{
				spoofedRegistries.add(registryId);
				continue;
			}
			
			unSyncedRegistries
				.add(registryId + " (" + unknownEntries + " unknown entries)");
			registries.remove();
		}
		
		logResult(unknownRegistries, spoofedRegistries, unSyncedRegistries);
	}
	
	private static int countUnknownEntries(Registry<?> registry,
		Object2IntMap<Identifier> remoteIds)
	{
		int count = 0;
		
		for(Identifier id : remoteIds.keySet())
			if(!registry.containsKey(id))
				count++;
			
		return count;
	}
	
	/**
	 * Registers a placeholder for every entry of the given registry that the
	 * client doesn't have. Returns false if that isn't possible, which makes
	 * the caller leave the whole registry out of the sync.
	 */
	private static boolean spoofUnknownEntries(Registry<?> registry,
		Object2IntMap<Identifier> remoteIds)
	{
		if(registry != BuiltInRegistries.MOB_EFFECT)
			return false;
		
		for(Identifier id : remoteIds.keySet())
		{
			if(registry.containsKey(id))
				continue;
			
			if(!registerMobEffect(id))
				return false;
		}
		
		return true;
	}
	
	/**
	 * Adds an inert stand-in for a mob effect that only the server has. The
	 * client can't know what such an effect does, but having it registered
	 * keeps the effect IDs of both sides identical.
	 */
	private static boolean registerMobEffect(Identifier id)
	{
		MappedRegistry<MobEffect> registry =
			(MappedRegistry<MobEffect>)BuiltInRegistries.MOB_EFFECT;
		MappedRegistryFrozenAccessor frozen =
			(MappedRegistryFrozenAccessor)(Object)registry;
		boolean wasFrozen = frozen.isFrozen();
		
		frozen.setFrozen(false);
		try
		{
			Registry.register(registry, id, new PlaceholderMobEffect());
			return true;
			
		}catch(Throwable e)
		{
			System.out
				.println("[RegistrySync] Couldn't spoof " + id + ": " + e);
			return false;
			
		}finally
		{
			frozen.setFrozen(wasFrozen);
		}
	}
	
	private static void logResult(List<Identifier> unknownRegistries,
		List<Identifier> spoofedRegistries, List<String> unSyncedRegistries)
	{
		if(unknownRegistries.isEmpty() && spoofedRegistries.isEmpty()
			&& unSyncedRegistries.isEmpty())
			return;
		
		System.out.println("[RegistrySync] Bypassing registry sync: "
			+ spoofedRegistries.size() + " registries spoofed, "
			+ unSyncedRegistries.size() + " registries left un-synced, "
			+ unknownRegistries.size() + " unknown registries ignored");
		
		logList("Spoofed unknown entries in ", toNames(spoofedRegistries));
		logList("Left un-synced (its IDs from the server will be wrong): ",
			unSyncedRegistries);
		logList("Ignored unknown registry ", toNames(unknownRegistries));
	}
	
	private static List<String> toNames(List<Identifier> ids)
	{
		List<String> names = new ArrayList<>(ids.size());
		ids.forEach(id -> names.add(id.toString()));
		return names;
	}
	
	private static void logList(String message, List<String> items)
	{
		int limit = Math.min(items.size(), MAX_LOGGED_REGISTRIES);
		
		for(int i = 0; i < limit; i++)
			System.out.println("[RegistrySync] " + message + items.get(i));
		
		if(items.size() > limit)
			System.out.println(
				"[RegistrySync] ...and " + (items.size() - limit) + " more");
	}
	
	private static final class PlaceholderMobEffect extends MobEffect
	{
		private PlaceholderMobEffect()
		{
			super(MobEffectCategory.NEUTRAL, 0xFFFFFF);
		}
	}
}
