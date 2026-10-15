/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.ShelfBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Slot-centred aim points on actual shelf geometry, including its open cavity.
 */
public final class ShelfTransferTargeting
{
	private ShelfTransferTargeting()
	{}
	
	public static Vec3 aimPoint(BlockState state, BlockPos pos, int slot,
		BlockGetter level)
	{
		if(!(state.getBlock() instanceof ShelfBlock) || slot < 0 || slot >= 3)
			return null;
		Direction facing = state.getValue(ShelfBlock.FACING);
		double horizontal = (slot + 0.5) / 3;
		double x = switch(facing)
		{
			case NORTH -> 1 - horizontal;
			case SOUTH -> horizontal;
			default -> 0.5;
		};
		double z = switch(facing)
		{
			case WEST -> horizontal;
			case EAST -> 1 - horizontal;
			default -> 0.5;
		};
		double boundary =
			facing.getAxisDirection() == Direction.AxisDirection.NEGATIVE
				? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
		boolean found = false;
		for(AABB box : state.getShape(level, pos).toAabbs())
		{
			if(box.minY > 0.5 || box.maxY < 0.5)
				continue;
			if(facing.getAxis() == Direction.Axis.X
				? z < box.minZ || z > box.maxZ : x < box.minX || x > box.maxX)
				continue;
			double face = switch(facing)
			{
				case NORTH -> box.minZ;
				case SOUTH -> box.maxZ;
				case WEST -> box.minX;
				case EAST -> box.maxX;
				default -> throw new IllegalArgumentException(
					"Vertical shelf facing");
			};
			boundary =
				facing.getAxisDirection() == Direction.AxisDirection.NEGATIVE
					? Math.min(boundary, face) : Math.max(boundary, face);
			found = true;
		}
		if(!found)
			return null;
		// End just inside the panel so a ray actually intersects its front
		// face.
		double inside = boundary - (facing.getAxis() == Direction.Axis.X
			? facing.getStepX() : facing.getStepZ()) * 0.0001;
		return new Vec3(
			pos.getX() + (facing.getAxis() == Direction.Axis.X ? inside : x),
			pos.getY() + 0.5,
			pos.getZ() + (facing.getAxis() == Direction.Axis.Z ? inside : z));
	}
}
