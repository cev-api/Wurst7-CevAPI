/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.lang.reflect.Method;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.Services;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.StringUtil;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import org.jetbrains.annotations.Nullable;

public final class OwnerResolver
{
	public static final String DUMMY_NAME = "???";
	
	private static final java.util.Set<UUID> FETCHING =
		java.util.concurrent.ConcurrentHashMap.newKeySet();
	private static final java.util.concurrent.ConcurrentHashMap<UUID, String> FETCHED =
		new java.util.concurrent.ConcurrentHashMap<>();
	
	private OwnerResolver()
	{}
	
	@Nullable
	public static UUID getOwnerUuid(@Nullable Entity entity)
	{
		return getOwnerUuid(entity, true);
	}
	
	@Nullable
	public static UUID getOwnerUuid(@Nullable Entity entity,
		boolean includeProjectiles)
	{
		if(entity == null)
			return null;
		
		if(entity instanceof TamableAnimal tameable)
			return getOwnerReferenceUuid(tameable);
		if(entity instanceof AbstractHorse horse)
		{
			UUID uuid = invokeUuidMethod(horse, "getOwnerUUID", "getOwnerId");
			if(uuid != null)
				return uuid;
			return getOwnerReferenceUuid(horse);
		}
		if(includeProjectiles && entity instanceof Projectile projectile)
		{
			Entity owner = projectile.getOwner();
			if(owner != null)
				return owner.getUUID();
		}
		if(entity instanceof OwnableEntity ownableEntity)
			return getOwnerReferenceUuid(ownableEntity);
		return getOwnerReferenceUuid(entity);
	}
	
	private static UUID getOwnerReferenceUuid(Object entity)
	{
		if(entity == null)
			return null;
		
		for(String methodName : new String[]{"getOwnerReference",
			"getOwnerUUID", "getOwnerId"})
		{
			try
			{
				Method method = entity.getClass().getMethod(methodName);
				Object value = method.invoke(entity);
				UUID uuid = uuidFrom(value);
				if(uuid != null)
					return uuid;
			}catch(ReflectiveOperationException ignored)
			{}
		}
		return null;
	}
	
	private static UUID invokeUuidMethod(Object value, String... methodNames)
	{
		if(value == null)
			return null;
		
		for(String methodName : methodNames)
		{
			try
			{
				Method method = value.getClass().getMethod(methodName);
				Object result = method.invoke(value);
				UUID uuid = uuidFrom(result);
				if(uuid != null)
					return uuid;
			}catch(ReflectiveOperationException ignored)
			{}
		}
		return null;
	}
	
	private static UUID uuidFrom(@Nullable Object value)
	{
		if(value instanceof UUID uuid)
			return uuid;
		if(value == null)
			return null;
		
		for(String methodName : new String[]{"uuid", "id", "getUuid",
			"getUUID"})
		{
			try
			{
				Method method = value.getClass().getMethod(methodName);
				Object result = method.invoke(value);
				if(result instanceof UUID uuid)
					return uuid;
			}catch(ReflectiveOperationException ignored)
			{}
		}
		
		return null;
	}
	
	public static boolean shouldFetchFromServer(@Nullable UUID uuid)
	{
		String name = lookupPlayerName(uuid);
		return !isResolvablePlayerName(name);
	}
	
	public static boolean isResolvablePlayerName(@Nullable String name)
	{
		return name != null && !name.isBlank() && !DUMMY_NAME.equals(name);
	}
	
	@Nullable
	public static String lookupPlayerName(@Nullable UUID uuid)
	{
		if(uuid == null)
			return null;
		
		Services services = Minecraft.getInstance().services();
		String name = get(uuid, services);
		ClientLevel level = Minecraft.getInstance().level;
		if(level != null)
		{
			Player player = level.getPlayerByUUID(uuid);
			if(name != null && player != null
				&& StringUtil.isValidPlayerName(player.getPlainTextName())
				&& !player.getPlainTextName().equals(name))
				services.nameToIdCache().add(player.nameAndId());
			if(name == null && player != null)
				return player.getPlainTextName();
		}
		return name;
	}
	
	@Nullable
	public static String get(@Nullable UUID uuid, Services services)
	{
		if(uuid == null)
			return null;
		if(FETCHED.containsKey(uuid))
			return FETCHED.get(uuid);
		
		String name = services.nameToIdCache().get(uuid).map(NameAndId::name)
			.orElse(null);
		if(name != null)
		{
			FETCHED.put(uuid, name);
			return name;
		}
		
		if(!FETCHING.add(uuid))
			return null;
		
		java.util.concurrent.CompletableFuture.runAsync(() -> services
			.profileResolver().fetchById(uuid).ifPresentOrElse(profile -> {
				FETCHED.put(uuid, profile.name());
				FETCHING.remove(uuid);
			}, () -> FETCHED.put(uuid, DUMMY_NAME)), Util.backgroundExecutor());
		return null;
	}
}
