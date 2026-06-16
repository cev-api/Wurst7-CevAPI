/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.chestsearch;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.wurstclient.WurstClient;

public final class ChestSearchItemStacks
{
	private ChestSearchItemStacks()
	{}
	
	public static JsonElement encode(ItemStack stack)
	{
		if(stack == null || stack.isEmpty() || WurstClient.MC.player == null)
			return null;
		
		try
		{
			return ItemStack.CODEC
				.encodeStart(WurstClient.MC.player.registryAccess()
					.createSerializationContext(JsonOps.INSTANCE), stack)
				.result().orElse(null);
		}catch(Throwable t)
		{
			return null;
		}
	}
	
	public static ItemStack decode(ChestEntry.ItemEntry item)
	{
		if(item == null)
			return ItemStack.EMPTY;
		
		if(item.nbt != null && WurstClient.MC.player != null)
			try
			{
				ItemStack stack = ItemStack.CODEC
					.decode(
						WurstClient.MC.player.registryAccess()
							.createSerializationContext(JsonOps.INSTANCE),
						item.nbt)
					.result().map(pair -> pair.getFirst())
					.orElse(ItemStack.EMPTY);
				if(!stack.isEmpty())
				{
					stack.setCount(Math.max(1, item.count));
					return stack;
				}
			}catch(Throwable ignored)
			{}
		
		try
		{
			Identifier id = Identifier.tryParse(item.itemId);
			if(id == null)
				return ItemStack.EMPTY;
			Item mcItem = BuiltInRegistries.ITEM.getValue(id);
			if(mcItem == null)
				return ItemStack.EMPTY;
			return new ItemStack(mcItem, Math.max(1, item.count));
		}catch(Throwable t)
		{
			return ItemStack.EMPTY;
		}
	}
}
