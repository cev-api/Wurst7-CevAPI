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
 * A component-safe, persisted view of every stack found in one source.
 * Empty item lists are meaningful: they say that this source was scanned and
 * contained no items.
 */
public record SourceContentsSnapshot(LootSorterProfile.ContainerPos position,
	List<String> items)
{
	public SourceContentsSnapshot
	{
		items = items == null ? List.of() : List.copyOf(items);
	}
}
