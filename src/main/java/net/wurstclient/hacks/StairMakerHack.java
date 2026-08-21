/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;

@SearchTags({"stair maker", "stairmaker", "stair builder", "staircase"})
public final class StairMakerHack extends Hack implements UpdateListener
{
	private final CheckboxSetting smoothClimb = new CheckboxSetting(
		"Smooth climb",
		"Glides continuously up the staircase instead of jumping each block.",
		true);
	
	private final CheckboxSetting autoFastPlace =
		new CheckboxSetting("Auto FastPlace",
			"Temporarily enables FastPlace while StairMaker is active.", true);
	
	private boolean enabledFastPlace;
	
	public StairMakerHack()
	{
		super("StairMaker");
		setCategory(Category.BLOCKS);
		addSetting(smoothClimb);
		addSetting(autoFastPlace);
	}
	
	@Override
	protected void onEnable()
	{
		enabledFastPlace = false;
		updateFastPlace();
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		if(enabledFastPlace && WURST.getHax().fastPlaceHack.isEnabled())
			WURST.getHax().fastPlaceHack.setEnabled(false);
		enabledFastPlace = false;
	}
	
	@Override
	public void onUpdate()
	{
		updateFastPlace();
		
		if(MC.player == null || MC.level == null
			|| !(MC.player.getMainHandItem().getItem() instanceof BlockItem)
			|| !MC.options.keyUp.isDown())
			return;
		
		Direction direction = MC.player.getDirection();
		BlockPos target = MC.player.blockPosition().relative(direction);
		boolean descending = MC.options.keyShift.isDown();
		if(descending)
			target = target.below();
		else
			applySmoothClimb();
		
		if(!MC.level.getBlockState(target).canBeReplaced())
			return;
			
		// Use the same direct useItemOn operation as the supplied module. The
		// target is the block face in front of the player; the server resolves
		// the stair's placement/orientation from this hit result.
		BlockHitResult hit = new BlockHitResult(Vec3.atLowerCornerOf(target),
			Direction.DOWN, target, false);
		MC.gameMode.useItemOn(MC.player, InteractionHand.MAIN_HAND, hit);
		MC.player.swing(InteractionHand.MAIN_HAND);
		MC.rightClickDelay = autoFastPlace.isChecked() ? 0 : 4;
	}
	
	private void applySmoothClimb()
	{
		if(!smoothClimb.isChecked())
			return;
		
		Vec3 velocity = MC.player.getDeltaMovement();
		double horizontalSpeed =
			Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
		
		// Match the vertical speed to forward movement, producing a continuous
		// 45-degree path instead of a sequence of full-height jumps.
		double climbSpeed = Math.clamp(horizontalSpeed, 0.08, 0.15);
		MC.player.setJumping(false);
		MC.player.setDeltaMovement(velocity.x, Math.max(velocity.y, climbSpeed),
			velocity.z);
		MC.player.fallDistance = 0;
	}
	
	private void updateFastPlace()
	{
		if(autoFastPlace.isChecked())
		{
			if(!WURST.getHax().fastPlaceHack.isEnabled())
			{
				WURST.getHax().fastPlaceHack.setEnabled(true);
				enabledFastPlace = true;
			}
		}else if(enabledFastPlace && WURST.getHax().fastPlaceHack.isEnabled())
		{
			WURST.getHax().fastPlaceHack.setEnabled(false);
			enabledFastPlace = false;
		}
	}
}
