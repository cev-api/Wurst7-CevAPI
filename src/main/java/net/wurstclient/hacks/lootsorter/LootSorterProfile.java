/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.lootsorter;

import java.util.List;

/**
 * Persisted profile data. Loading restores configuration only, never starts.
 */
public record LootSorterProfile(String name, String serverIdentifier,
	String dimensionKey, String dimensionType, List<ContainerPos> sources,
	List<DestinationProfile> destinations,
	List<SourceContentsSnapshot> sourceContents)
{
	public LootSorterProfile
	{
		sources = sources == null ? List.of() : List.copyOf(sources);
		destinations =
			destinations == null ? List.of() : List.copyOf(destinations);
		sourceContents =
			sourceContents == null ? List.of() : List.copyOf(sourceContents);
	}
	
	public record ContainerPos(int x, int y, int z)
	{}
	
	public record DestinationProfile(ContainerPos position, int priority,
		List<String> filters)
	{}
}
