/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket;
import net.minecraft.screen.slot.Slot;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.PacketOutputListener;
import net.wurstclient.events.PacketOutputListener.PacketOutputEvent;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.util.ChatUtils;

@SearchTags({"xcarry", "extra inventory", "crafting carry"})
public final class XCarryHack extends Hack
	implements PacketOutputListener, UpdateListener
{
	private static final int CRAFTING_SLOT_START = 1;
	private static final int CRAFTING_SLOT_COUNT = 4;
	
	private final CheckboxSetting dangerousMode = new CheckboxSetting(
		"Ignore safety checks",
		"NOT RECOMMENDED TO ENABLE!!\nWill disable following checks:\n1. Is in inventory screen (allows to get out of sync for other containers\n2. Disable general sync check.",
		false);
	
	private final CheckboxSetting disableInCreative = new CheckboxSetting(
		"Disable in Creative",
		"Turns off XCarry in Creative mode, since there is no 2x2 crafting grid.",
		false);
	
	private final ItemStack[] trackedCraftingStacks =
		new ItemStack[CRAFTING_SLOT_COUNT];
	private List<ItemStack> lastInventorySnapshot = Collections.emptyList();
	
	public XCarryHack()
	{
		super("XCarry");
		setCategory(Category.ITEMS);
		addSetting(dangerousMode);
		addSetting(disableInCreative);
		resetTracking();
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(PacketOutputListener.class, this);
		EVENTS.add(UpdateListener.class, this);
		resetTracking();
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(PacketOutputListener.class, this);
		EVENTS.remove(UpdateListener.class, this);
		resetTracking();
	}
	
	@Override
	public void onSentPacket(PacketOutputEvent event)
	{
		if(!(event
			.getPacket() instanceof CloseHandledScreenC2SPacket closeScreenPacket))
			return;
		
		if(MC.player == null)
			return;
		
		if(disableInCreative.isChecked()
			&& MC.player.getAbilities().creativeMode)
			return;
		
		if(dangerousMode.isChecked())
		{
			event.cancel();
			return;
		}
		
		if(MC.player.playerScreenHandler != null && closeScreenPacket
			.getSyncId() == MC.player.playerScreenHandler.syncId)
			event.cancel();
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null || MC.world == null)
		{
			resetTracking();
			return;
		}
		
		List<ItemStack> previousInventory = lastInventorySnapshot;
		List<ItemStack> currentInventory = captureInventorySnapshot();
		lastInventorySnapshot = currentInventory;
		
		monitorCraftingGrid(previousInventory, currentInventory);
	}
	
	private void monitorCraftingGrid(List<ItemStack> previousInventory,
		List<ItemStack> currentInventory)
	{
		if(MC.player == null || MC.player.playerScreenHandler == null)
			return;
		
		List<ItemStack> removedStacks = new ArrayList<>();
		for(int i = 0; i < CRAFTING_SLOT_COUNT; i++)
		{
			ItemStack current = getCraftingSlotStack(i);
			ItemStack previous = trackedCraftingStacks[i];
			trackedCraftingStacks[i] = current.copy();
			
			if(MC.currentScreen != null || previous.isEmpty()
				|| !current.isEmpty())
				continue;
			
			removedStacks.add(previous);
		}
		
		if(removedStacks.isEmpty())
			return;
		
		handleCraftingEjection(removedStacks, previousInventory,
			currentInventory);
	}
	
	private void handleCraftingEjection(List<ItemStack> removedStacks,
		List<ItemStack> previousInventory, List<ItemStack> currentInventory)
	{
		if(previousInventory.isEmpty() || currentInventory.isEmpty())
		{
			ChatUtils
				.message("[XCarry] Crafting grid items vanished (unable to "
					+ "determine destination).");
			return;
		}
		
		List<ItemCounter> inventoryDelta =
			buildInventoryDelta(previousInventory, currentInventory);
		List<String> returned = new ArrayList<>();
		List<String> dropped = new ArrayList<>();
		
		for(ItemStack removed : removedStacks)
		{
			int restored =
				consumeFromDelta(inventoryDelta, removed, removed.getCount());
			
			if(restored > 0)
				returned.add(formatStack(removed, restored));
			
			if(restored < removed.getCount())
				dropped
					.add(formatStack(removed, removed.getCount() - restored));
		}
		
		if(!returned.isEmpty())
			ChatUtils.message("[XCarry] Crafting items moved to inventory: "
				+ String.join(", ", returned));
		
		if(!dropped.isEmpty())
			ChatUtils.message("[XCarry] Crafting items dropped on ground: "
				+ String.join(", ", dropped));
	}
	
	private ItemStack getCraftingSlotStack(int offset)
	{
		if(MC.player == null || MC.player.playerScreenHandler == null)
			return ItemStack.EMPTY;
		
		List<Slot> slots = MC.player.playerScreenHandler.slots;
		int index = CRAFTING_SLOT_START + offset;
		if(index < 0 || index >= slots.size())
			return ItemStack.EMPTY;
		
		return slots.get(index).getStack();
	}
	
	private List<ItemStack> captureInventorySnapshot()
	{
		if(MC.player == null)
			return Collections.emptyList();
		
		PlayerInventory inv = MC.player.getInventory();
		List<ItemStack> snapshot = new ArrayList<>(inv.size());
		for(int i = 0; i < inv.size(); i++)
			snapshot.add(inv.getStack(i).copy());
		
		return snapshot;
	}
	
	private List<ItemCounter> buildInventoryDelta(List<ItemStack> before,
		List<ItemStack> after)
	{
		List<ItemCounter> delta = new ArrayList<>();
		
		for(ItemStack stack : after)
			addToCounters(delta, stack);
		
		for(ItemStack stack : before)
		{
			if(stack.isEmpty())
				continue;
			
			ItemCounter counter = findCounter(delta, stack);
			if(counter == null)
			{
				ItemStack sample = stack.copy();
				sample.setCount(1);
				counter = new ItemCounter(sample, 0);
				delta.add(counter);
			}
			
			counter.count -= stack.getCount();
		}
		
		delta.removeIf(counter -> counter.count == 0);
		return delta;
	}
	
	private static void addToCounters(List<ItemCounter> counters,
		ItemStack stack)
	{
		if(stack.isEmpty())
			return;
		
		ItemCounter counter = findCounter(counters, stack);
		if(counter == null)
		{
			ItemStack sample = stack.copy();
			sample.setCount(1);
			counters.add(new ItemCounter(sample, stack.getCount()));
			return;
		}
		
		counter.count += stack.getCount();
	}
	
	private static ItemCounter findCounter(List<ItemCounter> counters,
		ItemStack stack)
	{
		for(ItemCounter counter : counters)
			if(stacksMatch(counter.sample, stack))
				return counter;
			
		return null;
	}
	
	private static int consumeFromDelta(List<ItemCounter> deltas,
		ItemStack stack, int needed)
	{
		ItemCounter counter = findCounter(deltas, stack);
		if(counter == null || counter.count <= 0 || needed <= 0)
			return 0;
		
		int used = Math.min(needed, counter.count);
		counter.count -= used;
		if(counter.count == 0)
			deltas.remove(counter);
		return used;
	}
	
	private static String formatStack(ItemStack stack, int count)
	{
		return count + "x " + stack.getName().getString();
	}
	
	private void resetTracking()
	{
		Arrays.fill(trackedCraftingStacks, ItemStack.EMPTY);
		lastInventorySnapshot = Collections.emptyList();
	}
	
	private static boolean stacksMatch(ItemStack a, ItemStack b)
	{
		if(a.isEmpty() || b.isEmpty())
			return a.isEmpty() && b.isEmpty();
		
		return a.getItem() == b.getItem();
	}
	
	private static final class ItemCounter
	{
		private final ItemStack sample;
		private int count;
		
		private ItemCounter(ItemStack sample, int count)
		{
			this.sample = sample;
			this.count = count;
		}
	}
}
