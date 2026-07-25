/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.autoflypath.engine;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;

public final class NetherTerrainGenerator
{
	private static final int TERRAIN_MIN_Y = 0;
	private static final int TERRAIN_HEIGHT = 128;
	private static final int SEA_LEVEL = 32;
	private static final int FEATURE_MARGIN = 8;
	private final BlendedNoise noise;
	
	public NetherTerrainGenerator(long seed)
	{
		this.noise = BlendedNoise
			.createUnseeded((double)0.25, (double)0.375, (double)80.0,
				(double)60.0, (double)8.0)
			.withNewRandom((RandomSource)new LegacyRandomSource(seed));
	}
	
	private static double slide(double v, int y)
	{
		double top =
			NetherTerrainGenerator.clampedGradient(y, 104, 128, 1.0, 0.0);
		v = 0.9375 + top * (v - 0.9375);
		double bottom =
			NetherTerrainGenerator.clampedGradient(y, -8, 24, 0.0, 1.0);
		return 2.5 + bottom * (v - 2.5);
	}
	
	private static double clampedGradient(int y, int fromY, int toY,
		double fromValue, double toValue)
	{
		double t = Math.max(0.0,
			Math.min(1.0, (double)(y - fromY) / (double)(toY - fromY)));
		return fromValue + t * (toValue - fromValue);
	}
	
	public void generate(FlightGrid grid, int cx, int cz)
	{
		this.generate(grid, cx, cz, false);
	}
	
	/**
	 * Seed-only terrain cannot know trees, basalt pillars, or structures. In
	 * feature-risk chunks, reserve a safety margin under every ceiling so the
	 * predicted route does not treat thin, uncertain gaps as flyable corridors.
	 */
	public void generate(FlightGrid grid, int cx, int cz, boolean featureRisk)
	{
		int yi;
		long[] bits = grid.newChunkBits();
		long[] hazard = grid.newChunkBits();
		int bx = cx << 4;
		int bz = cz << 4;
		double[][][] corners = new double[5][17][5];
		for(int xi = 0; xi <= 4; ++xi)
		{
			for(yi = 0; yi <= 16; ++yi)
			{
				for(int zi = 0; zi <= 4; ++zi)
				{
					int wy = 0 + yi * 8;
					double raw = this.noise.compute(
						(DensityFunction.FunctionContext)new DensityFunction.SinglePointContext(
							bx + xi * 4, wy, bz + zi * 4));
					corners[xi][yi][zi] = NetherTerrainGenerator.slide(raw, wy);
				}
			}
		}
		for(int y = 0; y < 128; ++y)
		{
			yi = y >> 3;
			double fy = (double)(y & 7) / 8.0;
			int yIdx = 0 + y - grid.minY();
			for(int z = 0; z < 16; ++z)
			{
				int zi = z >> 2;
				double fz = (double)(z & 3) / 4.0;
				for(int x = 0; x < 16; ++x)
				{
					boolean lava;
					int xi = x >> 2;
					double fx = (double)(x & 3) / 4.0;
					double v = NetherTerrainGenerator.trilerp(corners, xi, yi,
						zi, fx, fy, fz);
					boolean terrain = v > 0.0;
					boolean bl = lava = !terrain && y < 32;
					if(!terrain && !lava || yIdx < 0 || yIdx >= grid.height())
						continue;
					int bit = yIdx << 8 | z << 4 | x;
					int n = bit >> 6;
					bits[n] = bits[n] | 1L << (bit & 0x3F);
					if(!lava)
						continue;
					int n2 = bit >> 6;
					hazard[n2] = hazard[n2] | 1L << (bit & 0x3F);
				}
			}
		}
		if(featureRisk)
		{
			boolean[] column = new boolean[TERRAIN_HEIGHT];
			for(int z = 0; z < 16; ++z)
			{
				for(int x = 0; x < 16; ++x)
				{
					for(int y = 0; y < TERRAIN_HEIGHT; ++y)
					{
						int yIdx = y - grid.minY();
						int bit = yIdx << 8 | z << 4 | x;
						column[y] = yIdx >= 0 && yIdx < grid.height()
							&& (bits[bit >> 6] & 1L << (bit & 0x3F)) != 0L;
					}
					int sinceSolid = 1000;
					for(int y = 0; y < TERRAIN_HEIGHT; ++y)
					{
						if(column[y])
						{
							sinceSolid = 0;
							continue;
						}
						int yIdx = y - grid.minY();
						if(++sinceSolid > FEATURE_MARGIN || yIdx < 0
							|| yIdx >= grid.height())
							continue;
						int bit = yIdx << 8 | z << 4 | x;
						bits[bit >> 6] |= 1L << (bit & 0x3F);
					}
				}
			}
		}
		grid.putChunk(cx, cz, bits, hazard, 0);
	}
	
	private static double trilerp(double[][][] c, int xi, int yi, int zi,
		double fx, double fy, double fz)
	{
		double x00 = c[xi][yi][zi] + fx * (c[xi + 1][yi][zi] - c[xi][yi][zi]);
		double x01 = c[xi][yi][zi + 1]
			+ fx * (c[xi + 1][yi][zi + 1] - c[xi][yi][zi + 1]);
		double x10 = c[xi][yi + 1][zi]
			+ fx * (c[xi + 1][yi + 1][zi] - c[xi][yi + 1][zi]);
		double x11 = c[xi][yi + 1][zi + 1]
			+ fx * (c[xi + 1][yi + 1][zi + 1] - c[xi][yi + 1][zi + 1]);
		double z0 = x00 + fz * (x01 - x00);
		double z1 = x10 + fz * (x11 - x10);
		return z0 + fy * (z1 - z0);
	}
}
