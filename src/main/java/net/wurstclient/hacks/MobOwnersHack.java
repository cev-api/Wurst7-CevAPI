/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.player.Player;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;

@SearchTags({"mob owners", "pet owners", "projectile owners"})
public final class MobOwnersHack extends Hack
{
	private final CheckboxSetting projectiles =
		new CheckboxSetting("Projectiles", false);
	private final CheckboxSetting showUuidWhenUnknown = new CheckboxSetting(
		"Show UUID when unknown",
		"Shows the owner's shortened UUID when the player name is not known.",
		true);
	
	private final Map<UUID, String> uuidNameCache = new HashMap<>();
	
	public MobOwnersHack()
	{
		super("MobOwners",
			"Shows which player owns tamed mobs, horses, and optionally projectiles.",
			false);
		setCategory(Category.RENDER);
		addSetting(projectiles);
		addSetting(showUuidWhenUnknown);
	}
	
	public Component addOwnerInfo(Entity entity, Component original)
	{
		if(!isEnabled())
			return original;
		
		UUID owner = getOwnerUuid(entity);
		if(owner == null)
			return original;
		
		String name = resolveOwnerName(owner);
		if(name == null)
		{
			if(!showUuidWhenUnknown.isChecked())
				return original;
			String raw = owner.toString();
			name = raw.substring(0, Math.min(8, raw.length()));
		}
		
		Component base = original == null ? entity.getName() : original;
		return base.copy().append(Component.literal(" owned by " + name)
			.withStyle(ChatFormatting.GRAY));
	}
	
	private UUID getOwnerUuid(Entity entity)
	{
		if(entity instanceof TamableAnimal tameable)
			return getOwnerReferenceUuid(tameable);
		if(entity instanceof AbstractHorse horse)
			return getHorseOwner(horse);
		if(projectiles.isChecked() && entity instanceof Projectile projectile)
		{
			Entity owner = projectile.getOwner();
			return owner != null ? owner.getUUID() : null;
		}
		
		return null;
	}
	
	private UUID getHorseOwner(AbstractHorse horse)
	{
		for(String methodName : new String[]{"getOwnerUUID", "getOwnerId"})
		{
			try
			{
				Method method = horse.getClass().getMethod(methodName);
				Object value = method.invoke(horse);
				if(value instanceof UUID uuid)
					return uuid;
			}catch(ReflectiveOperationException ignored)
			{}
		}
		
		return getOwnerReferenceUuid(horse);
	}
	
	private UUID getOwnerReferenceUuid(Entity entity)
	{
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
	
	private UUID uuidFrom(Object value)
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
	
	private String resolveOwnerName(UUID owner)
	{
		String cached = uuidNameCache.get(owner);
		if(cached != null)
			return cached;
		
		if(MC.level != null)
			for(Player player : MC.level.players())
				if(owner.equals(player.getUUID()))
				{
					String name = player.getName().getString();
					uuidNameCache.put(owner, name);
					return name;
				}
			
		return null;
	}
}
