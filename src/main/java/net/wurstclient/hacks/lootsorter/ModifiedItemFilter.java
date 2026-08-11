/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.lootsorter;

import net.minecraft.world.item.ItemStack;

/** Adds modifiers without duplicating every category's matching logic. */
public record ModifiedItemFilter(ItemFilter base, ItemFilterModifiers modifiers)
	implements ItemFilter
{
	@Override
	public boolean matches(ItemStack stack)
	{
		return base.matches(stack) && modifiers.matches(stack);
	}
	
	@Override
	public int specificity()
	{
		return base.specificity() + 15;
	}
	
	@Override
	public String getDisplayName()
	{
		return base.getDisplayName() + " (modified)";
	}
}
