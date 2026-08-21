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
	public StairMakerHack()
	{
		super("StairMaker");
		setCategory(Category.BLOCKS);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null || MC.level == null
			|| !(MC.player.getMainHandItem().getItem() instanceof BlockItem)
			|| !MC.options.keyUp.isDown())
			return;
		
		Direction direction = MC.player.getDirection();
		BlockPos target = MC.player.blockPosition().relative(direction);
		if(MC.options.keyShift.isDown())
			target = target.below();
		
		if(!MC.level.getBlockState(target).canBeReplaced())
			return;
			
		// Use the same direct useItemOn operation as the supplied module. The
		// target is the block face in front of the player; the server resolves
		// the stair's placement/orientation from this hit result.
		BlockHitResult hit = new BlockHitResult(Vec3.atLowerCornerOf(target),
			Direction.DOWN, target, false);
		MC.gameMode.useItemOn(MC.player, InteractionHand.MAIN_HAND, hit);
		MC.player.swing(InteractionHand.MAIN_HAND);
		MC.rightClickDelay = 4;
	}
}
