/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;

public enum RegistryUtils
{
	;
	
	public static EntityType<?> entityType(String id)
	{
		return BuiltInRegistries.ENTITY_TYPE
			.getValue(Identifier.parse("minecraft:" + id));
	}
	
	public static Item item(String id)
	{
		return BuiltInRegistries.ITEM
			.getValue(Identifier.parse("minecraft:" + id));
	}
	
	public static Block block(String id)
	{
		return BuiltInRegistries.BLOCK
			.getValue(Identifier.parse("minecraft:" + id));
	}
	
	public static BlockEntityType<?> blockEntityType(String id)
	{
		return BuiltInRegistries.BLOCK_ENTITY_TYPE
			.getValue(Identifier.parse("minecraft:" + id));
	}
}
