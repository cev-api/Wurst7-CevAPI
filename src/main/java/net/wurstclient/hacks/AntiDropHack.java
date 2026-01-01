/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.ItemListSetting;

@SearchTags({"anti drop", "item lock", "drop lock", "prevent drop"})
public final class AntiDropHack extends Hack
{
	private static final String[] DEFAULT_ITEMS = {
		// swords
		"minecraft:wooden_sword", "minecraft:stone_sword",
		"minecraft:iron_sword", "minecraft:golden_sword",
		"minecraft:diamond_sword", "minecraft:netherite_sword",
		// axes
		"minecraft:wooden_axe", "minecraft:stone_axe", "minecraft:iron_axe",
		"minecraft:golden_axe", "minecraft:diamond_axe",
		"minecraft:netherite_axe",
		// pickaxes
		"minecraft:wooden_pickaxe", "minecraft:stone_pickaxe",
		"minecraft:iron_pickaxe", "minecraft:golden_pickaxe",
		"minecraft:diamond_pickaxe", "minecraft:netherite_pickaxe",
		// shovels
		"minecraft:wooden_shovel", "minecraft:stone_shovel",
		"minecraft:iron_shovel", "minecraft:golden_shovel",
		"minecraft:diamond_shovel", "minecraft:netherite_shovel",
		// hoes
		"minecraft:wooden_hoe", "minecraft:stone_hoe", "minecraft:iron_hoe",
		"minecraft:golden_hoe", "minecraft:diamond_hoe",
		"minecraft:netherite_hoe",
		// other weapons & tools
		"minecraft:bow", "minecraft:crossbow", "minecraft:trident",
		"minecraft:mace", "minecraft:shield", "minecraft:flint_and_steel",
		"minecraft:shears", "minecraft:fishing_rod",
		"minecraft:carrot_on_a_stick", "minecraft:warped_fungus_on_a_stick",
		"minecraft:brush",
		// shulker boxes
		"minecraft:shulker_box", "minecraft:white_shulker_box",
		"minecraft:orange_shulker_box", "minecraft:magenta_shulker_box",
		"minecraft:light_blue_shulker_box", "minecraft:yellow_shulker_box",
		"minecraft:lime_shulker_box", "minecraft:pink_shulker_box",
		"minecraft:gray_shulker_box", "minecraft:light_gray_shulker_box",
		"minecraft:cyan_shulker_box", "minecraft:purple_shulker_box",
		"minecraft:blue_shulker_box", "minecraft:brown_shulker_box",
		"minecraft:green_shulker_box", "minecraft:red_shulker_box",
		"minecraft:black_shulker_box"};
	
	private final ItemListSetting items = new ItemListSetting("Items",
		"Items that can't be dropped while AntiDrop is enabled.",
		DEFAULT_ITEMS);
	private boolean temporarilyBypass;
	
	public AntiDropHack()
	{
		super("AntiDrop");
		setCategory(Category.ITEMS);
		addSetting(items);
	}
	
	public boolean shouldBlock(ItemStack stack)
	{
		if(temporarilyBypass)
			return false;
		
		return isProtectedItem(stack);
	}
	
	public boolean isProtectedItem(ItemStack stack)
	{
		if(stack == null || stack.isEmpty())
			return false;
		
		String itemName =
			BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
		return items.getItemNames().contains(itemName);
	}
	
	public void setTemporarilyBypass(boolean value)
	{
		temporarilyBypass = value;
	}
	
	public boolean isTemporarilyBypassing()
	{
		return temporarilyBypass;
	}
}
