/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.villageroll;

import java.util.Objects;

public record VillagerRollRoll(VillagerRollTrade first,
	VillagerRollTrade second)
{
	public VillagerRollRoll
	{
		Objects.requireNonNull(first);
		Objects.requireNonNull(second);
		if(first.kind() == second.kind())
			throw new IllegalArgumentException(
				"A novice librarian roll must contain two trade kinds.");
	}
	
	public VillagerRollNormalizedRoll normalize()
	{
		return new VillagerRollNormalizedRoll(first.normalize(),
			second.normalize());
	}
	
	public boolean contains(String enchantmentId, Integer level,
		Integer maxPrice)
	{
		return matches(first, enchantmentId, level, maxPrice)
			|| matches(second, enchantmentId, level, maxPrice);
	}
	
	private static boolean matches(VillagerRollTrade trade,
		String enchantmentId, Integer level, Integer maxPrice)
	{
		return trade.kind() == VillagerRollTradeKind.ENCHANTED_BOOK
			&& trade.enchantmentId().equals(enchantmentId)
			&& (level == null || trade.level() == level)
			&& (maxPrice == null || trade.emeraldPrice() <= maxPrice);
	}
}
