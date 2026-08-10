/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.autoflypath.flight;

import net.wurstclient.autoflypath.engine.FlightGrid;
import net.wurstclient.autoflypath.engine.GridRay;
import net.wurstclient.autoflypath.engine.LazyThetaStar;
import net.wurstclient.autoflypath.engine.NetherBiomeRisk;
import net.wurstclient.autoflypath.engine.NetherTerrainGenerator;
import java.lang.ref.SoftReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.phys.Vec3;

public final class FlightPathfinder
{
	private static final BlockState AIR_BLOCK_STATE =
		Blocks.AIR.defaultBlockState();
	private static final int SEARCH_MAX_POPS = 400000;
	private static final long SEARCH_TIME_BUDGET_NANOS = 450000000L;
	private static final int MAX_ESCALATION = 3;
	private long lastSearchStart = Long.MIN_VALUE;
	private double lastSearchBestH = Double.POSITIVE_INFINITY;
	private int escalation;
	private final List<double[]> noGoZones = new CopyOnWriteArrayList<>();
	private final FlightGrid grid;
	private final long seed;
	private final boolean predictTerrain;
	private final NetherTerrainGenerator generator;
	private final ExecutorService executor;
	private final ExecutorService packExecutor;
	private volatile NetherBiomeRisk biomeRisk;
	private static final double CONTINUE_PROBE = 24.0;
	private static final double[][] CONTINUE_ROTS = {{1.0, 0.0}, {0.819, 0.574},
		{0.819, -0.574}, {0.342, 0.94}, {0.342, -0.94}};
	
	private static boolean isBurnHazard(BlockState state)
	{
		return state.is(Blocks.LAVA) || state.is(Blocks.FIRE)
			|| state.is(Blocks.SOUL_FIRE);
	}
	
