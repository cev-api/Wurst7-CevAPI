/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.lootsorter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.wurstclient.chestsearch.ChestEntry;
import net.wurstclient.chestsearch.ChestManager;
import net.wurstclient.chestsearch.ChestSearchItemStacks;

/**
 * Read-only ChestSearch data backed by LootSorter's selected source chests.
 * It never reads from or writes to the normal ChestSearch database.
 */
public final class LootSorterSourceChestManager extends ChestManager
{
	private final List<ChestEntry> entries;
	
	public LootSorterSourceChestManager(
		List<LootSorterProfile.ContainerPos> sources,
		List<SourceContentsSnapshot> sourceContents, String serverIdentifier,
		String dimensionKey, HolderLookup.Provider registries)
	{
		entries = createEntries(sources, sourceContents, serverIdentifier,
			dimensionKey, registries);
	}
	
	@Override
	public List<ChestEntry> all()
	{
		return new ArrayList<>(entries);
	}
	
	@Override
	public List<ChestEntry> search(String query)
	{
		if(query == null || query.isBlank())
			return all();
		String[] terms = normalize(query).split(" ");
		List<ChestEntry> matches = new ArrayList<>();
		for(ChestEntry entry : entries)
			if(matches(entry, terms))
				matches.add(entry);
		return matches;
	}
	
	@Override
	public void removeChest(String serverIp, String dimension, int x, int y,
		int z)
	{
		// Source selections and their saved scans are intentionally immutable
		// from a search view. Use LootSorter's preset commands to change them.
	}
	
	private static List<ChestEntry> createEntries(
		List<LootSorterProfile.ContainerPos> sources,
		List<SourceContentsSnapshot> sourceContents, String serverIdentifier,
		String dimensionKey, HolderLookup.Provider registries)
	{
		java.util.Map<LootSorterProfile.ContainerPos, SourceContentsSnapshot> cache =
			new java.util.HashMap<>();
		if(sourceContents != null)
			for(SourceContentsSnapshot snapshot : sourceContents)
				if(snapshot != null && snapshot.position() != null)
					cache.put(snapshot.position(), snapshot);
		List<ChestEntry> result = new ArrayList<>();
		if(sources == null)
			return result;
		for(LootSorterProfile.ContainerPos source : sources)
		{
			if(source == null)
				continue;
			List<ChestEntry.ItemEntry> items = new ArrayList<>();
			SourceContentsSnapshot snapshot = cache.get(source);
			if(snapshot != null)
				for(String token : snapshot.items())
				{
					ItemStack stack =
						ItemStackSnapshotCodec.decode(token, registries);
					if(!stack.isEmpty())
						items.add(item(items.size(), stack));
				}
			ChestEntry entry = new ChestEntry(serverIdentifier, dimensionKey,
				source.x(), source.y(), source.z(), items);
			result.add(entry);
		}
		return result;
	}
	
	private static ChestEntry.ItemEntry item(int slot, ItemStack stack)
	{
		ChestEntry.ItemEntry item = new ChestEntry.ItemEntry();
		item.slot = slot;
		item.count = stack.getCount();
		item.itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
		item.displayName = stack.getHoverName().getString();
		item.nbt = ChestSearchItemStacks.encode(stack);
		return item;
	}
	
	private static boolean matches(ChestEntry entry, String[] terms)
	{
		StringBuilder text = new StringBuilder();
		append(text, entry.dimension);
		append(text, entry.serverIp);
		if(entry.items != null)
			for(ChestEntry.ItemEntry item : entry.items)
			{
				append(text, item.itemId);
				append(text, item.displayName);
				if(item.nbt != null)
					append(text, item.nbt.toString());
			}
		String normalized = normalize(text.toString());
		for(String term : terms)
			if(!term.isEmpty() && !normalized.contains(term))
				return false;
		return true;
	}
	
	private static void append(StringBuilder text, String value)
	{
		if(value != null && !value.isBlank())
			text.append(' ').append(value);
	}
	
	private static String normalize(String text)
	{
		return text == null ? "" : text.toLowerCase(Locale.ROOT)
			.replaceAll("[^a-z0-9]+", " ").trim();
	}
}
