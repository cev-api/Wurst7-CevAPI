/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * A server-only click plan. Only a newer full snapshot can acknowledge a step.
 */
public final class BookBotInventoryPlan
{
	public record Click(String stage, int slot, int button,
		ContainerInput input, ItemStack cursor,
		Map<Integer, ItemStack> expected, ItemStack beforeCursor,
		Map<Integer, ItemStack> before)
	{}
	
	private final ArrayDeque<Click> steps = new ArrayDeque<>();
	private final String id = UUID.randomUUID().toString();
	private Click pending;
	private int revision;
	private boolean failed;
	public final int destination;
	
	private BookBotInventoryPlan(int destination, int revision)
	{
		this.destination = destination;
		this.revision = revision;
	}
	
	public static BookBotInventoryPlan craft(List<ItemStack> slots,
		ItemStack cursor, int revision)
	{
		if(!cursor.isEmpty() || slots.size() < 46)
			throw new IllegalArgumentException(
				"Crafting requires a confirmed empty cursor.");
		for(int i = 0; i <= 4; i++)
			if(!slots.get(i).isEmpty())
				throw new IllegalArgumentException(
					"Clear the crafting grid before auto-crafting.");
		int destination = -1;
		for(int i = 9; i < 45; i++)
			if(slots.get(i).isEmpty())
			{
				destination = i;
				break;
			}
		if(destination < 0)
			throw new IllegalArgumentException(
				"No empty destination; crafting was not started.");
		BookBotInventoryPlan plan =
			new BookBotInventoryPlan(destination, revision);
		var ingredients = List.of(Items.BOOK, Items.FEATHER, Items.INK_SAC);
		for(int grid = 1; grid <= 3; grid++)
		{
			int source = -1;
			for(int slot = 9; slot < 45; slot++)
				if(slots.get(slot).is(ingredients.get(grid - 1)))
				{
					source = slot;
					break;
				}
			if(source < 0)
				throw new IllegalArgumentException(
					"Missing book, feather or ink sac.");
			ItemStack stack = slots.get(source).copy();
			plan.add("PICK_UP_INGREDIENT", source, 0, stack,
				Map.of(source, ItemStack.EMPTY), ItemStack.EMPTY,
				Map.of(source, stack));
			ItemStack remainder = stack.copy();
			remainder.shrink(1);
			plan.add("PLACE_INGREDIENT", grid, 1, remainder,
				Map.of(grid, stack.copyWithCount(1)), stack,
				Map.of(grid, ItemStack.EMPTY));
			if(!remainder.isEmpty())
				plan.add("RETURN_INGREDIENT_REMAINDER", source, 0,
					ItemStack.EMPTY, Map.of(source, remainder), remainder,
					Map.of(source, ItemStack.EMPTY));
		}
		plan.add("PICK_UP_CRAFTED_BOOK", 0, 0,
			new ItemStack(Items.WRITABLE_BOOK), Map.of(0, ItemStack.EMPTY),
			ItemStack.EMPTY, Map.of(0, new ItemStack(Items.WRITABLE_BOOK)));
		plan.add("PLACE_IN_RESERVED_SLOT", destination, 0, ItemStack.EMPTY,
			Map.of(destination, new ItemStack(Items.WRITABLE_BOOK)),
			new ItemStack(Items.WRITABLE_BOOK),
			Map.of(destination, ItemStack.EMPTY));
		return plan;
	}
	
	public static BookBotInventoryPlan move(List<ItemStack> slots, int source,
		int hotbar, int revision)
	{
		BookBotInventoryPlan plan =
			new BookBotInventoryPlan(hotbar + 36, revision);
		plan.steps.add(new Click("MOVE_TO_HOTBAR", source, hotbar,
			ContainerInput.SWAP, ItemStack.EMPTY,
			Map.of(source, slots.get(hotbar + 36).copy(), hotbar + 36,
				slots.get(source).copy()),
			ItemStack.EMPTY, Map.of(source, slots.get(source).copy(),
				hotbar + 36, slots.get(hotbar + 36).copy())));
		return plan;
	}
	
	private void add(String stage, int slot, int button, ItemStack cursor,
		Map<Integer, ItemStack> expected, ItemStack beforeCursor,
		Map<Integer, ItemStack> before)
	{
		steps.add(new Click(stage, slot, button, ContainerInput.PICKUP, cursor,
			expected, beforeCursor, before));
	}
	
	public Click next(List<ItemStack> slots, ItemStack cursor)
	{
		if(pending != null || failed || steps.isEmpty())
			return null;
		Click next = steps.peek();
		if(!ItemStack.matches(cursor, next.beforeCursor())
			|| next.before().entrySet().stream().anyMatch(
				e -> !ItemStack.matches(slots.get(e.getKey()), e.getValue())))
		{
			failed = true;
			return null;
		}
		if(next.stage().equals("PICK_UP_CRAFTED_BOOK")
			&& (!cursor.isEmpty() || !slots.get(destination).isEmpty()
				|| !slots.get(0).is(Items.WRITABLE_BOOK)))
		{
			failed = true;
			return null;
		}
		if(next.stage().equals("PLACE_IN_RESERVED_SLOT")
			&& !slots.get(destination).isEmpty())
		{
			failed = true;
			return null;
		}
		pending = steps.remove();
		return pending;
	}
	
	public boolean acknowledge(int stateId, List<ItemStack> slots,
		ItemStack cursor)
	{
		int distance = (stateId - revision) & 32767;
		if(distance == 0 || distance > 16383)
			return false;
		revision = stateId;
		if(pending == null)
			return false;
		failed = !ItemStack.matches(cursor, pending.cursor())
			|| pending.expected().entrySet().stream().anyMatch(
				e -> !ItemStack.matches(slots.get(e.getKey()), e.getValue()));
		pending = null;
		return true;
	}
	
	public void cancel()
	{
		failed = true;
		pending = null;
		steps.clear();
	}
	
	public boolean waiting()
	{
		return pending != null;
	}
	
	public boolean complete()
	{
		return !failed && pending == null && steps.isEmpty();
	}
	
	public boolean failed()
	{
		return failed;
	}
	
	public String id()
	{
		return id;
	}
}
