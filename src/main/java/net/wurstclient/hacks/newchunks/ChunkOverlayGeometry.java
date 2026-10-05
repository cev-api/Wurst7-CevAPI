/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.newchunks;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;

/** Collects visible geometry directly from the chunk region index. */
public final class ChunkOverlayGeometry
{
	public static final int REGION_SHIFT = 5;
	
	private ChunkOverlayGeometry()
	{}
	
	public static void collectBoxes(Map<Long, Set<ChunkPos>> index,
		BlockPos playerPos, double y, double maxDist,
		Predicate<ChunkPos> excluded, List<AABB> boxes)
	{
		boxes.clear();
		int cx = playerPos.getX() >> 4;
		int cz = playerPos.getZ() >> 4;
		int radius = (int)Math.ceil(maxDist / 16.0);
		int minRx = (cx - radius) >> REGION_SHIFT;
		int maxRx = (cx + radius) >> REGION_SHIFT;
		int minRz = (cz - radius) >> REGION_SHIFT;
		int maxRz = (cz + radius) >> REGION_SHIFT;
		double maxDistSquared = maxDist * maxDist;
		for(int rx = minRx; rx <= maxRx; rx++)
			for(int rz = minRz; rz <= maxRz; rz++)
			{
				long key = ((long)rx << 32) ^ (rz & 0xFFFFFFFFL);
				Set<ChunkPos> bucket = index.get(key);
				if(bucket == null)
					continue;
				for(ChunkPos chunk : bucket)
				{
					if(Math.abs(chunk.x() - cx) > radius
						|| Math.abs(chunk.z() - cz) > radius)
						continue;
					double dx =
						(double)playerPos.getX() - chunk.getMiddleBlockX();
					double dz =
						(double)playerPos.getZ() - chunk.getMiddleBlockZ();
					if(!(dx * dx + dz * dz < maxDistSquared)
						|| excluded != null && excluded.test(chunk))
						continue;
					double minX = chunk.getMinBlockX();
					double minZ = chunk.getMinBlockZ();
					boxes.add(
						new AABB(minX, y, minZ, minX + 16, y + 1, minZ + 16));
				}
			}
	}
}
