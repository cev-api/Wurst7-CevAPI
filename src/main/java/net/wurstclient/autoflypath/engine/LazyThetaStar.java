/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.autoflypath.engine;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class LazyThetaStar
{
	private static final double[] CLEARANCE_PENALTY =
		{0.0, 4.0, 1.5, 0.6, 0.25, 0.0};
	private static final double PREDICTED_CLEARANCE_SCALE = 1.5;
	private static final double CEIL_NEAR_PENALTY = 1.0;
	private static final double CEIL_MID_PENALTY = 0.4;
	private static final double FLOOR_NEAR_PENALTY = 0.5;
	private static final double FLOOR_MID_PENALTY = 0.2;
	private static final int[][][] CLEARANCE_RINGS = {
		{{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}},
		{{2, 0}, {-2, 0}, {0, 2}, {0, -2}, {2, 2}, {2, -2}, {-2, 2}, {-2, -2}},
		{{3, 0}, {-3, 0}, {0, 3}, {0, -3}, {3, 3}, {3, -3}, {-3, 3}, {-3, -3}},
		{{4, 0}, {-4, 0}, {0, 4}, {0, -4}}};
	private static final int HAZARD_CLEAR_BELOW = 4;
	private static final int HAZARD_PENALTY_DEPTH = 8;
	private static final double HAZARD_LOW_PENALTY = 1.2;
	private static final double NO_GO_PENALTY = 4.0;
	private static final double TAIL_OPEN_BIAS = 8.0;
	private static final double PREFERRED_CLEARANCE = 1.6;
	private static final double HEURISTIC_WEIGHT = 1.15;
	private static final int[][] NEIGHBORS = LazyThetaStar.buildNeighbors();
	private static final int[] NEIGHBOR_CUBE =
		LazyThetaStar.buildNeighborCube();
	private static final double[] NEIGHBOR_LEN =
		LazyThetaStar.buildNeighborLen();
	private static final int[][] NEIGHBOR_SUBSETS =
		LazyThetaStar.buildNeighborSubsets();
	private final FlightGrid grid;
	private final ChunkEnsurer ensurer;
	private final double[][] noGoZones;
	private final boolean preferOpenSpace;
	private final LongOpenHashSet ensured = new LongOpenHashSet();
	private long forcedPassable = Long.MIN_VALUE;
	private final Long2IntOpenHashMap posToId = new Long2IntOpenHashMap();
	private long[] nPos = new long[1024];
	private double[] nG = new double[1024];
	private double[] nPenalty = new double[1024];
	private int[] nParent = new int[1024];
	private boolean[] nClosed = new boolean[1024];
	private int nodeCount;
	private int[] heapId = new int[1024];
	private double[] heapKey = new double[1024];
	private int heapSize;
	private final boolean[] cube = new boolean[27];
	private int memoCx = Integer.MIN_VALUE;
	private int memoCz = Integer.MIN_VALUE;
	private FlightGrid.GridChunk memoChunk;
	
	public LazyThetaStar(FlightGrid grid, ChunkEnsurer ensurer)
	{
		this(grid, ensurer, new double[0][], false);
	}
	
	public LazyThetaStar(FlightGrid grid, ChunkEnsurer ensurer,
		double[][] noGoZones)
	{
		this(grid, ensurer, noGoZones, false);
	}
	
	public LazyThetaStar(FlightGrid grid, ChunkEnsurer ensurer,
		double[][] noGoZones, boolean preferOpenSpace)
	{
		this.grid = grid;
		this.ensurer = ensurer;
		this.noGoZones = noGoZones;
		this.preferOpenSpace = preferOpenSpace;
		this.posToId.defaultReturnValue(-1);
	}
	
	private static int[][] buildNeighbors()
	{
		ArrayList<int[]> out = new ArrayList<int[]>(26);
		for(int dx = -1; dx <= 1; ++dx)
		{
			for(int dy = -1; dy <= 1; ++dy)
			{
				for(int dz = -1; dz <= 1; ++dz)
				{
					if(dx == 0 && dy == 0 && dz == 0)
						continue;
					out.add(new int[]{dx, dy, dz});
				}
			}
		}
		return out.toArray(int[][]::new);
	}
	
	private static int cubeIdx(int dx, int dy, int dz)
	{
		return (dx + 1) * 9 + (dy + 1) * 3 + (dz + 1);
	}
	
	private static int[] buildNeighborCube()
	{
		int[] out = new int[NEIGHBORS.length];
		for(int i = 0; i < NEIGHBORS.length; ++i)
		{
			out[i] = LazyThetaStar.cubeIdx(NEIGHBORS[i][0], NEIGHBORS[i][1],
				NEIGHBORS[i][2]);
		}
		return out;
	}
	
	private static double[] buildNeighborLen()
	{
		double[] out = new double[NEIGHBORS.length];
		for(int i = 0; i < NEIGHBORS.length; ++i)
		{
			int[] d = NEIGHBORS[i];
			out[i] = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
		}
		return out;
	}
	
	private static int[][] buildNeighborSubsets()
	{
		int[][] out = new int[NEIGHBORS.length][];
		for(int i = 0; i < NEIGHBORS.length; ++i)
		{
			int dx = NEIGHBORS[i][0];
			int dy = NEIGHBORS[i][1];
			int dz = NEIGHBORS[i][2];
			int axes =
				(dx != 0 ? 1 : 0) + (dy != 0 ? 1 : 0) + (dz != 0 ? 1 : 0);
			ArrayList<int[]> subs = new ArrayList<int[]>();
			if(axes >= 2)
			{
				if(dx != 0)
				{
					subs.add(new int[]{dx, 0, 0});
				}
				if(dy != 0)
				{
					subs.add(new int[]{0, dy, 0});
				}
				if(dz != 0)
				{
					subs.add(new int[]{0, 0, dz});
				}
				if(axes == 3)
				{
					subs.add(new int[]{dx, dy, 0});
					subs.add(new int[]{dx, 0, dz});
					subs.add(new int[]{0, dy, dz});
				}
			}
			out[i] = new int[subs.size()];
			for(int j = 0; j < subs.size(); ++j)
			{
				out[i][j] = LazyThetaStar.cubeIdx(((int[])subs.get(j))[0],
					((int[])subs.get(j))[1], ((int[])subs.get(j))[2]);
			}
		}
		return out;
	}
	
	private static long pack(int x, int y, int z)
	{
		return (long)(x & 0x3FFFFFF) << 38 | (long)(y & 0xFFF) << 26
			| (long)(z & 0x3FFFFFF);
	}
	
	private static int unpackX(long p)
	{
		return (int)(p >> 38);
	}
	
	private static int unpackY(long p)
	{
		return (int)(p << 26 >> 52);
	}
	
	private static int unpackZ(long p)
	{
		return (int)(p << 38 >> 38);
	}
	
	private void touch(int x, int z)
	{
		if(this.ensurer == null)
		{
			return;
		}
		long key = FlightGrid.key(x >> 4, z >> 4);
		if(this.ensured.add(key))
		{
			this.ensurer.ensure(x >> 4, z >> 4);
			this.memoCx = Integer.MIN_VALUE;
		}
	}
	
	private FlightGrid.GridChunk memoChunk(int x, int z)
	{
		int cx = x >> 4;
		int cz = z >> 4;
		if(cx != this.memoCx || cz != this.memoCz)
		{
			this.memoCx = cx;
			this.memoCz = cz;
			this.memoChunk = this.grid.chunkAt(cx, cz);
		}
		return this.memoChunk;
	}
	
	private boolean solid(int x, int y, int z)
	{
		int yIdx = y - this.grid.minY();
		if(yIdx >= this.grid.height())
		{
			return false;
		}
		if(yIdx < 0)
		{
			return true;
		}
		FlightGrid.GridChunk c = this.memoChunk(x, z);
		if(c == null)
		{
			return true;
		}
		int bit = yIdx << 8 | (z & 0xF) << 4 | x & 0xF;
		return (c.bits[bit >> 6] & 1L << (bit & 0x3F)) != 0L;
	}
	
	private boolean hazard(int x, int y, int z)
	{
		int yIdx = y - this.grid.minY();
		if(yIdx < 0 || yIdx >= this.grid.height())
		{
			return false;
		}
		FlightGrid.GridChunk c = this.memoChunk(x, z);
		if(c == null)
		{
			return false;
		}
		int bit = yIdx << 8 | (z & 0xF) << 4 | x & 0xF;
		return (c.hazard[bit >> 6] & 1L << (bit & 0x3F)) != 0L;
	}
	
	private boolean columnBlocked(int x, int y, int z)
	{
		return this.solid(x, y, z) || this.solid(x, y + 1, z)
			|| this.solid(x, y + 2, z);
	}
	
	private boolean passable(int x, int y, int z)
	{
		this.touch(x, z);
		if(LazyThetaStar.pack(x, y, z) == this.forcedPassable)
		{
			return true;
		}
		if(this.columnBlocked(x, y, z))
		{
			return false;
		}
		return !this.hazardNear(x, y, z);
	}
	
	private boolean hazardBelow(int x, int y, int z, int depth)
	{
		for(int dy = 1; dy <= depth; ++dy)
		{
			if(!this.solid(x, y - dy, z))
				continue;
			return this.hazard(x, y - dy, z);
		}
		return false;
	}
	
	private boolean hazardNear(int x, int y, int z)
	{
		if(this.hazardBelow(x, y, z, 4))
		{
			return true;
		}
		for(int dx = -1; dx <= 1; ++dx)
		{
			for(int dz = -1; dz <= 1; ++dz)
			{
				if(dx == 0 && dz == 0)
					continue;
				this.touch(x + dx, z + dz);
				if(!this.hazard(x + dx, y, z + dz)
					&& !this.hazard(x + dx, y + 1, z + dz)
					&& !this.hazard(x + dx, y + 2, z + dz))
					continue;
				return true;
			}
		}
		return this.hazard(x, y + 3, z);
	}
	
	private boolean columnBlockedEnsured(int x, int y, int z)
	{
		this.touch(x, z);
		return this.columnBlocked(x, y, z);
	}
	
	private boolean los(long a, long b)
	{
		return this.los(a, b, 0.45);
	}
	
	private boolean los(long a, long b, double offset)
	{
		double[][] offs;
		GridRay.CellTest test = (x, y, z) -> this.columnBlockedEnsured(x, y, z)
			|| this.hazardBelow(x, y, z, 4);
		double ax = (double)LazyThetaStar.unpackX(a) + 0.5;
		double ay = LazyThetaStar.unpackY(a);
		double az = (double)LazyThetaStar.unpackZ(a) + 0.5;
		double bx = (double)LazyThetaStar.unpackX(b) + 0.5;
		double by = LazyThetaStar.unpackY(b);
		double bz = (double)LazyThetaStar.unpackZ(b) + 0.5;
		for(double[] o : offs = new double[][]{{offset, offset},
			{offset, -offset}, {-offset, offset}, {-offset, -offset}})
		{
			if(GridRay.clear(test, ax + o[0], ay + 0.5, az + o[1], bx + o[0],
				by + 0.5, bz + o[1]))
				continue;
			return false;
		}
		return true;
	}
	
	private double penalty(int x, int y, int z)
	{
		int clearance = this.lateralClearance(x, y, z);
		double p = CLEARANCE_PENALTY[clearance];
		if(this.preferOpenSpace && clearance < 5)
			p += (5 - clearance) * 2.5;
		FlightGrid.GridChunk chunk = this.memoChunk(x, z);
		if(p > 0.0 && (chunk == null || chunk.source != 1))
			p *= PREDICTED_CLEARANCE_SCALE;
		if(this.solid(x, y + 3, z))
			p += CEIL_NEAR_PENALTY;
		else if(this.solid(x, y + 4, z))
			p += CEIL_MID_PENALTY;
		if(this.solid(x, y - 1, z))
			p += FLOOR_NEAR_PENALTY;
		else if(this.solid(x, y - 2, z))
			p += FLOOR_MID_PENALTY;
		for(int dy = 1; dy <= 8; ++dy)
		{
			if(!this.solid(x, y - dy, z))
				continue;
			if(!this.hazard(x, y - dy, z))
				break;
			p += 1.2 * (double)(9 - dy) / 8.0;
			break;
		}
		for(double[] zone : this.noGoZones)
		{
			double dx = x + 0.5 - zone[0];
			double dy = y + 0.5 - zone[1];
			double dz = z + 0.5 - zone[2];
			if(dx * dx + dy * dy + dz * dz < zone[3])
				p += NO_GO_PENALTY;
		}
		return p;
	}
	
	private boolean columnSolidAt(int x, int y, int z)
	{
		this.touch(x, z);
		return this.solid(x, y, z) || this.solid(x, y + 1, z)
			|| this.solid(x, y + 2, z);
	}
	
	private int lateralClearance(int x, int y, int z)
	{
		for(int ring = 0; ring < CLEARANCE_RINGS.length; ++ring)
		{
			for(int[] offset : CLEARANCE_RINGS[ring])
			{
				if(this.columnSolidAt(x + offset[0], y, z + offset[1]))
					return ring + 1;
			}
		}
		return CLEARANCE_RINGS.length + 1;
	}
	
	private static double dist(long a, long b)
	{
		double dx = LazyThetaStar.unpackX(a) - LazyThetaStar.unpackX(b);
		double dy = LazyThetaStar.unpackY(a) - LazyThetaStar.unpackY(b);
		double dz = LazyThetaStar.unpackZ(a) - LazyThetaStar.unpackZ(b);
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}
	
	private int nodeId(long pos)
	{
		int id = this.posToId.get(pos);
		if(id >= 0)
		{
			return id;
		}
		if((id = this.nodeCount++) >= this.nPos.length)
		{
			int cap = this.nPos.length << 1;
			this.nPos = Arrays.copyOf(this.nPos, cap);
			this.nG = Arrays.copyOf(this.nG, cap);
			this.nPenalty = Arrays.copyOf(this.nPenalty, cap);
			this.nParent = Arrays.copyOf(this.nParent, cap);
			this.nClosed = Arrays.copyOf(this.nClosed, cap);
		}
		this.nPos[id] = pos;
		this.nG[id] = Double.POSITIVE_INFINITY;
		this.nPenalty[id] = -1.0;
		this.nParent[id] = -1;
		this.nClosed[id] = false;
		this.posToId.put(pos, id);
		return id;
	}
	
	private void heapPush(int id, double key)
	{
		int parent;
		if(this.heapSize >= this.heapId.length)
		{
			this.heapId = Arrays.copyOf(this.heapId, this.heapId.length << 1);
			this.heapKey =
				Arrays.copyOf(this.heapKey, this.heapKey.length << 1);
		}
		int i = this.heapSize++;
		this.heapId[i] = id;
		this.heapKey[i] = key;
		while(i > 0 && !(this.heapKey[parent = i - 1 >> 1] <= this.heapKey[i]))
		{
			this.swap(i, parent);
			i = parent;
		}
	}
	
	private int heapPop()
	{
		int top = this.heapId[0];
		--this.heapSize;
		if(this.heapSize > 0)
		{
			this.heapId[0] = this.heapId[this.heapSize];
			this.heapKey[0] = this.heapKey[this.heapSize];
			int i = 0;
			while(true)
			{
				int l = (i << 1) + 1;
				int r = l + 1;
				int smallest = i;
				if(l < this.heapSize
					&& this.heapKey[l] < this.heapKey[smallest])
				{
					smallest = l;
				}
				if(r < this.heapSize
					&& this.heapKey[r] < this.heapKey[smallest])
				{
					smallest = r;
				}
				if(smallest == i)
					break;
				this.swap(i, smallest);
				i = smallest;
			}
		}
		return top;
	}
	
	private void swap(int a, int b)
	{
		int ti = this.heapId[a];
		this.heapId[a] = this.heapId[b];
		this.heapId[b] = ti;
		double tk = this.heapKey[a];
		this.heapKey[a] = this.heapKey[b];
		this.heapKey[b] = tk;
	}
	
	private double zoneBiasedHeuristic(long pos, double heuristic)
	{
		double x = LazyThetaStar.unpackX(pos) + 0.5;
		double y = LazyThetaStar.unpackY(pos) + 0.5;
		double z = LazyThetaStar.unpackZ(pos) + 0.5;
		for(double[] zone : this.noGoZones)
		{
			double dx = x - zone[0];
			double dy = y - zone[1];
			double dz = z - zone[2];
			if(dx * dx + dy * dy + dz * dz < zone[3])
				heuristic += 64.0;
		}
		return heuristic;
	}
	
	public Result search(int sx, int sy, int sz, int gx, int gy, int gz,
		int maxPops, long deadlineNanos)
	{
		return this.search(sx, sy, sz, gx, gy, gz, maxPops, deadlineNanos,
			HEURISTIC_WEIGHT);
	}
	
	public Result search(int sx, int sy, int sz, int gx, int gy, int gz,
		int maxPops, long deadlineNanos, double heuristicWeight)
	{
		long startPos;
		int minY = this.grid.minY();
		int maxFeetY = this.grid.minY() + this.grid.height() - 3;
		sy = Math.max(minY, Math.min(maxFeetY, sy));
		gy = Math.max(minY, Math.min(maxFeetY, gy));
		this.forcedPassable = startPos = this.findUsableStart(sx, sy, sz);
		long goalPos = LazyThetaStar.pack(gx, gy, gz);
		int startId = this.nodeId(startPos);
		this.nG[startId] = 0.0;
		this.nParent[startId] = startId;
		this.heapPush(startId,
			heuristicWeight * LazyThetaStar.dist(startPos, goalPos));
		int bestId = startId;
		double bestH = this.zoneBiasedHeuristic(startPos,
			LazyThetaStar.dist(startPos, goalPos))
			+ this.penalty(LazyThetaStar.unpackX(startPos),
				LazyThetaStar.unpackY(startPos),
				LazyThetaStar.unpackZ(startPos)) * TAIL_OPEN_BIAS;
		int pops = 0;
		boolean finished = false;
		while(this.heapSize > 0)
		{
			int[] d;
			int i;
			int s = this.heapPop();
			if(this.nClosed[s])
				continue;
			this.nClosed[s] = true;
			if((++pops & 0x3FF) == 0 && System.nanoTime() > deadlineNanos
				|| pops > maxPops)
				break;
			long spos = this.nPos[s];
			int x = LazyThetaStar.unpackX(spos);
			int y = LazyThetaStar.unpackY(spos);
			int z = LazyThetaStar.unpackZ(spos);
			double nodePenalty = this.nPenalty[s];
			if(nodePenalty < 0.0)
				this.nPenalty[s] = nodePenalty = this.penalty(x, y, z);
			double h = this.zoneBiasedHeuristic(spos,
				LazyThetaStar.dist(spos, goalPos))
				+ nodePenalty * TAIL_OPEN_BIAS;
			if(h < bestH)
			{
				bestH = h;
				bestId = s;
			}
			if(spos == goalPos)
			{
				bestId = s;
				finished = true;
				break;
			}
			double sg = this.nG[s];
			boolean[] cube = this.cube;
			for(i = 0; i < NEIGHBORS.length; ++i)
			{
				d = NEIGHBORS[i];
				int ny = y + d[1];
				cube[LazyThetaStar.NEIGHBOR_CUBE[i]] = ny >= minY
					&& ny <= maxFeetY && this.passable(x + d[0], ny, z + d[2]);
			}
			block2: for(i = 0; i < NEIGHBORS.length; ++i)
			{
				double tentative;
				double step;
				if(!cube[NEIGHBOR_CUBE[i]])
					continue;
				for(int sub : NEIGHBOR_SUBSETS[i])
				{
					if(!cube[sub])
						continue block2;
				}
				d = NEIGHBORS[i];
				int nx = x + d[0];
				int ny = y + d[1];
				int nz = z + d[2];
				long npos = LazyThetaStar.pack(nx, ny, nz);
				int n = this.nodeId(npos);
				if(this.nClosed[n]
					|| sg + (step = NEIGHBOR_LEN[i]) >= this.nG[n])
					continue;
				double penalty = this.nPenalty[n];
				if(penalty < 0.0)
					this.nPenalty[n] = penalty = this.penalty(nx, ny, nz);
				if(!((tentative = sg + step * (1.0 + penalty)) < this.nG[n]))
					continue;
				this.nG[n] = tentative;
				this.nParent[n] = s;
				this.heapPush(n, tentative
					+ heuristicWeight * LazyThetaStar.dist(npos, goalPos));
			}
		}
		ArrayList<Long> chain = new ArrayList<Long>();
		int cur = bestId;
		while(cur >= 0)
		{
			chain.add(0, this.nPos[cur]);
			if(this.nParent[cur] == cur)
				break;
			cur = this.nParent[cur];
		}
		return new Result(this.stringPull(chain), finished, pops);
	}
	
	private List<int[]> stringPull(List<Long> chain)
	{
		ArrayList<int[]> out = new ArrayList<int[]>();
		if(chain.isEmpty())
		{
			return out;
		}
		int i = 0;
		out.add(LazyThetaStar.cell(chain.get(0)));
		while(i < chain.size() - 1)
		{
			int j;
			int hiCap = Math.min(chain.size() - 1, i + 256);
			int reach = i + 1;
			int span = 2;
			while(reach < hiCap)
			{
				j = Math.min(i + span, hiCap);
				if(!this.los(chain.get(i), chain.get(j), 1.6))
					break;
				reach = j;
				span <<= 1;
			}
			if(reach == i + 1 && !this.los(chain.get(i), chain.get(i + 1), 1.6))
			{
				span = 2;
				while(reach < hiCap)
				{
					j = Math.min(i + span, hiCap);
					if(!this.los(chain.get(i), chain.get(j)))
						break;
					reach = j;
					span <<= 1;
				}
			}
			out.add(LazyThetaStar.cell(chain.get(reach)));
			i = reach;
		}
		return out;
	}
	
	private static int[] cell(long pos)
	{
		return new int[]{LazyThetaStar.unpackX(pos), LazyThetaStar.unpackY(pos),
			LazyThetaStar.unpackZ(pos)};
	}
	
	private long findUsableStart(int sx, int sy, int sz)
	{
		this.touch(sx, sz);
		if(!this.columnBlocked(sx, sy, sz))
		{
			return LazyThetaStar.pack(sx, sy, sz);
		}
		long best = LazyThetaStar.pack(sx, sy, sz);
		double bestD = Double.POSITIVE_INFINITY;
		for(int dy = -3; dy <= 3; ++dy)
		{
			for(int dx = -3; dx <= 3; ++dx)
			{
				for(int dz = -3; dz <= 3; ++dz)
				{
					double d;
					this.touch(sx + dx, sz + dz);
					if(this.columnBlocked(sx + dx, sy + dy, sz + dz) || !((d =
						(double)(dx * dx + dy * dy + dz * dz)) < bestD))
						continue;
					bestD = d;
					best = LazyThetaStar.pack(sx + dx, sy + dy, sz + dz);
				}
			}
		}
		return best;
	}
	
	public static interface ChunkEnsurer
	{
		public void ensure(int var1, int var2);
	}
	
	public static final class Result
	{
		public final List<int[]> path;
		public final boolean finished;
		public final int pops;
		
		Result(List<int[]> path, boolean finished, int pops)
		{
			this.path = path;
			this.finished = finished;
			this.pops = pops;
		}
	}
}
