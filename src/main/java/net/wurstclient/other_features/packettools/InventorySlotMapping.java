/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features.packettools;

import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.Container;

/**
 * Maps protocol/menu slots to semantic locations without mixing index spaces.
 */
public final class InventorySlotMapping
{
	private InventorySlotMapping()
	{}
	
	public static String describe(AbstractContainerMenu menu, Slot slot,
		Minecraft mc)
	{
		return describe(menu, slot,
			mc == null || mc.player == null ? null : mc.player.getInventory());
	}
	
	public static String describe(AbstractContainerMenu menu, Slot slot,
		Container playerInventory)
	{
		// Slot.index is assigned by AbstractContainerMenu.addSlot().
		// getContainerSlot() belongs to the backing Container, not the packet.
		int rawSlot = slot.index;
		if(menu instanceof InventoryMenu)
			return playerInventoryMenuSlot(rawSlot);
		if(playerInventory != null && slot.container == playerInventory)
			return playerInventoryIndex(slot.getContainerSlot());
		String menuName = menu.getClass().getSimpleName();
		if(menuName.contains("CraftingMenu"))
		{
			if(rawSlot == 0)
				return "crafting_output";
			if(rawSlot >= 1 && rawSlot <= 9)
				return "crafting_input[" + (rawSlot - 1) + "]";
		}
		return "unknown_menu_slot[" + menuName + ":" + rawSlot + "]";
	}
	
	static String playerInventoryMenuSlot(int rawSlot)
	{
		if(rawSlot == 0)
			return "crafting_output";
		if(rawSlot >= 1 && rawSlot <= 4)
			return "crafting_input[" + (rawSlot - 1) + "]";
		if(rawSlot >= 5 && rawSlot <= 8)
			return switch(rawSlot)
			{
				case 5 -> "armour.head";
				case 6 -> "armour.chest";
				case 7 -> "armour.legs";
				default -> "armour.feet";
			};
		if(rawSlot >= 9 && rawSlot <= 35)
			return "main_inventory[" + (rawSlot - 9) + "]";
		if(rawSlot >= 36 && rawSlot <= 44)
			return "hotbar[" + (rawSlot - 36) + "]";
		if(rawSlot == 45)
			return "offhand";
		return "unknown_player_menu_slot[" + rawSlot + "]";
	}
	
	static String playerInventoryIndex(int inventoryIndex)
	{
		if(inventoryIndex >= 0 && inventoryIndex <= 8)
			return "hotbar[" + inventoryIndex + "]";
		if(inventoryIndex >= 9 && inventoryIndex <= 35)
			return "main_inventory[" + (inventoryIndex - 9) + "]";
		if(inventoryIndex >= 36 && inventoryIndex <= 39)
			return switch(inventoryIndex)
			{
				case 39 -> "armour.head";
				case 38 -> "armour.chest";
				case 37 -> "armour.legs";
				default -> "armour.feet";
			};
		if(inventoryIndex == 40)
			return "offhand";
		return "unknown_player_inventory_index[" + inventoryIndex + "]";
	}
}
