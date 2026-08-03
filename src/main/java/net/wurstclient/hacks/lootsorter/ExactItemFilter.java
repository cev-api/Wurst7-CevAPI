/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.lootsorter;

import net.minecraft.world.item.ItemStack;

/** Exact stack identity is deliberately more specific than every preset. */
public final class ExactItemFilter implements ItemFilter
{
	private final ItemStack exemplar;
	private final ItemStackEquivalenceKey key;
	private final String name;
	
	public ExactItemFilter(ItemStack stack)
	{
		this(stack, stack.getHoverName().getString());
	}
	
	public ExactItemFilter(ItemStack stack, String name)
	{
		exemplar = stack.copyWithCount(1);
		key = ItemStackEquivalenceKey.of(exemplar);
		this.name = name;
	}
	
	public ExactItemFilter(ItemStackEquivalenceKey key, String name)
	{
		this.key = key;
		ItemStack reconstructed = new ItemStack(key.item());
		reconstructed.applyComponents(key.components());
		exemplar = reconstructed;
		this.name = name;
	}
	
	@Override
	public boolean matches(ItemStack stack)
	{
		return !stack.isEmpty()
			&& key.equals(ItemStackEquivalenceKey.of(stack));
	}
	
	@Override
	public int specificity()
	{
		return 100;
	}
	
	@Override
	public String getDisplayName()
	{
		return name;
	}
	
	public ItemStackEquivalenceKey key()
	{
		return key;
	}
	
	public ItemStack exemplar()
	{
		return exemplar.copy();
	}
}
