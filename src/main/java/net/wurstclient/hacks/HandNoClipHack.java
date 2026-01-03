/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.TridentItem;
import java.util.Locale;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.GUIRenderListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.BlockListSetting;
import net.wurstclient.util.BlockUtils;

@SearchTags({"hand noclip", "hand no clip"})
public final class HandNoClipHack extends Hack implements GUIRenderListener
{
	private static final int WARNING_COLOR = 0xFFFF0000;
	private static final int WARNING_SIZE = 5;
	
	private final BlockListSetting blocks = new BlockListSetting("Blocks",
		"The blocks you want to reach through walls.", "minecraft:barrel",
		"minecraft:black_shulker_box", "minecraft:blue_shulker_box",
		"minecraft:brown_shulker_box", "minecraft:chest",
		"minecraft:cyan_shulker_box", "minecraft:dispenser",
		"minecraft:dropper", "minecraft:ender_chest",
		"minecraft:gray_shulker_box", "minecraft:green_shulker_box",
		"minecraft:hopper", "minecraft:light_blue_shulker_box",
		"minecraft:light_gray_shulker_box", "minecraft:lime_shulker_box",
		"minecraft:magenta_shulker_box", "minecraft:orange_shulker_box",
		"minecraft:pink_shulker_box", "minecraft:purple_shulker_box",
		"minecraft:red_shulker_box", "minecraft:shulker_box",
		"minecraft:trapped_chest", "minecraft:white_shulker_box",
		"minecraft:yellow_shulker_box");
	
	public HandNoClipHack()
	{
		super("HandNoClip");
		
		setCategory(Category.BLOCKS);
		addSetting(blocks);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(GUIRenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(GUIRenderListener.class, this);
	}
	
	public boolean isBlockInList(BlockPos pos)
	{
		return blocks.matchesBlock(BlockUtils.getBlock(pos));
	}
	
	@Override
	public void onRenderGUI(GuiGraphics context, float partialTicks)
	{
		if(MC.player == null || isHoldingCombatWeapon())
			return;
		
		drawWarningCrosshair(context);
	}
	
	private boolean isHoldingCombatWeapon()
	{
		ItemStack stack = MC.player.getInventory().getSelectedItem();
		if(stack == null || stack.isEmpty())
			return false;
		
		Item item = stack.getItem();
		return stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES)
			|| item instanceof MaceItem || item instanceof TridentItem
			|| item instanceof BowItem || item instanceof CrossbowItem
			|| isSpearItem(item);
	}
	
	private boolean isSpearItem(Item item)
	{
		if(item == null)
			return false;
		
		Identifier id = BuiltInRegistries.ITEM.getKey(item);
		if(id == null)
			return false;
		
		return id.getPath().toLowerCase(Locale.ROOT).contains("spear");
	}
	
	private void drawWarningCrosshair(GuiGraphics context)
	{
		int centerX = context.guiWidth() / 2;
		int centerY = context.guiHeight() / 2;
		
		for(int i = -WARNING_SIZE; i <= WARNING_SIZE; i++)
		{
			int x1 = centerX + i;
			int y1 = centerY + i;
			context.fill(x1, y1, x1 + 1, y1 + 1, WARNING_COLOR);
			
			int x2 = centerX + i;
			int y2 = centerY - i;
			context.fill(x2, y2, x2 + 1, y2 + 1, WARNING_COLOR);
		}
	}
	
	// See AbstractBlockStateMixin.onGetOutlineShape()
}
