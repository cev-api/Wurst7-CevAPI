/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.lootsorter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** The next source-to-destination batch selected by {@link SortPlanner}. */
public record SortRoute(LogicalContainer source, DestinationRule destination,
	Map<LogicalContainer, Set<ItemStackEquivalenceKey>> sourceItemKeys,
	Set<ItemStackEquivalenceKey> itemKeys, int sourceItemCount,
	int groupItemCount, int specificity)
{
	public SortRoute
	{
		sourceItemKeys = Map.copyOf(new LinkedHashMap<>(sourceItemKeys));
		itemKeys = Set.copyOf(itemKeys);
	}
	
	/** Item identities still expected from one particular source container. */
	public Set<ItemStackEquivalenceKey> itemKeysFor(LogicalContainer source)
	{
		return sourceItemKeys.getOrDefault(source, Set.of());
	}
}
