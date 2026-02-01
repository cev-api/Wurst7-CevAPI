/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.Locale;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.GUIRenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.BlockListSetting;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.RotationUtils;
import net.wurstclient.util.text.WText;

@SearchTags({"hand noclip", "hand no clip"})
public final class HandNoClipHack extends Hack
	implements GUIRenderListener, UpdateListener
{
	private static final int WARNING_COLOR = 0xFFFF0000;
	private static final int WARNING_SIZE = 5;
	private static final double TARGET_RANGE = 6.0;
	private static final double TARGET_INCREMENT = 0.1;
	
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
	private final CheckboxSetting enableOnTarget = new CheckboxSetting(
		"Enable On Target",
		WText.literal("Only noclip while aiming at a listed block."), false);
	
	private final CheckboxSetting villagerThroughWalls = new CheckboxSetting(
		"Villagers Through Walls",
		WText.literal(
			"Allows interacting with villagers through walls when aiming at them."
				+ " Only works with Enable On Target."),
		false);
	
	private BlockPos targetBlock;
	private boolean targetVillager;
	private Set<BlockPos> occludingBlocks = Collections.emptySet();
	
	public HandNoClipHack()
	{
		super("HandNoClip");
		
		setCategory(Category.BLOCKS);
		addSetting(blocks);
		addSetting(enableOnTarget);
		addSetting(villagerThroughWalls);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(GUIRenderListener.class, this);
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(GUIRenderListener.class, this);
		EVENTS.remove(UpdateListener.class, this);
		clearTarget();
	}
	
	public boolean isBlockInList(BlockPos pos)
	{
		return blocks.matchesBlock(BlockUtils.getBlock(pos));
	}
	
	public boolean shouldClearBlock(BlockPos pos)
	{
		if(enableOnTarget.isChecked())
			return occludingBlocks.contains(pos);
		
		return !isBlockInList(pos);
	}
	
	@Override
	public void onUpdate()
	{
		if(!enableOnTarget.isChecked() || MC.player == null || MC.level == null)
		{
			clearTarget();
			return;
		}
		
		updateTargeting();
	}
	
	private void clearTarget()
	{
		targetBlock = null;
		targetVillager = false;
		occludingBlocks = Collections.emptySet();
	}
	
	private void updateTargeting()
	{
		Vec3 eyes = RotationUtils.getEyesPos();
		Vec3 look = RotationUtils.getServerLookVec();
		
		BlockPos prev = null;
		Set<BlockPos> path = new HashSet<>();
		BlockPos foundBlock = null;
		boolean foundVillager = false;
		
		double villagerHitDist = Double.NaN;
		if(villagerThroughWalls.isChecked())
		{
			Vec3 end = eyes.add(look.scale(TARGET_RANGE));
			villagerHitDist = getClosestVillagerHitDistance(eyes, end);
		}
		
		for(double distance = 0; distance <= TARGET_RANGE; distance +=
			TARGET_INCREMENT)
		{
			Vec3 point = eyes.add(look.scale(distance));
			BlockPos pos = BlockPos.containing(point);
			
			if(pos.equals(prev))
				continue;
			
			prev = pos;
			
			if(MC.level.isEmptyBlock(pos))
				continue;
			
			if(isBlockInList(pos))
			{
				foundBlock = pos;
				break;
			}
			
			// If aiming at a villager and we have reached it, stop here
			if(!Double.isNaN(villagerHitDist) && distance >= villagerHitDist)
			{
				foundVillager = true;
				break;
			}
			
			path.add(pos);
		}
		
		if(foundBlock == null && !foundVillager)
		{
			clearTarget();
			return;
		}
		
		targetBlock = foundBlock;
		targetVillager = foundVillager;
		occludingBlocks = path.isEmpty() ? Collections.emptySet()
			: Collections.unmodifiableSet(path);
	}
	
	@Override
	public void onRenderGUI(GuiGraphics context, float partialTicks)
	{
		if(MC.player == null || !shouldDrawWarningCrosshair())
			return;
		
		drawWarningCrosshair(context);
	}
	
	private boolean shouldDrawWarningCrosshair()
	{
		if(!enableOnTarget.isChecked())
			return true;
		
		return targetBlock != null || targetVillager;
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
	
	private double getClosestVillagerHitDistance(Vec3 start, Vec3 end)
	{
		if(MC.level == null)
			return Double.NaN;
		
		Vec3 dir = end.subtract(start);
		double maxDist = dir.length();
		if(maxDist <= 0)
			return Double.NaN;
		
		// Normalize for distance computation along the ray
		Vec3 dirNorm = dir.scale(1.0 / maxDist);
		
		double closest = Double.NaN;
		for(var e : MC.level.entitiesForRendering())
		{
			if(!(e instanceof Villager vil) || vil.isRemoved()
				|| vil.getHealth() <= 0)
				continue;
			
			AABB box = vil.getBoundingBox();
			var opt = box.clip(start, end);
			if(opt.isEmpty())
				continue;
			
			Vec3 hit = opt.get();
			double dist = hit.subtract(start).dot(dirNorm);
			if(dist < 0 || dist > maxDist)
				continue;
			
			if(Double.isNaN(closest) || dist < closest)
				closest = dist;
		}
		
		return closest;
	}
}
