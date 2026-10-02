/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.render.esp;

import java.util.ArrayList;

/** Per-frame screen-space tag budget. Rejected tags do not consume slots. */
public final class ScreenTagLayout
{
	private final ArrayList<Bounds> bounds = new ArrayList<>();
	private int count;
	private int limit;
	private boolean avoidOverlap;
	
	public void reset(int limit, boolean avoidOverlap)
	{
		bounds.clear();
		count = 0;
		this.limit = Math.max(0, limit);
		this.avoidOverlap = avoidOverlap;
	}
	
	public boolean isFull()
	{
		return limit > 0 && count >= limit;
	}
	
	public boolean reserve(float left, float top, float right, float bottom)
	{
		if(isFull())
			return false;
		if(avoidOverlap)
		{
			for(Bounds b : bounds)
				if(left < b.right && right > b.left && top < b.bottom
					&& bottom > b.top)
					return false;
			bounds.add(new Bounds(left, top, right, bottom));
		}
		count++;
		return true;
	}
	
	private record Bounds(float left, float top, float right, float bottom)
	{}
}
