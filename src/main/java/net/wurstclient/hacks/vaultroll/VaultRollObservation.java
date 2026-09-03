/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.vaultroll;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Collections;

/**
 * Evidence observed from one complete Vault opening.
 *
 * <p>
 * Manual observations use {@link #items()} and may omit counts. Automatic
 * observations can additionally preserve the ordered item stacks. Empty
 * stack details are treated as wildcards for stack metadata, but item IDs and
 * counts are always exact.
 * </p>
 */
public record VaultRollObservation(VaultRollMode mode,
	Map<String, Integer> items, List<VaultRollStack> stacks,
	boolean allowAggregateFallback)
{
	public VaultRollObservation(VaultRollMode mode, Map<String, Integer> items)
	{
		this(mode, items, List.of(), false);
	}
	
	public VaultRollObservation(VaultRollMode mode, List<VaultRollStack> stacks)
	{
		this(mode, stacks, false);
	}
	
	public VaultRollObservation(VaultRollMode mode, List<VaultRollStack> stacks,
		boolean allowAggregateFallback)
	{
		this(mode, aggregate(stacks), stacks, allowAggregateFallback);
	}
	
	public VaultRollObservation
	{
		mode = Objects.requireNonNull(mode);
		items = Collections.unmodifiableMap(
			new LinkedHashMap<>(Objects.requireNonNull(items)));
		stacks = List.copyOf(Objects.requireNonNull(stacks));
		if(items.isEmpty() || stacks.stream().anyMatch(Objects::isNull))
			throw new IllegalArgumentException(
				"An observation cannot be empty");
		for(var entry : items.entrySet())
			if(entry.getKey() == null || entry.getKey().isBlank()
				|| entry.getValue() != null && entry.getValue() < 1)
				throw new IllegalArgumentException("Invalid observation item");
	}
	
	public static VaultRollObservation exact(VaultRollMode mode,
		VaultRollOpening opening)
	{
		return new VaultRollObservation(mode, opening.stacks());
	}
	
	public boolean matches(VaultRollOpening opening)
	{
		if(!stacks.isEmpty())
		{
			List<VaultRollStack> actual = opening.stacks();
			if(actual.size() == stacks.size())
			{
				boolean matches = true;
				for(int i = 0; i < stacks.size(); i++)
					if(!matchesStack(stacks.get(i), actual.get(i)))
					{
						matches = false;
						break;
					}
				if(matches)
					return true;
			}
			if(!allowAggregateFallback)
				return false;
		}
		
		Map<String, Integer> actual = opening.aggregate();
		if(!actual.keySet().equals(items.keySet()))
			return false;
		for(var entry : items.entrySet())
			if(entry.getValue() != null
				&& !entry.getValue().equals(actual.get(entry.getKey())))
				return false;
		return true;
	}
	
	public String describe()
	{
		StringBuilder result = new StringBuilder();
		if(!stacks.isEmpty())
		{
			for(VaultRollStack stack : stacks)
			{
				if(result.length() > 0)
					result.append(", ");
				result.append(stack.describe());
			}
			return result.toString();
		}
		
		for(var entry : items.entrySet())
		{
			if(result.length() > 0)
				result.append(", ");
			result.append(entry.getKey());
			if(entry.getValue() != null)
				result.append('=').append(entry.getValue());
		}
		return result.toString();
	}
	
	public static VaultRollObservation parse(VaultRollMode mode, String input)
	{
		if(input == null || input.isBlank())
			throw new IllegalArgumentException("Observation is empty");
		Map<String, Integer> items = new LinkedHashMap<>();
		for(String rawPart : input.split(","))
		{
			String part = rawPart.trim();
			if(part.isEmpty())
				continue;
			String item;
			Integer count = null;
			int equals = part.indexOf('=');
			if(equals >= 0)
			{
				item = part.substring(0, equals).trim();
				count = parseCount(part.substring(equals + 1).trim());
			}else
			{
				String[] words = part.split("\\s+");
				if(words.length == 2 && isCount(words[0]))
				{
					count = parseCount(words[0]);
					item = words[1];
				}else if(words.length == 2 && isCount(words[1]))
				{
					item = words[0];
					count = parseCount(words[1]);
				}else if(words.length == 1)
					item = words[0];
				else
					throw new IllegalArgumentException(
						"Use item=count, item xN, or item");
			}
			item = normalizeItemId(item);
			if(item.isBlank())
				throw new IllegalArgumentException("Observation item is empty");
			if(items.containsKey(item))
				throw new IllegalArgumentException("Duplicate item: " + item);
			items.put(item, count);
		}
		return new VaultRollObservation(mode, items);
	}
	
	private static Map<String, Integer> aggregate(List<VaultRollStack> stacks)
	{
		Map<String, Integer> result = new LinkedHashMap<>();
		for(VaultRollStack stack : Objects.requireNonNull(stacks))
			result.merge(stack.itemId(), stack.count(), Integer::sum);
		return result;
	}
	
	private static boolean matchesStack(VaultRollStack observed,
		VaultRollStack actual)
	{
		if(!observed.itemId().equals(actual.itemId())
			|| observed.count() != actual.count())
			return false;
		if(!observed.enchantments().isEmpty()
			&& !observed.enchantments().equals(actual.enchantments()))
			return false;
		return observed.note() == null || observed.note().equals(actual.note());
	}
	
	public static String normalizeItemId(String input)
	{
		String id = input.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
		if(!id.contains(":"))
			id = "minecraft:" + id;
		return id;
	}
	
	private static boolean isCount(String value)
	{
		return value.matches("\\d+");
	}
	
	private static Integer parseCount(String value)
	{
		try
		{
			int count = Integer.parseInt(value);
			if(count < 1)
				throw new NumberFormatException();
			return count;
		}catch(NumberFormatException e)
		{
			throw new IllegalArgumentException(
				"Count must be positive: " + value);
		}
	}
}
