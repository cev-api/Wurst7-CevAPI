/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.stream.Stream;

public enum EspLimitUtils
{
	;
	
	public static <T> ArrayList<T> collectNearest(Iterable<T> items, int limit,
		ToDoubleFunction<T> distanceFunction)
	{
		return collectNearest(items, limit, distanceFunction, t -> true);
	}
	
	public static <T> ArrayList<T> collectNearest(Iterable<T> items, int limit,
		ToDoubleFunction<T> distanceFunction, Predicate<T> predicate)
	{
		if(limit <= 0)
		{
			ArrayList<T> all = new ArrayList<>();
			for(T item : items)
				if(predicate.test(item))
					all.add(item);
			return all;
		}
		
		PriorityQueue<T> heap = new PriorityQueue<>(limit + 1,
			Comparator.comparingDouble(distanceFunction).reversed());
		
		for(T item : items)
			addNearest(heap, item, limit, distanceFunction, predicate);
		
		return sortByDistance(heap, distanceFunction);
	}
	
	public static <T> ArrayList<T> collectNearest(Stream<T> stream, int limit,
		ToDoubleFunction<T> distanceFunction)
	{
		return collectNearest(stream, limit, distanceFunction, t -> true);
	}
	
	public static <T> ArrayList<T> collectNearest(Stream<T> stream, int limit,
		ToDoubleFunction<T> distanceFunction, Predicate<T> predicate)
	{
		if(limit <= 0)
			return stream.filter(predicate).collect(ArrayList::new,
				ArrayList::add, ArrayList::addAll);
		
		PriorityQueue<T> heap = new PriorityQueue<>(limit + 1,
			Comparator.comparingDouble(distanceFunction).reversed());
		
		stream.forEach(
			item -> addNearest(heap, item, limit, distanceFunction, predicate));
		
		return sortByDistance(heap, distanceFunction);
	}
	
	private static <T> ArrayList<T> sortByDistance(PriorityQueue<T> heap,
		ToDoubleFunction<T> distanceFunction)
	{
		ArrayList<T> result = new ArrayList<>(heap);
		result.sort(Comparator.comparingDouble(distanceFunction));
		return result;
	}
	
	private static <T> void addNearest(PriorityQueue<T> heap, T item, int limit,
		ToDoubleFunction<T> distanceFunction, Predicate<T> predicate)
	{
		if(!predicate.test(item))
			return;
		
		double distance = distanceFunction.applyAsDouble(item);
		if(heap.size() < limit)
		{
			heap.offer(item);
			return;
		}
		
		T farthest = heap.peek();
		if(farthest != null
			&& distance < distanceFunction.applyAsDouble(farthest))
		{
			heap.poll();
			heap.offer(item);
		}
	}
}
