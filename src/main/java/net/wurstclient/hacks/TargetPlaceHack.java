/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.awt.Color;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.WurstClient;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.mixinterface.IMinecraftClient;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.Rotation;
import net.wurstclient.util.RotationUtils;

public final class TargetPlaceHack extends Hack
	implements RenderListener, UpdateListener
{
	private static final WurstClient WURST = WurstClient.INSTANCE;
	
	private static final IMinecraftClient IMC = WurstClient.IMC;
	
	private final ColorSetting highlightColor =
		new ColorSetting("Highlight color", Color.CYAN);
	
	private final TextFieldSetting activationKey =
		new TextFieldSetting("Activation key",
			"Determines which key activates TargetPlace.\n\n"
				+ "Use translation keys such as key.keyboard.p.",
			"key.keyboard.p", this::isValidActivationKey);
	
	private BlockPos targetBlock;
	private boolean activationKeyDown;
	private long lastActivationTime;
	
	public TargetPlaceHack()
	{
		super("TargetPlace");
		setCategory(Category.BLOCKS);
		addSetting(highlightColor);
		addSetting(activationKey);
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
		targetBlock = null;
		activationKeyDown = false;
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.screen != null)
		{
			activationKeyDown = false;
			return;
		}
		
		InputConstants.Key key = getActivationKey();
		if(key != null)
		{
			boolean currentlyDown =
				InputConstants.isKeyDown(MC.getWindow(), key.getValue());
			if(currentlyDown && !activationKeyDown)
				handleActivation(MC.options.keyShift.isDown());
			activationKeyDown = currentlyDown;
		}
		
		// extra fast placement
		if(targetBlock == null || MC.level == null || MC.player == null)
			return;
		
		ItemStack stack = MC.player.getMainHandItem();
		if(stack.isEmpty())
			return;
		
		// Original behavior: once client sees air, attempt a placement.
		if(BlockUtils.getState(targetBlock).isAir())
			attemptPlace(targetBlock);
			
		// Extra speed: while armed, also spam a few placements every tick so
		// one of them lands right after the server breaks the block.
		for(int i = 0; i < 3; i++)
			attemptPlace(targetBlock);
	}
	
	@Override
	public void onRender(PoseStack matrices, float partialTicks)
	{
		if(targetBlock == null || MC.level == null)
			return;
		
		BlockState state = MC.level.getBlockState(targetBlock);
		if(state.isAir())
			return;
		
		AABB box;
		try
		{
			box = BlockUtils.getBoundingBox(targetBlock);
		}catch(UnsupportedOperationException e)
		{
			return;
		}
		if(box == null)
			return;
		
		int color = highlightColor.getColorI(160);
		RenderUtils.drawOutlinedBox(matrices, box, color, false);
	}
	
	public boolean hasValidTarget()
	{
		return isTargetValid(targetBlock);
	}
	
	private boolean isTargetValid(BlockPos blockPos)
	{
		if(blockPos == null || MC.level == null)
			return false;
		
		BlockState state = MC.level.getBlockState(blockPos);
		if(state.isAir())
			return false;
		
		return !BlockUtils.isUnbreakable(blockPos);
	}
	
	public boolean handleActivation()
	{
		return handleActivation(false);
	}
	
	public boolean handleActivation(boolean shiftDown)
	{
		if(!isEnabled())
			return false;
		
		BlockHitResult hitResult = null;
		if(MC.hitResult instanceof BlockHitResult blockHit)
			hitResult = blockHit;
		
		return handleActivation(hitResult, shiftDown);
	}
	
	private boolean handleActivation(BlockHitResult hitResult,
		boolean shiftDown)
	{
		BlockPos lookedPos = null;
		if(hitResult != null && hitResult.getType() == HitResult.Type.BLOCK)
			lookedPos = hitResult.getBlockPos();
		
		if(lookedPos != null && lookedPos.equals(targetBlock))
		{
			if(shiftDown)
			{
				targetBlock = null;
				ChatUtils.message("TargetPlace: selection cleared.");
				return finishActivation(true);
			}
			return finishActivation(false);
		}
		
		if(lookedPos != null && isTargetValid(lookedPos))
		{
			faceUnderside(lookedPos);
			targetBlock = lookedPos;
			ChatUtils.message(
				"TargetPlace: selected " + BlockUtils.getName(lookedPos)
					+ " at " + lookedPos.toShortString() + ".");
			return finishActivation(true);
		}
		
		return finishActivation(false);
	}
	
	private boolean finishActivation(boolean handled)
	{
		if(handled)
			lastActivationTime = System.currentTimeMillis();
		return handled;
	}
	
	private void attemptPlace(BlockPos target)
	{
		faceUnderside(target);
		Placement placement = findPlacement(target);
		if(placement == null)
			return;
		
		ItemStack stack = MC.player.getMainHandItem();
		if(stack.isEmpty())
			return;
		
		// Look almost straight up before placing so the piston faces DOWN. //
		if(MC.player != null)
		{
			float yaw = MC.player.getYRot();
			Rotation rot = new Rotation(yaw, -89F);
			rot.sendPlayerLookPacket();
		}
		
		WURST.getRotationFaker().faceVectorPacket(placement.hitVec());
		IMC.getInteractionManager().rightClickBlock(placement.neighbor(),
			placement.side(), placement.hitVec());
	}
	
	private void faceUnderside(BlockPos target)
	{
		if(MC.player == null || MC.level == null)
			return;
		
		BlockPos above = target.relative(Direction.UP);
		Vec3 hitVec = Vec3.atLowerCornerOf(above).add(0.5, -0.5, 0.5);
		WURST.getRotationFaker().faceVectorPacket(hitVec);
		Rotation needed = RotationUtils.getNeededRotations(hitVec);
		needed.sendPlayerLookPacket();
	}
	
	private Placement findPlacement(BlockPos target)
	{
		BlockPos above = target.relative(Direction.UP);
		if(MC.level == null)
			return null;
		
		BlockState state = MC.level.getBlockState(above);
		if(state.isAir())
			return null;
		
		Vec3 center = Vec3.atLowerCornerOf(above).add(0.5, 0.5, 0.5);
		Vec3[] offsets =
			new Vec3[]{new Vec3(0.2, -0.5, 0.2), new Vec3(0.2, -0.5, -0.2),
				new Vec3(-0.2, -0.5, 0.2), new Vec3(-0.2, -0.5, -0.2),
				new Vec3(0.0, -0.5, 0.2), new Vec3(0.2, -0.5, 0.0),
				new Vec3(0.0, -0.5, -0.2), new Vec3(-0.2, -0.5, 0.0),
				new Vec3(-0.1, -0.5, 0.1), new Vec3(0.1, -0.5, -0.1)};
		
		for(Vec3 offset : offsets)
		{
			Vec3 hitVec = center.add(offset);
			if(needsPlacementAdjust(above, hitVec))
				return new Placement(above, Direction.DOWN, hitVec);
		}
		
		return new Placement(above, Direction.DOWN, center.add(0, -0.5, 0));
	}
	
	private boolean needsPlacementAdjust(BlockPos above, Vec3 hitVec)
	{
		// ensure we are close enough to the block edge
		Vec3 center = Vec3.atLowerCornerOf(above).add(0.5, 0.5, 0.5);
		double dx = Math.abs(hitVec.x - center.x);
		double dz = Math.abs(hitVec.z - center.z);
		return dx >= 0.2 || dz >= 0.2;
	}
	
	private InputConstants.Key getActivationKey()
	{
		try
		{
			return InputConstants.getKey(activationKey.getValue());
			
		}catch(IllegalArgumentException e)
		{
			return null;
		}
	}
	
	private boolean isValidActivationKey(String translationKey)
	{
		try
		{
			return InputConstants.getKey(translationKey) != null;
			
		}catch(IllegalArgumentException e)
		{
			return false;
		}
	}
	
	public boolean isActivationKeyDown()
	{
		return activationKeyDown;
	}
	
	public long getLastActivationTime()
	{
		return lastActivationTime;
	}
	
	private record Placement(BlockPos neighbor, Direction side, Vec3 hitVec)
	{}
}
