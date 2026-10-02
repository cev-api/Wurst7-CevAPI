/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.render.esp;

import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Bounded frame-local leases: two callers never receive the same live resource.
 */
public final class FrameResourcePool<T>
{
	private final int capacity;
	private final Function<Boolean, T> factory;
	private final Consumer<T> endFrame;
	private final Consumer<T> close;
	private final ArrayList<T> retained = new ArrayList<>();
	private int used;
	
	public FrameResourcePool(int capacity, Function<Boolean, T> factory,
		Consumer<T> endFrame, Consumer<T> close)
	{
		this.capacity = Math.max(0, capacity);
		this.factory = factory;
		this.endFrame = endFrame;
		this.close = close;
	}
	
	public T acquire()
	{
		if(used >= capacity)
			return factory.apply(false);
		if(used == retained.size())
			retained.add(factory.apply(true));
		return retained.get(used++);
	}
	
	public void endFrame()
	{
		try
		{
			for(int i = 0; i < used; i++)
				endFrame.accept(retained.get(i));
		}finally
		{
			used = 0;
		}
	}
	
	public void clear()
	{
		for(T resource : retained)
			close.accept(resource);
		retained.clear();
		used = 0;
	}
}
