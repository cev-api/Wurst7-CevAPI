/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

public final class ShulkerInventoryScreen extends ShulkerBoxScreen
{
	private final QuickShulkerHack quickShulker;
	private final Screen previousScreen;
	private final Inventory playerInventory;
	private final int shulkerSlot;
	private final List<ItemStack> originalPlayerItems;
	private final List<ItemStack> droppedItems = new ArrayList<>();
	private final int originalSelectedSlot;
	private boolean closed;
	
	private ShulkerInventoryScreen(QuickShulkerHack quickShulker,
		Screen previousScreen, Inventory playerInventory, int shulkerSlot,
		SimpleContainer shulkerContents)
	{
		super(new ShulkerBoxMenu(0, playerInventory, shulkerContents),
			playerInventory, Component.literal("Shulker Inventory"));
		this.quickShulker = quickShulker;
		this.previousScreen = previousScreen;
		this.playerInventory = playerInventory;
		this.shulkerSlot = shulkerSlot;
		this.originalPlayerItems = snapshot(playerInventory);
		this.originalSelectedSlot = playerInventory.getSelectedSlot();
	}
	
	public static ShulkerInventoryScreen open(QuickShulkerHack quickShulker,
		Screen previousScreen, Inventory inventory, int shulkerSlot,
		ItemStack shulker)
	{
		SimpleContainer contents = new SimpleContainer(27);
		ItemContainerContents component = shulker.getOrDefault(
			net.minecraft.core.component.DataComponents.CONTAINER,
			ItemContainerContents.EMPTY);
		NonNullList<ItemStack> copied =
			NonNullList.withSize(27, ItemStack.EMPTY);
		component.copyInto(copied);
		for(int i = 0; i < copied.size(); i++)
			contents.setItem(i, copied.get(i));
		return new ShulkerInventoryScreen(quickShulker, previousScreen,
			inventory, shulkerSlot, contents);
	}
	
	@Override
	public void slotClicked(Slot slot, int slotId, int button,
		ContainerInput actionType)
	{
		if(actionType == ContainerInput.THROW)
		{
			ItemStack thrown = slotId == -999 ? menu.getCarried()
				: slot == null ? ItemStack.EMPTY : slot.getItem();
			if(!thrown.isEmpty())
			{
				ItemStack copy = thrown.copy();
				if(button == 0)
					copy.setCount(1);
				droppedItems.add(copy);
			}
		}
		
		if(menu != null && minecraft != null && minecraft.player != null)
			menu.clicked(slotId, button, actionType, minecraft.player);
	}
	
	@Override
	public void removed()
	{
		if(closed)
			return;
		closed = true;
		
		List<ItemStack> desiredShulker = new ArrayList<>(27);
		for(int i = 0; i < 27; i++)
			desiredShulker.add(menu.getSlot(i).getItem().copy());
		
		List<ItemStack> desiredPlayer = snapshot(playerInventory);
		restore(playerInventory, originalPlayerItems);
		playerInventory.setSelectedSlot(originalSelectedSlot);
		menu.setCarried(ItemStack.EMPTY);
		
		quickShulker.queueShulkerInventoryEdit(shulkerSlot, desiredShulker,
			desiredPlayer, droppedItems);
		super.removed();
	}
	
	@Override
	public void onClose()
	{
		if(minecraft != null)
			minecraft.gui.setScreen(previousScreen);
	}
	
	private static List<ItemStack> snapshot(Container container)
	{
		List<ItemStack> result = new ArrayList<>(container.getContainerSize());
		for(int i = 0; i < container.getContainerSize(); i++)
			result.add(container.getItem(i).copy());
		return result;
	}
	
	private static void restore(Container container, List<ItemStack> items)
	{
		int size = Math.min(container.getContainerSize(), items.size());
		for(int i = 0; i < size; i++)
			container.setItem(i, items.get(i).copy());
	}
}
