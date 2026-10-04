/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Blocks touched by a player-sized tunnel swept along the requested aim. */
public final class MiningTunnel
{
	private static final double HALF_WIDTH = 0.3;
	private static final double HEIGHT = 2;
	private static final double EPSILON = 1E-7;
	
	private MiningTunnel()
	{}
	
	public static List<BlockPos> blocks(Vec3 feet, Vec3 aim, double distance)
	{
		ArrayList<BlockPos> blocks = new ArrayList<>();
		if(distance <= 0 || aim.lengthSqr() < EPSILON)
			return blocks;
		Vec3 direction = aim.normalize();
		// Keep the existing two-block-high opening aligned with the floor.
		Vec3 start = new Vec3(feet.x, Math.floor(feet.y), feet.z);
		Vec3 end = start.add(direction.scale(distance));
		int minX = (int)Math.floor(Math.min(start.x, end.x) - HALF_WIDTH);
		int maxX = (int)Math.floor(Math.max(start.x, end.x) + HALF_WIDTH);
		int minY = (int)Math.floor(Math.min(start.y, end.y));
		int maxY = (int)Math.floor(Math.max(start.y, end.y) + HEIGHT - EPSILON);
		int minZ = (int)Math.floor(Math.min(start.z, end.z) - HALF_WIDTH);
		int maxZ = (int)Math.floor(Math.max(start.z, end.z) + HALF_WIDTH);
		for(int x = minX; x <= maxX; x++)
			for(int y = minY; y <= maxY; y++)
				for(int z = minZ; z <= maxZ; z++)
				{
					// Leave the player's current opening alone. Sideways
					// movement
					// shifts the sweep automatically without snapping its
					// heading.
					if(x + 1 > start.x - HALF_WIDTH + EPSILON
						&& x < start.x + HALF_WIDTH - EPSILON
						&& y + 1 > start.y + EPSILON
						&& y < start.y + HEIGHT - EPSILON
						&& z + 1 > start.z - HALF_WIDTH + EPSILON
						&& z < start.z + HALF_WIDTH - EPSILON)
						continue;
					if(intersects(start, direction, distance, x, y, z))
						blocks.add(new BlockPos(x, y, z));
				}
		return blocks;
	}
	
	private static boolean intersects(Vec3 start, Vec3 direction,
		double distance, int x, int y, int z)
	{
		// Expand each block by the tunnel footprint, then clip the ray against
		// all three slabs. This includes corner blocks a thin ray would miss.
		double enter = 0;
		double exit = distance;
		for(int axis = 0; axis < 3; axis++)
		{
			double origin = axis == 0 ? start.x : axis == 1 ? start.y : start.z;
			double velocity =
				axis == 0 ? direction.x : axis == 1 ? direction.y : direction.z;
			double block = axis == 0 ? x : axis == 1 ? y : z;
			double min = block - (axis == 1 ? HEIGHT : HALF_WIDTH);
			double max = block + 1 + (axis == 1 ? 0 : HALF_WIDTH);
			if(Math.abs(velocity) < EPSILON)
			{
				if(origin <= min + EPSILON || origin >= max - EPSILON)
					return false;
				continue;
			}
			double a = (min - origin) / velocity;
			double b = (max - origin) / velocity;
			enter = Math.max(enter, Math.min(a, b));
			exit = Math.min(exit, Math.max(a, b));
			if(exit - enter <= EPSILON)
				return false;
		}
		return true;
	}
}
