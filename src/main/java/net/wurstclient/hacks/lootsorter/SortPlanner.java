/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.lootsorter;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Selects the largest destination/filter group first. Conflicting matches are
 * resolved by filter specificity, then destination priority; only then are
 * travel distance and source selection order considered.
 */
public final class SortPlanner
{
	public SortRoute plan(Vec3 playerPos,
		Map<LogicalContainer, List<ItemStack>> sourceContents,
		List<DestinationRule> destinations)
	{
		return plan(playerPos, sourceContents, destinations,
			new java.util.ArrayList<>(sourceContents.keySet()));
	}
	
	public SortRoute plan(Vec3 playerPos,
		Map<LogicalContainer, List<ItemStack>> sourceContents,
		List<DestinationRule> destinations,
		List<LogicalContainer> sourceSelectionOrder)
	{
		Map<Group, Map<LogicalContainer, SourceGroup>> groups = new HashMap<>();
		for(Map.Entry<LogicalContainer, List<ItemStack>> entry : sourceContents
			.entrySet())
			for(ItemStack stack : entry.getValue())
			{
				DestinationRule destination =
					chooseDestination(stack, destinations);
				if(destination == null)
					continue;
				Group group =
					new Group(destination, destination.specificity(stack),
						destination.routingKey(stack));
				groups.computeIfAbsent(group, ignored -> new HashMap<>())
					.computeIfAbsent(entry.getKey(),
						ignored -> new SourceGroup())
					.add(stack);
			}
		return groups.entrySet().stream()
			.map(entry -> createRoute(entry.getKey(), entry.getValue(),
				playerPos, sourceSelectionOrder))
			.filter(route -> route != null)
			.sorted(Comparator.comparingInt(SortRoute::groupItemCount)
				.reversed()
				.thenComparing(
					Comparator.comparingInt(SortRoute::specificity).reversed())
				.thenComparingDouble(
					route -> route.source().anchor().distToCenterSqr(playerPos))
				.thenComparingDouble(route -> route.source().anchor()
					.distToCenterSqr(Vec3.atCenterOf(
						route.destination().getContainer().anchor())))
				.thenComparingInt(route -> route.destination().getPriority())
				.thenComparingInt(route -> selectionIndex(sourceSelectionOrder,
					route.source())))
			.findFirst().orElse(null);
	}
	
	private DestinationRule chooseDestination(ItemStack stack,
		List<DestinationRule> destinations)
	{
		/*
		 * Everything is a strict fallback, never a catch-all for an item that
		 * belongs to another configured category. Check the category predicates
		 * without availability flags first, so a full or unreachable category
		 * does not silently divert its items into Everything.
		 */
		boolean hasSpecificMatch =
			destinations.stream().filter(DestinationRule::isConfigured)
				.filter(destination -> !isEverythingFallback(destination))
				.anyMatch(destination -> destination.getFilters().stream()
					.anyMatch(filter -> filter.matches(stack)));
		return destinations.stream()
			.filter(destination -> !hasSpecificMatch
				|| !isEverythingFallback(destination))
			.filter(destination -> destination.matches(stack))
			.sorted(Comparator.comparingInt(
				(DestinationRule destination) -> destination.specificity(stack))
				.reversed().thenComparingInt(DestinationRule::getPriority))
			.findFirst().orElse(null);
	}
	
	private boolean isEverythingFallback(DestinationRule destination)
	{
		return destination.getFilters().size() == 1
			&& destination.getFilters().get(0) == BuiltInItemFilter.ALL;
	}
	
	private SortRoute createRoute(Group group,
		Map<LogicalContainer, SourceGroup> sourceGroups, Vec3 playerPos,
		List<LogicalContainer> sourceSelectionOrder)
	{
		Map.Entry<LogicalContainer, SourceGroup> source = sourceGroups
			.entrySet().stream()
			.sorted(
				Comparator.<Map.Entry<LogicalContainer, SourceGroup>> comparingInt(
					entry -> entry.getValue().count).reversed()
					.thenComparingDouble(entry -> entry.getKey().anchor()
						.distToCenterSqr(playerPos))
					.thenComparingInt(entry -> selectionIndex(
						sourceSelectionOrder, entry.getKey())))
			.findFirst().orElse(null);
		if(source == null)
			return null;
		int groupCount = sourceGroups.values().stream()
			.mapToInt(sourceGroup -> sourceGroup.count).sum();
		Map<LogicalContainer, Set<ItemStackEquivalenceKey>> sourceItemKeys =
			new LinkedHashMap<>();
		Set<ItemStackEquivalenceKey> itemKeys = new HashSet<>();
		sourceGroups.entrySet().stream().sorted(Comparator
			.comparingDouble(
				(Map.Entry<LogicalContainer, SourceGroup> entry) -> entry
					.getKey().anchor().distToCenterSqr(playerPos))
			.thenComparingInt(
				entry -> selectionIndex(sourceSelectionOrder, entry.getKey())))
			.forEach(entry -> {
				Set<ItemStackEquivalenceKey> keys =
					Set.copyOf(entry.getValue().itemKeys);
				sourceItemKeys.put(entry.getKey(), keys);
				itemKeys.addAll(keys);
			});
		return new SortRoute(source.getKey(), group.destination, sourceItemKeys,
			itemKeys, source.getValue().count, groupCount, group.specificity);
	}
	
	private static int selectionIndex(List<LogicalContainer> sources,
		LogicalContainer source)
	{
		int index = sources.indexOf(source);
		return index < 0 ? Integer.MAX_VALUE : index;
	}
	
	private record Group(DestinationRule destination, int specificity,
		String routingKey)
	{}
	
	private static final class SourceGroup
	{
		private final Set<ItemStackEquivalenceKey> itemKeys = new HashSet<>();
		private int count;
		
		private void add(ItemStack stack)
		{
			itemKeys.add(ItemStackEquivalenceKey.of(stack));
			count += stack.getCount();
		}
	}
}
