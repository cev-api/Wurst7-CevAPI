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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record VaultRollOpening(List<VaultRollStack> stacks)
{
	public VaultRollOpening
	{
		stacks = List.copyOf(stacks);
	}
	
	public Map<String, Integer> aggregate()
	{
		Map<String, Integer> result = new LinkedHashMap<>();
		for(VaultRollStack stack : stacks)
			result.merge(stack.itemId(), stack.count(), Integer::sum);
		return Collections.unmodifiableMap(result);
	}
	
	public String describe()
	{
		List<String> descriptions = new ArrayList<>();
		for(VaultRollStack stack : stacks)
			descriptions.add(stack.describe());
		return String.join(", ", descriptions);
	}
}
