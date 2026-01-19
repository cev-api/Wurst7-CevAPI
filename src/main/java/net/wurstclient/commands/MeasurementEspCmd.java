/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.commands;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.command.Command;
import net.wurstclient.command.CmdException;
import net.wurstclient.events.RenderListener;
import java.util.ArrayList;
import java.util.List;
import net.wurstclient.WurstClient;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.RotationUtils;
import net.wurstclient.nicewurst.NiceWurstModule;

/**
 * Command-only measurement ESP. Usage: .measurementesp <distance> | off
 */
public final class MeasurementEspCmd extends Command
{
	private static boolean active = false;
	private static int distance = 5;
	private static BlockPos lastPos = null; // last dynamic target
	private static final List<BlockPos> marked = new ArrayList<>();
	
	private static final RenderListener LISTENER = new RenderListener()
	{
		@Override
		public void onRender(PoseStack matrixStack, float partialTicks)
		{
			if(!active)
				return;
			
			if(net.wurstclient.WurstClient.MC == null
				|| net.wurstclient.WurstClient.MC.level == null
				|| net.wurstclient.WurstClient.MC.player == null)
				return;
			
			// If freecam is enabled, don't update the dynamic target.
			boolean freecam =
				WurstClient.INSTANCE.getHax().freecamHack.isEnabled();
			
			Vec3 target;
			if(freecam && lastPos != null)
			{
				target = new Vec3(lastPos.getX() + 0.5, lastPos.getY() + 0.5,
					lastPos.getZ() + 0.5);
			}else
			{
				Vec3 eyes = RotationUtils.getEyesPos();
				Vec3 look = RotationUtils.getClientLookVec(partialTicks);
				target = eyes.add(look.scale(distance));
				lastPos = BlockPos.containing(target);
			}
			
			BlockPos pos = BlockPos.containing(target);
			BlockState state =
				net.wurstclient.WurstClient.MC.level.getBlockState(pos);
			
			int lineColor;
			int fillColor;
			
			boolean isAir = state.isAir();
			boolean aboveAir = false;
			try
			{
				BlockState above = net.wurstclient.WurstClient.MC.level
					.getBlockState(pos.above());
				aboveAir = above.isAir();
			}catch(Throwable ignored)
			{}
			
			if(isAir && aboveAir)
			{
				lineColor = 0xFF00FF00; // green
				fillColor = 0x4000FF00;
			}else if(isAir)
			{
				lineColor = 0xFFFFFF00; // yellow
				fillColor = 0x40FFFF00;
			}else
			{
				lineColor = 0xFFFF0000; // red
				fillColor = 0x40FF0000;
			}
			
			AABB box = new AABB(pos.getX(), pos.getY(), pos.getZ(),
				pos.getX() + 1.0, pos.getY() + 1.0, pos.getZ() + 1.0);
			
			boolean depthTest = NiceWurstModule.enforceDepthTest(false);
			RenderUtils.drawSolidBox(matrixStack, box, fillColor, depthTest);
			RenderUtils.drawOutlinedBox(matrixStack, box, lineColor, depthTest);
			
			// Draw all marked positions
			for(BlockPos mpos : marked)
			{
				BlockState mstate =
					net.wurstclient.WurstClient.MC.level.getBlockState(mpos);
				boolean misAir = mstate.isAir();
				boolean maboveAir = false;
				try
				{
					maboveAir = net.wurstclient.WurstClient.MC.level
						.getBlockState(mpos.above()).isAir();
				}catch(Throwable ignored)
				{}
				int mline, mfill;
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
	};
	
	public MeasurementEspCmd()
	{
		super("measurementesp",
			"Create a hovering ESP box at the block you're pointing at.",
			".measurementesp <distance>", ".measurementesp off");
	}
	
	@Override
	public void call(String[] args) throws CmdException
	{
		if(args == null || args.length == 0)
		{
			printHelp();
			return;
		}
		
		String a = args[0].toLowerCase();
		
		if("off".equals(a) || "disable".equals(a))
		{
			if(active)
			{
				active = false;
				EVENTS.remove(RenderListener.class, LISTENER);
				ChatUtils.message("MeasurementESP disabled.");
			}else
				ChatUtils.message("MeasurementESP is not active.");
			return;
		}
		
		if("mark".equals(a))
		{
			if(lastPos == null)
				ChatUtils.message("No target to mark.");
			else
			{
				marked.add(lastPos);
				ChatUtils
					.message("Marked position: " + lastPos.toShortString());
			}
			return;
		}
		
		if("clear".equals(a))
		{
			marked.clear();
			ChatUtils.message("Cleared MeasurementESP marks.");
			return;
		}
		
		// distance
		try
		{
			int d = Integer.parseInt(a);
			if(d <= 0)
				throw new NumberFormatException();
			distance = d;
		}catch(NumberFormatException e)
		{
			throw new net.wurstclient.command.CmdSyntaxError(
				"Invalid distance: " + a);
		}
		
		if(!active)
		{
			active = true;
			EVENTS.add(RenderListener.class, LISTENER);
		}
		
		ChatUtils.message("MeasurementESP active: " + distance + " blocks.");
	}
}
