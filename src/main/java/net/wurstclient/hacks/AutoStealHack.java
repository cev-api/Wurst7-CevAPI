/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.List;
import java.util.stream.IntStream;

import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.Item;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

@SearchTags({"auto steal", "ChestStealer", "chest stealer",
	"steal store buttons", "Steal/Store buttons"})
public final class AutoStealHack extends Hack
{
	private final SliderSetting delay = new SliderSetting("Delay",
		"Delay between moving stacks of items.\n"
			+ "Should be at least 70ms for NoCheat+ servers.",
		100, 0, 500, 10, ValueDisplay.INTEGER.withSuffix("ms"));
	
	private final CheckboxSetting buttons =
		new CheckboxSetting("Steal/Store buttons", true);
	
	private final CheckboxSetting reverseSteal =
		new CheckboxSetting("Reverse steal order", false);
	
	private final CheckboxSetting stealStoreSame = new CheckboxSetting(
		"Steal/Store same",
		"Only move exact matching item types present in the source.", false);
	
	private Thread thread;
	
	public AutoStealHack()
	{
		super("AutoSteal");
		setCategory(Category.ITEMS);
		addSetting(buttons);
		addSetting(delay);
		addSetting(reverseSteal);
		addSetting(stealStoreSame);
	}
	
	public void steal(HandledScreen<?> screen, int rows)
	{
		startClickingSlots(screen, 0, rows * 9, true);
	}
	
	public void store(HandledScreen<?> screen, int rows)
	{
		startClickingSlots(screen, rows * 9, rows * 9 + 36, false);
	}
	
	private void startClickingSlots(HandledScreen<?> screen, int from, int to,
		boolean steal)
	{
		if(thread != null && thread.isAlive())
			thread.interrupt();
		
		thread = Thread.ofPlatform().name("AutoSteal")
			.uncaughtExceptionHandler((t, e) -> e.printStackTrace()).daemon()
			.start(() -> shiftClickSlots(screen, from, to, steal));
	}
	
	private void shiftClickSlots(HandledScreen<?> screen, int from, int to,
		boolean steal)
	{
		List<Slot> slots = IntStream.range(from, to)
			.mapToObj(i -> screen.getScreenHandler().slots.get(i)).toList();
		
		if(reverseSteal.isChecked() && steal)
			slots = slots.reversed();
		
		java.util.Set<Item> inventoryTypes = null;
		java.util.Set<Item> chestTypes = null;
		if(stealStoreSame.isChecked())
		{
			if(steal)
			{
				// Try to read player inventory item types directly from the UI
				// The chest UI has `rows * 9` chest slots first; the player
				// inventory follows and is commonly 36 slots (27 main + 9
				// hotbar).
				int rows = to / 9; // when stealing `to` equals rows*9
				int invStart = rows * 9;
				int invEnd = Math.min(invStart + 36,
					screen.getScreenHandler().slots.size());
				java.util.Set<Item> typesFromUI = new java.util.HashSet<>();
				for(int i = invStart; i < invEnd; i++)
				{
					Slot s = screen.getScreenHandler().slots.get(i);
					if(!s.getStack().isEmpty())
						typesFromUI.add(s.getStack().getItem());
				}
				if(!typesFromUI.isEmpty())
					inventoryTypes = typesFromUI;
				else
				{
					// Fallback to previous UI-reflection attempt, then to
					// player
					// inventory via reflection if necessary
					inventoryTypes = getInventoryItemTypesUI(screen);
					if(inventoryTypes == null)
						inventoryTypes = getInventoryItemTypes();
				}
			}else
			{
				chestTypes = new java.util.HashSet<>();
				for(int i = 0; i < from; i++)
				{
					Slot s = screen.getScreenHandler().slots.get(i);
					if(!s.getStack().isEmpty())
						chestTypes.add(s.getStack().getItem());
				}
			}
		}
		
		for(Slot slot : slots)
			try
			{
				if(slot.getStack().isEmpty())
					continue;
				
				// Exact-type filtering (Steal/Store same)
				if(stealStoreSame.isChecked())
				{
					net.minecraft.item.Item item = slot.getStack().getItem();
					if(steal)
					{
						if(inventoryTypes != null
							&& !inventoryTypes.contains(item))
							continue;
					}else
					{
						if(chestTypes != null && !chestTypes.contains(item))
							continue;
					}
				}
				
				Thread.sleep(delay.getValueI());
				
				if(MC.currentScreen == null)
					break;
				
				screen.onMouseClick(slot, slot.id, 0,
					SlotActionType.QUICK_MOVE);
				
			}catch(InterruptedException e)
			{
				Thread.currentThread().interrupt();
				break;
			}
	}
	
