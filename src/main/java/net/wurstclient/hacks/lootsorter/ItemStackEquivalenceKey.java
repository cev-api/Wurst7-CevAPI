/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.lootsorter;

import net.minecraft.core.component.DataComponentMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * The component map is deliberately included in full. On 26.2 it covers
 * damage, enchantments, names and all custom component/NBT-backed data. A
 * copied stack prevents later slot mutations from changing an existing key.
 */
public record ItemStackEquivalenceKey(Item item, DataComponentMap components)
{
	public static ItemStackEquivalenceKey of(ItemStack stack)
	{
		if(stack == null || stack.isEmpty())
			throw new IllegalArgumentException(
				"Cannot create a key for an empty stack.");
		ItemStack copy = stack.copy();
		return new ItemStackEquivalenceKey(copy.getItem(),
			copy.getComponents());
	}
}
