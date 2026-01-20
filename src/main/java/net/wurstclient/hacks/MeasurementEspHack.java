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
		
		boolean isAir = state.isAir();
		boolean aboveAir = false;
		try
		{
			BlockState above = MC.level.getBlockState(pos.above());
			aboveAir = above.isAir();
		}catch(Throwable ignored)
		{}
		
		if(isAir && aboveAir)
		{
			lineColor = 0xFF00FF00;
			fillColor = 0x4000FF00;
		}else if(isAir)
		{
			lineColor = 0xFFFFFF00;
			fillColor = 0x40FFFF00;
		}else
		{
			lineColor = 0xFFFF0000;
			fillColor = 0x40FF0000;
		}
		
		AABB box = new AABB(pos.getX(), pos.getY(), pos.getZ(),
			pos.getX() + 1.0, pos.getY() + 1.0, pos.getZ() + 1.0);
		
		boolean depthTest = NiceWurstModule.enforceDepthTest(false);
		RenderUtils.drawSolidBox(matrixStack, box, fillColor, depthTest);
		RenderUtils.drawOutlinedBox(matrixStack, box, lineColor, depthTest);
		
		for(BlockPos mpos : marked)
		{
			BlockState mstate = MC.level.getBlockState(mpos);
			boolean misAir = mstate.isAir();
			boolean maboveAir = false;
			try
			{
				maboveAir = MC.level.getBlockState(mpos.above()).isAir();
			}catch(Throwable ignored)
			{}
			
			int mline;
			int mfill;
			if(misAir && maboveAir)
			{
				mline = 0xFF00FF00;
				mfill = 0x4000FF00;
			}else if(misAir)
			{
				mline = 0xFFFFFF00;
				mfill = 0x40FFFF00;
			}else
			{
				mline = 0xFFFF0000;
				mfill = 0x40FF0000;
			}
			
			AABB mb = new AABB(mpos.getX(), mpos.getY(), mpos.getZ(),
				mpos.getX() + 1.0, mpos.getY() + 1.0, mpos.getZ() + 1.0);
			RenderUtils.drawSolidBox(matrixStack, mb, mfill, depthTest);
			RenderUtils.drawOutlinedBox(matrixStack, mb, mline, depthTest);
		}
	}
	
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
