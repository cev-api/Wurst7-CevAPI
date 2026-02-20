/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.lootsearch;

import net.wurstclient.chestsearch.ChestEntry;
import net.wurstclient.chestsearch.ChestManager;
import net.wurstclient.WurstClient;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class LootChestManager extends ChestManager
{
	private final List<ChestEntry> entries = new ArrayList<>();
	private final boolean mixedDimensionFile;
	
	public LootChestManager(File lootFile, String serverIp)
	{
		super();
		mixedDimensionFile = LootSearchUtil.isMixedDimensionFile(lootFile);
		if(lootFile != null && lootFile.exists())
		{
			entries.addAll(LootSearchUtil.parseLootFile(lootFile, serverIp));
		}
	}
	
	@Override
	public java.util.List<ChestEntry> search(String q)
	{
		if(q == null || q.isBlank())
			return new ArrayList<>(entries);
		String qq = q.toLowerCase(Locale.ROOT).trim();
		String[] tokens = tokenizeQuery(qq);
		List<ChestEntry> out = new ArrayList<>();
		for(ChestEntry e : entries)
		{
			boolean matched = false;
			if(e.serverIp != null && containsQueryTokens(
				e.serverIp.toLowerCase(Locale.ROOT), qq, tokens))
				matched = true;
			if(e.dimension != null && containsQueryTokens(
				e.dimension.toLowerCase(Locale.ROOT), qq, tokens))
				matched = true;
			if(!matched && e.items != null)
			{
				for(ChestEntry.ItemEntry it : e.items)
				{
					if(itemMatchesQuery(it, qq, tokens))
					{
						matched = true;
						break;
					}
				}
			}
			if(matched)
				out.add(e);
		}
		return filterForCurrentDimension(out);
	}
	
	@Override
	public java.util.List<ChestEntry> all()
	{
		return filterForCurrentDimension(new ArrayList<>(entries));
	}
	
	@Override
	public void removeChest(String serverIp, String dimension, int x, int y,
		int z)
	{
		entries.removeIf(e -> e.x == x && e.y == y && e.z == z);
	}
	
	private List<ChestEntry> filterForCurrentDimension(List<ChestEntry> input)
	{
		if(!mixedDimensionFile || input == null || input.isEmpty())
			return input;
		
		String playerDim = getPlayerDimensionKey();
		if(playerDim.isEmpty())
			return input;
		
		List<ChestEntry> filtered = new ArrayList<>(input.size());
		for(ChestEntry entry : input)
		{
			if(entry == null)
				continue;
			String entryDim = canonicalDimension(entry.dimension);
			if(entryDim.isEmpty() || entryDim.equals("mixed")
				|| entryDim.equals(playerDim))
			{
				filtered.add(entry);
			}
		}
		return filtered;
	}
	
	private static String getPlayerDimensionKey()
	{
		try
		{
			if(WurstClient.MC == null || WurstClient.MC.level == null)
				return "";
			String full =
				WurstClient.MC.level.dimension().identifier().toString();
			return canonicalDimension(full);
		}catch(Throwable ignored)
		{
			return "";
		}
	}
	
	private static String canonicalDimension(String dimension)
	{
		if(dimension == null || dimension.isBlank())
			return "";
		String lower = dimension.trim().toLowerCase(Locale.ROOT);
		int colon = lower.indexOf(':');
		if(colon >= 0 && colon < lower.length() - 1)
			return lower.substring(colon + 1);
		return lower;
	}
	
	private static boolean itemMatchesQuery(ChestEntry.ItemEntry item, String q,
		String[] tokens)
	{
		if(item == null)
			return false;
		if(q.isEmpty())
			return true;
		
		StringBuilder sb = new StringBuilder(256);
		appendSearchPart(sb, item.itemId);
		appendSearchPart(sb, item.displayName);
		if(item.nbt != null)
			appendSearchPart(sb, item.nbt.toString());
		if(item.enchantments != null)
			for(String en : item.enchantments)
				appendSearchPart(sb, en);
		if(item.potionEffects != null)
			for(String pe : item.potionEffects)
				appendSearchPart(sb, pe);
		appendSearchPart(sb, item.primaryPotion);
		
		return containsQueryTokens(sb.toString(), q, tokens);
	}
	
	private static void appendSearchPart(StringBuilder sb, String part)
	{
		if(part == null || part.isBlank())
			return;
		if(sb.length() > 0)
			sb.append(' ');
		sb.append(part.toLowerCase(Locale.ROOT));
	}
	
	private static String[] tokenizeQuery(String query)
	{
		if(query == null || query.isBlank())
			return new String[0];
		String normalized = normalizeForTokenSearch(query);
		if(normalized.isEmpty())
			return new String[0];
		return normalized.split(" ");
	}
	
	private static boolean containsQueryTokens(String haystack, String rawQuery,
		String[] tokens)
	{
		if(rawQuery == null || rawQuery.isEmpty())
			return true;
		if(haystack == null || haystack.isEmpty())
			return false;
		
		String lowerHaystack = haystack.toLowerCase(Locale.ROOT);
		if(lowerHaystack.contains(rawQuery))
			return true;
		
		if(tokens == null || tokens.length == 0)
			return false;
		
		String normalizedHaystack = normalizeForTokenSearch(lowerHaystack);
		for(String token : tokens)
		{
			if(token == null || token.isEmpty())
				continue;
			if(!lowerHaystack.contains(token)
				&& !normalizedHaystack.contains(token))
				return false;
		}
		return true;
	}
	
	private static String normalizeForTokenSearch(String value)
	{
		if(value == null || value.isBlank())
			return "";
		return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ")
			.trim();
	}
}
