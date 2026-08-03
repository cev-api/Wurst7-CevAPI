/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.lootsorter;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.tags.EnchantmentTags;

/**
 * Broad categories use components/classes first and registry names only for
 * families which vanilla does not currently expose as a common item class.
 */
public enum BuiltInItemFilter implements ItemFilter
{
	ALL("Everything (move all items)", 10),
	WEAPONS("Weapons", 20),
	TOOLS("Tools", 20),
	ARMOUR("Armour", 20),
	FOOD("Food", 20),
	POTIONS("Potions", 20),
	ENCHANTED_BOOKS("Enchanted books", 30),
	BUILDING_BLOCKS("Building blocks", 20),
	NATURAL_BLOCKS("Natural blocks", 30),
	ORES_AND_MATERIALS("Ores and materials", 30),
	REDSTONE("Redstone", 30),
	FARMING("Farming", 30),
	MOB_DROPS("Mob drops", 30),
	TRANSPORTATION("Transportation", 30),
	UTILITY_ITEMS("Utility items", 30),
	DECORATIVE_ITEMS("Decorative items", 30),
	MISCELLANEOUS("Miscellaneous", 10),
	SWORDS("Swords", 40),
	WEAPON_AXES("Weapon axes", 40),
	BOWS_AND_CROSSBOWS("Bows and crossbows", 40),
	TRIDENTS("Tridents", 40),
	MACES("Maces", 40),
	SPEARS("Spears", 40),
	PICKAXES("Pickaxes", 40),
	TOOL_AXES("Tool axes", 40),
	SHOVELS("Shovels", 40),
	HOES("Hoes", 40),
	SHEARS("Shears", 40),
	FISHING_RODS("Fishing rods", 40),
	HELMETS("Helmets", 40),
	CHESTPLATES("Chestplates", 40),
	LEGGINGS("Leggings", 40),
	BOOTS("Boots", 40),
	SHIELDS("Shields", 40),
	ELYTRA("Elytra", 40),
	STONE_LIKE("Stone-like blocks", 40),
	WOOD("Wood", 40),
	GLASS("Glass", 40),
	CONCRETE_TERRACOTTA("Concrete and terracotta", 40),
	BRICKS("Bricks", 40),
	SLABS_STAIRS_WALLS("Slabs, stairs and walls", 40),
	LIGHTING("Lighting", 40),
	DIRT_GRASS_MUD("Dirt, grass and mud", 40),
	SAND_GRAVEL("Sand and gravel", 40),
	LOGS_LEAVES("Logs and leaves", 40),
	FLOWERS_PLANTS("Flowers and plants", 40),
	NETHER_BLOCKS("Nether blocks", 40),
	END_BLOCKS("End blocks", 40),
	ICE_SNOW("Ice and snow", 40),
	REDSTONE_COMPONENTS("Redstone components", 40),
	RAILS("Rails", 40),
	PISTONS("Pistons", 40),
	STORAGE_COMPONENTS("Storage components", 40),
	REDSTONE_BLOCKS_AND_DUST("Redstone blocks and dust", 40),
	DECORATIVE_BLOCKS("Decorative blocks", 40),
	WEAPONS_ENCHANTED("Enchanted weapons", 45),
	WEAPONS_UNENCHANTED("Non-enchanted weapons", 45),
	TOOLS_ENCHANTED("Enchanted tools", 45),
	TOOLS_UNENCHANTED("Non-enchanted tools", 45),
	ARMOUR_ENCHANTED("Enchanted armour", 45),
	ARMOUR_UNENCHANTED("Non-enchanted armour", 45),
	ENCHANTED_BOOK_LEVEL_1("Enchanted books: level 1+", 45),
	ENCHANTED_BOOK_LEVEL_2("Enchanted books: level 2+", 45),
	ENCHANTED_BOOK_LEVEL_3("Enchanted books: level 3+", 45),
	ENCHANTED_BOOK_LEVEL_4("Enchanted books: level 4+", 45),
	ENCHANTED_BOOK_LEVEL_5("Enchanted books: level 5+", 45),
	ENCHANTED_BOOK_TREASURE("Enchanted books: treasure", 45),
	ENCHANTED_BOOK_CURSES("Enchanted books: curses", 45),
	TOTEMS_OF_UNDYING("Totems of Undying", 50);
	
	private final String name;
	private final int specificity;
	
	BuiltInItemFilter(String name, int specificity)
	{
		this.name = name;
		this.specificity = specificity;
	}
	
