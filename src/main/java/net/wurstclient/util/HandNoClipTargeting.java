/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

public final class HandNoClipTargeting
{
	public static Result trace(BlockGetter world, Vec3 eyes, Vec3 look,
		double blockReach, double entityDistance, Predicate<BlockState> listed,
		boolean bedrockPassthrough)
	{
		Set<BlockPos> path = new HashSet<>();
		boolean[] passedBedrock = {false};
		boolean hasEntity = Double.isFinite(entityDistance);
		double range =
			hasEntity ? Math.max(blockReach, entityDistance) : blockReach;
		Vec3 end = eyes.add(look.scale(range));
		return BlockGetter.traverseBlocks(eyes, end, world, (view, pos) -> {
			BlockState state = view.getBlockState(pos);
			// Read unfiltered outlines so last frame's HandNoClip path cannot
			// hide either the new target or its intervening blocks.
			var shape = state.getShape(view, pos, CollisionContext.empty());
			var hit =
				view.clipWithInteractionOverride(eyes, end, pos, shape, state);
			if(hit == null)
				return null;
			double distance = eyes.distanceTo(hit.getLocation());
			if(hasEntity && distance >= entityDistance)
				return new Result(null, Set.copyOf(path));
			if(distance <= blockReach
				&& (listed.test(state) || bedrockPassthrough && passedBedrock[0]
					&& !state.is(Blocks.BEDROCK)))
				return new Result(pos.immutable(), Set.copyOf(path));
			if(state.is(Blocks.BEDROCK))
				passedBedrock[0] = true;
			path.add(pos.immutable());
			return null;
		}, view -> new Result(null, hasEntity ? Set.copyOf(path) : Set.of()));
	}
	
	public record Result(BlockPos block, Set<BlockPos> occludingBlocks)
	{}
}
