/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.ArrayList;

import org.lwjgl.glfw.GLFW;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.MouseButtonPressListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.mixin.HandledScreenAccessor;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EnumSetting;

@SearchTags({"inventory sorter", "InventorySorter", "InventoryTweaks",
	"inventory tweaks", "sort inventory", "middle click sort", "auto sort"})
public final class InventorySorterHack extends Hack
	implements MouseButtonPressListener
{
	private final EnumSetting<SortButton> button = new EnumSetting<>("Button",
		"Which mouse button sorts the hovered inventory section.",
		SortButton.values(), SortButton.MIDDLE);
	
	private final CheckboxSetting includeHotbar =
		new CheckboxSetting("Include hotbar",
			"Also sorts your hotbar when sorting your own inventory.\n"
				+ "When off, only the upper 27 storage slots are sorted.",
			false);
	
	public InventorySorterHack()
	{
		super("InventorySorter");
		setCategory(Category.ITEMS);
		addSetting(button);
		addSetting(includeHotbar);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(MouseButtonPressListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(MouseButtonPressListener.class, this);
	}
	
	@Override
	public void onMouseButtonPress(MouseButtonPressEvent event)
	{
		if(event.getAction() != GLFW.GLFW_PRESS
			|| event.getButton() != button.getSelected().glfwButton)
			return;
		
		// only inside a real container screen, not the creative item palette
		if(!(MC.gui.screen() instanceof AbstractContainerScreen<?> screen)
			|| MC.gui.screen() instanceof CreativeModeInventoryScreen)
			return;
		
		Slot hovered = ((HandledScreenAccessor)screen).getHoveredSlot();
		if(hovered == null)
			return;
		
		sort(screen, sectionSlots(screen, hovered));
	}
	
	private ArrayList<Slot> sectionSlots(AbstractContainerScreen<?> screen,
		Slot hovered)
	{
		AbstractContainerMenu menu = screen.getMenu();
		boolean playerInv = hovered.container == MC.player.getInventory();
		
		ArrayList<Slot> slots = new ArrayList<>();
		for(Slot slot : menu.slots)
		{
			if(slot.container != hovered.container)
				continue;
			
			if(playerInv)
			{
				int idx = slot.getContainerSlot();
				// 0-8 hotbar, 9-35 main storage, 36+ armor/offhand
				boolean main = idx >= 9 && idx <= 35;
				boolean hotbar = idx >= 0 && idx <= 8;
				if(!main && !(hotbar && includeHotbar.isChecked()))
					continue;
			}
			
			slots.add(slot);
		}
		
		return slots;
	}
	
	private void sort(AbstractContainerScreen<?> screen, ArrayList<Slot> slots)
	{
		if(slots.size() < 2)
			return;
			
		// 1. merge partial stacks of the same item so each item ends up as a
		// run of full stacks plus at most one partial
		for(int i = 0; i < slots.size(); i++)
		{
			Slot dst = slots.get(i);
			if(dst.getItem().isEmpty())
				continue;
			
			for(int j = i + 1; j < slots.size(); j++)
			{
				ItemStack dstStack = dst.getItem();
				if(dstStack.getCount() >= dstStack.getMaxStackSize())
					break;
				
				Slot src = slots.get(j);
				if(src.getItem().isEmpty() || !ItemStack
					.isSameItemSameComponents(dstStack, src.getItem()))
					continue;
				
				// pick up src, drop onto dst (fills dst), return any remainder
				click(screen, src);
				click(screen, dst);
				if(!screen.getMenu().getCarried().isEmpty())
					click(screen, src);
			}
		}
		
		// 2. group items by name via a selection sort; only ever swaps two
		// slots
		// holding different items, so each swap is a clean 3-click exchange
		for(int a = 0; a < slots.size() - 1; a++)
		{
			int min = a;
			for(int b = a + 1; b < slots.size(); b++)
				if(compare(slots.get(b).getItem(),
					slots.get(min).getItem()) < 0)
					min = b;
				
			if(min == a)
				continue;
			
			Slot slotA = slots.get(a);
			Slot slotMin = slots.get(min);
			// same item type can't reach here as a swap target (compare == 0),
			// so this is always an empty<->item or itemA<->itemB exchange
			click(screen, slotA);
			click(screen, slotMin);
			click(screen, slotA);
		}
	}
	
	private void click(AbstractContainerScreen<?> screen, Slot slot)
	{
		screen.slotClicked(slot, slot.index, 0, ContainerInput.PICKUP);
	}
	
	private int compare(ItemStack a, ItemStack b)
	{
		boolean ae = a.isEmpty();
		boolean be = b.isEmpty();
		if(ae || be)
			return ae == be ? 0 : ae ? 1 : -1;
		
		return sortKey(a).compareTo(sortKey(b));
	}
	
	private String sortKey(ItemStack stack)
	{
		return stack.getHoverName().getString().toLowerCase();
	}
	
	private enum SortButton
	{
		LEFT("Left", GLFW.GLFW_MOUSE_BUTTON_LEFT),
		MIDDLE("Middle", GLFW.GLFW_MOUSE_BUTTON_MIDDLE),
		RIGHT("Right", GLFW.GLFW_MOUSE_BUTTON_RIGHT);
		
		private final String name;
		private final int glfwButton;
		
		SortButton(String name, int glfwButton)
		{
			this.name = name;
			this.glfwButton = glfwButton;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
}