	@Override
	public boolean matches(ItemStack stack)
	{
		if(stack.isEmpty())
			return false;
		return switch(this)
		{
			case ALL, MISCELLANEOUS -> true;
			case WEAPONS -> stack.is(ItemTags.SWORDS)
				|| stack.is(ItemTags.SPEARS) || path(stack, "trident", "mace")
				|| stack.getItem() instanceof AxeItem
				|| stack.getItem() instanceof BowItem
				|| stack.getItem() instanceof CrossbowItem;
			case TOOLS -> stack.is(ItemTags.PICKAXES) || stack.is(ItemTags.AXES)
				|| stack.is(ItemTags.SHOVELS) || stack.is(ItemTags.HOES)
				|| path(stack, "fishing_rod", "shears")
				|| stack.getItem() instanceof AxeItem
				|| stack.getItem() instanceof ShovelItem
				|| stack.getItem() instanceof HoeItem;
			case ARMOUR -> stack.has(DataComponents.EQUIPPABLE);
			case FOOD -> stack.has(DataComponents.FOOD);
			case POTIONS -> stack.has(DataComponents.POTION_CONTENTS);
			case ENCHANTED_BOOKS -> stack
				.has(DataComponents.STORED_ENCHANTMENTS);
			case BUILDING_BLOCKS -> stack.getItem() instanceof BlockItem;
			case NATURAL_BLOCKS -> path(stack, "dirt", "grass", "mud", "sand",
				"gravel", "log", "leaves", "flower", "plant", "netherrack",
				"end_stone", "ice", "snow");
			case ORES_AND_MATERIALS -> path(stack, "ore", "ingot", "raw_",
				"diamond", "emerald", "lapis", "quartz", "amethyst", "copper",
				"iron", "gold");
			case REDSTONE -> path(stack, "redstone", "repeater", "comparator",
				"piston", "observer", "rail", "hopper", "dispenser", "dropper");
			case FARMING -> path(stack, "seed", "wheat", "carrot", "potato",
				"beetroot", "sugar_cane", "kelp", "cactus", "sapling",
				"bone_meal");
			case MOB_DROPS -> path(stack, "rotten_flesh", "bone", "string",
				"gunpowder", "spider_eye", "slime", "leather", "feather",
				"blaze", "ghast", "ender_pearl", "totem");
			case TRANSPORTATION -> path(stack, "boat", "minecart", "saddle",
				"elytra");
			case UTILITY_ITEMS -> path(stack, "bucket", "shears",
				"flint_and_steel", "clock", "compass", "lead", "name_tag",
				"totem", "shield");
			case DECORATIVE_ITEMS -> path(stack, "banner", "painting", "pot",
				"carpet", "coral", "lantern", "candle", "flower", "head",
				"skull");
			case SWORDS -> stack.is(ItemTags.SWORDS);
			case WEAPON_AXES -> stack.is(ItemTags.AXES);
			case BOWS_AND_CROSSBOWS -> stack.getItem() instanceof BowItem
				|| stack.getItem() instanceof CrossbowItem;
			case TRIDENTS -> path(stack, "trident");
			case MACES -> path(stack, "mace");
			case SPEARS -> stack.is(ItemTags.SPEARS);
			case PICKAXES -> stack.is(ItemTags.PICKAXES);
			case TOOL_AXES -> stack.is(ItemTags.AXES);
			case SHOVELS -> stack.is(ItemTags.SHOVELS);
			case HOES -> stack.is(ItemTags.HOES);
			case SHEARS -> path(stack, "shears");
			case FISHING_RODS -> path(stack, "fishing_rod");
			case HELMETS -> path(stack, "helmet");
			case CHESTPLATES -> path(stack, "chestplate");
			case LEGGINGS -> path(stack, "leggings");
			case BOOTS -> path(stack, "boots");
			case SHIELDS -> path(stack, "shield");
			case ELYTRA -> path(stack, "elytra");
			case STONE_LIKE -> path(stack, "stone", "deepslate", "andesite",
				"diorite", "granite");
			case WOOD -> path(stack, "log", "wood", "planks", "stem", "hyphae");
			case GLASS -> path(stack, "glass");
			case CONCRETE_TERRACOTTA -> path(stack, "concrete", "terracotta");
			case BRICKS -> path(stack, "brick");
			case SLABS_STAIRS_WALLS -> path(stack, "slab", "stairs", "wall");
			case LIGHTING -> path(stack, "torch", "lantern", "glowstone",
				"sea_lantern", "lamp");
			case DIRT_GRASS_MUD -> path(stack, "dirt", "grass", "mud");
			case SAND_GRAVEL -> path(stack, "sand", "gravel");
			case LOGS_LEAVES -> path(stack, "log", "leaves", "stem", "hyphae");
			case FLOWERS_PLANTS -> path(stack, "flower", "sapling", "bush",
				"fern", "plant");
			case NETHER_BLOCKS -> path(stack, "nether", "crimson", "warped",
				"soul_");
			case END_BLOCKS -> path(stack, "end_stone", "chorus", "purpur");
			case ICE_SNOW -> path(stack, "ice", "snow");
			case REDSTONE_COMPONENTS -> path(stack, "redstone", "repeater",
				"comparator", "observer");
			case RAILS -> path(stack, "rail");
			case PISTONS -> path(stack, "piston");
			case STORAGE_COMPONENTS -> path(stack, "chest", "barrel", "shulker",
				"hopper");
			case REDSTONE_BLOCKS_AND_DUST -> path(stack, "redstone",
				"redstone_block");
			case DECORATIVE_BLOCKS -> stack.getItem() instanceof BlockItem
				&& path(stack, "banner", "pot", "carpet", "coral", "lantern",
					"candle", "flower", "head", "skull", "moss", "azalea");
			case WEAPONS_ENCHANTED -> isWeapon(stack) && hasEnchantments(stack);
			case WEAPONS_UNENCHANTED -> isWeapon(stack)
				&& !hasEnchantments(stack);
			case TOOLS_ENCHANTED -> isTool(stack) && hasEnchantments(stack);
			case TOOLS_UNENCHANTED -> isTool(stack) && !hasEnchantments(stack);
			case ARMOUR_ENCHANTED -> stack.has(DataComponents.EQUIPPABLE)
				&& hasEnchantments(stack);
			case ARMOUR_UNENCHANTED -> stack.has(DataComponents.EQUIPPABLE)
				&& !hasEnchantments(stack);
			case ENCHANTED_BOOK_LEVEL_1 -> isBookAtLeast(stack, 1);
			case ENCHANTED_BOOK_LEVEL_2 -> isBookAtLeast(stack, 2);
			case ENCHANTED_BOOK_LEVEL_3 -> isBookAtLeast(stack, 3);
			case ENCHANTED_BOOK_LEVEL_4 -> isBookAtLeast(stack, 4);
			case ENCHANTED_BOOK_LEVEL_5 -> isBookAtLeast(stack, 5);
			case ENCHANTED_BOOK_TREASURE -> stack
				.has(DataComponents.STORED_ENCHANTMENTS)
				&& EnchantmentHelper.getEnchantmentsForCrafting(stack)
					.entrySet().stream().anyMatch(
						entry -> entry.getKey().is(EnchantmentTags.TREASURE));
			case ENCHANTED_BOOK_CURSES -> stack
				.has(DataComponents.STORED_ENCHANTMENTS)
				&& EnchantmentHelper.getEnchantmentsForCrafting(stack)
					.entrySet().stream().anyMatch(
						entry -> entry.getKey().is(EnchantmentTags.CURSE));
			case TOTEMS_OF_UNDYING -> stack.is(Items.TOTEM_OF_UNDYING);
		};
	}
	
