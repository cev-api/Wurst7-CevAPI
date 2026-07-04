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
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.BlockListSetting;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.EspStyleSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.SwingHandSetting;
import net.wurstclient.settings.SwingHandSetting.SwingHand;
import net.wurstclient.util.BlockBreaker;
import net.wurstclient.util.BlockPlacer;
import net.wurstclient.util.BlockPlacer.BlockPlacingParams;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.InteractionSimulator;
import net.wurstclient.util.InventoryUtils;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.RotationUtils;

@SearchTags({"source fill", "SourceFiller", "lava fill", "water fill"})
public final class SourceFillHack extends Hack
	implements UpdateListener, RenderListener
{
	private final SliderSetting range =
		new SliderSetting("Range", 6, 1, 7, 0.05, ValueDisplay.DECIMAL);
	
	private final CheckboxSetting autoFill = new CheckboxSetting("Auto fill",
		"Automatically fills highlighted source blocks.", false);
	
	private final CheckboxSetting autoBreak = new CheckboxSetting("Auto break",
		"After filling a source block, breaks the placed block.", false);
	
	private final LiquidModeSetting liquidMode = new LiquidModeSetting();
	
	private final BlockListSetting blockWhitelist = new BlockListSetting(
		"Block whitelist", "The blocks that SourceFill is allowed to use.",
		"minecraft:dirt", "minecraft:cobblestone", "minecraft:netherrack");
	
	private final EspStyleSetting style = new EspStyleSetting();
	private final CheckboxSetting renderWater =
		new CheckboxSetting("Render water",
			"Render ESP boxes and tracers for water sources.", true);
	private final CheckboxSetting renderLava = new CheckboxSetting(
		"Render lava", "Render ESP boxes and tracers for lava sources.", true);
	private final ColorSetting waterColor = new ColorSetting("Water color",
		"Water source blocks will be highlighted in this color.",
		new Color(0x3F76E4));
	private final ColorSetting lavaColor = new ColorSetting("Lava color",
		"Lava source blocks will be highlighted in this color.",
		new Color(0xFF8C00));
	private final SliderSetting fillAlpha = new SliderSetting("Fill opacity",
		"Opacity of the highlighted source block fill.", 64, 0, 255, 1,
		ValueDisplay.INTEGER);
	private final SliderSetting outlineAlpha =
		new SliderSetting("Outline opacity",
			"description.wurst.setting.sourcefill.outline_opacity", 255, 0, 255,
			1, ValueDisplay.INTEGER);
	private final CheckboxSetting tracerFlash =
		new CheckboxSetting("Tracer flash", "Make ESP tracers pulse.", false);
	
	private final SwingHandSetting swingHand =
		new SwingHandSetting(this, SwingHand.SERVER);
	
	private final CheckboxSetting autoJesus =
		new CheckboxSetting("Auto-enable Jesus",
			"Automatically enables Jesus when SourceFill is enabled and"
				+ " disables it when SourceFill is turned off.",
			false);
	
	private List<BlockPos> targets = List.of();
	private final HashSet<BlockPos> blocksToBreak = new HashSet<>();
	private BlockPos currentTarget;
	private BlockPos currentlyBreaking;
	private boolean autoEnabledJesus;
	
	public SourceFillHack()
	{
		super("SourceFill");
		setCategory(Category.BLOCKS);
		addSetting(range);
		addSetting(autoFill);
		addSetting(autoBreak);
		addSetting(liquidMode);
		addSetting(blockWhitelist);
		addSetting(style);
		addSetting(renderWater);
		addSetting(renderLava);
		addSetting(waterColor);
		addSetting(lavaColor);
		addSetting(fillAlpha);
		addSetting(outlineAlpha);
		addSetting(tracerFlash);
		addSetting(swingHand);
		addSetting(autoJesus);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RenderListener.class, this);
		
		if(autoJesus.isChecked() && !WURST.getHax().jesusHack.isEnabled())
		{
			WURST.getHax().jesusHack.setEnabled(true);
			autoEnabledJesus = true;
		}
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		
		if(autoEnabledJesus && WURST.getHax().jesusHack.isEnabled())
			WURST.getHax().jesusHack.setEnabled(false);
		autoEnabledJesus = false;
		
		if(currentlyBreaking != null)
		{
			MC.gameMode.isDestroying = true;
			MC.gameMode.stopDestroyBlock();
		}
		
		targets = List.of();
		blocksToBreak.clear();
		currentTarget = null;
		currentlyBreaking = null;
	}
	
	@Override
	public void onUpdate()
	{
		currentTarget = null;
		currentlyBreaking = null;
		
		Vec3 eyesVec = RotationUtils.getEyesPos();
		BlockPos eyesBlock = BlockPos.containing(eyesVec);
		double rangeSq = range.getValueSq();
		int blockRange = range.getValueCeil();
		
		targets = BlockUtils.getAllInBoxStream(eyesBlock, blockRange)
			.filter(pos -> pos.distToCenterSqr(eyesVec) <= rangeSq)
			.filter(this::isWantedSource)
			.sorted(
				Comparator.comparingDouble(pos -> pos.distToCenterSqr(eyesVec)))
			.toList();
		
		blocksToBreak.removeIf(pos -> BlockUtils.getState(pos).canBeReplaced()
			&& !isWantedSource(pos));
		
		if(autoBreak.isChecked() && breakFilledBlock(rangeSq))
			return;
		
		if(autoFill.isChecked())
			fillSourceBlock(rangeSq);
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		if(targets.isEmpty())
			return;
		
		List<AABB> waterBoxes = renderWater.isChecked()
			? targets.stream().filter(pos -> getFluid(pos).is(FluidTags.WATER))
				.map(AABB::new).toList()
			: List.of();
		List<AABB> lavaBoxes = renderLava.isChecked()
			? targets.stream().filter(pos -> getFluid(pos).is(FluidTags.LAVA))
				.map(AABB::new).toList()
			: List.of();
		
		if(waterBoxes.isEmpty() && lavaBoxes.isEmpty())
			return;
		
		if(style.getSelected().hasBoxes())
		{
			RenderUtils.drawSolidBoxes(matrixStack, waterBoxes,
				waterColor.getColorI(fillAlpha.getValueI()), false);
			RenderUtils.drawSolidBoxes(matrixStack, lavaBoxes,
				lavaColor.getColorI(fillAlpha.getValueI()), false);
			
			RenderUtils.drawOutlinedBoxes(matrixStack, waterBoxes,
				waterColor.getColorI(outlineAlpha.getValueI()), false);
			RenderUtils.drawOutlinedBoxes(matrixStack, lavaBoxes,
				lavaColor.getColorI(outlineAlpha.getValueI()), false);
		}
		
		if(style.getSelected().hasLines())
		{
			renderTracers(matrixStack, partialTicks, waterBoxes,
				waterColor.getColorI(outlineAlpha.getValueI()));
			renderTracers(matrixStack, partialTicks, lavaBoxes,
				lavaColor.getColorI(outlineAlpha.getValueI()));
		}
	}
	
	private void renderTracers(PoseStack matrixStack, float partialTicks,
		List<AABB> boxes, int color)
	{
		if(boxes.isEmpty())
			return;
		
		if(tracerFlash.isChecked())
			color = RenderUtils.flashColor(color);
		
		RenderUtils.drawTracers("SourceFill", matrixStack, partialTicks,
			boxes.stream().map(AABB::getCenter).toList(), color, false);
	}
	
	private boolean fillSourceBlock(double rangeSq)
	{
		if(MC.rightClickDelay > 0 || MC.gameMode.isDestroying()
			|| MC.player.isHandsBusy())
			return false;
		
		for(BlockPos pos : targets)
		{
			SourcePlacement placement = getThroughWallPlacement(pos);
			if(placement == null || placement.distanceSq() > rangeSq)
				continue;
			
			if(!selectWhitelistedBlock())
				return false;
			
			ItemStack heldStack = MC.player.getMainHandItem();
			if(!isWhitelistedBlock(heldStack))
				return false;
			
			currentTarget = pos;
			MC.rightClickDelay = 4;
			WURST.getRotationFaker().faceVectorPacket(placement.hitVec());
			InteractionSimulator.rightClickBlock(placement.hitResult(),
				InteractionHand.MAIN_HAND, swingHand.getSelected());
			if(autoBreak.isChecked())
				blocksToBreak.add(pos);
			return true;
		}
		
		return false;
	}
	
	private SourcePlacement getThroughWallPlacement(BlockPos pos)
	{
		BlockPlacingParams params = BlockPlacer.getBlockPlacingParams(pos);
		if(params != null && !params.requiresSneaking())
			return new SourcePlacement(params.toHitResult(), params.hitVec(),
				params.distanceSq());
		
		Vec3 hitVec = Vec3.atCenterOf(pos);
		double distanceSq = RotationUtils.getEyesPos().distanceToSqr(hitVec);
		BlockHitResult hitResult =
			new BlockHitResult(hitVec, Direction.UP, pos, false);
		return new SourcePlacement(hitResult, hitVec, distanceSq);
	}
	
	private boolean breakFilledBlock(double rangeSq)
	{
		var params = blocksToBreak.stream()
			.map(BlockBreaker::getBlockBreakingParams).filter(Objects::nonNull)
			.filter(p -> p.distanceSq() <= rangeSq)
			.sorted(BlockBreaker.comparingParams()).findFirst().orElse(null);
		
		if(params == null)
			return false;
		
		currentlyBreaking = params.pos();
		WURST.getRotationFaker().faceVectorPacket(params.hitVec());
		
		if(!MC.gameMode.continueDestroyBlock(params.pos(), params.side()))
			return false;
		
		swingHand.swing(InteractionHand.MAIN_HAND);
		return true;
	}
	
	private boolean selectWhitelistedBlock()
	{
		if(isWhitelistedBlock(MC.player.getMainHandItem()))
			return true;
		
		return InventoryUtils.selectItem(this::isWhitelistedBlock);
	}
	
	private boolean isWhitelistedBlock(ItemStack stack)
	{
		if(!(stack.getItem() instanceof BlockItem blockItem))
			return false;
		
		Block block = blockItem.getBlock();
		if(!blockWhitelist.matchesBlock(block))
			return false;
		
		Inventory inventory = MC.player.getInventory();
		return !stack.isEmpty() && (MC.player.getAbilities().instabuild
			|| inventory.countItem(stack.getItem()) > 0);
	}
	
	private boolean isWantedSource(BlockPos pos)
	{
		BlockState state = BlockUtils.getState(pos);
		if(!state.canBeReplaced())
			return false;
		
		FluidState fluid = state.getFluidState();
		if(!fluid.isSource())
			return false;
		
		return switch(liquidMode.getSelected())
		{
			case WATER_ONLY -> fluid.is(FluidTags.WATER);
			case LAVA_ONLY -> fluid.is(FluidTags.LAVA);
			case BOTH -> fluid.is(FluidTags.WATER) || fluid.is(FluidTags.LAVA);
		};
	}
	
	private FluidState getFluid(BlockPos pos)
	{
		return BlockUtils.getState(pos).getFluidState();
	}
	
	private static final class LiquidModeSetting extends EnumSetting<LiquidMode>
	{
		private LiquidModeSetting()
		{
			super("Liquids", "Which source blocks SourceFill should target.",
				LiquidMode.values(), LiquidMode.BOTH);
		}
	}
	
	private enum LiquidMode
	{
		BOTH("Water and lava"),
		WATER_ONLY("Water only"),
		LAVA_ONLY("Lava only");
		
		private final String name;
		
		private LiquidMode(String name)
		{
			this.name = name;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
	
	private static record SourcePlacement(BlockHitResult hitResult, Vec3 hitVec,
		double distanceSq)
	{}
}
