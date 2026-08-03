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
	}
	
	public boolean matches(ItemStack stack)
	{
		return configured && !full && !unreachable && !temporarilyUnavailable
			&& filters.stream().anyMatch(f -> f.matches(stack));
	}
	
	public int specificity(ItemStack stack)
	{
		return filters.stream().filter(f -> f.matches(stack))
			.mapToInt(ItemFilter::specificity).max().orElse(-1);
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
