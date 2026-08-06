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
	AUTOSORT("Autosort (match container contents)", 60),
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
	BOATS_AND_RAFTS("Boats and rafts", 40),
	MINECARTS("Minecarts", 40),
	HORSE_EQUIPMENT("Horse equipment", 40),
	TRANSPORTATION_MISC("Other transportation", 40),
	SEEDS("Seeds", 40),
	CROPS("Crops", 40),
	FARMING_TOOLS("Farming tools", 40),
	ANIMAL_PRODUCTS("Animal products", 40),
	ORES("Ores", 40),
	INGOTS_AND_GEMS("Ingots and gems", 40),
	CRAFTING_MATERIALS("Crafting materials", 40),
	BUCKETS("Buckets", 40),
	CONTAINERS("Containers", 40),
	UTILITY_TOOLS("Utility tools", 40),
	HORSE_AND_PLAYER_EQUIPMENT("Equipment", 40),
	MOB_DROPS_COMMON("Common mob drops", 40),
	MOB_DROPS_RARE("Rare mob drops", 40),
	MOB_DROPS_NETHER("Nether mob drops", 40),
	REDSTONE_SIGNAL("Redstone signals", 40),
	REDSTONE_DEVICES("Redstone devices", 40),
	WORKSTATIONS("Workstations", 40),
	END_MATERIALS("End materials", 40),
	NETHER_MATERIALS("Nether materials", 40),
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
			case ALL, AUTOSORT, MISCELLANEOUS -> true;
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
				"gravel", "clay", "mycelium", "podzol", "root", "log", "leaves",
				"stem", "hyphae", "flower", "plant", "bamboo", "moss",
				"netherrack", "end_stone", "ice", "snow");
			case ORES_AND_MATERIALS -> isOreOrMaterial(stack);
			case REDSTONE -> isRedstone(stack);
			case FARMING -> path(stack, "seed", "sapling", "wheat", "carrot",
				"potato", "beetroot", "sugar_cane", "kelp", "cactus", "melon",
				"pumpkin", "cocoa", "berry", "mushroom", "wart", "bamboo",
				"hay", "composter", "bone_meal", "honeycomb");
			case MOB_DROPS -> isMobDrop(stack);
			case TRANSPORTATION -> path(stack, "boat", "minecart", "saddle",
				"elytra");
			case UTILITY_ITEMS -> path(stack, "bucket", "shears",
				"flint_and_steel", "clock", "compass", "lead", "name_tag",
				"totem", "shield");
			case DECORATIVE_ITEMS -> path(stack, "banner", "painting",
				"flower_pot", "decorated_pot", "carpet", "coral", "lantern",
				"candle", "flower", "head", "skull");
			case BOATS_AND_RAFTS -> path(stack, "boat", "raft");
			case MINECARTS -> path(stack, "minecart");
			case HORSE_EQUIPMENT -> path(stack, "saddle", "horse_armor");
			case TRANSPORTATION_MISC -> path(stack, "elytra", "lead");
			case SEEDS -> path(stack, "seed", "sapling", "bamboo",
				"cocoa_beans", "wart");
			case CROPS -> path(stack, "wheat", "carrot", "potato", "beetroot",
				"sugar_cane", "kelp", "cactus", "melon", "pumpkin", "cocoa",
				"berry", "mushroom", "wart", "bamboo", "honeycomb");
			case FARMING_TOOLS -> path(stack, "hoe", "shears", "bone_meal");
			case ANIMAL_PRODUCTS -> path(stack, "leather", "feather", "egg",
				"milk", "rabbit", "mutton", "beef", "porkchop", "chicken",
				"wool", "honey", "ink_sac", "glow_ink_sac", "scute",
				"turtle_helmet");
			case ORES -> path(stack, "ore", "raw_", "ancient_debris");
			case INGOTS_AND_GEMS -> path(stack, "ingot", "diamond", "emerald",
				"lapis", "quartz", "amethyst");
			case CRAFTING_MATERIALS -> path(stack, "stick", "string", "leather",
				"flint", "clay", "paper", "slime", "feather", "ink_sac",
				"honeycomb", "scute", "prismarine_shard", "amethyst_shard");
			case BUCKETS -> path(stack, "bucket");
			case CONTAINERS -> exactPath(stack, "chest", "trapped_chest",
				"ender_chest", "barrel", "shulker_box", "hopper", "bundle");
			case UTILITY_TOOLS -> path(stack, "flint_and_steel", "fishing_rod",
				"shears", "compass", "clock", "spyglass");
			case HORSE_AND_PLAYER_EQUIPMENT -> path(stack, "shield", "elytra",
				"helmet", "chestplate", "leggings", "boots");
			case MOB_DROPS_COMMON -> path(stack, "rotten_flesh", "bone",
				"string", "string", "gunpowder", "spider_eye", "feather",
				"leather", "wool", "ink_sac", "rabbit", "mutton", "beef",
				"porkchop", "chicken");
			case MOB_DROPS_RARE -> path(stack, "ender_pearl", "shulker_shell",
				"phantom_membrane", "heart_of_the_sea", "totem", "rabbit_foot",
				"nautilus_shell", "scute", "prismarine_shard",
				"prismarine_crystals");
			case MOB_DROPS_NETHER -> path(stack, "blaze", "ghast", "magma",
				"wither", "nether_star");
			case REDSTONE_SIGNAL -> path(stack, "redstone", "repeater",
				"comparator", "target", "lever", "button", "daylight_detector",
				"tripwire_hook", "redstone_torch");
			case REDSTONE_DEVICES -> path(stack, "piston", "observer",
				"dispenser", "dropper", "hopper", "rail", "redstone_lamp",
				"note_block", "crafter", "sculk_sensor");
			case WORKSTATIONS -> path(stack, "crafting_table", "furnace",
				"blast_furnace", "smoker", "stonecutter", "smithing_table",
				"cartography_table", "fletching_table", "loom", "grindstone",
				"brewing_stand", "enchanting_table", "anvil", "composter",
				"crafter", "lectern", "beacon", "cauldron");
			case END_MATERIALS -> path(stack, "end_stone", "purpur", "chorus",
				"shulker_shell");
			case NETHER_MATERIALS -> path(stack, "netherrack", "nether_brick",
				"quartz", "glowstone", "crimson", "warped", "basalt",
				"blackstone");
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
				"diorite", "granite")
				&& !exactPath(stack, "redstone", "redstone_block",
					"redstone_torch", "redstone_wall_torch");
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
			case REDSTONE_COMPONENTS -> isRedstoneComponent(stack);
			case RAILS -> path(stack, "rail");
			case PISTONS -> path(stack, "piston");
			case STORAGE_COMPONENTS -> path(stack, "chest", "barrel", "shulker",
				"hopper");
			case REDSTONE_BLOCKS_AND_DUST -> exactPath(stack, "redstone",
				"redstone_block", "redstone_torch", "redstone_wall_torch");
			case DECORATIVE_BLOCKS -> stack.getItem() instanceof BlockItem
				&& path(stack, "banner", "flower_pot", "decorated_pot",
					"carpet", "coral", "lantern", "candle", "flower", "head",
					"skull", "moss", "azalea");
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
	
	private static boolean isRedstone(ItemStack stack)
	{
		return isRedstoneComponent(stack) || path(stack, "piston", "rail",
			"hopper", "dispenser", "dropper", "tnt", "trapped_chest");
	}
	
	private static boolean isRedstoneComponent(ItemStack stack)
	{
		return exactPath(stack, "redstone", "redstone_block", "redstone_torch",
			"redstone_wall_torch", "repeater", "comparator", "observer",
			"lever", "stone_button", "oak_button", "spruce_button",
			"birch_button", "jungle_button", "acacia_button", "dark_oak_button",
			"mangrove_button", "cherry_button", "bamboo_button",
			"crimson_button", "warped_button", "polished_blackstone_button",
			"target", "daylight_detector", "tripwire_hook", "redstone_lamp",
			"note_block", "crafter", "sculk_sensor", "calibrated_sculk_sensor");
	}
	
	private static boolean isOreOrMaterial(ItemStack stack)
	{
		return path(stack, "ore", "raw_", "ancient_debris", "coal", "charcoal",
			"diamond", "emerald", "lapis", "quartz", "amethyst", "copper_ingot",
			"iron_ingot", "gold_ingot", "netherite_ingot", "netherite_scrap");
	}
	
	private static boolean isMobDrop(ItemStack stack)
	{
		return path(stack, "rotten_flesh", "bone", "string", "gunpowder",
			"spider_eye", "slime", "leather", "feather", "wool", "ink_sac",
			"glow_ink_sac", "blaze", "ghast", "magma", "ender_pearl",
			"shulker_shell", "phantom_membrane", "heart_of_the_sea", "totem",
			"rabbit_foot", "nautilus_shell", "scute", "prismarine_shard",
			"prismarine_crystals", "wither_skeleton_skull", "dragon_breath");
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
	
	private static boolean exactPath(ItemStack stack, String... names)
	{
		String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
		for(String name : names)
			if(path.equals(name))
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
