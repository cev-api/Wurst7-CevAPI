/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.autoflypath.flight;

import net.minecraft.core.BlockPos;

public final class BetterBlockPos extends BlockPos
{
	public final int x;
	public final int y;
	public final int z;
	
	public BetterBlockPos(int x, int y, int z)
	{
		super(x, y, z);
		this.x = x;
		this.y = y;
		this.z = z;
	}
	
	public BetterBlockPos(BlockPos pos)
	{
		this(pos.getX(), pos.getY(), pos.getZ());
	}
	
	public double distanceSq(BlockPos other)
	{
		double dx = this.x - other.getX();
		double dy = this.y - other.getY();
		double dz = this.z - other.getZ();
		return dx * dx + dy * dy + dz * dz;
	}
}
