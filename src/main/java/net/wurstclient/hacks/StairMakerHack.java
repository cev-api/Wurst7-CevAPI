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

@SearchTags({"stair maker", "stairmaker", "stair builder", "staircase"})
public final class StairMakerHack extends Hack implements UpdateListener
{
	private BlockPos nextTarget;
	private Direction lastDirection;
	private int lastVerticalDirection;
	
	public StairMakerHack()
	{
		super("StairMaker");
		setCategory(Category.BLOCKS);
	}
	
	@Override
	protected void onEnable()
	{
		nextTarget = null;
		lastDirection = null;
		lastVerticalDirection = 0;
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		nextTarget = null;
		lastDirection = null;
		lastVerticalDirection = 0;
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null || MC.level == null
			|| !(MC.player.getMainHandItem().getItem() instanceof BlockItem))
			return;
			
		// Re-arm the sequence when Forward is released. This makes a new run
		// start immediately in front of the player instead of continuing an old
		// path after a pause or a turn.
		if(!MC.options.keyUp.isDown())
		{
			nextTarget = null;
			return;
		}
		
		Direction direction = MC.player.getDirection();
		int verticalDirection = MC.options.keyShift.isDown() ? -1 : 1;
		if(nextTarget == null || direction != lastDirection
			|| verticalDirection != lastVerticalDirection)
		{
			lastDirection = direction;
			lastVerticalDirection = verticalDirection;
			nextTarget = MC.player.blockPosition().relative(direction);
		}
		
		// If the player has already reached a previously placed/obstructed
		// position, advance exactly one block forward and one block vertically.
		// That produces / or \\ geometry and never an L-shaped jump.
		for(int i = 0; i < 32
			&& !MC.level.getBlockState(nextTarget).canBeReplaced(); i++)
			nextTarget = nextTarget.relative(direction).relative(
				verticalDirection > 0 ? Direction.UP : Direction.DOWN);
		
		if(!MC.level.getBlockState(nextTarget).canBeReplaced())
			return;
		
		place(nextTarget);
		
		// Spider's useful part, kept local to StairMaker so it does not toggle
		// another hack behind the user's back. This is skipped during creative
		// flying; flight movement should remain under the flight controller.
		if(verticalDirection > 0 && !MC.player.getAbilities().flying
			&& (MC.player.horizontalCollision || MC.player.onGround()))
		{
			Vec3 velocity = MC.player.getDeltaMovement();
			if(velocity.y < 0.2)
				MC.player.setDeltaMovement(velocity.x, 0.2, velocity.z);
		}
	}
	
	private void place(BlockPos target)
	{
		// FastPlace-compatible behavior: StairMaker owns its click cadence and
		// also works when AirPlace is enabled, including while flying.
		MC.rightClickDelay = 0;
		
		// Prefer a real neighboring face when one exists. If the target is in
		// air, use the synthetic upper face expected by AirPlace so the placed
		// block is target itself, not the block underneath it.
		BlockPos below = target.below();
		BlockPos above = target.above();
		BlockPos clickPos;
		Direction face;
		if(!MC.level.getBlockState(below).canBeReplaced())
		{
			clickPos = below;
			face = Direction.UP;
		}else
		{
			clickPos = above;
			face = Direction.DOWN;
		}
		
		BlockHitResult hit = new BlockHitResult(Vec3.atLowerCornerOf(clickPos),
			face, clickPos, false);
		MC.gameMode.useItemOn(MC.player, InteractionHand.MAIN_HAND, hit);
		MC.player.swing(InteractionHand.MAIN_HAND);
		MC.rightClickDelay = 0;
	}
}
