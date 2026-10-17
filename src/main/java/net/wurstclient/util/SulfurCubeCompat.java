/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.util.function.Predicate;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Compatibility helpers for the sulfur cube, a mob that only exists in
 * Minecraft 26.3 and newer.
 *
 * <p>
 * This client is built against 1.21.11, where {@code SulfurCube} does not
 * exist, so the mob is detected and queried reflectively. In practice sulfur
 * cubes are only reachable while playing on a 26.3+ server through
 * ViaFabricPlus, and even then ViaFabricPlus may translate them into a
 * different entity type. Everything here therefore degrades to "not a sulfur
 * cube" instead of throwing.
 */
public enum SulfurCubeCompat
{
	;
	
	private static final String SULFUR_CUBE_CLASS_NAME =
		"net.minecraft.world.entity.monster.cubemob.SulfurCube";
	
	private static final TagKey<Item> SULFUR_CUBE_FOOD =
		TagKey.create(Registries.ITEM,
			Identifier.fromNamespaceAndPath("minecraft", "sulfur_cube_food"));
	
	public static boolean isSulfurCube(Entity entity)
	{
		return entity != null && hasClassNamed(entity, SULFUR_CUBE_CLASS_NAME);
	}
	
	/**
	 * Returns true if the given entity is a baby sulfur cube that can be fed
	 * with the given stack.
	 *
	 * <p>
	 * Only meaningful on 26.3+ servers. Returns false everywhere else.
	 */
	public static boolean canFeed(Entity entity, ItemStack stack)
	{
		if(!isSulfurCube(entity) || stack.isEmpty())
			return false;
		
		if(!invokeBoolean(entity, "canAgeUp"))
			return false;
		
		return stack.is(SULFUR_CUBE_FOOD);
	}
	
	/**
	 * Returns a filter that only accepts entities whose class (or one of its
	 * superclasses) matches the given binary name.
	 */
	public static Predicate<Entity> isInstanceOf(String binaryName)
	{
		return entity -> hasClassNamed(entity, binaryName);
	}
	
	private static boolean hasClassNamed(Entity entity, String binaryName)
	{
		for(Class<?> type = entity.getClass(); type != null; type =
			type.getSuperclass())
			if(type.getName().equals(binaryName))
				return true;
			
		return false;
	}
	
	private static boolean invokeBoolean(Entity entity, String method)
	{
		try
		{
			return Boolean.TRUE
				.equals(entity.getClass().getMethod(method).invoke(entity));
		}catch(ReflectiveOperationException | RuntimeException e)
		{
			return false;
		}
	}
}
