/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

public final class HandNoClipTargetingRegressionTest
{
	private static final Vec3 EYES = new Vec3(0.5, 0.5, 0.5);
	private static final Vec3 LOOK = new Vec3(1, 0, 0);
	
	public static void main(String[] args)
	{
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		World world = new World();
		world.put(1, Blocks.BEDROCK.defaultBlockState());
		world.put(2, Blocks.STONE.defaultBlockState());
		var result = trace(world, 4.5, Double.POSITIVE_INFINITY, true);
		check(pos(2).equals(result.block()),
			"Stone behind bedrock must remain targetable");
		check(result.occludingBlocks().equals(Set.of(pos(1))),
			"Only intervening bedrock must be hidden");
		check(trace(world, 4.5, Double.POSITIVE_INFINITY, false)
			.occludingBlocks().isEmpty(),
			"No eligible target must preserve normal outlines");
		world.put(3, Blocks.CHEST.defaultBlockState());
		result = trace(world, 4.5, Double.POSITIVE_INFINITY, false);
		check(
			pos(3).equals(result.block())
				&& result.occludingBlocks().equals(Set.of(pos(1), pos(2))),
			"Listed target must survive while both intervening blocks are hidden");
		
		world = new World();
		world.put(5, Blocks.STONE.defaultBlockState());
		world.put(1, Blocks.BEDROCK.defaultBlockState());
		check(
			pos(5).equals(
				trace(world, 4.5001, Double.POSITIVE_INFINITY, true).block()),
			"A target face just inside block reach must be accepted");
		check(
			trace(world, 4.49, Double.POSITIVE_INFINITY, true).occludingBlocks()
				.isEmpty(),
			"Targets beyond effective reach must not hide the foreground");
		world.put(5, Blocks.AIR.defaultBlockState());
		world.put(6, Blocks.STONE.defaultBlockState());
		check(trace(world, 4.5, Double.POSITIVE_INFINITY, true).block() == null,
			"Survival reach must not use the former six-block range");
		check(
			pos(6).equals(
				trace(world, 6, Double.POSITIVE_INFINITY, true).block()),
			"Extended effective reach must be respected");
		
		world = new World();
		world.put(1, Blocks.STONE.defaultBlockState());
		result = trace(world, 4.5, 2.25, false);
		check(
			result.block() == null
				&& result.occludingBlocks().equals(Set.of(pos(1))),
			"An entity in air must hide the wall without a later solid block");
		world.put(3, Blocks.CHEST.defaultBlockState());
		check(trace(world, 4.5, 2.25, false).block() == null,
			"An entity nearer than a listed block must win");
		check(pos(3).equals(trace(world, 4.5, 4, false).block()),
			"A listed block nearer than an entity must win");
		world = new World();
		world.put(4, Blocks.STONE.defaultBlockState());
		check(trace(world, 3, 5, false).occludingBlocks().contains(pos(4)),
			"Entity reach may exceed block reach");
		check(trace(new World(), 4.5, 2, false).occludingBlocks().isEmpty(),
			"An unobstructed entity must not invent intervening blocks");
		
		// The ray crosses this slab's block cell, but passes above its outline.
		world = new World();
		world.put(1, Blocks.STONE_SLAB.defaultBlockState());
		world.put(2, Blocks.CHEST.defaultBlockState());
		result = HandNoClipTargeting.trace(world, new Vec3(0.5, 0.75, 0.5),
			LOOK, 4.5, Double.POSITIVE_INFINITY,
			state -> state.is(Blocks.CHEST), false);
		check(
			pos(2).equals(result.block()) && result.occludingBlocks().isEmpty(),
			"Voxel traversal must test real outline shapes");
		System.out.println("HandNoClip targeting regression checks passed.");
	}
	
	private static HandNoClipTargeting.Result trace(World world, double reach,
		double entityDistance, boolean bedrock)
	{
		return HandNoClipTargeting.trace(world, EYES, LOOK, reach,
			entityDistance, state -> state.is(Blocks.CHEST), bedrock);
	}
	
	private static BlockPos pos(int x)
	{
		return new BlockPos(x, 0, 0);
	}
	
	private static void check(boolean condition, String message)
	{
		if(!condition)
			throw new AssertionError(message);
	}
	
	private static final class World implements BlockGetter
	{
		private final Map<BlockPos, BlockState> blocks = new HashMap<>();
		
		void put(int x, BlockState state)
		{
			blocks.put(pos(x), state);
		}
		
		@Override
		public BlockState getBlockState(BlockPos pos)
		{
			return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
		}
		
		@Override
		public FluidState getFluidState(BlockPos pos)
		{
			return getBlockState(pos).getFluidState();
		}
		
		@Override
		public BlockEntity getBlockEntity(BlockPos pos)
		{
			return null;
		}
		
		@Override
		public int getHeight()
		{
			return 384;
		}
		
		@Override
		public int getMinY()
		{
			return -64;
		}
	}
}
