/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/** Shared loot categories and upgrade ranking for AutoLoot and ItemHandler. */
public final class LootItemPolicy
{
	private LootItemPolicy()
	{}
	
	public static String type(ItemStack stack)
	{
		return type(stack.getItem());
	}
	
	private static String type(net.minecraft.world.item.Item item)
	{
		String id = BuiltInRegistries.ITEM.getKey(item).getPath();
		for(String suffix : List.of("helmet", "chestplate", "leggings", "boots",
			"pickaxe", "sword", "shovel", "hoe", "axe"))
			if(id.endsWith("_" + suffix))
				return suffix;
		if(List.of("bow", "crossbow", "trident", "mace", "shield",
			"fishing_rod", "elytra").contains(id))
			return id;
		return "item:" + id;
	}
	
	public static boolean isGear(ItemStack stack)
	{
		return !stack.isEmpty() && !type(stack).startsWith("item:");
	}
	
	public static String[] defaultWhitelist()
	{
		return BuiltInRegistries.ITEM.stream().filter(item -> {
			String id = BuiltInRegistries.ITEM.getKey(item).getPath();
			return !type(item).startsWith("item:") || id.endsWith("shulker_box")
				|| List.of("diamond", "diamond_block", "emerald",
					"emerald_block", "netherite_ingot", "netherite_block",
					"netherite_scrap", "ancient_debris", "gold_ingot",
					"gold_block", "iron_ingot", "iron_block", "lapis_lazuli",
					"enchanted_golden_apple", "golden_apple",
					"totem_of_undying", "enchanted_book", "experience_bottle",
					"ender_pearl", "ender_eye", "end_crystal",
					"firework_rocket", "cooked_beef", "golden_carrot",
					"nether_star", "heart_of_the_sea", "sponge", "wet_sponge",
					"dragon_egg", "heavy_core",
					"netherite_upgrade_smithing_template").contains(id);
		}).map(item -> BuiltInRegistries.ITEM.getKey(item).toString())
			.toArray(String[]::new);
	}
	
	/** Heuristic value for browsing, not a promise of a server market price. */
	public static int value(ItemStack stack)
	{
		if(stack == null || stack.isEmpty())
			return 0;
		String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
		int rarity = id.contains("netherite")
			|| List
				.of("elytra", "nether_star", "dragon_egg", "heavy_core",
					"enchanted_golden_apple", "totem_of_undying")
				.contains(id)
					? 100
					: id.contains("diamond") || id.endsWith("shulker_box") ? 80
						: id.contains("emerald") || id.equals("enchanted_book")
							? 60
							: id.contains("gold") || id.equals("ender_pearl")
								? 40 : isGear(stack) ? 30 : 0;
		return rarity * 1000000 + Math.max(0, score(stack));
	}
	
	public static int score(ItemStack stack)
	{
		if(stack == null || stack.isEmpty())
			return Integer.MIN_VALUE;
		String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
		int material = id.startsWith("netherite_") ? 6
			: id.startsWith("diamond_") ? 5
				: id.startsWith("iron_") ? 4
					: id.startsWith("chainmail_") ? 3 : id.startsWith("stone_")
						? 2 : id.startsWith("golden_") ? 1 : 0;
		int enchants = EnchantmentHelper.getEnchantmentsForCrafting(stack)
			.entrySet().stream().mapToInt(e -> {
				String name = e.getKey().getRegisteredName();
				int weight = name.endsWith(":protection")
					|| name.endsWith(":sharpness") || name.endsWith(":power")
					|| name.endsWith(":efficiency") ? 100 : 10;
				if(name.endsWith("_curse"))
					weight = -100;
				return e.getIntValue() * weight;
			}).sum();
		int durability = stack.isDamageableItem()
			? (int)(999.0 * (stack.getMaxDamage() - stack.getDamageValue())
				/ Math.max(1, stack.getMaxDamage()))
			: 0;
		return material * 1000000 + enchants * 1000 + durability;
	}
}
