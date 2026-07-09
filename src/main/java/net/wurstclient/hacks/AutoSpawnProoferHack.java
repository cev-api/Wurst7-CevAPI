/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.Color;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.BlockListSetting;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.EspStyleSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.SwingHandSetting;
import net.wurstclient.settings.SwingHandSetting.SwingHand;
import net.wurstclient.util.BlockPlacer;
import net.wurstclient.util.BlockPlacer.BlockPlacingParams;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.InteractionSimulator;
import net.wurstclient.util.InventoryUtils;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.RotationUtils;

@SearchTags({"auto spawn proofer", "AutoTorch", "auto torch", "torch placer",
	"spawn proof", "spawnproof", "anti spawn"})
public final class AutoSpawnProoferHack extends Hack
	implements UpdateListener, RenderListener
{
	private final SliderSetting range =
		new SliderSetting("Range", 5, 1, 7, 0.05, ValueDisplay.DECIMAL);
	
	private final CheckboxSetting autoPlace = new CheckboxSetting("Auto place",
		"Automatically places light sources on highlighted spawn spots.\n"
			+ "When off, AutoSpawnProofer only highlights where mobs can spawn.",
		true);
	
	private final SliderSetting lightLevel = new SliderSetting("Light level",
		"Maximum block light at which a tile still counts as spawnable.\n"
			+ "Hostile mobs spawn at block light 0, so the default of 1 proofs"
			+ " exactly the tiles where they can appear.",
		1, 0, 7, 1, ValueDisplay.INTEGER);
	
	private final BlockListSetting lightBlocks = new BlockListSetting(
		"Light blocks", "The light sources AutoSpawnProofer is allowed to use.",
		"minecraft:torch", "minecraft:soul_torch", "minecraft:jack_o_lantern",
		"minecraft:shroomlight", "minecraft:glowstone",
		"minecraft:sea_lantern");
	
	private final EspStyleSetting style = new EspStyleSetting();
	private final ColorSetting color = new ColorSetting("Color",
		"Spawnable tiles are highlighted in this color.", new Color(0xFF0000));
	private final SliderSetting fillAlpha = new SliderSetting("Fill opacity",
		"Opacity of the highlighted spawn tiles.", 64, 0, 255, 1,
		ValueDisplay.INTEGER);
	private final SliderSetting outlineAlpha = new SliderSetting(
		"Outline opacity", "Opacity of the highlight outlines.", 255, 0, 255, 1,
		ValueDisplay.INTEGER);
	
	private final SwingHandSetting swingHand =
		new SwingHandSetting(this, SwingHand.SERVER);
	
	private List<BlockPos> targets = List.of();
	private BlockPos currentTarget;
	
	public AutoSpawnProoferHack()
	{
		super("AutoSpawnProofer");
		setCategory(Category.BLOCKS);
		addSetting(range);
		addSetting(autoPlace);
		addSetting(lightLevel);
		addSetting(lightBlocks);
		addSetting(style);
		addSetting(color);
		addSetting(fillAlpha);
		addSetting(outlineAlpha);
		addSetting(swingHand);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		
		targets = List.of();
		currentTarget = null;
	}
	
	@Override
	public void onUpdate()
	{
		currentTarget = null;
		
		Vec3 eyesVec = RotationUtils.getEyesPos();
		BlockPos eyesBlock = BlockPos.containing(eyesVec);
		double rangeSq = range.getValueSq();
		int blockRange = range.getValueCeil();
		
		targets = BlockUtils.getAllInBoxStream(eyesBlock, blockRange)
			.filter(pos -> pos.distToCenterSqr(eyesVec) <= rangeSq)
			.filter(this::isSpawnable)
			.sorted(
				Comparator.comparingDouble(pos -> pos.distToCenterSqr(eyesVec)))
			.toList();
		
		if(autoPlace.isChecked())
			placeLight(rangeSq);
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		if(targets.isEmpty())
			return;
		
		List<AABB> boxes = targets.stream().map(AABB::new).toList();
		
		if(style.getSelected().hasBoxes())
		{
			RenderUtils.drawSolidBoxes(matrixStack, boxes,
				color.getColorI(fillAlpha.getValueI()), false);
			RenderUtils.drawOutlinedBoxes(matrixStack, boxes,
				color.getColorI(outlineAlpha.getValueI()), false);
		}
		
		if(style.getSelected().hasLines())
			RenderUtils.drawTracers(matrixStack, partialTicks,
				boxes.stream().map(AABB::getCenter).toList(),
				color.getColorI(outlineAlpha.getValueI()), false);
	}
	
	private boolean placeLight(double rangeSq)
	{
		if(MC.rightClickDelay > 0 || MC.gameMode.isDestroying()
			|| MC.player.isHandsBusy())
			return false;
		
		for(BlockPos pos : targets)
		{
			BlockPlacingParams params = BlockPlacer.getBlockPlacingParams(pos);
			if(params == null || params.requiresSneaking()
				|| params.distanceSq() > rangeSq)
				continue;
			
			if(!selectLightBlock())
				return false;
			
			ItemStack heldStack = MC.player.getMainHandItem();
			if(!isLightBlock(heldStack))
				return false;
			
			currentTarget = pos;
			MC.rightClickDelay = 4;
			WURST.getRotationFaker().faceVectorPacket(params.hitVec());
			InteractionSimulator.rightClickBlock(params.toHitResult(),
				InteractionHand.MAIN_HAND, swingHand.getSelected());
			return true;
		}
		
		return false;
	}
	
	private boolean selectLightBlock()
	{
		if(isLightBlock(MC.player.getMainHandItem()))
			return true;
		
		return InventoryUtils.selectItem(this::isLightBlock);
	}
	
	private boolean isLightBlock(ItemStack stack)
	{
		if(!(stack.getItem() instanceof BlockItem blockItem))
			return false;
		
		Block block = blockItem.getBlock();
		if(!lightBlocks.matchesBlock(block))
			return false;
		
		Inventory inventory = MC.player.getInventory();
		return !stack.isEmpty() && (MC.player.getAbilities().instabuild
			|| inventory.countItem(stack.getItem()) > 0);
	}
	
	private boolean isSpawnable(BlockPos pos)
	{
		if(!BlockUtils.getState(pos).canBeReplaced())
			return false;
		
		if(!SpawnPlacements.isSpawnPositionOk(EntityTypes.CREEPER, MC.level,
			pos))
			return false;
		
		return MC.level.getBrightness(LightLayer.BLOCK, pos) <= lightLevel
			.getValueI();
	}
}