	private static boolean isWeapon(ItemStack stack)
	{
		return stack.is(ItemTags.SWORDS) || stack.is(ItemTags.SPEARS)
			|| path(stack, "trident", "mace")
			|| stack.getItem() instanceof AxeItem
			|| stack.getItem() instanceof BowItem
			|| stack.getItem() instanceof CrossbowItem;
	}
	
	private static boolean isTool(ItemStack stack)
	{
		return stack.is(ItemTags.PICKAXES) || stack.is(ItemTags.AXES)
			|| stack.is(ItemTags.SHOVELS) || stack.is(ItemTags.HOES)
			|| path(stack, "fishing_rod", "shears")
			|| stack.getItem() instanceof AxeItem
			|| stack.getItem() instanceof ShovelItem
			|| stack.getItem() instanceof HoeItem;
	}
	
	private static boolean hasEnchantments(ItemStack stack)
	{
		return !EnchantmentHelper.getEnchantmentsForCrafting(stack).isEmpty();
	}
	
	private static boolean isBookAtLeast(ItemStack stack, int level)
	{
		return stack.has(DataComponents.STORED_ENCHANTMENTS)
			&& EnchantmentHelper.getEnchantmentsForCrafting(stack).entrySet()
				.stream().anyMatch(entry -> entry.getIntValue() >= level);
	}
	
	private static boolean path(ItemStack stack, String... parts)
	{
		String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
		for(String part : parts)
			if(path.contains(part))
				return true;
		return false;
	}
	
	@Override
	public int specificity()
	{
		return specificity;
	}
	
	@Override
	public String getDisplayName()
	{
		return name;
	}
}
