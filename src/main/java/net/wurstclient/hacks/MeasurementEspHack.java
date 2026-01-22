/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.PressurePlateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.SearchTags;
import net.wurstclient.events.RenderListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.nicewurst.NiceWurstModule;
import net.wurstclient.settings.ButtonSetting;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.RotationUtils;

@SearchTags({"measurement esp", "measure", "distance"})
public final class MeasurementEspHack extends Hack implements RenderListener
{
	private final SliderSetting distance = new SliderSetting("Distance",
		"Blocks in front of your crosshair to place the ESP box.", 5, 1, 64, 1,
		ValueDisplay.INTEGER.withSuffix(" blocks"));
	private final CheckboxSetting lockWhileFreecam =
		new CheckboxSetting("Lock while Freecam",
			"Keep the last target when Freecam is enabled.", true);
	private final CheckboxSetting blockMode = new CheckboxSetting("Block mode",
		"Use the current block-based color scheme.", true);
	private final CheckboxSetting reachMode = new CheckboxSetting("Reach mode",
		"Use reach-based colors (white default, red on entities, yellow on interactive blocks). Overrides Block mode.",
		false);
	private final ButtonSetting markButton =
		new ButtonSetting("Mark current", this::markCurrent);
	private final ButtonSetting clearButton =
		new ButtonSetting("Clear marks", this::clearMarks);
	
	private BlockPos lastPos;
	private final List<BlockPos> marked = new ArrayList<>();
	
	public MeasurementEspHack()
	{
		super("MeasurementESP");
		addSetting(distance);
		addSetting(lockWhileFreecam);
		addSetting(blockMode);
		addSetting(reachMode);
		addSetting(markButton);
		addSetting(clearButton);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(RenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(RenderListener.class, this);
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		if(MC == null || MC.level == null || MC.player == null)
			return;
		
		boolean useReachMode = reachMode.isChecked();
		boolean useBlockMode = blockMode.isChecked();
		if(!useReachMode && !useBlockMode)
			return;
		
		boolean freecam = WURST.getHax().freecamHack.isEnabled();
		Vec3 target;
		if(freecam && lockWhileFreecam.isChecked() && lastPos != null)
		{
			target = new Vec3(lastPos.getX() + 0.5, lastPos.getY() + 0.5,
				lastPos.getZ() + 0.5);
		}else
		{
			Vec3 eyes = RotationUtils.getEyesPos();
			Vec3 look = RotationUtils.getClientLookVec(partialTicks);
			target = eyes.add(look.scale(distance.getValue()));
			lastPos = BlockPos.containing(target);
		}
		
		BlockPos pos = BlockPos.containing(target);
		BlockState state = MC.level.getBlockState(pos);
		
		int lineColor;
		int fillColor;
		AABB box;
		
		if(useReachMode)
		{
			ReachVisual reach = getReachVisuals(pos);
			lineColor = reach.lineColor;
			fillColor = reach.fillColor;
			box = reach.box;
		}else
		{
			BlockColors colors = getBlockModeColors(pos, state);
			lineColor = colors.lineColor;
			fillColor = colors.fillColor;
			box = new AABB(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1.0,
				pos.getY() + 1.0, pos.getZ() + 1.0);
		}
		
		boolean depthTest = NiceWurstModule.enforceDepthTest(false);
		RenderUtils.drawSolidBox(matrixStack, box, fillColor, depthTest);
		RenderUtils.drawOutlinedBox(matrixStack, box, lineColor, depthTest);
		
		for(BlockPos mpos : marked)
		{
			BlockState mstate = MC.level.getBlockState(mpos);
			BlockColors mcolors = useReachMode ? getReachBlockColors(mstate)
				: getBlockModeColors(mpos, mstate);
			
			AABB mb = new AABB(mpos.getX(), mpos.getY(), mpos.getZ(),
				mpos.getX() + 1.0, mpos.getY() + 1.0, mpos.getZ() + 1.0);
			RenderUtils.drawSolidBox(matrixStack, mb, mcolors.fillColor,
				depthTest);
			RenderUtils.drawOutlinedBox(matrixStack, mb, mcolors.lineColor,
				depthTest);
		}
	}
	
	private BlockColors getBlockModeColors(BlockPos pos, BlockState state)
	{
		boolean isAir = state.isAir();
		boolean aboveAir = false;
		try
		{
			BlockState above = MC.level.getBlockState(pos.above());
			aboveAir = above.isAir();
		}catch(Throwable ignored)
		{}
		
		if(isAir && aboveAir)
			return new BlockColors(0xFF00FF00, 0x4000FF00);
		
		if(isAir)
			return new BlockColors(0xFFFFFF00, 0x40FFFF00);
		
		return new BlockColors(0xFFFF0000, 0x40FF0000);
	}
	
	private ReachVisual getReachVisuals(BlockPos fallbackPos)
	{
		AABB box = new AABB(fallbackPos.getX(), fallbackPos.getY(),
			fallbackPos.getZ(), fallbackPos.getX() + 1.0,
			fallbackPos.getY() + 1.0, fallbackPos.getZ() + 1.0);
		
		if(hasEntityAt(box))
			return new ReachVisual(box, 0xFFFF0000, 0x20FF0000);
		
		BlockState state = MC.level.getBlockState(fallbackPos);
		BlockColors colors = getReachBlockColors(state);
		return new ReachVisual(box, colors.lineColor, colors.fillColor);
	}
	
	private BlockColors getReachBlockColors(BlockState state)
	{
		if(isReachInteractive(state))
			return new BlockColors(0xFFFFFF00, 0x20FFFF00);
		
		return new BlockColors(0xFFFFFFFF, 0x20FFFFFF);
	}
	
	private boolean isReachInteractive(BlockState state)
	{
		return state.getBlock() instanceof BaseEntityBlock
			|| state.getBlock() instanceof CraftingTableBlock
			|| state.getBlock() instanceof DoorBlock
			|| state.getBlock() instanceof TrapDoorBlock
			|| state.getBlock() instanceof FenceGateBlock
			|| state.getBlock() instanceof ButtonBlock
			|| state.getBlock() instanceof LeverBlock
			|| state.getBlock() instanceof PressurePlateBlock;
	}
	
	private boolean hasEntityAt(AABB box)
	{
		List<Entity> entities =
			MC.level.getEntities(MC.player, box, Entity::isAlive);
		return !entities.isEmpty();
	}
	
	private record BlockColors(int lineColor, int fillColor)
	{}
	
	private record ReachVisual(AABB box, int lineColor, int fillColor)
	{}
	
	public void setDistance(int value)
	{
		distance.setValue(value);
	}
	
	public boolean markCurrent()
	{
		if(lastPos == null)
			return false;
		
		marked.add(lastPos);
		return true;
	}
	
	public void clearMarks()
	{
		marked.clear();
	}
	
	public BlockPos getLastPos()
	{
		return lastPos;
	}
	
	public int getDistance()
	{
		return distance.getValueI();
	}
}
