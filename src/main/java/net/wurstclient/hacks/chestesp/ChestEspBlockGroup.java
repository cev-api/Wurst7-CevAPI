/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.chestesp;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.AABB;
import net.wurstclient.util.BlockUtils;

public abstract class ChestEspBlockGroup extends ChestEspGroup
{
	private final java.util.ArrayList<AABB> buriedBoxes =
		new java.util.ArrayList<>();
	
	protected abstract boolean matches(BlockEntity be);
	
	public final void addIfMatches(BlockEntity be)
	{
		if(!matches(be))
			return;
		
		AABB box = getBox(be);
		if(box == null)
			return;
		
		// Always add to main list so buried containers are never invisible
		boxes.add(box);
		if(isBuried(be))
			buriedBoxes.add(box);
	}
	
	private AABB getBox(BlockEntity be)
	{
		BlockPos pos = be.getBlockPos();
		// For ESP, draw even when not directly clickable (e.g., buried)
		if(be instanceof ChestBlockEntity)
			return getChestBox((ChestBlockEntity)be);
		return BlockUtils.getBoundingBox(pos);
	}
	
	private AABB getChestBox(ChestBlockEntity chestBE)
	{
		BlockState state = chestBE.getBlockState();
		if(!state.hasProperty(ChestBlock.TYPE))
			return null;
		
		ChestType chestType = state.getValue(ChestBlock.TYPE);
		
		// ignore other block in double chest
		if(chestType == ChestType.LEFT)
			return null;
		
		BlockPos pos = chestBE.getBlockPos();
		AABB box = BlockUtils.getBoundingBox(pos);
		
		// larger box for double chest
		if(chestType != ChestType.SINGLE)
		{
			BlockPos pos2 =
				pos.relative(ChestBlock.getConnectedDirection(state));
			AABB box2 = BlockUtils.getBoundingBox(pos2);
			box = box.minmax(box2);
		}
		
		return box;
	}
	
	private boolean isBuried(BlockEntity be)
	{
		BlockPos pos = be.getBlockPos();
		BlockState state = be.getBlockState();
		// Shulker: check facing side is not air
		if(be instanceof ShulkerBoxBlockEntity
			&& state.hasProperty(ShulkerBoxBlock.FACING))
		{
			Direction facing = state.getValue(ShulkerBoxBlock.FACING);
			BlockPos front = pos.relative(facing);
			BlockBehaviour.BlockStateBase bs = BlockUtils.getState(front);
			return bs != null && !bs.isAir();
		}
		// Chest: buried if block(s) above are not air
		if(be instanceof ChestBlockEntity)
		{
			boolean above1 = false;
			BlockBehaviour.BlockStateBase s1 = BlockUtils.getState(pos.above());
			above1 = s1 != null && !s1.isAir();
			
			boolean above2 = false;
			if(state.hasProperty(ChestBlock.TYPE))
			{
				ChestType type = state.getValue(ChestBlock.TYPE);
				if(type != ChestType.SINGLE)
				{
					BlockPos pos2 =
						pos.relative(ChestBlock.getConnectedDirection(state));
					BlockBehaviour.BlockStateBase s2 =
						BlockUtils.getState(pos2.above());
					above2 = s2 != null && !s2.isAir();
				}
			}
			return above1 || above2;
		}
		return false;
	}
	
	@Override
	public void clear()
	{
		super.clear();
		buriedBoxes.clear();
	}
	
	public java.util.List<AABB> getBuriedBoxes()
	{
		return java.util.Collections.unmodifiableList(buriedBoxes);
	}
	
}
