/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.ShelfBlock;
import net.minecraft.world.level.block.entity.ShelfBlockEntity;
import net.minecraft.world.phys.Vec3;

public enum ShelfUtils
{
	;
	
	public static Vec3 getItemPosition(ShelfBlockEntity shelf, int slot)
	{
		if(shelf == null)
			return null;
		
		double itemSlotPosition = (slot - 1) * 0.3125;
		Vec3 itemOffset = new Vec3(itemSlotPosition,
			shelf.getAlignItemsToBottom() ? -0.25 : 0.0, -0.25);
		Direction facing =
			(Direction)shelf.getBlockState().getValue(ShelfBlock.FACING);
		float yRot =
			facing.getAxis().isHorizontal() ? -facing.toYRot() : 180.0F;
		return shelf.position().add(rotateY(itemOffset, yRot));
	}
	
	private static Vec3 rotateY(Vec3 offset, float degrees)
	{
		double radians = Math.toRadians(degrees);
		double sin = Math.sin(radians);
		double cos = Math.cos(radians);
		return new Vec3(offset.x * cos + offset.z * sin, offset.y,
			offset.z * cos - offset.x * sin);
	}
}
