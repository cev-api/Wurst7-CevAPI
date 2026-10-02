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
import java.util.function.Function;
import java.util.function.ToIntFunction;
import net.minecraft.world.phys.Vec3;

/**
 * Same seed-radius grouping as ItemESP, using nearby cells instead of all
 * pairs.
 */
public final class ItemTagClusterer
{
	private ItemTagClusterer()
	{}
	
	public record Cluster<T>(T seed, int count, Vec3 position)
	{}
	
	public record Group<T>(T seed, List<T> members)
	{}
	
	/**
	 * Membership only: live counts and interpolated positions are never cached.
	 */
	public static final class Cache<T, K>
	{
		private List<T> items = List.of();
		private final ArrayList<K> keys = new ArrayList<>();
		private final ArrayList<Vec3> positions = new ArrayList<>();
		private List<Group<T>> groups = List.of();
		private double radius = Double.NaN;
		
		public List<Group<T>> groups(List<T> current, Function<T, K> key,
			Function<T, Vec3> position, double currentRadius)
		{
			boolean unchanged =
				radius == currentRadius && items.size() == current.size();
			for(int i = 0; unchanged && i < current.size(); i++)
				unchanged = items.get(i) == current.get(i)
					&& java.util.Objects.equals(keys.get(i),
						key.apply(current.get(i)))
					&& positions.get(i).equals(position.apply(current.get(i)));
			if(unchanged)
				return groups;
			groups =
				ItemTagClusterer.groups(current, key, position, currentRadius);
			items = List.copyOf(current);
			keys.clear();
			positions.clear();
			for(T item : current)
			{
				keys.add(key.apply(item));
				positions.add(position.apply(item));
			}
			radius = currentRadius;
			return groups;
		}
		
		public void clear()
		{
			items = List.of();
			keys.clear();
			positions.clear();
			groups = List.of();
			radius = Double.NaN;
		}
	}
	
	private record Cell<K>(K item, long x, long y, long z)
	{}
	
	public static <T, K> List<Cluster<T>> cluster(List<T> items,
		Function<T, K> itemKey, Function<T, Vec3> position,
		Function<T, Vec3> labelPosition, ToIntFunction<T> count, double radius)
	{
		return evaluate(groups(items, itemKey, position, radius), labelPosition,
			count);
	}
	
	public static <T> List<Cluster<T>> evaluate(List<Group<T>> groups,
		Function<T, Vec3> labelPosition, ToIntFunction<T> count)
	{
		ArrayList<Cluster<T>> clusters = new ArrayList<>(groups.size());
		for(Group<T> group : groups)
		{
			Vec3 center = Vec3.ZERO;
			int total = 0;
			for(T item : group.members)
			{
				center = center.add(labelPosition.apply(item));
				total += count.applyAsInt(item);
			}
			clusters.add(new Cluster<>(group.seed, total,
				center.scale(1.0 / group.members.size())));
		}
		return clusters;
	}
	
	public static <T, K> List<Group<T>> groups(List<T> items,
		Function<T, K> itemKey, Function<T, Vec3> position, double radius)
	{
		if(!(radius > 0) || !Double.isFinite(radius))
			throw new IllegalArgumentException(
				"Radius must be positive and finite.");
		HashMap<Cell<K>, ArrayList<Integer>> cells = new HashMap<>();
		ArrayList<K> keys = new ArrayList<>(items.size());
		ArrayList<Vec3> positions = new ArrayList<>(items.size());
		for(int i = 0; i < items.size(); i++)
		{
			T item = items.get(i);
			K key = itemKey.apply(item);
			Vec3 pos = position.apply(item);
			keys.add(key);
			positions.add(pos);
			Cell<K> cell = cell(key, pos, radius);
			cells.computeIfAbsent(cell, ignored -> new ArrayList<>()).add(i);
		}
		boolean[] visited = new boolean[items.size()];
		ArrayList<Group<T>> groups = new ArrayList<>();
		double radiusSq = radius * radius;
		for(int i = 0; i < items.size(); i++)
		{
			if(visited[i])
				continue;
			visited[i] = true;
			T seed = items.get(i);
			Vec3 seedPos = positions.get(i);
			ArrayList<Integer> members = new ArrayList<>();
			members.add(i);
			Cell<K> origin = cell(keys.get(i), seedPos, radius);
			for(int dx = -1; dx <= 1; dx++)
				for(int dy = -1; dy <= 1; dy++)
					for(int dz = -1; dz <= 1; dz++)
					{
						List<Integer> neighbors =
							cells.get(new Cell<>(origin.item, origin.x + dx,
								origin.y + dy, origin.z + dz));
						if(neighbors == null)
							continue;
						for(int j : neighbors)
						{
							if(visited[j] || seedPos
								.distanceToSqr(positions.get(j)) > radiusSq)
								continue;
							visited[j] = true;
							members.add(j);
						}
					}
			// Keep the original addition order, including at float rounding
			// edges.
			members.sort(Integer::compareTo);
			ArrayList<T> grouped = new ArrayList<>(members.size());
			for(int index : members)
				grouped.add(items.get(index));
			groups.add(new Group<>(seed, List.copyOf(grouped)));
		}
		return groups;
	}
	
	private static <K> Cell<K> cell(K key, Vec3 pos, double radius)
	{
		return new Cell<>(key, (long)Math.floor(pos.x / radius),
			(long)Math.floor(pos.y / radius), (long)Math.floor(pos.z / radius));
	}
}
