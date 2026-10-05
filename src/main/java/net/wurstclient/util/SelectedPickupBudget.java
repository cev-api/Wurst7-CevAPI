/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.ItemStack;

/**
 * Keeps pre-session inventory and explicitly confirmed pickups, by components.
 */
public final class SelectedPickupBudget
{
	private static final class Entry
	{
		final ItemStack prototype;
		int allowed;
		
		Entry(ItemStack stack, int count)
		{
			prototype = stack.copyWithCount(1);
			allowed = count;
		}
	}
	
	private final List<Entry> entries = new ArrayList<>();
	private List<ItemStack> previous = List.of();
	
	public void clear()
	{
		entries.clear();
		previous = List.of();
	}
	
	public void start(List<ItemStack> inventory)
	{
		clear();
		for(ItemStack stack : inventory)
			allow(stack, stack.getCount());
		rememberInventory(inventory);
	}
	
	public void allow(ItemStack stack, int count)
	{
		if(stack.isEmpty() || count <= 0)
			return;
		for(Entry entry : entries)
			if(ItemStack.isSameItemSameComponents(entry.prototype, stack))
			{
				entry.allowed += count;
				return;
			}
		entries.add(new Entry(stack, count));
	}
	
	public void observeInventory(List<ItemStack> inventory)
	{
		for(Entry entry : entries)
		{
			int loss = count(previous, entry.prototype)
				- count(inventory, entry.prototype);
			if(loss > 0)
				entry.allowed = Math.max(0, entry.allowed - loss);
		}
		rememberInventory(inventory);
	}
	
	/**
	 * Record our own rejection without reducing the player's original
	 * allowance.
	 */
	public void rememberInventory(List<ItemStack> inventory)
	{
		previous = inventory.stream().map(ItemStack::copy).toList();
	}
	
	public int excess(List<ItemStack> inventory, ItemStack stack)
	{
		int allowed = 0;
		for(Entry entry : entries)
			if(ItemStack.isSameItemSameComponents(entry.prototype, stack))
				allowed += entry.allowed;
		return Math.max(0, count(inventory, stack) - allowed);
	}
	
	private int count(List<ItemStack> inventory, ItemStack stack)
	{
		return inventory.stream()
			.filter(s -> ItemStack.isSameItemSameComponents(s, stack))
			.mapToInt(ItemStack::getCount).sum();
	}
}
