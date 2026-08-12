/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.lootsorter;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.ItemStack;

/** A container's ordered filters and its explicit tie-break priority. */
public final class DestinationRule
{
	private final LogicalContainer container;
	private final List<ItemFilter> filters = new ArrayList<>();
	private int priority;
	private boolean full;
	private boolean unreachable;
	private boolean temporarilyUnavailable;
	private boolean configured;
	private List<ItemStack> observedContents = List.of();
	private ItemStack frameItem = ItemStack.EMPTY;
	private ItemStack autosortFamilyItem = ItemStack.EMPTY;
	
	public DestinationRule(LogicalContainer container, int priority)
	{
		this.container = container;
		this.priority = priority;
	}
	
	public LogicalContainer getContainer()
	{
		return container;
	}
	
	public List<ItemFilter> getFilters()
	{
		return List.copyOf(filters);
	}
	
	public void addFilter(ItemFilter filter)
	{
		if(filter != null)
			filters.add(filter);
	}
	
	public void clearFilters()
	{
		filters.clear();
		autosortFamilyItem = ItemStack.EMPTY;
	}
	
	public boolean matches(ItemStack stack)
	{
		if(isAutosort())
			return configured && !full && !unreachable
				&& !temporarilyUnavailable && autosortMatches(stack);
		return configured && !full && !unreachable && !temporarilyUnavailable
			&& filters.stream().anyMatch(f -> f.matches(stack));
	}
	
	public int specificity(ItemStack stack)
	{
		if(isAutosort() && autosortMatches(stack))
			return 60;
		return filters.stream().filter(f -> f.matches(stack))
			.mapToInt(ItemFilter::specificity).max().orElse(-1);
	}
	
	public boolean isAutosort()
	{
		return filters.stream().anyMatch(f -> f == BuiltInItemFilter.AUTOSORT
			|| f == BuiltInItemFilter.AUTOSORT_FRAMES);
	}
	
	public boolean isFrameAutosort()
	{
		return filters.stream()
			.anyMatch(f -> f == BuiltInItemFilter.AUTOSORT_FRAMES);
	}
	
	public void setFrameItem(ItemStack item)
	{
		frameItem =
			item == null || item.isEmpty() ? ItemStack.EMPTY : item.copy();
	}
	
	public ItemStack getFrameItem()
	{
		return frameItem.copy();
	}
	
	public void setAutosortFamilyItem(ItemStack item)
	{
		autosortFamilyItem =
			item == null || item.isEmpty() ? ItemStack.EMPTY : item.copy();
	}
	
	/**
	 * Updates the live contents used by Autosort; stacks are copied
	 * defensively.
	 */
	public void setObservedContents(List<ItemStack> contents)
	{
		observedContents = contents == null ? List.of()
			: contents.stream()
				.filter(stack -> stack != null && !stack.isEmpty())
				.map(ItemStack::copy).toList();
	}
	
	public List<ItemStack> getObservedContents()
	{
		return observedContents.stream().map(ItemStack::copy).toList();
	}
	
	/** Returns the broad category used to keep similar items together. */
	public String autosortCategory(ItemStack stack)
	{
		if(stack == null || stack.isEmpty())
			return "empty";
		BuiltInItemFilter[] categories = {BuiltInItemFilter.WEAPONS,
			BuiltInItemFilter.TOOLS, BuiltInItemFilter.ARMOUR,
			BuiltInItemFilter.REDSTONE, BuiltInItemFilter.TRANSPORTATION,
			BuiltInItemFilter.UTILITY_ITEMS, BuiltInItemFilter.ENCHANTED_BOOKS,
			BuiltInItemFilter.POTIONS, BuiltInItemFilter.FOOD,
			BuiltInItemFilter.MOB_DROPS, BuiltInItemFilter.ORES_AND_MATERIALS,
			BuiltInItemFilter.FARMING, BuiltInItemFilter.STONE_LIKE,
			BuiltInItemFilter.WOOD, BuiltInItemFilter.NATURAL_BLOCKS,
			BuiltInItemFilter.BUILDING_BLOCKS,
			BuiltInItemFilter.DECORATIVE_ITEMS,
			BuiltInItemFilter.MISCELLANEOUS};
		for(BuiltInItemFilter category : categories)
			if(category.matches(stack))
				return category.name();
		return BuiltInItemFilter.MISCELLANEOUS.name();
	}
	
	public boolean autosortMatches(ItemStack stack)
	{
		if(!isAutosort() || stack == null || stack.isEmpty())
			return false;
		if(isFrameAutosort())
			return !frameItem.isEmpty() && ItemFamily.matches(frameItem, stack);
		if(!autosortFamilyItem.isEmpty())
			return ItemFamily.matches(autosortFamilyItem, stack);
		if(observedContents.isEmpty())
			return true;
		String category = autosortCategory(stack);
		return observedContents.stream()
			.anyMatch(existing -> autosortCategory(existing).equals(category));
	}
	
	public String routingKey(ItemStack stack)
	{
		if(!autosortFamilyItem.isEmpty())
			return ItemFamily.of(stack);
		return isAutosort() ? autosortCategory(stack) : "normal";
	}
	
	public int getPriority()
	{
		return priority;
	}
	
	public void setPriority(int priority)
	{
		this.priority = priority;
	}
	
	public boolean isFull()
	{
		return full;
	}
	
	public void setFull(boolean full)
	{
		this.full = full;
	}
	
	public boolean isUnreachable()
	{
		return unreachable;
	}
	
	public void setUnreachable(boolean unreachable)
	{
		this.unreachable = unreachable;
	}
	
	public boolean isTemporarilyUnavailable()
	{
		return temporarilyUnavailable;
	}
	
	public void setTemporarilyUnavailable(boolean temporarilyUnavailable)
	{
		this.temporarilyUnavailable = temporarilyUnavailable;
	}
	
	public boolean isConfigured()
	{
		return configured;
	}
	
	public void setConfigured(boolean configured)
	{
		this.configured = configured;
	}
}
