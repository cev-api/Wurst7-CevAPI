/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.autoflypath.engine;

public final class GridRay
{
	private GridRay()
	{}
	
	public static double traceDistance(CellTest test, double fx, double fy,
		double fz, double tx, double ty, double tz)
	{
		double tDeltaZ;
		double dx = tx - fx;
		double dy = ty - fy;
		double dz = tz - fz;
		double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if(!(len > 1.0E-7) || !Double.isFinite(len))
		{
			return Double.POSITIVE_INFINITY;
		}
		double inv = 1.0 / len;
		double ux = dx * inv;
		double uy = dy * inv;
		double uz = dz * inv;
		int x = (int)Math.floor(fx);
		int y = (int)Math.floor(fy);
		int z = (int)Math.floor(fz);
		int stepX = ux > 0.0 ? 1 : -1;
		int stepY = uy > 0.0 ? 1 : -1;
		int stepZ = uz > 0.0 ? 1 : -1;
		double tDeltaX =
			ux != 0.0 ? Math.abs(1.0 / ux) : Double.POSITIVE_INFINITY;
		double tDeltaY =
			uy != 0.0 ? Math.abs(1.0 / uy) : Double.POSITIVE_INFINITY;
		tDeltaZ = uz != 0.0 ? Math.abs(1.0 / uz) : Double.POSITIVE_INFINITY;
		double tMaxX = ux != 0.0
			? (ux > 0.0 ? (double)(x + 1) - fx : fx - (double)x) * tDeltaX
			: Double.POSITIVE_INFINITY;
		double tMaxY = uy != 0.0
			? (uy > 0.0 ? (double)(y + 1) - fy : fy - (double)y) * tDeltaY
			: Double.POSITIVE_INFINITY;
		double tMaxZ = uz != 0.0
			? (uz > 0.0 ? (double)(z + 1) - fz : fz - (double)z) * tDeltaZ
			: Double.POSITIVE_INFINITY;
		double tEntry = 0.0;
		while(tEntry <= len)
		{
			if(test.blocked(x, y, z))
			{
				return Math.max(0.0, tEntry);
			}
			if(tMaxX <= tMaxY && tMaxX <= tMaxZ)
			{
				tEntry = tMaxX;
				tMaxX += tDeltaX;
				x += stepX;
				continue;
			}
			if(tMaxY <= tMaxZ)
			{
				tEntry = tMaxY;
				tMaxY += tDeltaY;
				y += stepY;
				continue;
			}
			tEntry = tMaxZ;
			tMaxZ += tDeltaZ;
			z += stepZ;
		}
		return Double.POSITIVE_INFINITY;
	}
	
	public static boolean clear(CellTest test, double fx, double fy, double fz,
		double tx, double ty, double tz)
	{
		return GridRay.traceDistance(test, fx, fy, fz, tx, ty,
			tz) == Double.POSITIVE_INFINITY;
	}
	
	public static boolean corridorClear(FlightGrid grid, double fx, double fy,
		double fz, double tx, double ty, double tz)
	{
		return GridRay.corridorClear(grid, fx, fy, fz, tx, ty, tz, 0.45);
	}
	
	public static boolean corridorClear(FlightGrid grid, double fx, double fy,
		double fz, double tx, double ty, double tz, double offset)
	{
		double[][] offs;
		CellTest column = grid::columnBlocked;
		for(double[] o : offs = new double[][]{{offset, offset},
			{offset, -offset}, {-offset, offset}, {-offset, -offset}})
		{
			if(GridRay.clear(column, fx + o[0], fy + 0.05, fz + o[1], tx + o[0],
				ty + 0.05, tz + o[1]))
				continue;
			return false;
		}
		return true;
	}
	
	public static interface CellTest
	{
		public boolean blocked(int var1, int var2, int var3);
	}
}
