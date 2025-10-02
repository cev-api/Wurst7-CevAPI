/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.ArrayList;
import java.util.List;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.client.gui.screen.ingame.GrindstoneScreen;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.EnchantmentTags;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

@SearchTags({"auto disenchant", "grindstone", "repair and disenchant",
	"disenchanter"})
public final class AutoDisenchantHack extends Hack
{
	private static final int INPUT_TOP_SLOT = 0;
	private static final int INPUT_BOTTOM_SLOT = 1;
	private static final int OUTPUT_SLOT = 2;
	private static final int PLAYER_SLOT_START = 3;
	private static final int HOTBAR_SIZE = 9;
	
	private final CheckboxSetting includeHotbar =
		new CheckboxSetting("Include hotbar", false);
	
	private final SliderSetting clickDelay = new SliderSetting("Click delay",
		"Delay between container interactions.", 150, 0, 500, 10,
		ValueDisplay.INTEGER.withSuffix("ms"));
	
	private final SliderSetting outputWait = new SliderSetting("Output wait",
		"Maximum time to wait for the grindstone to produce an output.", 600,
		100, 2000, 50, ValueDisplay.INTEGER.withSuffix("ms"));
	
	private volatile Thread worker;
	
	public AutoDisenchantHack()
	{
		super("AutoDisenchant");
		setCategory(Category.ITEMS);
		addSetting(includeHotbar);
		addSetting(clickDelay);
		addSetting(outputWait);
	}
	
	public void start(GrindstoneScreen screen)
	{
		if(screen == null || MC.player == null)
			return;
		
		stopWorker();
		
		worker = Thread.ofPlatform().name("AutoDisenchant").daemon()
			.uncaughtExceptionHandler((t, e) -> e.printStackTrace())
			.start(() -> run(screen));
	}
	
	private void run(GrindstoneScreen screen)
	{
		try
		{
			if(!isScreenValid(screen))
				return;
			
			var handler = screen.getScreenHandler();
			List<Slot> slots = handler.slots;
			if(slots.size() <= OUTPUT_SLOT)
				return;
			
			if(!handler.getCursorStack().isEmpty())
				return;
			
			clearInputSlots(screen);
			
			List<Integer> targets = collectTargets(slots);
			for(int slotIndex : targets)
			{
				if(Thread.currentThread().isInterrupted()
					|| !isScreenValid(screen))
					break;
				
				Slot slot = handler.slots.get(slotIndex);
				ItemStack stack = slot.getStack();
				if(stack.isEmpty())
					continue;
				
				if(!canDisenchant(stack))
					continue;
				
				disenchantSlot(screen, slotIndex);
				sleep(clickDelay.getValueI());
			}
		}catch(InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}finally
		{
			worker = null;
			try
			{
				if(isScreenValid(screen))
					clearInputSlots(screen);
			}catch(InterruptedException e)
			{
				Thread.currentThread().interrupt();
			}
		}
	}
	
	private List<Integer> collectTargets(List<Slot> slots)
	{
		int start = PLAYER_SLOT_START;
		int end = slots.size();
		if(!includeHotbar.isChecked())
			end = Math.max(start, end - HOTBAR_SIZE);
		
		List<Integer> targets = new ArrayList<>();
		for(int i = start; i < end; i++)
		{
			Slot slot = slots.get(i);
			if(slot.getStack().isEmpty())
				continue;
			targets.add(i);
		}
		return targets;
	}
	
	private void disenchantSlot(GrindstoneScreen screen, int slotIndex)
		throws InterruptedException
	{
		var handler = screen.getScreenHandler();
		Slot inventorySlot = handler.slots.get(slotIndex);
		if(inventorySlot.getStack().isEmpty())
			return;
		
		clickAndWait(screen, inventorySlot, SlotActionType.PICKUP);
		clickAndWait(screen, handler.slots.get(INPUT_TOP_SLOT),
			SlotActionType.PICKUP);
		
		if(waitForOutput(screen))
		{
			Slot outputSlot = handler.slots.get(OUTPUT_SLOT);
			if(outputSlot.hasStack())
				clickAndWait(screen, outputSlot, SlotActionType.QUICK_MOVE);
		}else
		{
			clickAndWait(screen, handler.slots.get(INPUT_TOP_SLOT),
				SlotActionType.QUICK_MOVE);
		}
		
		clearInputSlots(screen);
	}
	
	private boolean canDisenchant(ItemStack stack)
	{
		// if(!stack.hasEnchantments())
		// return false;
		
		// Use EnchantmentHelper instead of ItemStack::hasEnchantments
		// because the latter returns false for enchanted books.
		
		var enchantments =
			EnchantmentHelper.getEnchantments(stack).getEnchantmentEntries();
		if(enchantments.isEmpty())
			return false;
		
		for(Object2IntMap.Entry<RegistryEntry<Enchantment>> entry : enchantments)
			if(!entry.getKey().isIn(EnchantmentTags.CURSE))
				return true;
			
		return false;
	}
	
	private void clearInputSlots(GrindstoneScreen screen)
		throws InterruptedException
	{
		var handler = screen.getScreenHandler();
		List<Slot> slots = handler.slots;
		for(int i = INPUT_TOP_SLOT; i <= INPUT_BOTTOM_SLOT
			&& i < slots.size(); i++)
		{
			Slot slot = slots.get(i);
			if(slot.getStack().isEmpty())
				continue;
			
			clickAndWait(screen, slot, SlotActionType.QUICK_MOVE);
		}
	}
	
	private void clickAndWait(GrindstoneScreen screen, Slot slot,
		SlotActionType action) throws InterruptedException
	{
		if(slot == null)
			return;
		
		if(Thread.currentThread().isInterrupted())
			throw new InterruptedException();
		
		screen.onMouseClick(slot, slot.id, 0, action);
		sleep(clickDelay.getValueI());
	}
	
	private boolean waitForOutput(GrindstoneScreen screen)
		throws InterruptedException
	{
		long timeout = outputWait.getValueI();
		long waited = 0;
		while(waited < timeout && isScreenValid(screen))
		{
			Slot outputSlot = screen.getScreenHandler().slots.get(OUTPUT_SLOT);
			if(outputSlot.hasStack())
				return true;
			
			Thread.sleep(50);
			waited += 50;
		}
		
		Slot outputSlot = screen.getScreenHandler().slots.get(OUTPUT_SLOT);
		return outputSlot.hasStack();
	}
	
	private boolean isScreenValid(GrindstoneScreen screen)
	{
		return MC.currentScreen == screen;
	}
	
	private void sleep(long millis) throws InterruptedException
	{
		if(millis <= 0)
			return;
		
		Thread.sleep(millis);
	}
	
	@Override
	protected void onDisable()
	{
		stopWorker();
	}
	
	private void stopWorker()
	{
		Thread current = worker;
		if(current != null)
		{
			current.interrupt();
			worker = null;
		}
	}
}
