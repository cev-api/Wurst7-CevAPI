/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.autoflypath.engine;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class FlightGrid
{
	public static final int SOURCE_PREDICTED = 0;
	public static final int SOURCE_REAL = 1;
	private final int minY;
	private final int height;
	private final ConcurrentHashMap<Long, GridChunk> chunks =
		new ConcurrentHashMap();
	
	public FlightGrid(int minY, int height)
	{
		this.minY = minY;
		this.height = height;
	}
	
	public int minY()
	{
		return this.minY;
	}
	
	public int height()
	{
		return this.height;
	}
	
	public static long key(int cx, int cz)
	{
		return (long)cx << 32 | (long)cz & 0xFFFFFFFFL;
	}
	
	private static int bitIndex(int lx, int yIdx, int lz)
	{
		return yIdx << 8 | lz << 4 | lx;
	}
	
	public long[] newChunkBits()
	{
		return new long[(this.height << 8) / 64];
	}
	
	public void putChunk(int cx, int cz, long[] bits, int source)
	{
		this.putChunk(cx, cz, bits, this.newChunkBits(), source);
	}
	
	public void putChunk(int cx, int cz, long[] bits, long[] hazard, int source)
	{
		this.chunks.compute(FlightGrid.key(cx, cz), (k, existing) -> {
			if(existing != null && existing.source == 1 && source == 0)
			{
				return existing;
			}
			return new GridChunk(bits, hazard, source);
		});
	}
	
	public boolean hasChunk(int cx, int cz)
	{
		return this.chunks.containsKey(FlightGrid.key(cx, cz));
	}
	
	public boolean hasRealChunk(int cx, int cz)
	{
		GridChunk c = this.chunks.get(FlightGrid.key(cx, cz));
		return c != null && c.source == 1;
	}
	
	public long[] chunkBits(int cx, int cz)
	{
		GridChunk c = this.chunks.get(FlightGrid.key(cx, cz));
		return c == null ? null : c.bits;
	}
	
	GridChunk chunkAt(int cx, int cz)
	{
		return this.chunks.get(FlightGrid.key(cx, cz));
	}
	
	public boolean isHazard(int x, int y, int z)
	{
		int yIdx = y - this.minY;
		if(yIdx < 0 || yIdx >= this.height)
		{
			return false;
		}
		GridChunk c = this.chunks.get(FlightGrid.key(x >> 4, z >> 4));
		if(c == null)
		{
			return false;
		}
		int bit = FlightGrid.bitIndex(x & 0xF, yIdx, z & 0xF);
		return (c.hazard[bit >> 6] & 1L << (bit & 0x3F)) != 0L;
	}
	
	public void setBlock(int x, int y, int z, boolean solid)
	{
		this.setBlock(x, y, z, solid, false);
	}
	
	public void setBlock(int x, int y, int z, boolean solid, boolean hazardous)
	{
		GridChunk c = this.chunks.get(FlightGrid.key(x >> 4, z >> 4));
		if(c == null)
		{
			return;
		}
		int yIdx = y - this.minY;
		if(yIdx < 0 || yIdx >= this.height)
		{
			return;
		}
		int bit = FlightGrid.bitIndex(x & 0xF, yIdx, z & 0xF);
		if(solid)
		{
			int n = bit >> 6;
			c.bits[n] = c.bits[n] | 1L << (bit & 0x3F);
		}else
		{
			int n = bit >> 6;
			c.bits[n] = c.bits[n] & (1L << (bit & 0x3F) ^ 0xFFFFFFFFFFFFFFFFL);
		}
		if(hazardous)
		{
			int n = bit >> 6;
			c.hazard[n] = c.hazard[n] | 1L << (bit & 0x3F);
		}else
		{
			int n = bit >> 6;
			c.hazard[n] =
				c.hazard[n] & (1L << (bit & 0x3F) ^ 0xFFFFFFFFFFFFFFFFL);
		}
	}
	
	public boolean isSolid(int x, int y, int z)
	{
		int yIdx = y - this.minY;
		if(yIdx >= this.height)
		{
			return false;
		}
		if(yIdx < 0)
		{
			return true;
		}
		GridChunk c = this.chunks.get(FlightGrid.key(x >> 4, z >> 4));
		if(c == null)
		{
			return true;
		}
		int bit = FlightGrid.bitIndex(x & 0xF, yIdx, z & 0xF);
		return (c.bits[bit >> 6] & 1L << (bit & 0x3F)) != 0L;
	}
	
	public boolean columnBlocked(int x, int y, int z)
	{
		return this.isSolid(x, y, z) || this.isSolid(x, y + 1, z)
			|| this.isSolid(x, y + 2, z);
	}
	
	public void cullBeyond(int ccx, int ccz, int chunkRadius)
	{
		Iterator<Map.Entry<Long, GridChunk>> it =
			this.chunks.entrySet().iterator();
		while(it.hasNext())
		{
			long k = it.next().getKey();
			int cx = (int)(k >> 32);
			int cz = (int)k;
			if(Math.max(Math.abs(cx - ccx), Math.abs(cz - ccz)) <= chunkRadius)
				continue;
			it.remove();
		}
	}
	
	public int chunkCount()
	{
		return this.chunks.size();
	}
	
	public static final class GridChunk
	{
		final long[] bits;
		final long[] hazard;
		volatile int source;
		
		GridChunk(long[] bits, long[] hazard, int source)
		{
			this.bits = bits;
			this.hazard = hazard;
			this.source = source;
		}
	}
}
