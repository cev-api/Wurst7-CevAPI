/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util.timer;

import java.util.Comparator;
import java.util.PriorityQueue;
import net.wurstclient.hack.Hack;

public final class RequestHandler<T>
{
	private int currentTick;
	
	private final PriorityQueue<Request<T>> activeRequests =
		new PriorityQueue<>(11, Comparator.comparingInt(r -> -r.priority));
	
	public void tick()
	{
		currentTick++;
	}
	
	public void request(Request<T> request)
	{
		activeRequests.removeIf(r -> r.provider == request.provider);
		request.expiresIn += currentTick;
		activeRequests.add(request);
	}
	
	public T getActiveRequestValue()
	{
		Request<T> top = activeRequests.peek();
		if(top == null)
			return null;
		
		while(top != null && (top.expiresIn <= currentTick
			|| (top.provider != null && !top.provider.isEnabled())))
		{
			activeRequests.remove();
			top = activeRequests.peek();
		}
		
		return top != null ? top.value : null;
	}
	
	public static final class Request<T>
	{
		private int expiresIn;
		private final int priority;
		private final Hack provider;
		private final T value;
		
		public Request(int expiresIn, int priority, Hack provider, T value)
		{
			this.expiresIn = expiresIn;
			this.priority = priority;
			this.provider = provider;
			this.value = value;
		}
	}
}