	private java.util.Set<Item> getInventoryItemTypes()
	{
		java.util.Set<Item> types = new java.util.HashSet<>();
		try
		{
			Object invObj = MC.player.getClass().getMethod("getInventory")
				.invoke(MC.player);
			// First attempt: read the primary 'main' list from the inventory
			Object main =
				invObj.getClass().getDeclaredField("main").get(invObj);
			if(main instanceof java.util.List)
			{
				for(Object o : (java.util.List<?>)main)
				{
					if(o instanceof net.minecraft.item.ItemStack)
					{
						net.minecraft.item.ItemStack stack =
							(net.minecraft.item.ItemStack)o;
						if(stack != null && !stack.isEmpty())
							types.add(stack.getItem());
					}
				}
			}
			// If we couldn't collect any items, try alternative inventory
			// structures via reflection
			if(types.isEmpty())
			{
				for(java.lang.reflect.Field f : invObj.getClass()
					.getDeclaredFields())
				{
					if(java.util.List.class.isAssignableFrom(f.getType()))
					{
						f.setAccessible(true);
						Object listObj = f.get(invObj);
						if(listObj instanceof java.util.List)
						{
							for(Object obj : (java.util.List<?>)listObj)
							{
								if(obj instanceof net.minecraft.item.ItemStack)
								{
									net.minecraft.item.ItemStack st =
										(net.minecraft.item.ItemStack)obj;
									if(st != null && !st.isEmpty())
										types.add(st.getItem());
								}
							}
						}
					}
				}
			}
		}catch(Exception ignored)
		{
			// Ignore and return whatever we could collect
		}
		return types.isEmpty() ? null : types;
	}
	
	private java.util.Set<Item> getInventoryItemTypesUI(HandledScreen<?> screen)
	{
		java.util.Set<Item> types = new java.util.HashSet<>();
		try
		{
			// Access the chest inventory directly from the screen handler
			Object chestInventory = screen.getScreenHandler().getClass()
				.getMethod("getInventory").invoke(screen.getScreenHandler());
			
			// Try to read the 'stacks' field directly, which should contain
			// the items visible in the player's chest GUI
			Object stacksObj = chestInventory.getClass()
				.getDeclaredField("stacks").get(chestInventory);
			if(stacksObj instanceof java.util.List)
			{
				for(Object o : (java.util.List<?>)stacksObj)
				{
					if(o instanceof net.minecraft.item.ItemStack)
					{
						net.minecraft.item.ItemStack stack =
							(net.minecraft.item.ItemStack)o;
						if(stack != null && !stack.isEmpty())
							types.add(stack.getItem());
					}
				}
			}
			// Fallback: scan the typical 36-item UI region if the stacks field
			// is inaccessible or empty
			if(types.isEmpty())
			{
				for(int i = 27; i < 63; i++)
				{
					Slot s = screen.getScreenHandler().slots.get(i);
					if(!s.getStack().isEmpty())
						types.add(s.getStack().getItem());
				}
			}
		}catch(Exception ignored)
		{
			// Ignore and fallback to the regular inventory scan
		}
		return types.isEmpty() ? null : types;
	}
	
	public boolean areButtonsVisible()
	{
		return buttons.isChecked();
	}
	
	// See GenericContainerScreenMixin and ShulkerBoxScreenMixin
}