	public FlightPathfinder(long seed, int worldMinY, int worldHeight,
		boolean predictTerrain)
	{
		this.grid = new FlightGrid(worldMinY, worldHeight);
		this.seed = seed;
		this.predictTerrain = predictTerrain;
		this.generator =
			predictTerrain ? new NetherTerrainGenerator(seed) : null;
		this.executor = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "AutoFly-Engine");
			t.setDaemon(true);
			return t;
		});
		this.packExecutor = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "AutoFly-Pack");
			t.setDaemon(true);
			return t;
		});
		if(predictTerrain)
		{
			this.executor.execute(() -> {
				try
				{
					this.biomeRisk = NetherBiomeRisk.create(seed);
				}catch(Throwable t)
				{
					t.printStackTrace();
				}
			});
		}
	}
	
	public long getSeed()
	{
		return this.seed;
	}
	
	public boolean isPredictTerrain()
	{
		return this.predictTerrain;
	}
	
	public int gridMinY()
	{
		return this.grid.minY();
	}
	
	public int gridHeight()
	{
		return this.grid.height();
	}
	
	public NetherBiomeRisk getBiomeRisk()
	{
		return this.biomeRisk;
	}
	
	public boolean hasChunk(ChunkPos pos)
	{
		return this.grid.hasRealChunk(pos.x(), pos.z());
	}
	
	public boolean hasRealChunk(int chunkX, int chunkZ)
	{
		return this.grid.hasRealChunk(chunkX, chunkZ);
	}
	
	public FlightGrid grid()
	{
		return this.grid;
	}
	
	public void queueForPacking(LevelChunk chunkIn)
	{
		SoftReference<LevelChunk> ref = new SoftReference<LevelChunk>(chunkIn);
		this.packExecutor.execute(() -> {
			LevelChunk chunk = (LevelChunk)ref.get();
			if(chunk != null)
			{
				this.packRealChunk(chunk);
			}
		});
	}
	
	public void queueBlockUpdate(BlockPos pos, BlockState state)
	{
		BlockPos p = pos.immutable();
		boolean solid = state != AIR_BLOCK_STATE && !state.isAir();
		boolean hazard = FlightPathfinder.isBurnHazard(state);
		this.packExecutor.execute(() -> this.grid.setBlock(p.getX(), p.getY(),
			p.getZ(), solid, hazard));
	}
	
	public void queueCacheCulling(int chunkX, int chunkZ, int maxDistanceBlocks)
	{
		this.packExecutor.execute(() -> this.grid.cullBeyond(chunkX, chunkZ,
			Math.max(1, maxDistanceBlocks >> 4)));
	}
	
	private void packRealChunk(LevelChunk chunk)
	{
		try
		{
			long[] bits = this.grid.newChunkBits();
			long[] hazard = this.grid.newChunkBits();
			int gridMinY = this.grid.minY();
			int gridHeight = this.grid.height();
			int chunkMinY = chunk.getMinY();
			LevelChunkSection[] sections = chunk.getSections();
			for(int i = 0; i < sections.length; ++i)
			{
				LevelChunkSection section;
				int sectionBottomY = chunkMinY + (i << 4);
				if(sectionBottomY + 16 <= gridMinY
					|| sectionBottomY >= gridMinY + gridHeight
					|| (section = sections[i]) == null || section.hasOnlyAir())
					continue;
				PalettedContainer states = section.getStates();
				for(int ly = 0; ly < 16; ++ly)
				{
					int yIdx = sectionBottomY + ly - gridMinY;
					if(yIdx < 0 || yIdx >= gridHeight)
						continue;
					for(int z = 0; z < 16; ++z)
					{
						for(int x = 0; x < 16; ++x)
						{
							BlockState state = (BlockState)states.get(x, ly, z);
							if(state.isAir())
								continue;
							int bit = yIdx << 8 | z << 4 | x;
							int n = bit >> 6;
							bits[n] = bits[n] | 1L << (bit & 0x3F);
							if(!FlightPathfinder.isBurnHazard(state))
								continue;
							int n2 = bit >> 6;
							hazard[n2] = hazard[n2] | 1L << (bit & 0x3F);
						}
					}
				}
			}
			this.grid.putChunk(chunk.getPos().x(), chunk.getPos().z(), bits,
				hazard, 1);
		}catch(Exception e)
		{
			e.printStackTrace();
			throw new RuntimeException(e);
		}
	}
	
	public void setNoGoZones(Collection<double[]> zones)
	{
		this.noGoZones.clear();
		for(double[] zone : zones)
			this.noGoZones.add(zone.clone());
	}
	
	public CompletableFuture<UnpackedSegment> pathFindAsync(BlockPos src,
		BlockPos dst)
	{
		return CompletableFuture.supplyAsync(() -> {
			LazyThetaStar.ChunkEnsurer ensurer =
				this.generator == null ? null : (cx, cz) -> {
					if(!this.grid.hasChunk(cx, cz))
					{
						NetherBiomeRisk risk = this.biomeRisk;
						this.generator.generate(this.grid, cx, cz,
							risk != null && risk.isRiskyChunk(cx, cz));
					}
				};
			long destKey = (long)(dst.getX() >> 4) << 40
				^ (long)(dst.getY() >> 4) << 20 ^ (long)(dst.getZ() >> 4);
			if(destKey != this.lastSearchStart)
			{
				this.escalation = 0;
				this.lastSearchBestH = Double.POSITIVE_INFINITY;
			}
			this.lastSearchStart = destKey;
			double weight = this.escalation >= 2 ? 1.0 : 1.15;
			LazyThetaStar.Result result = new LazyThetaStar(this.grid, ensurer,
				this.noGoZones.toArray(double[][]::new)).search(src.getX(),
					src.getY(), src.getZ(), dst.getX(), dst.getY(), dst.getZ(),
					400000 << this.escalation,
					System.nanoTime() + (450000000L << this.escalation),
					weight);
			if(result.path.isEmpty())
			{
				throw new PathCalculationException("Path calculation failed");
			}
			List<int[]> path = result.path;
			if(!result.finished && path.size() > 2)
				path = this.trimToContinuable(path, dst, ensurer);
			int[] tail = path.get(path.size() - 1);
			double bestH = Math.sqrt(Math.pow(tail[0] - dst.getX(), 2.0)
				+ Math.pow(tail[1] - dst.getY(), 2.0)
				+ Math.pow(tail[2] - dst.getZ(), 2.0));
			int[] head = path.get(0);
			double tailAdvance = Math.sqrt(Math.pow(tail[0] - head[0], 2.0)
				+ Math.pow(tail[1] - head[1], 2.0)
				+ Math.pow(tail[2] - head[2], 2.0));
			this.escalation = !result.finished
				&& (bestH > this.lastSearchBestH - 8.0 || tailAdvance < 96.0)
					? Math.min(this.escalation + 1, 3) : 0;
			this.lastSearchBestH = Math.min(this.lastSearchBestH, bestH);
			return new UnpackedSegment(
				path.stream().map(c -> new BetterBlockPos(c[0], c[1], c[2])),
				result.finished);
		}, this.executor);
	}
	
	private List<int[]> trimToContinuable(List<int[]> path, BlockPos dst,
		LazyThetaStar.ChunkEnsurer ensurer)
	{
		for(int i = path.size() - 1; i >= 1; --i)
		{
			if(!this.continuable(path.get(i), dst, ensurer))
				continue;
			return i == path.size() - 1 ? path
				: new ArrayList<>(path.subList(0, i + 1));
		}
		return path;
	}
	
	private boolean continuable(int[] node, BlockPos dst,
		LazyThetaStar.ChunkEnsurer ensurer)
	{
		double cx = node[0] + 0.5;
		double cy = node[1] + 1.0;
		double cz = node[2] + 0.5;
		double dx = dst.getX() + 0.5 - cx;
		double dz = dst.getZ() + 0.5 - cz;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		if(horizontal < CONTINUE_PROBE)
			return true;
		dx /= horizontal;
		dz /= horizontal;
		GridRay.CellTest test = (x, y, z) -> {
			if(ensurer != null)
				ensurer.ensure(x >> 4, z >> 4);
			return this.grid.isSolid(x, y, z);
		};
		for(double[] rotation : CONTINUE_ROTS)
		{
			double rx = dx * rotation[0] - dz * rotation[1];
			double rz = dx * rotation[1] + dz * rotation[0];
			for(int pitch = 0; pitch <= 1; ++pitch)
			{
				double py = pitch == 0 ? 0.0 : 0.45;
				double norm = Math.sqrt(1.0 + py * py);
				if(GridRay.clear(test, cx, cy, cz,
					cx + rx / norm * CONTINUE_PROBE,
					cy + py / norm * CONTINUE_PROBE,
					cz + rz / norm * CONTINUE_PROBE))
					return true;
			}
		}
		return false;
	}
	
	public boolean pathLineClear(Vec3 a, Vec3 b)
	{
		return GridRay.corridorClear(this.grid, a.x, a.y, a.z, b.x, b.y, b.z);
	}
	
	public boolean pathLineClear(Vec3 a, Vec3 b, double offset)
	{
		return GridRay.corridorClear(this.grid, a.x, a.y, a.z, b.x, b.y, b.z,
			offset);
	}
	
	public boolean pathSegmentSafe(BlockPos a, BlockPos b)
	{
		double[][] offs;
		GridRay.CellTest test = (x, y, z) -> {
			if(this.grid.columnBlocked(x, y, z))
			{
				return true;
			}
			for(int dy = 1; dy <= 4; ++dy)
			{
				if(!this.grid.isSolid(x, y - dy, z))
					continue;
				return this.grid.isHazard(x, y - dy, z);
			}
			return false;
		};
		double ax = (double)a.getX() + 0.5;
		double ay = (double)a.getY() + 0.5;
		double az = (double)a.getZ() + 0.5;
		double bx = (double)b.getX() + 0.5;
		double by = (double)b.getY() + 0.5;
		double bz = (double)b.getZ() + 0.5;
		for(double[] o : offs = new double[][]{{0.45, 0.45}, {0.45, -0.45},
			{-0.45, 0.45}, {-0.45, -0.45}})
		{
			if(GridRay.clear(test, ax + o[0], ay, az + o[1], bx + o[0], by,
				bz + o[1]))
				continue;
			return false;
		}
		return true;
	}
	
	public double raytraceDistance(Vec3 from, Vec3 to)
	{
		return GridRay.traceDistance(this.grid::isSolid, from.x, from.y, from.z,
			to.x, to.y, to.z);
	}
	
	public void destroy()
	{
		this.executor.shutdownNow();
		this.packExecutor.shutdownNow();
		try
		{
			this.executor.awaitTermination(2L, TimeUnit.SECONDS);
			this.packExecutor.awaitTermination(2L, TimeUnit.SECONDS);
		}catch(InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}
	}
}
