/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.lootsorter;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.properties.ChestType;

/** A physical container, with both halves folded into one double-chest ID. */
public record LogicalContainer(BlockPos anchor)
{
	public static LogicalContainer fromTarget(Level level, BlockPos pos)
	{
		if(level == null || pos == null)
			return null;
		var state = level.getBlockState(pos);
		if(state.getBlock() instanceof ChestBlock)
		{
			if(state.getValue(ChestBlock.TYPE) == ChestType.RIGHT)
				pos = pos.relative(ChestBlock.getConnectedDirection(state));
			return new LogicalContainer(pos.immutable());
		}
		if(state.getBlock() instanceof BarrelBlock
			|| state.getBlock() instanceof ShulkerBoxBlock)
			return new LogicalContainer(pos.immutable());
		return null;
	}
	
	/**
	 * Re-resolves the world block so removed containers and changed chest
	 * halves are not treated as the old logical container.
	 */
	public boolean isStillSupported(Level level)
	{
		LogicalContainer current = fromTarget(level, anchor);
		return equals(current);
	}
}
