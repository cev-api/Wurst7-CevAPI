/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.Optional;

import net.minecraft.core.Holder.Reference;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.BlockListSetting;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.util.ChatUtils;

@SearchTags({"silkonly", "silk only", "silk touch only"})
public final class SilkOnlyHack extends Hack
{
	private final BlockListSetting blockList = new BlockListSetting(
		"Block List", "Blocks that can only be broken with Silk Touch enabled.",
		"minecraft:ender_chest", "minecraft:bookshelf",
		"minecraft:amethyst_cluster",
		
		// Glass (any kind)
		"minecraft:glass", "minecraft:tinted_glass", "minecraft:glass_pane",
		"minecraft:white_stained_glass", "minecraft:orange_stained_glass",
		"minecraft:magenta_stained_glass", "minecraft:light_blue_stained_glass",
		"minecraft:yellow_stained_glass", "minecraft:lime_stained_glass",
		"minecraft:pink_stained_glass", "minecraft:gray_stained_glass",
		"minecraft:light_gray_stained_glass", "minecraft:cyan_stained_glass",
		"minecraft:purple_stained_glass", "minecraft:blue_stained_glass",
		"minecraft:brown_stained_glass", "minecraft:green_stained_glass",
		"minecraft:red_stained_glass", "minecraft:black_stained_glass",
		"minecraft:white_stained_glass_pane",
		"minecraft:orange_stained_glass_pane",
		"minecraft:magenta_stained_glass_pane",
		"minecraft:light_blue_stained_glass_pane",
		"minecraft:yellow_stained_glass_pane",
		"minecraft:lime_stained_glass_pane",
		"minecraft:pink_stained_glass_pane",
		"minecraft:gray_stained_glass_pane",
		"minecraft:light_gray_stained_glass_pane",
		"minecraft:cyan_stained_glass_pane",
		"minecraft:purple_stained_glass_pane",
		"minecraft:blue_stained_glass_pane",
		"minecraft:brown_stained_glass_pane",
		"minecraft:green_stained_glass_pane",
		"minecraft:red_stained_glass_pane",
		"minecraft:black_stained_glass_pane",
		
		// Light blocks people often want to preserve
		"minecraft:glowstone", "minecraft:sea_lantern",
		
		// Silverfish
		"minecraft:infested_stone");
	
	private final CheckboxSetting chatMessage = new CheckboxSetting(
		"Chat message",
		"Print a chat message when refusing to break a listed block.", true);
	
	private net.minecraft.core.BlockPos lastRefusePos;
	private long lastRefuseMs;
	
	public SilkOnlyHack()
	{
		super("SilkOnly");
		setCategory(Category.BLOCKS);
		addSetting(blockList);
		addSetting(chatMessage);
	}
	
	/**
	 * Returns true if the hack should refuse to break the given block state
	 * with the given tool.
	 */
	public boolean shouldRefuseBreak(net.minecraft.core.BlockPos pos,
		BlockState state, ItemStack tool)
	{
		if(!isEnabled() || pos == null || state == null)
			return false;
		if(MC.player == null || MC.level == null)
			return false;
		if(MC.player.getAbilities().instabuild)
			return false;
		
		Block block = state.getBlock();
		if(block == null || !blockList.contains(block))
			return false;
		
		if(hasSilkTouch(tool))
			return false;
		
		maybeMessage(pos, block);
		return true;
	}
	
	private void maybeMessage(net.minecraft.core.BlockPos pos, Block block)
	{
		if(!chatMessage.isChecked())
			return;
		
		long now = System.currentTimeMillis();
		if(lastRefusePos != null && lastRefusePos.equals(pos)
			&& now - lastRefuseMs < 1000L)
			return;
		
		lastRefusePos = pos;
		lastRefuseMs = now;
		
		String name = getBlockDisplayName(block);
		ChatUtils.message("Refusing To Break " + name + ". Use Silk Touch!");
	}
	
	private static String getBlockDisplayName(Block block)
	{
		if(block == null)
			return "block";
		
		try
		{
			ItemStack asItem = new ItemStack(block.asItem());
			if(!asItem.isEmpty())
			{
				String s = asItem.getHoverName().getString();
				if(s != null && !s.isBlank())
					return s;
			}
		}catch(Throwable ignored)
		{}
		
		try
		{
			return net.minecraft.core.registries.BuiltInRegistries.BLOCK
				.getKey(block).toString();
		}catch(Throwable ignored)
		{
			return "block";
		}
	}
	
	private static boolean hasSilkTouch(ItemStack stack)
	{
		if(stack == null || stack.isEmpty())
			return false;
		
		if(MC.level == null)
			return false;
		
		try
		{
			RegistryAccess access = MC.level.registryAccess();
			Registry<Enchantment> registry =
				access.lookupOrThrow(Registries.ENCHANTMENT);
			Optional<Reference<Enchantment>> silk =
				registry.get(Enchantments.SILK_TOUCH);
			
			int lvl = silk.map(
				ref -> EnchantmentHelper.getItemEnchantmentLevel(ref, stack))
				.orElse(0);
			return lvl > 0;
			
		}catch(Throwable t)
		{
			return false;
		}
	}
}
