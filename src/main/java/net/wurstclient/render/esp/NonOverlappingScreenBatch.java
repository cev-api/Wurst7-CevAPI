/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.render.esp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.navigation.ScreenRectangle;

/** Only consecutive, disjoint operations may be reordered inside a batch. */
public final class NonOverlappingScreenBatch<T>
{
	private final int capacity;
	private final Consumer<List<T>> submit;
	private final ArrayList<T> pending = new ArrayList<>();
	private final HashMap<Long, ArrayList<ScreenRectangle>> cells =
		new HashMap<>();
	private boolean oversized;
	
	public NonOverlappingScreenBatch(int capacity, Consumer<List<T>> submit)
	{
		this.capacity = Math.max(1, capacity);
		this.submit = submit;
	}
	
	public void add(T operation, ScreenRectangle area)
	{
		if(area == null)
			return;
		boolean large = cellCount(area) > 256;
		if(pending.size() >= capacity || oversized || large || overlaps(area))
			flush();
		pending.add(operation);
		oversized = large;
		if(!large)
			for(int x = cell(area.left()); x <= cell(area.right()); x++)
				for(int y = cell(area.top()); y <= cell(area.bottom()); y++)
					cells.computeIfAbsent(key(x, y),
						ignored -> new ArrayList<>()).add(area);
	}
	
	private boolean overlaps(ScreenRectangle area)
	{
		for(int x = cell(area.left()); x <= cell(area.right()); x++)
			for(int y = cell(area.top()); y <= cell(area.bottom()); y++)
			{
				List<ScreenRectangle> neighbors = cells.get(key(x, y));
				if(neighbors == null)
					continue;
				for(ScreenRectangle previous : neighbors)
					if(previous.intersects(area))
						return true;
			}
		return false;
	}
	
	private static int cell(int coordinate)
	{
		return Math.floorDiv(coordinate, 64);
	}
	
	private static long cellCount(ScreenRectangle area)
	{
		return (cell(area.right()) - (long)cell(area.left()) + 1)
			* (cell(area.bottom()) - (long)cell(area.top()) + 1);
	}
	
	private static long key(int x, int y)
	{
		return (long)x << 32 | y & 0xffffffffL;
	}
	
	public void flush()
	{
		try
		{
			if(!pending.isEmpty())
				submit.accept(pending);
		}finally
		{
			clear();
		}
	}
	
	public void clear()
	{
		pending.clear();
		cells.clear();
		oversized = false;
	}
}
