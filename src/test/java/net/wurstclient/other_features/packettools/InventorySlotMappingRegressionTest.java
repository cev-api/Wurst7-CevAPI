/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features.packettools;

public final class InventorySlotMappingRegressionTest
{
	public static void main(String[] args)
	{
		check(InventorySlotMapping.playerInventoryMenuSlot(37)
			.equals("hotbar[1]"), "protocol slot 37 is hotbar index 1");
		check(InventorySlotMapping.playerInventoryMenuSlot(9)
			.equals("main_inventory[0]"), "player inventory main slot mapping");
		check(
			InventorySlotMapping.playerInventoryMenuSlot(45).equals("offhand"),
			"offhand player menu slot mapping");
		check(InventorySlotMapping.playerInventoryMenuSlot(5)
			.equals("armour.head"), "helmet protocol slot mapping");
		check(InventorySlotMapping.playerInventoryMenuSlot(8)
			.equals("armour.feet"), "boots protocol slot mapping");
		check(InventorySlotMapping.playerInventoryMenuSlot(0)
			.equals("crafting_output"), "player menu crafting output mapping");
		check(InventorySlotMapping.playerInventoryMenuSlot(4)
			.equals("crafting_input[3]"), "player menu crafting input mapping");
		check(
			InventorySlotMapping.playerInventoryMenuSlot(46)
				.startsWith("unknown_player_menu_slot"),
			"unsupported slots are explicit rather than guessed");
		check(InventorySlotMapping.playerInventoryIndex(1).equals("hotbar[1]"),
			"backing inventory indexes remain a separate mapping");
		check(
			InventorySlotMapping.playerInventoryIndex(37).equals("armour.legs"),
			"backing armor index mapping");
		System.out.println("Inventory slot mapping regression checks passed.");
	}
	
	private static void check(boolean condition, String description)
	{
		if(!condition)
			throw new AssertionError(description);
	}
}
