/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.vaultroll;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public record VaultRollStack(String itemId, int count,
	List<Enchantment> enchantments, String note)
{
	public VaultRollStack
	{
		itemId = Objects.requireNonNull(itemId);
		if(count < 1)
			throw new IllegalArgumentException("count must be positive");
		enchantments = Collections.unmodifiableList(
			new ArrayList<>(Objects.requireNonNull(enchantments)));
	}
	
	public static VaultRollStack plain(String itemId)
	{
		return plain(itemId, 1);
	}
	
	public static VaultRollStack plain(String itemId, int count)
	{
		return new VaultRollStack(itemId, count, List.of(), null);
	}
	
	public String describe()
	{
		StringBuilder result =
			new StringBuilder(itemId).append('x').append(count);
		if(note != null && !note.isBlank())
			result.append(" [").append(note).append(']');
		for(Enchantment enchantment : enchantments)
			result.append(" [").append(shortId(enchantment.id())).append(' ')
				.append(enchantment.level()).append(']');
		return result.toString();
	}
	
	private static String shortId(String id)
	{
		int colon = id.lastIndexOf(':');
		return colon < 0 ? id : id.substring(colon + 1);
	}
	
	public record Enchantment(String id, int level)
	{
		public Enchantment
		{
			Objects.requireNonNull(id);
			if(level < 1)
				throw new IllegalArgumentException("level must be positive");
		}
	}
}
