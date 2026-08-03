/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.lootsorter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * Tracks quantities, not slots, so client stack merging cannot lose provenance.
 */
public final class InventoryProvenanceLedger
{
	private final Map<ItemStackEquivalenceKey, Integer> protectedAmounts =
		new HashMap<>();
	private final Map<ItemStackEquivalenceKey, Integer> movableAmounts =
		new HashMap<>();
	private final List<StartupSlot> startupSlots = new ArrayList<>();
	
	public void snapshot(Inventory inventory)
	{
		protectedAmounts.clear();
		movableAmounts.clear();
		startupSlots.clear();
		for(int i = 0; i < inventory.getContainerSize(); i++)
		{
			ItemStack stack = inventory.getItem(i);
			if(!stack.isEmpty())
			{
				ItemStackEquivalenceKey key = ItemStackEquivalenceKey.of(stack);
				startupSlots.add(new StartupSlot(i, key, stack.getCount()));
				protectedAmounts.merge(key, stack.getCount(), Integer::sum);
			}
		}
	}
	
	public int getMovable(ItemStack stack)
	{
		return stack.isEmpty() ? 0
			: movableAmounts.getOrDefault(ItemStackEquivalenceKey.of(stack), 0);
	}
	
	public int getMovable(ItemStackEquivalenceKey key)
	{
		return movableAmounts.getOrDefault(key, 0);
	}
	
	public void confirmWithdrawal(ItemStack stack, int amount)
	{
		if(amount > 0 && !stack.isEmpty())
			movableAmounts.merge(ItemStackEquivalenceKey.of(stack), amount,
				Integer::sum);
	}
	
	public boolean canDepositWholeStack(ItemStack stack)
	{
		return !stack.isEmpty() && stack.getCount() <= getMovable(stack);
	}
	
	/** A direct transfer is safe only when no protected quantity is in it. */
	public boolean isEntireStackMovable(ItemStack stack)
	{
		return !stack.isEmpty() && getMovable(stack) >= stack.getCount();
	}
	
	public int getProtected(ItemStack stack)
	{
		return stack.isEmpty() ? 0 : protectedAmounts
			.getOrDefault(ItemStackEquivalenceKey.of(stack), 0);
	}
	
	public void confirmDeposit(ItemStack stack, int amount)
	{
		if(amount <= 0 || stack.isEmpty())
			return;
		ItemStackEquivalenceKey key = ItemStackEquivalenceKey.of(stack);
		movableAmounts.computeIfPresent(key,
			(k, value) -> value <= amount ? null : value - amount);
	}
	
	public boolean hasMovableItems()
	{
		return movableAmounts.values().stream().anyMatch(v -> v > 0);
	}
	
	/**
	 * Stops tracking an amount that has merged into a protected stack. The
	 * caller leaves that stack in the player's inventory; this is preferable to
	 * moving protected items or aborting an otherwise healthy sorting run.
	 */
	public int deferMovable(ItemStackEquivalenceKey key)
	{
		if(key == null)
			return 0;
		Integer amount = movableAmounts.remove(key);
		return amount == null ? 0 : amount;
	}
	
	public Map<ItemStackEquivalenceKey, Integer> getProtectedAmounts()
	{
		return Map.copyOf(protectedAmounts);
	}
	
	/** Slot-level startup evidence for diagnostics and reconciliation. */
	public List<StartupSlot> getStartupSlots()
	{
		return List.copyOf(startupSlots);
	}
	
	public record StartupSlot(int slotIndex, ItemStackEquivalenceKey key,
		int count)
	{}
}
