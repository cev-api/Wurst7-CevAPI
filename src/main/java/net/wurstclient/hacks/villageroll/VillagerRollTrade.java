/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.villageroll;

import java.util.Objects;

public record VillagerRollTrade(VillagerRollTradeKind kind,
	String enchantmentId, int level, int emeraldPrice)
{
	public VillagerRollTrade
	{
		Objects.requireNonNull(kind);
		if(kind == VillagerRollTradeKind.ENCHANTED_BOOK
			&& enchantmentId == null)
			throw new IllegalArgumentException(
				"Enchanted book trades need an enchantment ID.");
		if(kind != VillagerRollTradeKind.ENCHANTED_BOOK
			&& (enchantmentId != null || level != 0))
			throw new IllegalArgumentException(
				"Non-book trades must not have enchantment data.");
	}
	
	public static VillagerRollTrade paper()
	{
		return new VillagerRollTrade(VillagerRollTradeKind.PAPER, null, 0, 0);
	}
	
	public static VillagerRollTrade bookshelf()
	{
		return new VillagerRollTrade(VillagerRollTradeKind.BOOKSHELF, null, 0,
			0);
	}
	
	public static VillagerRollTrade enchantedBook(String enchantmentId,
		int level, int emeraldPrice)
	{
		return new VillagerRollTrade(VillagerRollTradeKind.ENCHANTED_BOOK,
			enchantmentId, level, emeraldPrice);
	}
	
	public Normalized normalize()
	{
		return new Normalized(kind, enchantmentId, level);
	}
	
	public record Normalized(VillagerRollTradeKind kind, String enchantmentId,
		int level)
	{
		public Normalized
		{
			Objects.requireNonNull(kind);
			if(kind == VillagerRollTradeKind.ENCHANTED_BOOK
				&& enchantmentId == null)
				throw new IllegalArgumentException(
					"Enchanted book trades need an enchantment ID.");
			if(kind != VillagerRollTradeKind.ENCHANTED_BOOK
				&& (enchantmentId != null || level != 0))
				throw new IllegalArgumentException(
					"Non-book trades must not have enchantment data.");
		}
	}
}
